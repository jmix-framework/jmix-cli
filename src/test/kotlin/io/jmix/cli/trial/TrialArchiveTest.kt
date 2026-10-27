package io.jmix.cli.trial

import io.jmix.cli.FakeServer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TrialArchiveTest {

    @TempDir
    lateinit var dir: Path

    private val root = TRIAL_ROOT
    private fun trialZip(vararg extra: Pair<String, String>) = writeTrialZip(dir.resolve("trial.zip"), *extra)

    @Test
    fun `download sends no credentials and keeps the archive`() {
        val bytes = Files.readAllBytes(trialZip())
        FakeServer().use { server ->
            server.on("GET", "/t.zip") { FakeServer.Response(200, bytes, mapOf("Content-Type" to "application/zip")) }
            val target = dir.resolve("download.zip")
            TrialArchive.download("${server.url}/t.zip", target, { _, _ -> })
            assertArrayEquals(bytes, Files.readAllBytes(target))
            assertNull(server.requests.single().headers.getFirst("Authorization"))
        }
    }

    @Test
    fun `error pages, failed and oversized downloads are rejected`() {
        FakeServer().use { server ->
            server.on("GET", "/html") { FakeServer.Response(200, "<html>login</html>".toByteArray()) }
            server.on("GET", "/denied") { FakeServer.Response(403) }
            server.on("GET", "/big") { FakeServer.Response(200, ByteArray(2048).also { it[0] = 0x50; it[1] = 0x4B }) }
            val target = dir.resolve("download.zip")
            assertTrue(assertThrows<IOException> { TrialArchive.download("${server.url}/html", target, { _, _ -> }) }.message!!.contains("not a zip"))
            assertTrue(assertThrows<IOException> { TrialArchive.download("${server.url}/denied", target, { _, _ -> }) }.message!!.contains("HTTP 403"))
            assertThrows<IOException> { TrialArchive.download("${server.url}/big", target, { _, _ -> }, maxBytes = 1024) }
        }
        // A capability link must never reach a message: unencoded, plain-http and relative links.
        for (link in listOf("https://dl.example/Jmix demo.zip?sig=SECRET", "http://dl.example/t.zip?sig=SECRET", "/rest/t.zip?sig=SECRET")) {
            val error = assertThrows<IOException> { TrialArchive.download(link, dir.resolve("x.zip"), { _, _ -> }) }
            assertFalse(error.message!!.contains("SECRET"), error.message)
        }
    }

    @Test
    fun `links that are not https with a host are refused with a fixed message`() {
        val links = listOf(
            "https://dl.example/Jmix demo.zip?sig=SECRET", "http://dl.example/t.zip?sig=SECRET", "/rest/t.zip?sig=SECRET",
            "ftp://dl.example/t.zip", "https:///t.zip", "http://localhost.example/t.zip", "",
        )
        for (link in links) {
            val error = assertThrows<IOException>(link) { TrialArchive.download(link, dir.resolve("x.zip"), { _, _ -> }) }
            assertEquals("the Jmix Store returned an unusable trial download link", error.message, link)
        }
    }

    @Test
    fun `other top-level entries are ignored`() {
        val parent = dir.resolve("work")
        assertEquals(listOf(parent.resolve(root)), TrialArchive.unpack(trialZip("__MACOSX/x" to "y"), parent))
        assertEquals(listOf(root), Files.list(parent).use { s -> s.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun `every project folder of an archive is unpacked, by name`() {
        val parent = dir.resolve("work")
        val older = "jmix-commercial-addons-demo-2.8.0"
        val zip = trialZip("$older/settings.gradle.kts" to "", "$older/trial_repository/x" to "x")
        assertEquals(listOf(parent.resolve(older), parent.resolve(root)), TrialArchive.unpack(zip, parent))
    }

    @Test
    fun `unpack keeps the layout and makes every wrapper executable`() {
        val parent = dir.resolve("work")
        val projects = TrialArchive.unpack(trialZip(), parent)
        assertEquals(listOf(parent.resolve(root)), projects)
        assertTrue(Files.isRegularFile(parent.resolve("$root/trial_repository/io/jmix/bpm/jmix-bpm/3.1.0/jmix-bpm-3.1.0.jar")))
        assertTrue(Files.isRegularFile(parent.resolve("$root/demo/bpm/build.gradle")))
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
        assertTrue(Files.isExecutable(parent.resolve("$root/gradlew")))
        assertTrue(Files.isExecutable(parent.resolve("$root/demo/bpm/gradlew")))
    }

    @Test
    fun `unpack refuses an existing project folder and writes nothing`() {
        val parent = dir.resolve("work")
        val marker = Files.createDirectories(parent.resolve(root)).resolve("mine.txt").also { Files.writeString(it, "keep") }
        val error = assertThrows<IOException> { TrialArchive.unpack(trialZip(), parent) }
        assertTrue(error.message!!.contains("already exists"), error.message)
        assertEquals("keep", Files.readString(marker))
        assertEquals(listOf(root), Files.list(parent).use { s -> s.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun `archives that are not trial projects or escape the folder are rejected`() {
        val notTrial = dir.resolve("plain.zip").also { zip ->
            ZipOutputStream(Files.newOutputStream(zip)).use { it.putNextEntry(ZipEntry("app/settings.gradle")); it.closeEntry() }
        }
        assertThrows<IOException> { TrialArchive.unpack(notTrial, dir.resolve("a")) }
        assertThrows<IOException> { TrialArchive.unpack(trialZip("../evil.txt" to "x"), dir.resolve("b")) }
        // The staging folder lives inside b/, so an escaping entry would land in b/.
        assertFalse(Files.exists(dir.resolve("b/evil.txt")))
        assertEquals(emptyList<String>(), Files.list(dir.resolve("b")).use { s -> s.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun `jmix version comes from the build files without a trial suffix`() {
        val project = TrialArchive.unpack(trialZip(), dir.resolve("work")).single()
        assertEquals("3.1.0", TrialArchive.jmixVersion(project))
        val legacy = Files.createDirectories(dir.resolve("legacy"))
        Files.writeString(legacy.resolve("build.gradle"), "jmix { bomVersion = '1.4.4.trial' }")
        assertEquals("1.4.4", TrialArchive.jmixVersion(legacy))
        Files.writeString(legacy.resolve("build.gradle"), "jmix { bomVersion = '\$(touch x)' }")
        assertNull(TrialArchive.jmixVersion(legacy))
    }

    @Test
    fun `runnable apps are the demos, or the project itself without demos`() {
        val project = TrialArchive.unpack(trialZip(), dir.resolve("work")).single()
        assertEquals(listOf(project.resolve("demo/bpm"), project.resolve("demo/webdav")), TrialArchive.runnableApps(project))
        val single = Files.createDirectories(dir.resolve("single"))
        assertEquals(listOf(single), TrialArchive.runnableApps(single))
    }
}
