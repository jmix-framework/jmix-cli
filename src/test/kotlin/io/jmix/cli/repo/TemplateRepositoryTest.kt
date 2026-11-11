package io.jmix.cli.repo

import io.jmix.cli.FakeServer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
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
    private val artifactPath = "/io/jmix/templates/studio/jmix-studio-templates"
    private val metadataPath = "$artifactPath/maven-metadata.xml"
    private val sourceEntry = "project/application/source.txt"

    private fun jarName(version: String) = "jmix-studio-templates-$version.jar"

    private fun FakeServer.serveJar(version: String, body: ByteArray) {
        on("GET", "$artifactPath/$version/${jarName(version)}") { FakeServer.Response(200, body) }
    }

    private fun seedMetadata(repoUrl: String, versions: List<String>): Path {
        val dir = TemplateRepository(repoUrl, cacheDir).repoCacheDir
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
        val body = jarWithEntry("project/application/payload.bin", ByteArray(200_000) { it.toByte() })
        FakeServer().use { server ->
            server.serveJar("3.0.1", body)
            val repo = TemplateRepository(server.url, cacheDir)
            val reported = mutableListOf<Pair<Long, Long>>()

            val jar = repo.templatesJar("3.0.1") { done, total -> reported += done to total }

            assertEquals(body.size.toLong(), Files.size(jar))
            assertEquals(body.size.toLong() to body.size.toLong(), reported.last())
            assertTrue(reported.all { it.second == body.size.toLong() }, reported.toString())
            assertTrue(reported.zipWithNext().all { (a, b) -> a.first < b.first }, "progress must grow: $reported")

            reported.clear()
            assertEquals(jar, repo.templatesJar("3.0.1") { done, total -> reported += done to total })
            assertEquals(1, server.requests.size, "a cached jar is not downloaded again")
            assertTrue(reported.isEmpty(), "a cache hit reports no progress")
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
    fun `repositories with the same legacy key have independent offline caches`() {
        val version = "3.0.1"
        val jarName = jarName(version)
        val jarPath = "$artifactPath/$version/$jarName"
        val versionsByPath = mapOf(
            "/repo-a" to listOf(version, "3.0.2"),
            "/repoa" to listOf(version, "3.0.3"),
        )

        val serverUrl = FakeServer().use { server ->
            val legacyKey = Regex("[^a-zA-Z0-9]")
            val legacyDir = cacheDir.resolve(legacyKey.replace("${server.url}/repo-a", ""))
            Files.createDirectories(legacyDir)
            Files.writeString(legacyDir.resolve("maven-metadata.xml"), metadata(listOf("3.0.0")))
            Files.write(legacyDir.resolve(jarName), jarWithSource("legacy"))

            for ((path, versions) in versionsByPath) {
                server.on("GET", "$path$metadataPath") { FakeServer.Response(200, metadata(versions).toByteArray()) }
                server.on("GET", "$path$jarPath") { FakeServer.Response(200, jarWithSource(path)) }
                val repo = TemplateRepository(server.url + path, cacheDir)
                assertEquals(versions, repo.fetchVersions())
                repo.templatesJar(version)
                ageCache(repo.repoCacheDir.resolve("maven-metadata.xml"), 13)
            }
            assertEquals(
                versionsByPath.keys.flatMap { listOf("$it$metadataPath", "$it$jarPath") },
                server.requests.map { it.path },
            )
            server.url
        }

        for ((path, versions) in versionsByPath) {
            val repo = TemplateRepository("$serverUrl$path/", cacheDir)
            val catalog = repo.versionCatalog()
            assertTrue(catalog.stale)
            assertEquals(versions, catalog.versions)
            ZipFile(repo.templatesJar(version).toFile()).use { jar ->
                assertEquals(path, jar.getInputStream(jar.getEntry(sourceEntry)).bufferedReader().use { it.readText() })
            }
        }
    }

    private fun jarWithSource(source: String): ByteArray = jarWithEntry(sourceEntry, source.toByteArray())

    private fun jarWithEntry(name: String, content: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            val entry = ZipEntry(name).apply {
                method = ZipEntry.STORED
                size = content.size.toLong()
                crc = CRC32().apply { update(content) }.value
            }
            zip.putNextEntry(entry)
            zip.write(content)
        }
        return bytes.toByteArray()
    }

    @Test
    fun `a truncated cached jar is downloaded again`() {
        val version = "3.0.1"
        val valid = jarWithSource("recovered")
        FakeServer().use { server ->
            val repo = TemplateRepository(server.url, cacheDir)
            Files.createDirectories(repo.repoCacheDir)
            val jar = repo.repoCacheDir.resolve(jarName(version))
            // Keep the local entry and central headers, but remove the end-of-central-directory record.
            Files.write(jar, valid.copyOf(valid.size - 22))
            assertThrows(IOException::class.java) { repo.templatesJar(version) }

            server.serveJar(version, valid)
            assertEquals(jar, repo.templatesJar(version))
            assertArrayEquals(valid, Files.readAllBytes(jar))
            assertEquals(jar, repo.templatesJar(version))
            assertEquals(2, server.requests.size)
        }
    }

    @Test
    fun `a jar without project templates is rejected before caching`() {
        val version = "3.0.1"
        FakeServer().use { server ->
            server.serveJar(version, jarWithEntry("README.txt", "No project templates".toByteArray()))
            val repo = TemplateRepository(server.url, cacheDir)

            assertThrows(IOException::class.java) { repo.templatesJar(version) }
            Files.list(repo.repoCacheDir).use { assertEquals(0L, it.count()) }
        }
    }

    @Test
    fun `a corrupt snapshot refresh keeps the cached jar and can be retried`() {
        val version = "3.1.0-SNAPSHOT"
        val original = jarWithSource("original")
        val updated = jarWithSource("updated")
        val broken = jarWithSource("damaged")
        // Change the stored payload while keeping the recorded CRC and ZIP structure.
        val dataOffset = broken.toString(Charsets.ISO_8859_1).indexOf("damaged")
        broken[dataOffset] = 'x'.code.toByte()
        val repo = FakeServer().use { server ->
            val repo = TemplateRepository(server.url, cacheDir)
            server.serveJar(version, original)
            val jar = repo.templatesJar(version)

            server.serveJar(version, broken)
            assertEquals(jar, repo.templatesJar(version))
            assertArrayEquals(original, Files.readAllBytes(jar))
            Files.list(repo.repoCacheDir).use { assertEquals(listOf(jar), it.toList()) }

            server.serveJar(version, updated)
            assertEquals(jar, repo.templatesJar(version))
            assertArrayEquals(updated, Files.readAllBytes(jar))
            assertEquals(3, server.requests.size)
            repo
        }

        assertArrayEquals(updated, Files.readAllBytes(repo.templatesJar(version)))
    }

    @Test
    fun `valid cached legacy jar is returned without network`() {
        val repo = TemplateRepository(unreachableUrl, cacheDir)
        val repoDir = repo.repoCacheDir
        Files.createDirectories(repoDir)
        val jar = repoDir.resolve(jarName("3.0.1"))
        Files.write(jar, jarWithEntry("project/single-module-application/template.description", "Legacy app".toByteArray()))

        assertEquals(jar, repo.templatesJar("3.0.1"))
    }
}
