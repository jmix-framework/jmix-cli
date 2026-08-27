package io.jmix.cli.repo

import io.jmix.cli.util.JmixVersionComparator
import io.jmix.cli.util.PlatformVersions
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant

/**
 * Access to the jmix-studio-templates Maven artifact: version list from
 * maven-metadata.xml and template jar download with a local cache under
 * ~/.jmix/templates/<sanitized-repo-url>/ (per-repository, like Studio).
 */
class TemplateRepository(
    private val repositoryUrl: String = DEFAULT_REPOSITORY_URL,
    cacheDir: Path = defaultCacheDir(),
) {
    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val artifactBaseUrl =
        "${repositoryUrl.trimEnd('/')}/$TEMPLATES_GROUP_PATH/$TEMPLATES_ARTIFACT_ID"

    // Cache is keyed by repository URL (Studio's REPO_DIR_SKIP_CHARS_REGEX scheme)
    // so switching --repository never serves another repo's data.
    private val repoCacheDir: Path =
        cacheDir.resolve(repositoryUrl.replace(Regex("[^a-zA-Z0-9]"), ""))

    /**
     * Available template versions, ascending (Studio ordering). Filtering ported
     * from Studio: versions below 1.0 and future/unknown versions are dropped;
     * unstable versions (RC, SNAPSHOT, beta) are dropped unless requested.
     * Fetched from maven-metadata.xml, cached for [VERSION_CACHE_TTL]; falls
     * back to the cache when offline.
     */
    fun fetchVersions(includeUnstable: Boolean = false): List<String> {
        val xml = fetchVersionsXml()
        val versions = Regex("<version>([^<]+)</version>")
            .findAll(xml)
            .map { it.groupValues[1].trim() }
            // Version strings flow into file names and process arguments;
            // never accept shell or path metacharacters from a repository.
            .filter { VERSION_SHAPE.matches(it) }
            .distinct()
            .filter { PlatformVersions.isSupportedPlatformVersion(it) }
            .filter { !PlatformVersions.isFutureUnknownVersion(it) }
            .filter { includeUnstable || !PlatformVersions.isUnstable(it) }
            .sortedWith(JmixVersionComparator)
            .toList()
        if (versions.isEmpty()) {
            throw IOException("No template versions found at $artifactBaseUrl")
        }
        return versions
    }

    /**
     * Path to the templates jar for [version], downloading into the cache if
     * absent or corrupt. Snapshot versions are re-fetched on every run (falling
     * back to the cache offline) so they never go stale.
     */
    fun templatesJar(version: String): Path {
        val jar = repoCacheDir.resolve("$TEMPLATES_ARTIFACT_ID-$version.jar")
        val cached = Files.exists(jar) && isZip(jar)
        if (Files.exists(jar) && !cached) Files.deleteIfExists(jar)

        if (cached && !version.endsWith("-SNAPSHOT")) {
            // Mark the jar as used: cache pruning keeps recently used files.
            runCatching { Files.setLastModifiedTime(jar, FileTime.from(Instant.now())) }
            return jar
        }
        if (cached) {
            // Snapshot: prefer a fresh remote copy, keep the cache as offline fallback.
            return try {
                downloadJar(version, jar)
            } catch (e: Exception) {
                jar
            }
        }
        return downloadJar(version, jar)
    }

    /** True when the repository host answers; used by the environment check. */
    fun isReachable(): Boolean = try {
        val response = http.send(
            HttpRequest.newBuilder(URI.create("$artifactBaseUrl/maven-metadata.xml"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(10))
                .build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        response.statusCode() in 200..399
    } catch (e: Exception) {
        false
    }

    private fun downloadJar(version: String, jar: Path): Path {
        Files.createDirectories(repoCacheDir)
        val url = "$artifactBaseUrl/$version/$TEMPLATES_ARTIFACT_ID-$version.jar"
        val response = http.send(
            HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30)).GET().build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        if (response.statusCode() != 200) {
            throw IOException("Failed to download templates ($url): HTTP ${response.statusCode()}")
        }
        val tmp = Files.createTempFile(repoCacheDir, "download", ".tmp")
        try {
            response.body().use { Files.copy(it, tmp, StandardCopyOption.REPLACE_EXISTING) }
            if (!isZip(tmp)) {
                // Captive portal / proxy error page served as 200 — never cache it.
                throw IOException("Downloaded templates file is not a valid jar ($url)")
            }
            Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
        return jar
    }

    private fun isZip(file: Path): Boolean = try {
        Files.newInputStream(file).use { input ->
            val magic = input.readNBytes(4)
            magic.size == 4 && magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte()
        }
    } catch (e: IOException) {
        false
    }

    private fun fetchVersionsXml(): String {
        val metadataCache = repoCacheDir.resolve("maven-metadata.xml")
        if (isCacheFresh(metadataCache)) {
            return Files.readString(metadataCache)
        }
        return try {
            val response = http.send(
                HttpRequest.newBuilder(URI.create("$artifactBaseUrl/maven-metadata.xml"))
                    .timeout(Duration.ofSeconds(15)).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            if (response.statusCode() != 200) {
                throw IOException("HTTP ${response.statusCode()} from $artifactBaseUrl/maven-metadata.xml")
            }
            Files.createDirectories(repoCacheDir)
            val tmp = Files.createTempFile(repoCacheDir, "metadata", ".tmp")
            Files.writeString(tmp, response.body())
            Files.move(
                tmp, metadataCache,
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
            response.body()
        } catch (e: Exception) {
            // Offline fallback: use a stale cache when present.
            if (Files.exists(metadataCache)) Files.readString(metadataCache)
            else throw IOException(
                "Cannot reach $repositoryUrl and no cached version list exists. " +
                    "Check your network connection.", e,
            )
        }
    }

    private fun isCacheFresh(file: Path): Boolean =
        Files.exists(file) &&
            Files.getLastModifiedTime(file).toInstant().isAfter(Instant.now().minus(VERSION_CACHE_TTL))

    companion object {
        // Constants ported from Studio's CubaConstants.
        const val DEFAULT_REPOSITORY_URL = "https://global.repo.jmix.io/repository/public"
        const val TEMPLATES_GROUP_PATH = "io/jmix/templates/studio"
        const val TEMPLATES_ARTIFACT_ID = "jmix-studio-templates"

        private val VERSION_CACHE_TTL: Duration = Duration.ofHours(24)

        private val VERSION_SHAPE = Regex("[0-9A-Za-z._+-]+")

        fun defaultCacheDir(): Path =
            Path.of(System.getProperty("user.home"), ".jmix", "templates")
    }
}
