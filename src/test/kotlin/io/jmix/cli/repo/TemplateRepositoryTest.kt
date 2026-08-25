package io.jmix.cli.repo

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Offline cache-behavior tests — no network calls succeed from here. */
class TemplateRepositoryTest {

    @TempDir
    lateinit var cacheDir: Path

    private val unreachableUrl = "http://127.0.0.1:1/repo"
    private val otherUnreachableUrl = "http://127.0.0.1:1/other-repo"

    private fun seedMetadata(repoUrl: String, versions: List<String>) {
        val dir = cacheDir.resolve(repoUrl.replace(Regex("[^a-zA-Z0-9]"), ""))
        Files.createDirectories(dir)
        val xml = "<metadata><versioning><versions>" +
            versions.joinToString("") { "<version>$it</version>" } +
            "</versions></versioning></metadata>"
        Files.writeString(dir.resolve("maven-metadata.xml"), xml)
    }

    @Test
    fun `versions served from per-repository cache`() {
        seedMetadata(unreachableUrl, listOf("2.8.3", "3.0.0", "3.0.1", "3.0.0-RC1", "0.5.0", "3.5.0"))
        val repo = TemplateRepository(unreachableUrl, cacheDir)

        // Filters: below 1.0 dropped, future (3.5) dropped, RC dropped by default.
        assertEquals(listOf("2.8.3", "3.0.0", "3.0.1"), repo.fetchVersions())
        assertEquals(listOf("2.8.3", "3.0.0-RC1", "3.0.0", "3.0.1"), repo.fetchVersions(includeUnstable = true))
    }

    @Test
    fun `versions with unexpected characters are rejected`() {
        // Version strings flow into file names and process arguments; a
        // malicious repository must not smuggle shell metacharacters through.
        seedMetadata(unreachableUrl, listOf("3.0.1", "2.9.0'; touch pwned #", "2.9.0/../../etc"))
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
