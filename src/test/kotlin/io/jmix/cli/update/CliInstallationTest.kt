package io.jmix.cli.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CliInstallationTest {

    @TempDir
    lateinit var tempDir: Path

    private val checksum = "ab".repeat(32)

    @Test
    fun `detects an installation from a launcher inside a versions directory`() {
        val launcher = tempDir.resolve("versions/$checksum/Contents/MacOS/jmix")

        val installation = CliInstallation.detect(launcher, "macos", "arm64")

        assertEquals(tempDir.toAbsolutePath().normalize(), installation?.installRoot)
        assertEquals(checksum, installation?.currentChecksum)
    }

    @Test
    fun `returns null when the launcher is outside a versions layout`() {
        assertNull(CliInstallation.detect(tempDir.resolve("bin/jmix"), "linux", "x64"))
    }

    @Test
    fun `returns null when the version directory is not a checksum`() {
        assertNull(CliInstallation.detect(tempDir.resolve("versions/latest/bin/jmix"), "linux", "x64"))
    }

    @Test
    fun `returns null without a launcher path or a supported platform`() {
        assertNull(CliInstallation.detect(null, "linux", "x64"))
        assertNull(CliInstallation.detect(tempDir.resolve("versions/$checksum/bin/jmix"), null, "x64"))
        assertNull(CliInstallation.detect(tempDir.resolve("versions/$checksum/bin/jmix"), "linux", null))
    }

    @Test
    fun `detects an installation through the bin directory symlink`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        val launcher = tempDir.resolve("root/versions/$checksum/bin/jmix")
        Files.createDirectories(launcher.parent)
        Files.createFile(launcher)
        val link = tempDir.resolve("bin/jmix")
        Files.createDirectories(link.parent)
        Files.createSymbolicLink(link, launcher)

        val installation = CliInstallation.detect(link, "linux", "x64")

        assertEquals(checksum, installation?.currentChecksum)
        assertEquals(tempDir.resolve("root").toRealPath(), installation?.installRoot)
    }

    @Test
    fun `derives platform specific asset and launcher names`() {
        val macos = CliInstallation(tempDir, checksum, "macos", "arm64")
        assertEquals("jmix-cli-macos-arm64.tar.gz", macos.archiveName)
        assertEquals("jmix.app", macos.imageName)
        assertEquals("Contents/MacOS/jmix", macos.launcherRelativePath)

        val linux = CliInstallation(tempDir, checksum, "linux", "x64")
        assertEquals("jmix-cli-linux-x64.tar.gz", linux.archiveName)
        assertEquals("jmix", linux.imageName)
        assertEquals("bin/jmix", linux.launcherRelativePath)

        val windows = CliInstallation(tempDir, checksum, "windows", "x64")
        assertEquals("jmix-cli-windows-x64.zip", windows.archiveName)
        assertEquals("jmix", windows.imageName)
        assertEquals("jmix.exe", windows.launcherRelativePath)
    }
}
