package io.jmix.cli.telemetry

import com.google.gson.JsonParser
import io.jmix.cli.FakeServer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

class UsageStatisticsTest {

    @TempDir
    lateinit var directory: Path

    private val route = "/rest/event/open"
    private val clock = Clock.fixed(Instant.parse("2026-11-09T12:30:00.123Z"), ZoneOffset.UTC)

    private fun sender(server: FakeServer, file: Path = directory.resolve("installation-id")) =
        UsageStatistics(file, server.url + route, clock, Duration.ofSeconds(5))

    @Test
    fun `open events use the Studio fields and a stable CLI installation id`() {
        FakeServer().use { server ->
            server.on("POST", route) { FakeServer.Response(200) }
            sender(server).record(UsageEvent.PROJECT_CREATED, "3.1.0", addonIds = listOf("quartz", "russian-translation"))
            sender(server).record(UsageEvent.TRIAL_DOWNLOADED, "3.0.1")
            sender(server).record(UsageEvent.USER_LOGIN)
            sender(server, directory.resolve("other/installation-id")).record(UsageEvent.USER_LOGIN)

            val id = Files.readString(directory.resolve("installation-id"))
            assertEquals(id, UUID.fromString(id).toString())
            val payloads = server.requests.map { request ->
                assertEquals("POST", request.method)
                assertEquals(route, request.path)
                assertEquals("application/json", request.headers.getFirst("Content-Type"))
                assertFalse(request.headers.containsKey("Authorization"))
                JsonParser.parseString(request.body).asJsonObject.also { payload ->
                    val keys = setOf("studioId", "eventDate", "studioVersion", "os", "ide", "addons", "databases")
                    assertEquals(if (payload.has("platformVersion")) keys + "platformVersion" else keys, payload.keySet())
                    assertEquals("2026-11-09T12:30:00Z", payload["eventDate"].asString)
                    assertTrue(payload["studioVersion"].asString.isNotBlank())
                    assertEquals(System.getProperty("os.name"), payload["os"].asString)
                    assertTrue(payload.getAsJsonArray("databases").isEmpty)
                }
            }
            assertEquals(listOf("cli-new", "cli-trial", "cli-login", "cli-login"), payloads.map { it["ide"].asString })
            assertEquals(listOf("3.1.0", "3.0.1", null, null), payloads.map { it["platformVersion"]?.asString })
            assertEquals(
                listOf(listOf("quartz", "russian-translation"), emptyList(), emptyList(), emptyList()),
                payloads.map { payload -> payload.getAsJsonArray("addons").map { it.asString } },
            )
            assertEquals(listOf(id, id, id), payloads.take(3).map { it["studioId"].asString })
            assertNotEquals(id, payloads.last()["studioId"].asString)
        }
    }

    @Test
    fun `reports conventional Jmix versions and omits private labels`() {
        FakeServer().use { server ->
            server.on("POST", route) { FakeServer.Response(200) }
            val stats = sender(server)
            stats.record(UsageEvent.PROJECT_CREATED, "3.1.0-RC1")
            stats.record(UsageEvent.PROJECT_CREATED, "3.2.999-SNAPSHOT")
            stats.record(UsageEvent.PROJECT_CREATED, "3.1.0-private-client")
            stats.record(UsageEvent.TRIAL_DOWNLOADED, "internal_trial_label")

            assertEquals(
                listOf("3.1.0-RC1", "3.2.999-SNAPSHOT", null, null),
                server.requests.map { JsonParser.parseString(it.body).asJsonObject["platformVersion"]?.asString },
            )
        }
    }

    @Test
    fun `a directory at the installation id path skips delivery`() {
        val file = Files.createDirectory(directory.resolve("installation-id"))
        FakeServer().use { server ->
            sender(server, file).record(UsageEvent.PROJECT_CREATED, "3.1.0")
            assertTrue(server.requests.isEmpty())
            assertTrue(Files.isDirectory(file))
        }
    }

    @Test
    fun `concurrent attempts recover one shared installation id from invalid state`() {
        val file = Files.writeString(directory.resolve("installation-id"), "invalid")
        FakeServer().use { server ->
            server.on("POST", route) { FakeServer.Response(200) }
            val start = CountDownLatch(1)
            Executors.newFixedThreadPool(2).use { pool ->
                val attempts = List(2) {
                    pool.submit {
                        start.await()
                        sender(server, file).record(UsageEvent.USER_LOGIN)
                    }
                }
                start.countDown()
                attempts.forEach { it.get(10, SECONDS) }
            }
            val id = Files.readString(file)
            assertEquals(id, UUID.fromString(id).toString())
            assertEquals(listOf(id, id), server.requests.map { JsonParser.parseString(it.body).asJsonObject["studioId"].asString })
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a linked installation id is skipped`() {
        val target = Files.writeString(directory.resolve("target"), UUID.randomUUID().toString())
        val link = Files.createSymbolicLink(directory.resolve("installation-id"), target)
        FakeServer().use { server ->
            sender(server, link).record(UsageEvent.USER_LOGIN)
            assertTrue(server.requests.isEmpty())
            assertTrue(Files.isSymbolicLink(link))
        }
    }

    @Test
    fun `delivery neither follows redirects nor retries failures`() {
        FakeServer().use { server ->
            server.on("POST", route) { FakeServer.Response(307, headers = mapOf("Location" to "/other")) }
            sender(server).record(UsageEvent.USER_LOGIN)
            assertEquals(listOf(route), server.requests.map { it.path })

            server.on("POST", route) { FakeServer.Response(503) }
            sender(server).record(UsageEvent.USER_LOGIN)
            assertEquals(listOf(route, route), server.requests.map { it.path })
        }
    }

    @Test
    fun `a collector that does not respond cannot hold up a successful command`() {
        FakeServer().use { server ->
            val release = CountDownLatch(1)
            server.on("POST", route) {
                release.await(10, SECONDS)
                FakeServer.Response(200)
            }
            val stats = UsageStatistics(directory.resolve("installation-id"), server.url + route, clock, Duration.ofSeconds(1))
            val start = System.nanoTime()
            try {
                stats.record(UsageEvent.USER_LOGIN)
                assertTrue(Duration.ofNanos(System.nanoTime() - start) < Duration.ofSeconds(5))
                assertEquals(listOf(route), server.requests.map { it.path })
            } finally {
                release.countDown()
            }
        }
    }
}
