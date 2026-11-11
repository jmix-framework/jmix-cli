package io.jmix.cli.repo

import io.jmix.cli.template.TemplateCatalog.Companion.PROJECT_TEMPLATES_DIR
import io.jmix.cli.util.Http
import io.jmix.cli.util.JmixVersionComparator
import io.jmix.cli.util.PlatformVersions
import io.jmix.cli.util.contentLength
import io.jmix.cli.util.copyHttpBody
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.ZipFile
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
 * `~/.jmix/templates/sha256-<hash>/` keyed by the repository URL.
 */
class TemplateRepository(
    repositoryUrl: String = DEFAULT_REPOSITORY_URL,
    cacheDir: Path = defaultCacheDir(),
) {
    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val normalizedRepositoryUrl = repositoryUrl.trimEnd('/')
    private val artifactBaseUrl =
        "$normalizedRepositoryUrl/$TEMPLATES_GROUP_PATH/$TEMPLATES_ARTIFACT_ID"
    private val metadataUrl = "$artifactBaseUrl/maven-metadata.xml"

    // The prefix separates the new keys from legacy directories with ambiguous origins.
    internal val repoCacheDir: Path = cacheDir.resolve(
        "sha256-" + HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(normalizedRepositoryUrl.toByteArray()),
        ),
    )

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
        val cached = isValidTemplatesJar(jar)

        if (cached && !version.endsWith("-SNAPSHOT")) {
            // Mark the jar as used: cache pruning keeps recently used files.
            runCatching { Files.setLastModifiedTime(jar, FileTime.from(Instant.now())) }
            return jar
        }
        return try {
            downloadJar(version, jar, onProgress)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while downloading templates from $normalizedRepositoryUrl.", e)
        } catch (e: Exception) {
            // Another process may have changed the file while the refresh was in flight.
            if (isValidTemplatesJar(jar)) jar else throw e
        }
    }

    /** True when the repository host answers; used by the environment check. */
    fun isReachable(): Boolean = try {
        val response = Http.send(
            http,
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
        val tmp = Files.createTempFile(repoCacheDir, "download", ".jar")
        try {
            val response = http.send(
                HttpRequest.newBuilder(URI.create(url))
                    .timeout(TEMPLATE_DOWNLOAD_TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
            if (response.statusCode() != 200) {
                response.body().use {
                    throw IOException("Failed to download templates ($url): HTTP ${response.statusCode()}")
                }
            }
            copyHttpBody(response.body(), tmp, response.contentLength(), TEMPLATE_DOWNLOAD_TIMEOUT, onProgress)
            if (!isValidTemplatesJar(tmp)) {
                throw IOException("Downloaded templates file is not a valid jar ($url)")
            }
            Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
        return jar
    }

    private fun isValidTemplatesJar(path: Path): Boolean {
        if (!Files.isRegularFile(path)) return false
        return try {
            // Check the template layout through the same filesystem provider as TemplateCatalog.
            FileSystems.newFileSystem(path).use { zip ->
                Files.walk(zip.getPath(PROJECT_TEMPLATES_DIR)).use { paths ->
                    if (paths.noneMatch { it.nameCount >= 3 && Files.isRegularFile(it) }) return false
                }
            }
            ZipFile(path.toFile()).use { zip ->
                val checksum = CRC32()
                val sink = OutputStream.nullOutputStream()
                for (entry in zip.entries()) {
                    checksum.reset()
                    val content = zip.getInputStream(entry) ?: return false
                    val size = CheckedInputStream(content, checksum).use { it.transferTo(sink) }
                    if (size != entry.size || checksum.value != entry.crc) return false
                }
            }
            true
        } catch (_: IOException) {
            false
        }
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
            val response = Http.sendBytes(
                http,
                HttpRequest.newBuilder(URI.create(metadataUrl))
                    .timeout(Duration.ofSeconds(15)).GET().build(),
                MAX_METADATA_BYTES,
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
        private val TEMPLATE_DOWNLOAD_TIMEOUT: Duration = Duration.ofSeconds(30)
        private const val MAX_METADATA_BYTES = 1024 * 1024

        private val TEMPLATE_VERSION_SHAPE = Regex("[0-9]+\\.[0-9]+(?:[.-][0-9A-Za-z._+-]+)?")
        internal val VERSION_SHAPE = Regex("[0-9A-Za-z._+-]+")

        fun defaultCacheDir(): Path =
            Path.of(System.getProperty("user.home"), ".jmix", "templates")
    }
}
