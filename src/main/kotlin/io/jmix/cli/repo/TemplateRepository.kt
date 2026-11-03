package io.jmix.cli.repo

import io.jmix.cli.util.JmixVersionComparator
import io.jmix.cli.util.PlatformVersions
import io.jmix.cli.util.contentLength
import io.jmix.cli.util.copyWithProgress
import io.jmix.cli.util.isZip
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
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathFactory
import javax.xml.xpath.XPathNodes
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

internal data class TemplateVersionCatalog(
    val versions: List<String>,
    val fromFreshCache: Boolean = false,
    val stale: Boolean = false,
) {
    fun available(includeUnstable: Boolean): List<String> {
        val available = if (includeUnstable) versions else versions.filterNot(PlatformVersions::isUnstable)
        if (available.isEmpty()) {
            throw IOException("No stable template versions found. Use --include-unstable to list pre-releases.")
        }
        return available
    }
}

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
    private val metadataUrl = "$artifactBaseUrl/maven-metadata.xml"

    // Cache is keyed by repository URL (Studio's REPO_DIR_SKIP_CHARS_REGEX scheme)
    // so switching --repository never serves another repo's data.
    private val repoCacheDir: Path =
        cacheDir.resolve(repositoryUrl.replace(Regex("[^a-zA-Z0-9]"), ""))

    /**
     * Available template versions, ascending (Studio ordering). Versions below
     * 1.0 are dropped; unstable versions (RC, SNAPSHOT, beta) are dropped unless
     * requested. New releases are discovered from the repository metadata.
     * Fetched from maven-metadata.xml, cached for [VERSION_CACHE_TTL]; falls
     * back to the cache when offline.
     */
    fun fetchVersions(includeUnstable: Boolean = false): List<String> =
        versionCatalog().available(includeUnstable)

    /**
     * Path to the templates jar for [version], downloading into the cache if
     * absent or corrupt. Snapshot versions are re-fetched on every run (falling
     * back to the cache offline) so they never go stale. A download reports
     * [onProgress] as (bytes so far, total or -1); a cache hit reports nothing.
     */
    fun templatesJar(version: String, onProgress: (Long, Long) -> Unit = { _, _ -> }): Path {
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
                downloadJar(version, jar, onProgress)
            } catch (e: Exception) {
                jar
            }
        }
        return downloadJar(version, jar, onProgress)
    }

    /** True when the repository host answers; used by the environment check. */
    fun isReachable(): Boolean = try {
        val response = http.send(
            HttpRequest.newBuilder(URI.create(metadataUrl))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(10))
                .build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        response.statusCode() in 200..399
    } catch (e: Exception) {
        false
    }

    private fun downloadJar(version: String, jar: Path, onProgress: (Long, Long) -> Unit): Path {
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
            copyWithProgress(response.body(), tmp, response.contentLength(), onProgress)
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

    /** A requested version missing from a fresh cache triggers the same single GET as an expired cache. */
    internal fun versionCatalog(requestedVersion: String? = null): TemplateVersionCatalog {
        val metadataCache = repoCacheDir.resolve("maven-metadata.xml")
        val cacheTime = try {
            Files.getLastModifiedTime(metadataCache).toInstant()
        } catch (_: IOException) {
            null
        }
        val cached = try {
            Files.newInputStream(metadataCache).use { parseVersions(it.readNBytes(MAX_METADATA_BYTES + 1)) }
        } catch (_: IOException) {
            null
        }
        if (cached != null && cacheTime != null && cacheTime.isAfter(Instant.now().minus(VERSION_CACHE_TTL)) &&
            (requestedVersion == null || requestedVersion in cached)
        ) {
            return TemplateVersionCatalog(cached, fromFreshCache = true)
        }
        return try {
            val response = http.send(
                HttpRequest.newBuilder(URI.create(metadataUrl))
                    .timeout(Duration.ofSeconds(15)).GET().build(),
                HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), MAX_METADATA_BYTES.toLong()),
            )
            if (response.statusCode() != 200) {
                throw IOException("HTTP ${response.statusCode()}")
            }
            val versions = parseVersions(response.body())
            Files.createDirectories(repoCacheDir)
            val tmp = Files.createTempFile(repoCacheDir, "metadata", ".tmp")
            try {
                Files.write(tmp, response.body())
                Files.move(tmp, metadataCache, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(tmp)
            }
            TemplateVersionCatalog(versions)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while fetching template versions from $metadataUrl.", e)
        } catch (e: IOException) {
            cached?.let { TemplateVersionCatalog(it, stale = true) } ?: throw IOException(
                "Cannot load template versions from $metadataUrl and no valid cache is available. ${e.message}", e,
            )
        }
    }

    private fun parseVersions(xml: ByteArray): List<String> {
        if (xml.size > MAX_METADATA_BYTES) throw IOException("Template metadata exceeds the size limit.")
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        val builder = factory.newDocumentBuilder()
        builder.setErrorHandler(object : DefaultHandler() {
            override fun error(e: SAXParseException) = throw e
            override fun fatalError(e: SAXParseException) = throw e
        })
        val document = try {
            builder.parse(xml.inputStream())
        } catch (e: SAXException) {
            throw IOException("Invalid template metadata.", e)
        }
        val nodes = XPathFactory.newInstance().newXPath()
            .evaluateExpression("/metadata/versioning/versions/version", document, XPathNodes::class.java)
        return nodes.asSequence()
            .map { it.textContent.trim() }
            // Version strings flow into file names and process arguments.
            .filter { TEMPLATE_VERSION_SHAPE.matches(it) && PlatformVersions.isSupportedPlatformVersion(it) }
            .distinct()
            .sortedWith(JmixVersionComparator)
            .toList()
            .ifEmpty { throw IOException("No usable versions in template metadata.") }
    }

    companion object {
        // Constants ported from Studio's CubaConstants.
        const val DEFAULT_REPOSITORY_URL = "https://global.repo.jmix.io/repository/public"
        const val TEMPLATES_GROUP_PATH = "io/jmix/templates/studio"
        const val TEMPLATES_ARTIFACT_ID = "jmix-studio-templates"

        private val VERSION_CACHE_TTL: Duration = Duration.ofHours(12)
        private const val MAX_METADATA_BYTES = 1024 * 1024

        private val TEMPLATE_VERSION_SHAPE = Regex("[0-9]+\\.[0-9]+(?:[.-][0-9A-Za-z._+-]+)?")
        internal val VERSION_SHAPE = Regex("[0-9A-Za-z._+-]+")

        fun defaultCacheDir(): Path =
            Path.of(System.getProperty("user.home"), ".jmix", "templates")
    }
}
