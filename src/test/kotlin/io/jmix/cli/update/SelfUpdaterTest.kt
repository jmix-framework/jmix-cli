package io.jmix.cli.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exercises the update flow against a local release directory using the real
 * archive format of the current platform (tar.gz via system tar on Unix, zip
 * on Windows), mirroring what install.sh / install.ps1 produce.
 */
class SelfUpdaterTest {

    @TempDir
    lateinit var tempDir: Path

    private val os = CliInstallation.currentOs() ?: error("unsupported test platform")
    private val arch = "x64"
    private val oldChecksum = "0".repeat(64)
    private val isWindows = os == "windows"

    @Test
    fun `update installs the latest release and repoints the command`() {
        val releaseDir = tempDir.resolve("release")
        val newChecksum = prepareRelease(releaseDir, "new-launcher")
        val root = tempDir.resolve("install")
        val binDir = tempDir.resolve("bin")
        linkCommand(binDir, installVersion(root, oldChecksum))
        val installation = installation(root, oldChecksum)

        val result = updater(installation, releaseDir, binDir).update()

        assertEquals(UpdateResult.UPDATED, result)
        val newLauncher = installation.launcher(newChecksum)
        assertTrue(Files.isRegularFile(newLauncher))
        assertEquals("new-launcher", Files.readString(newLauncher))
        assertCommandPointsTo(binDir, newLauncher)
        // The previous version stays until the next run cleans it up.
        assertTrue(Files.isRegularFile(installation.launcher(oldChecksum)))
    }

    @Test
    fun `update is a no-op when already on the latest release`() {
        val releaseDir = tempDir.resolve("release")
        val checksum = prepareRelease(releaseDir, "launcher")
        val root = tempDir.resolve("install")
        val binDir = tempDir.resolve("bin")
        linkCommand(binDir, installVersion(root, checksum))

        val result = updater(installation(root, checksum), releaseDir, binDir).update()

        assertEquals(UpdateResult.UP_TO_DATE, result)
    }

    @Test
    fun `an installed version carries its completeness marker`() {
        val releaseDir = tempDir.resolve("release")
        val newChecksum = prepareRelease(releaseDir, "new-launcher")
        val root = tempDir.resolve("install")
        val binDir = tempDir.resolve("bin")
        linkCommand(binDir, installVersion(root, oldChecksum))
        val installation = installation(root, oldChecksum)

        updater(installation, releaseDir, binDir).update()

        val marker = installation.versionsDir.resolve(newChecksum).resolve(SelfUpdater.MARKER_NAME)
        assertTrue(Files.isRegularFile(marker), "the installed version must be marked complete")
        assertTrue(
            Files.getLastModifiedTime(marker).toInstant().isAfter(Instant.now().minusSeconds(300)),
            "the marker must record the install time, not the archive timestamp",
        )
    }

    @Test
    fun `update is a no-op when the command already points at the latest release`() {
        // The running process is still the old image, which alone must not
        // trigger a second, pointless update.
        val releaseDir = tempDir.resolve("release")
        val newChecksum = prepareRelease(releaseDir, "new-launcher")
        val root = tempDir.resolve("install")
        val binDir = tempDir.resolve("bin")
        installVersion(root, oldChecksum)
        linkCommand(binDir, installVersion(root, newChecksum, "new-launcher"))

        val result = updater(installation(root, oldChecksum), releaseDir, binDir).update()

        assertEquals(UpdateResult.UP_TO_DATE, result)
    }

    @Test
    fun `update requires the installer when the command is missing or foreign`() {
        val releaseDir = tempDir.resolve("release")
        prepareRelease(releaseDir, "new-launcher")
        val root = tempDir.resolve("install")
        installVersion(root, oldChecksum)
        val binDir = Files.createDirectories(tempDir.resolve("bin"))
        val foreign = binDir.resolve(if (isWindows) "jmix.cmd" else "jmix")
        Files.writeString(foreign, "user-managed script")

        val result = updater(installation(root, oldChecksum), releaseDir, binDir).update()

        assertEquals(UpdateResult.INSTALLER_REQUIRED, result)
        assertEquals("user-managed script", Files.readString(foreign))
    }

    @Test
    fun `update rejects an archive that does not match its checksum`() {
        val releaseDir = tempDir.resolve("release")
        val newChecksum = prepareRelease(releaseDir, "new-launcher")
        val installation = installation(tempDir.resolve("install"), oldChecksum)
        val binDir = tempDir.resolve("bin")
        linkCommand(binDir, installVersion(installation.installRoot, oldChecksum))
        // Corrupt the archive after its checksum was published.
        Files.write(releaseDir.resolve(installation.archiveName), byteArrayOf(0), StandardOpenOption.APPEND)

        assertThrows<IOException> { updater(installation, releaseDir, binDir).update() }
        assertFalse(Files.exists(installation.versionsDir.resolve(newChecksum)))
        assertCommandPointsTo(binDir, installation.launcher(oldChecksum))
    }

    @Test
    fun `update reports a readable error for an unreachable release location`() {
        val installation = installation(tempDir.resolve("install"), oldChecksum)
        val binDir = tempDir.resolve("bin")
        linkCommand(binDir, installVersion(installation.installRoot, oldChecksum))

        val error = assertThrows<IOException> {
            updater(installation, tempDir.resolve("missing-release"), binDir).update()
        }
        assertFalse(SelfUpdater.describe(error).isBlank())
    }

    @Test
    fun `cleanupOldVersions removes versions that are neither running nor linked`() {
        val root = tempDir.resolve("install")
        val running = "a".repeat(64)
        val linked = "b".repeat(64)
        val stale = "c".repeat(64)
        installVersion(root, running)
        installVersion(root, stale)
        val binDir = tempDir.resolve("bin")
        linkCommand(binDir, installVersion(root, linked))
        val installation = installation(root, running)

        cleanupWithAgedVersions(installation, binDir)

        assertTrue(Files.isDirectory(installation.versionsDir.resolve(running)))
        assertTrue(Files.isDirectory(installation.versionsDir.resolve(linked)))
        assertFalse(Files.exists(installation.versionsDir.resolve(stale)))
    }

    @Test
    fun `cleanupOldVersions keeps a version installed moments ago`() {
        // A concurrent process may have installed it and not yet repointed.
        val root = tempDir.resolve("install")
        val running = "a".repeat(64)
        val fresh = "d".repeat(64)
        installVersion(root, running)
        installVersion(root, fresh)
        val binDir = tempDir.resolve("bin")
        linkCommand(binDir, installVersion(root, running))
        val installation = installation(root, running)

        updater(installation, tempDir, binDir).cleanupOldVersions()

        assertTrue(Files.isDirectory(installation.versionsDir.resolve(fresh)))
    }

    @Test
    fun `cleanupOldVersions keeps a version installed from an archive with fixed timestamps`() {
        // Release archives carry constant entry timestamps, so a just-installed
        // directory can look decades old; only the marker records install time.
        val root = tempDir.resolve("install")
        val running = "a".repeat(64)
        val fresh = "d".repeat(64)
        installVersion(root, running)
        installVersion(root, fresh)
        val binDir = tempDir.resolve("bin")
        linkCommand(binDir, installVersion(root, running))
        val installation = installation(root, running)
        Files.setLastModifiedTime(installation.versionsDir.resolve(fresh), FileTime.from(Instant.EPOCH))

        updater(installation, tempDir, binDir).cleanupOldVersions()

        assertTrue(Files.isDirectory(installation.versionsDir.resolve(fresh)))
    }

    @Test
    fun `cleanupOldVersions keeps a superseded version that is still being used`() {
        // A second command sharing this install root touches its own marker on
        // every run; pruning it would break that command.
        val root = tempDir.resolve("install")
        val running = "a".repeat(64)
        val otherCommandVersion = "b".repeat(64)
        installVersion(root, otherCommandVersion)
        val binDir = tempDir.resolve("bin")
        linkCommand(binDir, installVersion(root, running))
        val installation = installation(root, running)
        val staleVersion = "c".repeat(64)
        installVersion(root, staleVersion)
        ageMarkers(installation)
        // The other command runs now, refreshing only its own marker.
        SelfUpdater(installation(root, otherCommandVersion), "unused", binDir, echo = {}).markInUse()

        updater(installation, tempDir, binDir).cleanupOldVersions()

        assertTrue(Files.isDirectory(installation.versionsDir.resolve(otherCommandVersion)))
        assertFalse(Files.exists(installation.versionsDir.resolve(staleVersion)))
    }

    @Test
    fun `cleanupOldVersions removes an incomplete installation`() {
        val root = tempDir.resolve("install")
        val running = "a".repeat(64)
        val partial = "c".repeat(64)
        val binDir = tempDir.resolve("bin")
        linkCommand(binDir, installVersion(root, running))
        installVersion(root, partial, complete = false)
        val installation = installation(root, running)
        age(installation.versionsDir.resolve(partial))

        updater(installation, tempDir, binDir).cleanupOldVersions()

        assertFalse(Files.exists(installation.versionsDir.resolve(partial)))
    }

    @Test
    fun `update reinstalls a version whose image was partially deleted`() {
        val releaseDir = tempDir.resolve("release")
        val newChecksum = prepareRelease(releaseDir, "new-launcher")
        val root = tempDir.resolve("install")
        val binDir = tempDir.resolve("bin")
        linkCommand(binDir, installVersion(root, oldChecksum))
        val installation = installation(root, oldChecksum)
        // A gutted image: the launcher survived an aborted delete, the marker did not.
        writeLauncher(installation.launcher(newChecksum), "gutted")

        val result = updater(installation, releaseDir, binDir).update()

        assertEquals(UpdateResult.UPDATED, result)
        assertEquals("new-launcher", Files.readString(installation.launcher(newChecksum)))
    }

    @Test
    fun `cleanupOldVersions removes interrupted download directories`() {
        val root = tempDir.resolve("install")
        val binDir = tempDir.resolve("bin")
        linkCommand(binDir, installVersion(root, oldChecksum))
        val leftover = Files.createDirectories(root.resolve(".update-1234/extracted"))
        age(leftover.parent)
        val installation = installation(root, oldChecksum)

        updater(installation, tempDir, binDir).cleanupOldVersions()

        assertFalse(Files.exists(root.resolve(".update-1234")))
    }

    @Test
    fun `cleanupOldVersions is skipped when the managed command is not visible`() {
        val root = tempDir.resolve("install")
        val stale = "c".repeat(64)
        installVersion(root, stale)
        val installation = installation(root, oldChecksum)

        updater(installation, tempDir, tempDir.resolve("bin")).cleanupOldVersions()

        assertTrue(Files.isDirectory(installation.versionsDir.resolve(stale)))
    }

    @Test
    fun `cleanup keeps the linked version when the install root contains a hex directory`() {
        // The version checksum must come from the path structure, never from a
        // hex-looking directory earlier in the path.
        val hexRoot = Files.createDirectories(tempDir.resolve("cache").resolve("e".repeat(64)))
        val root = hexRoot.resolve("jmix-cli")
        val linked = "b".repeat(64)
        val binDir = tempDir.resolve("bin")
        linkCommand(binDir, installVersion(root, linked))
        val installation = installation(root, linked)

        cleanupWithAgedVersions(installation, binDir)

        assertTrue(Files.isDirectory(installation.versionsDir.resolve(linked)))
    }

    @Test
    fun `the managed command is recognized through a symlinked install root`() {
        assumeFalse(isWindows)
        // install.sh writes the link target as typed; the running launcher
        // resolves to a real path, so both sides must be compared as locations.
        val realRoot = tempDir.resolve("real-install")
        val linkedRoot = tempDir.resolve("install-link")
        val launcher = installVersion(realRoot, oldChecksum)
        Files.createSymbolicLink(linkedRoot, realRoot)
        val binDir = Files.createDirectories(tempDir.resolve("bin"))
        Files.createSymbolicLink(
            binDir.resolve("jmix"),
            linkedRoot.resolve("versions/$oldChecksum/${installation(realRoot, oldChecksum).launcherRelativePath}"),
        )
        val stale = "c".repeat(64)
        installVersion(realRoot, stale)
        val installation = CliInstallation.detect(launcher, os, arch)!!

        cleanupWithAgedVersions(installation, binDir)

        assertTrue(Files.isDirectory(installation.versionsDir.resolve(oldChecksum)))
        assertFalse(Files.exists(installation.versionsDir.resolve(stale)))
    }

    @Test
    fun `autoUpdate checks at most once per interval`() {
        val releaseDir = tempDir.resolve("release")
        val newChecksum = prepareRelease(releaseDir, "new-launcher")
        val root = tempDir.resolve("install")
        val binDir = tempDir.resolve("bin")
        linkCommand(binDir, installVersion(root, oldChecksum))
        val installation = installation(root, oldChecksum)
        val updater = updater(installation, releaseDir, binDir)
        val now = Instant.now()

        writeStamp(root, now.minusSeconds(60))
        updater.autoUpdate(now)
        assertFalse(Files.exists(installation.launcher(newChecksum)), "a fresh stamp must skip the check")

        writeStamp(root, now.minusSeconds(25 * 60 * 60))
        updater.autoUpdate(now)
        assertTrue(Files.isRegularFile(installation.launcher(newChecksum)))
        assertTrue(readStamp(root)!! >= now.minusSeconds(5), "the check must refresh the stamp")
    }

    @Test
    fun `autoUpdate stays silent when the release check fails`() {
        val root = tempDir.resolve("install")
        val binDir = tempDir.resolve("bin")
        linkCommand(binDir, installVersion(root, oldChecksum))
        val messages = mutableListOf<String>()
        val updater = SelfUpdater(
            installation(root, oldChecksum), tempDir.resolve("missing-release").toString(), binDir,
            autoUpdateEnabled = true, echo = { messages.add(it) },
        )

        updater.autoUpdate(Instant.now())

        assertTrue(messages.isEmpty(), "unreachable releases must not print anything: $messages")
    }

    @Test
    fun `autoUpdate reports a failure that interrupts a started download`() {
        val releaseDir = tempDir.resolve("release")
        val installation = installation(tempDir.resolve("install"), oldChecksum)
        prepareRelease(releaseDir, "new-launcher")
        val binDir = tempDir.resolve("bin")
        linkCommand(binDir, installVersion(installation.installRoot, oldChecksum))
        Files.write(releaseDir.resolve(installation.archiveName), byteArrayOf(0), StandardOpenOption.APPEND)
        val messages = mutableListOf<String>()
        val updater = SelfUpdater(
            installation, releaseDir.toString(), binDir,
            autoUpdateEnabled = true, echo = { messages.add(it) },
        )

        updater.autoUpdate(Instant.now())

        assertTrue(
            messages.any { it.contains("failed", ignoreCase = true) },
            "a failed download must not leave the progress line dangling: $messages",
        )
    }

    @Test
    fun `autoUpdate is disabled in CI and by the opt-out variable`() {
        assertTrue(SelfUpdater.autoUpdateAllowed { null })
        assertFalse(SelfUpdater.autoUpdateAllowed { if (it == "JMIX_CLI_NO_AUTO_UPDATE") "1" else null })
        assertFalse(SelfUpdater.autoUpdateAllowed { if (it == "CI") "true" else null })
        assertTrue(SelfUpdater.autoUpdateAllowed { if (it == "CI") "" else null })
    }

    @Test
    fun `autoUpdate does nothing when disabled`() {
        val releaseDir = tempDir.resolve("release")
        val newChecksum = prepareRelease(releaseDir, "new-launcher")
        val root = tempDir.resolve("install")
        val binDir = tempDir.resolve("bin")
        linkCommand(binDir, installVersion(root, oldChecksum))
        val installation = installation(root, oldChecksum)

        SelfUpdater(installation, releaseDir.toString(), binDir, autoUpdateEnabled = false, echo = {})
            .autoUpdate(Instant.now())

        assertFalse(Files.exists(installation.launcher(newChecksum)))
    }

    @Test
    fun `resolveBinDir prefers the directory recorded by the installer`() {
        val root = Files.createDirectories(tempDir.resolve("install"))
        val recorded = tempDir.resolve("custom-bin")
        Files.writeString(root.resolve(SelfUpdater.BIN_DIR_FILE), "$recorded\n")

        assertEquals(recorded, SelfUpdater.resolveBinDir(installation(root, oldChecksum)))
    }

    @Test
    fun `resolveBinDir falls back to the platform default`() {
        val root = Files.createDirectories(tempDir.resolve("install"))

        assertEquals(
            SelfUpdater.defaultBinDir(os),
            SelfUpdater.resolveBinDir(installation(root, oldChecksum)),
        )
    }

    private fun updater(installation: CliInstallation, releaseDir: Path, binDir: Path) =
        SelfUpdater(installation, releaseDir.toString(), binDir, autoUpdateEnabled = true, echo = {})

    /** Ages every installed version past the unused window, then cleans. */
    private fun cleanupWithAgedVersions(installation: CliInstallation, binDir: Path) {
        ageMarkers(installation)
        updater(installation, tempDir, binDir).cleanupOldVersions()
    }

    private fun ageMarkers(installation: CliInstallation) {
        val unused = Instant.now().minus(SelfUpdater.UNUSED_AFTER).minusSeconds(60)
        Files.list(installation.versionsDir).use { entries ->
            entries.forEach { version ->
                val marker = version.resolve(SelfUpdater.MARKER_NAME)
                if (Files.exists(marker)) Files.setLastModifiedTime(marker, FileTime.from(unused))
                age(version)
            }
        }
    }

    /** Backdates a path past the grace period used for incomplete directories. */
    private fun age(path: Path) {
        Files.setLastModifiedTime(path, FileTime.from(Instant.now().minusSeconds(2 * 60 * 60)))
    }

    private fun writeStamp(root: Path, value: Instant) {
        Files.createDirectories(root)
        Files.writeString(root.resolve(SelfUpdater.UPDATE_CHECK_STAMP), value.epochSecond.toString())
    }

    private fun readStamp(root: Path): Instant? =
        Files.readString(root.resolve(SelfUpdater.UPDATE_CHECK_STAMP)).trim()
            .toLongOrNull()?.let(Instant::ofEpochSecond)

    private fun installation(root: Path, checksum: String) =
        CliInstallation(root.toAbsolutePath().normalize(), checksum, os, arch)

    /** Creates a fake release asset pair and returns the archive checksum. */
    private fun prepareRelease(releaseDir: Path, launcherContent: String): String {
        Files.createDirectories(releaseDir)
        val installation = installation(tempDir, oldChecksum)
        val image = Files.createDirectories(
            Files.createTempDirectory(tempDir, "image").resolve(installation.imageName),
        )
        writeLauncher(image.resolve(installation.launcherRelativePath), launcherContent)
        val archive = releaseDir.resolve(installation.archiveName)
        archive(image, archive)
        val checksum = sha256(archive)
        Files.writeString(
            releaseDir.resolve("${installation.archiveName}.sha256"),
            "$checksum  ${installation.archiveName}\n",
        )
        return checksum
    }

    private fun installVersion(
        root: Path,
        checksum: String,
        content: String = "old",
        complete: Boolean = true,
    ): Path {
        val installation = installation(root, checksum)
        val launcher = installation.launcher(checksum)
        writeLauncher(launcher, content)
        if (complete) {
            Files.writeString(installation.versionsDir.resolve(checksum).resolve(SelfUpdater.MARKER_NAME), "")
        }
        return launcher
    }

    private fun writeLauncher(launcher: Path, content: String) {
        Files.createDirectories(launcher.parent)
        Files.writeString(launcher, content)
        launcher.toFile().setExecutable(true)
    }

    private fun linkCommand(binDir: Path, launcher: Path): Path {
        Files.createDirectories(binDir)
        return if (isWindows) {
            val command = binDir.resolve("jmix.cmd")
            Files.writeString(command, "${SelfUpdater.WRAPPER_MARKER}\r\n@echo off\r\n\"$launcher\" %*\r\n")
            command
        } else {
            val command = binDir.resolve("jmix")
            Files.deleteIfExists(command)
            Files.createSymbolicLink(command, launcher)
            command
        }
    }

    private fun assertCommandPointsTo(binDir: Path, launcher: Path) {
        if (isWindows) {
            val content = Files.readString(binDir.resolve("jmix.cmd"))
            assertTrue(content.startsWith(SelfUpdater.WRAPPER_MARKER))
            assertTrue(content.contains("\"$launcher\""), "the wrapper must call $launcher")
        } else {
            assertEquals(launcher, Files.readSymbolicLink(binDir.resolve("jmix")))
        }
    }

    private fun archive(imageDir: Path, destination: Path) {
        if (isWindows) {
            ZipOutputStream(Files.newOutputStream(destination)).use { zip ->
                Files.walk(imageDir).use { paths ->
                    paths.filter { Files.isRegularFile(it) }.forEach { file ->
                        val entryName = imageDir.parent.relativize(file).joinToString("/") { it.toString() }
                        zip.putNextEntry(ZipEntry(entryName))
                        Files.copy(file, zip)
                        zip.closeEntry()
                    }
                }
            }
        } else {
            val process = ProcessBuilder(
                "tar", "-czf", destination.toString(),
                "-C", imageDir.parent.toString(), imageDir.fileName.toString(),
            ).inheritIO().start()
            check(process.waitFor() == 0) { "tar failed while preparing the test release" }
        }
    }

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(Files.readAllBytes(file))
        return HexFormat.of().formatHex(digest.digest())
    }
}
