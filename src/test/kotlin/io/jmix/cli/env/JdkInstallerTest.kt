package io.jmix.cli.env

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class JdkInstallerTest {

    @Test
    fun `maps os and arch to adoptium api values`() {
        assertEquals("mac", JdkInstaller.adoptiumOs("Mac OS X"))
        assertEquals("windows", JdkInstaller.adoptiumOs("Windows 11"))
        assertEquals("linux", JdkInstaller.adoptiumOs("Linux"))
        assertEquals("aarch64", JdkInstaller.adoptiumArch("aarch64"))
        assertEquals("aarch64", JdkInstaller.adoptiumArch("arm64"))
        assertEquals("x64", JdkInstaller.adoptiumArch("amd64"))
        assertEquals("x64", JdkInstaller.adoptiumArch("x86_64"))
    }

    @Test
    fun `builds assets url for the requested platform`() {
        assertEquals(
            "https://api.adoptium.net/v3/assets/latest/25/hotspot" +
                "?architecture=aarch64&image_type=jdk&os=mac&vendor=eclipse",
            JdkInstaller.assetsUrl(25, os = "mac", arch = "aarch64"),
        )
    }

    @Test
    fun `picks the jdk asset from the api response`() {
        val json = """
            [
              {"release_name":"jdk-25.0.3+9",
               "binary":{"image_type":"debugimage",
                 "package":{"link":"https://x/debug.tar.gz","checksum":"aa","name":"debug.tar.gz","size":1}}},
              {"release_name":"jdk-25.0.3+9",
               "binary":{"image_type":"jdk",
                 "package":{"link":"https://x/jdk.tar.gz","checksum":"bb","name":"jdk.tar.gz","size":2}}}
            ]
        """.trimIndent()

        val asset = JdkInstaller.pickAsset(json)

        assertEquals("jdk-25.0.3+9", asset.releaseName)
        assertEquals("https://x/jdk.tar.gz", asset.pkg.link)
        assertEquals("bb", asset.pkg.checksum)
    }

    @Test
    fun `fails when no jdk asset is available`() {
        assertThrows(IOException::class.java) { JdkInstaller.pickAsset("[]") }
    }

    @Test
    fun `unpacks zip archives`(@TempDir tempDir: Path) {
        val archive = zipWith(tempDir, "jdk-25/bin/java.exe" to "binary", "jdk-25/release" to "JAVA_VERSION=\"25\"")
        val dest = Files.createDirectory(tempDir.resolve("out"))

        JdkInstaller.unpackZip(archive, dest)

        assertEquals("binary", Files.readString(dest.resolve("jdk-25/bin/java.exe")))
        assertEquals("JAVA_VERSION=\"25\"", Files.readString(dest.resolve("jdk-25/release")))
    }

    @Test
    fun `rejects zip entries escaping the destination`(@TempDir tempDir: Path) {
        val archive = zipWith(tempDir, "../evil.txt" to "boom")
        val dest = Files.createDirectory(tempDir.resolve("out"))

        assertThrows(IOException::class.java) { JdkInstaller.unpackZip(archive, dest) }
        assertTrue(Files.notExists(tempDir.resolve("evil.txt")))
    }

    @Test
    fun `locates java home in plain and macos bundle layouts`(@TempDir tempDir: Path) {
        assertNull(JdkInstaller.javaHomeIn(tempDir.resolve("missing")))
        val exe = if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"

        val plain = tempDir.resolve("plain/jdk")
        Files.createDirectories(plain.resolve("bin"))
        Files.createFile(plain.resolve("bin/$exe"))
        assertEquals(plain, JdkInstaller.javaHomeIn(plain))

        val bundle = tempDir.resolve("bundle/jdk")
        Files.createDirectories(bundle.resolve("Contents/Home/bin"))
        Files.createFile(bundle.resolve("Contents/Home/bin/$exe"))
        assertEquals(bundle.resolve("Contents/Home"), JdkInstaller.javaHomeIn(bundle))
    }

    private fun zipWith(dir: Path, vararg entries: Pair<String, String>): Path {
        val archive = dir.resolve("archive.zip")
        ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return archive
    }
}
