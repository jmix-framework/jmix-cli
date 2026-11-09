package io.jmix.cli.telemetry

import com.google.gson.Gson
import io.jmix.cli.util.Http
import io.jmix.cli.util.cliVersion
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.UUID

internal enum class UsageEvent(val ide: String) {
    PROJECT_CREATED("cli-new"),
    TRIAL_DOWNLOADED("cli-trial"),
    USER_LOGIN("cli-login"),
}

internal class UsageStatistics(
    private val file: Path = Path.of(System.getProperty("user.home"), ".jmix", "telemetry", "installation-id"),
    private val endpoint: String = "https://usage-stat.jmix.io/rest/event/open",
    private val clock: Clock = Clock.systemUTC(),
    private val timeout: Duration = Duration.ofMillis(750),
) {
    private val gson = Gson()
    private val client by lazy {
        HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

    fun record(event: UsageEvent, jmixVersion: String? = null, addonIds: List<String> = emptyList()) {
        try {
            val id = installationId() ?: return
            val body = OpenEvent(
                studioId = id,
                eventDate = clock.instant().truncatedTo(ChronoUnit.SECONDS).toString(),
                studioVersion = cliVersion() ?: "dev",
                os = System.getProperty("os.name"),
                ide = event.ide,
                platformVersion = jmixVersion?.takeIf(PLATFORM_VERSION::matches),
                addons = addonIds,
            )
            val request = Http.request(endpoint, timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build()
            Http.send(client, request, HttpResponse.BodyHandlers.discarding())
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            // Statistics must not affect the command that already succeeded.
        }
    }

    private fun installationId(): String? {
        val directory = Files.createDirectories(file.toAbsolutePath().parent)
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) return null
        readId()?.let { return it }

        return synchronized(ID_LOCK) {
            val lock = directory.resolve(".installation-id.lock")
            if (Files.exists(lock, NOFOLLOW_LINKS) && !Files.isRegularFile(lock, NOFOLLOW_LINKS)) return null
            FileChannel.open(lock, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                val writeLock = channel.tryLock() ?: return readId()
                writeLock.use {
                    readId()?.let { return it }
                    if (!Files.isRegularFile(file, NOFOLLOW_LINKS) && !Files.notExists(file, NOFOLLOW_LINKS)) return null

                    val id = UUID.randomUUID().toString()
                    val temporary = Files.createTempFile(directory, "installation-id", ".tmp")
                    try {
                        Files.writeString(temporary, id)
                        Files.move(temporary, file, ATOMIC_MOVE, REPLACE_EXISTING)
                    } finally {
                        Files.deleteIfExists(temporary)
                    }
                    id
                }
            }
        }
    }

    private fun readId(): String? {
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) return null
        val stored = Files.newInputStream(file, NOFOLLOW_LINKS).use {
            String(it.readNBytes(UUID_LENGTH + 1), Charsets.UTF_8)
        }
        return stored.takeIf { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) }
    }

    // The collector requires both arrays even when no project metadata is sent.
    private data class OpenEvent(
        val studioId: String,
        val eventDate: String,
        val studioVersion: String,
        val os: String,
        val ide: String,
        val platformVersion: String?,
        val addons: List<String>,
        val databases: List<String> = emptyList(),
    )

    private companion object {
        val ID_LOCK = Any()
        // Repository versions can contain private build labels.
        val PLATFORM_VERSION = Regex("[0-9]{1,6}(\\.[0-9]{1,6}){1,2}(-(RC[0-9]{1,4}|SNAPSHOT))?")
        const val UUID_LENGTH = 36
    }
}
