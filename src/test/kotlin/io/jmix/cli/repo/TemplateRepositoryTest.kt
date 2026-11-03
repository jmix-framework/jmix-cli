package io.jmix.cli.repo

import com.sun.net.httpserver.HttpServer
import io.jmix.cli.FakeServer
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class TemplateRepositoryTest {

    @TempDir
    lateinit var cacheDir: Path

    private val unreachableUrl = "http://127.0.0.1:1/repo"
    private val otherUnreachableUrl = "http://127.0.0.1:1/other-repo"
    private val metadataPath = "/io/jmix/templates/studio/jmix-studio-templates/maven-metadata.xml"

    private fun seedMetadata(repoUrl: String, versions: List<String>): Path {
        val dir = cacheDir.resolve(repoUrl.replace(Regex("[^a-zA-Z0-9]"), ""))
        Files.createDirectories(dir)
        return Files.writeString(dir.resolve("maven-metadata.xml"), metadata(versions))
    }

    private fun metadata(versions: List<String>): String =
        "<metadata><versioning><versions>" +
            versions.joinToString("") { "<version>$it</version>" } +
            "</versions></versioning></metadata>"

    private fun FakeServer.serveMetadata(xml: String) {
        on("GET", metadataPath) { FakeServer.Response(200, xml.toByteArray()) }
    }

    private fun ageCache(file: Path, hours: Long) {
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(Duration.ofHours(hours))))
    }

    @Test
    fun `versions served from per-repository cache`() {
        seedMetadata(unreachableUrl, listOf("3.5.0", "3.1.0", "3.1.0-RC1", "3.6.999-SNAPSHOT", "2.8.3", "0.5.0"))
        val repo = TemplateRepository(unreachableUrl, cacheDir)

        assertEquals(listOf("2.8.3", "3.1.0", "3.5.0"), repo.fetchVersions())
        assertEquals(listOf("2.8.3", "3.1.0-RC1", "3.1.0", "3.5.0", "3.6.999-SNAPSHOT"),
            repo.fetchVersions(includeUnstable = true))
    }

    @Test
    fun `version cache expires after twelve hours`() {
        FakeServer().use { server ->
            val cacheFile = seedMetadata(server.url, listOf("3.0.3"))
            val repo = TemplateRepository(server.url, cacheDir)
            val newMetadata = metadata(listOf("3.0.3", "3.1.0"))
            server.serveMetadata(newMetadata)

            ageCache(cacheFile, 11)
            assertEquals(listOf("3.0.3"), repo.fetchVersions())
            assertTrue(server.requests.isEmpty())

            ageCache(cacheFile, 13)
            assertEquals(listOf("3.0.3", "3.1.0"), repo.fetchVersions())
            assertEquals(newMetadata, Files.readString(cacheFile))
            assertEquals(listOf("3.0.3", "3.1.0"), repo.fetchVersions())
            assertEquals(1, server.requests.size)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `explicit version missing from fresh cache gets one lookup`(versionPublished: Boolean) {
        FakeServer().use { server ->
            val cacheFile = seedMetadata(server.url, listOf("3.0.3"))
            val repo = TemplateRepository(server.url, cacheDir)
            val published = if (versionPublished) listOf("3.0.3", "3.1.0") else listOf("3.0.3")
            server.serveMetadata(metadata(published))

            assertTrue(repo.versionCatalog(requestedVersion = "3.0.3").fromFreshCache)
            assertTrue(server.requests.isEmpty())

            val refreshed = repo.versionCatalog(requestedVersion = "3.1.0")
            assertEquals(published, refreshed.versions)
            assertFalse(refreshed.fromFreshCache)
            assertFalse(refreshed.stale)
            assertEquals(metadata(published), Files.readString(cacheFile))
            assertEquals(1, server.requests.size)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "<html><version>3.1.0</version></html>",
        "<metadata><versioning><versions><version>3.1.0</version>",
        "<metadata><versioning><versions><version>garbage</version></versions></versioning></metadata>",
        "<!DOCTYPE metadata [<!ENTITY release '3.1.0'>]>" +
            "<metadata><versioning><versions><version>&release;</version></versions></versioning></metadata>",
    ])
    fun `invalid metadata response preserves the last valid cache`(body: String) {
        FakeServer().use { server ->
            val cacheFile = seedMetadata(server.url, listOf("3.0.3"))
            ageCache(cacheFile, 13)
            val lastModified = Files.getLastModifiedTime(cacheFile)
            server.serveMetadata(body)

            val result = TemplateRepository(server.url, cacheDir).versionCatalog()
            assertEquals(listOf("3.0.3"), result.versions)
            assertTrue(result.stale)
            assertEquals(metadata(listOf("3.0.3")), Files.readString(cacheFile))
            assertEquals(lastModified, Files.getLastModifiedTime(cacheFile))
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun `failed explicit lookup falls back to valid cached versions`() {
        FakeServer().use { server ->
            val cacheFile = seedMetadata(server.url, listOf("3.0.3"))
            server.on("GET", metadataPath) { FakeServer.Response(503) }

            val result = TemplateRepository(server.url, cacheDir).versionCatalog(requestedVersion = "3.1.0")
            assertEquals(listOf("3.0.3"), result.versions)
            assertTrue(result.stale)
            assertEquals(metadata(listOf("3.0.3")), Files.readString(cacheFile))
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun `invalid fresh cache is replaced only after a valid response`() {
        FakeServer().use { server ->
            val cacheFile = seedMetadata(server.url, emptyList())
            Files.writeString(cacheFile, "<html>proxy error</html>")
            val repo = TemplateRepository(server.url, cacheDir)
            server.on("GET", metadataPath) { FakeServer.Response(503) }

            assertThrows(IOException::class.java) { repo.fetchVersions() }
            assertEquals("<html>proxy error</html>", Files.readString(cacheFile))

            val newMetadata = metadata(listOf("3.1.0"))
            server.serveMetadata(newMetadata)
            assertEquals(listOf("3.1.0"), repo.fetchVersions())
            assertEquals(newMetadata, Files.readString(cacheFile))
            assertEquals(2, server.requests.size)
        }
    }

    @Test
    fun `template jar download reports byte progress and is cached`() {
        val body = ByteArray(200_000) { it.toByte() }.also { it[0] = 0x50; it[1] = 0x4B; it[2] = 3; it[3] = 4 }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requests = 0
        server.createContext("/repo") { exchange ->
            requests++
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val repo = TemplateRepository("http://127.0.0.1:${server.address.port}/repo", cacheDir)
            val reported = mutableListOf<Pair<Long, Long>>()

            val jar = repo.templatesJar("3.0.1") { done, total -> reported += done to total }

            assertEquals(body.size.toLong(), Files.size(jar))
            assertEquals(body.size.toLong() to body.size.toLong(), reported.last())
            assertTrue(reported.all { it.second == body.size.toLong() }, reported.toString())
            assertTrue(reported.zipWithNext().all { (a, b) -> a.first < b.first }, "progress must grow: $reported")

            reported.clear()
            assertEquals(jar, repo.templatesJar("3.0.1") { done, total -> reported += done to total })
            assertEquals(1, requests, "a cached jar is not downloaded again")
            assertTrue(reported.isEmpty(), "a cache hit reports no progress")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `versions with unexpected characters are rejected`() {
        // Version strings flow into file names and process arguments; a
        // malicious repository must not smuggle shell metacharacters through.
        seedMetadata(unreachableUrl, listOf("3.0.1", "2.9.0'; touch pwned #", "2.9.0/../../etc", "latest", "4"))
        val repo = TemplateRepository(unreachableUrl, cacheDir)

        assertEquals(listOf("3.0.1"), repo.fetchVersions(includeUnstable = true))
    }

    @Test
    fun `cache is keyed by repository url`() {
        seedMetadata(unreachableUrl, listOf("3.0.1"))
        // A different repository must NOT see the first repository's cache.
        val other = TemplateRepository(otherUnreachableUrl, cacheDir)
        assertThrows(IOException::class.java) { other.fetchVersions() }
    }

    @Test
    fun `corrupt cached jar is not returned`() {
        val repoDir = cacheDir.resolve(unreachableUrl.replace(Regex("[^a-zA-Z0-9]"), ""))
        Files.createDirectories(repoDir)
        val jar = repoDir.resolve("jmix-studio-templates-3.0.1.jar")
        Files.writeString(jar, "<html>captive portal</html>")

        val repo = TemplateRepository(unreachableUrl, cacheDir)
        // Corrupt file must be discarded and a re-download attempted (which fails offline).
        assertThrows(Exception::class.java) { repo.templatesJar("3.0.1") }
    }

    @Test
    fun `valid cached jar is returned without network`() {
        val repoDir = cacheDir.resolve(unreachableUrl.replace(Regex("[^a-zA-Z0-9]"), ""))
        Files.createDirectories(repoDir)
        val jar = repoDir.resolve("jmix-studio-templates-3.0.1.jar")
        // Minimal zip: empty archive starts with PK\x05\x06; use PK\x03\x04 local header.
        Files.write(jar, byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0))

        val repo = TemplateRepository(unreachableUrl, cacheDir)
        assertEquals(jar, repo.templatesJar("3.0.1"))
    }
}
