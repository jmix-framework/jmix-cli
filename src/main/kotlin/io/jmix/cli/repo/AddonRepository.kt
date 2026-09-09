package io.jmix.cli.repo

import io.jmix.cli.addon.AddonCatalog
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/** Studio's public catalog, independent of the selected Maven repository. */
class AddonRepository(
    private val sourceUrl: String = DEFAULT_CATALOG_URL,
    cacheDir: Path = Path.of(System.getProperty("user.home"), ".jmix", "addons"),
) {
    internal val cacheFile: Path = cacheDir.resolve(
        MessageDigest.getInstance("SHA-256").digest(sourceUrl.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) } + ".json",
    )

    fun catalog(): AddonCatalog {
        val cached = runCatching { AddonCatalog.parse(Files.readString(cacheFile)) }.getOrNull()
        if (cached != null && Files.getLastModifiedTime(cacheFile).toInstant().isAfter(Instant.now().minus(Duration.ofHours(24)))) {
            return cached
        }
        return try {
            val response = http.send(
                HttpRequest.newBuilder(URI.create(sourceUrl)).timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
            val json = response.body().use { body ->
                if (response.statusCode() != 200) throw IOException("HTTP ${response.statusCode()}")
                val bytes = body.readNBytes(MAX_CATALOG_BYTES + 1)
                if (bytes.size > MAX_CATALOG_BYTES) throw IOException("Add-on catalog exceeds the size limit")
                bytes.toString(Charsets.UTF_8)
            }
            val result = AddonCatalog.parse(json)
            Files.createDirectories(cacheFile.parent)
            val tmp = Files.createTempFile(cacheFile.parent, "catalog-", ".tmp")
            try {
                Files.writeString(tmp, json)
                Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(tmp)
            }
            result
        } catch (e: Exception) {
            cached ?: throw IOException("Cannot load the add-on catalog from $sourceUrl and no valid cache is available. ${e.message}", e)
        }
    }

    companion object {
        const val DEFAULT_CATALOG_URL = "https://jmix.io/studio-addons/3.0/AppComponents.json"
        private const val MAX_CATALOG_BYTES = 2 * 1024 * 1024
        private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build()
    }
}
