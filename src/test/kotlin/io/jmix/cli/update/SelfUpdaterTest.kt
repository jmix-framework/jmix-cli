package io.jmix.cli.update

import com.sun.net.httpserver.HttpServer
import io.jmix.cli.util.describeIoError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
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
    fun `update installs a complete release reports progress and repoints the command`() {
        val releaseDir = tempDir.resolve("release")
        val newChecksum = prepareRelease(releaseDir, "new-launcher")
        val root = tempDir.resolve("install")
        val binDir = tempDir.resolve("bin")
        linkCommand(binDir, installVersion(root, oldChecksum))
        val installation = installation(root, oldChecksum)
        val phases = mutableListOf<String>()
        val progress = mutableListOf<Pair<Long, Long>>()
        val result = SelfUpdater(
            installation, releaseDir.toString(), binDir, echo = {},
            onStatus = phases::add, onProgress = { done, total -> progress += done to total },
        ).update()

        assertTrue(result is UpdateResult.Updated, "expected an update, got $result")
        val newLauncher = installation.launcher(newChecksum)
        assertTrue(Files.isRegularFile(newLauncher))
        assertEquals("new-launcher", Files.readString(newLauncher))
        assertCommandPointsTo(binDir, newLauncher)
        // The previous version stays until the next run cleans it up.
        assertTrue(Files.isRegularFile(installation.launcher(oldChecksum)))

        val marker = installation.versionsDir.resolve(newChecksum).resolve(SelfUpdater.MARKER_NAME)
        assertTrue(Files.isRegularFile(marker), "the installed version must be marked complete")
        assertTrue(
            Files.getLastModifiedTime(marker).toInstant().isAfter(Instant.now().minusSeconds(300)),
            "the marker must record the install time, not the archive timestamp",
        )

        val size = Files.size(releaseDir.resolve(installation.archiveName))
        assertEquals(size to size, progress.last())
        assertTrue(phases.first().startsWith("Downloading ${installation.archiveName} from "), phases.toString())
        assertEquals(
            listOf("Verifying the checksum of ${installation.archiveName}", "Extracting the new Jmix CLI version"),
            phases.drop(1),
        )
    }

    @Test
    fun `update is a no-op when already on the latest release`() {
        val releaseDir = tempDir.resolve("release")
        val checksum = prepareRelease(releaseDir, "launcher")
        val root = tempDir.resolve("install")
        val binDir = tempDir.resolve("bin")
        linkCommand(binDir, installVersion(root, checksum))

        val result = updater(installation(root, checksum), releaseDir, binDir).update()

        assertTrue(result is UpdateResult.UpToDate, "expected no update, got $result")
    }

    @Test
    fun `Windows update preserves the Unicode wrapper target`() {
        val root = tempDir.resolve("Установка %test% !")
        val binDir = Files.createDirectories(tempDir.resolve("Команды"))
        val installation = CliInstallation(root, oldChecksum, "windows", arch)
        val oldLauncher = installation.launcher(oldChecksum)
        writeLauncher(oldLauncher, "old-launcher")
        Files.writeString(oldLauncher.resolveSibling(SelfUpdater.MARKER_NAME), "")
        val command = binDir.resolve("jmix.cmd")
        val oldPath = oldLauncher.toString().replace("%", "%%")
        Files.writeString(
            command, "${SelfUpdater.WRAPPER_MARKER}\r\nset \"_JMIX_LAUNCHER=$oldPath\"\r\n\"%_JMIX_LAUNCHER%\" %*\r\n",
        )
        val releaseDir = tempDir.resolve("release")
        val checksum = prepareRelease(releaseDir, "new-launcher", installation)
        val updater = updater(installation, releaseDir, binDir)

        assertEquals(UpdateResult.Updated(checksum), updater.update())

        val launcher = installation.launcher(checksum)
        val target = launcher.toString().replace("%", "%%")
        val wrapper = Files.readString(command)
        assertTrue(wrapper.startsWith("${SelfUpdater.WRAPPER_MARKER}\r\n"))
        assertTrue(wrapper.contains("chcp 65001 >nul 2>&1\r\nset \"_JMIX_LAUNCHER=$target\"\r\n"))
        assertTrue(wrapper.endsWith("\"%_JMIX_LAUNCHER%\" %*\r\n"))
        assertEquals("new-launcher", Files.readString(launcher))
        assertEquals(UpdateResult.Updated(checksum), updater.update())
    }

    @Test
    fun `update reuses a release that is already installed and linked`() {
        // The running process is still the old image, so the caller must be
        // told to switch — but nothing needs downloading or relinking.
        val releaseDir = tempDir.resolve("release")
        val newChecksum = prepareRelease(releaseDir, "new-launcher")
        val root = tempDir.resolve("install")
        val binDir = tempDir.resolve("bin")
        installVersion(root, oldChecksum)
        val installedLauncher = installVersion(root, newChecksum, "already-installed")
        linkCommand(binDir, installedLauncher)

        val result = updater(installation(root, oldChecksum), releaseDir, binDir).update()

        assertEquals(UpdateResult.Updated(newChecksum), result)
        assertEquals("already-installed", Files.readString(installedLauncher), "must not re-download")
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

        assertTrue(result is UpdateResult.InstallerRequired, "expected an installer hint, got $result")
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
        assertFalse(describeIoError(error).isBlank())
    }

    @Test
    fun `describe names the server that cannot be reached`() {
        assertEquals("cannot reach the Jmix Store", describeIoError(java.net.ConnectException("refused"), "the Jmix Store"))
        assertEquals("cannot reach the release server", describeIoError(java.net.ConnectException("refused")))
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

        assertTrue(result is UpdateResult.Updated, "expected an update, got $result")
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
        val now = Instant.now()

        val result = updater(installation, releaseDir, binDir).autoUpdate(now)

        assertEquals(UpdateResult.Updated(newChecksum), result)
        assertTrue(Files.isRegularFile(installation.launcher(newChecksum)))

        // A command right after the check must not repeat the request.
        Files.delete(installation.versionsDir.resolve(newChecksum).resolve(SelfUpdater.MARKER_NAME))
        assertTrue(
            updater(installation, releaseDir, binDir).autoUpdate(now.plusSeconds(60)) is UpdateResult.UpToDate,
            "a check within the interval must be skipped",
        )

        // Once the interval passes, the next command checks again.
        val later = now.plus(SelfUpdater.CHECK_INTERVAL).plusSeconds(60)
        assertEquals(
            UpdateResult.Updated(newChecksum),
            updater(installation, releaseDir, binDir).autoUpdate(later),
        )
    }

    @Test
    fun `autoUpdate records the check time even when it fails`() {
        // Otherwise an offline machine would repeat the attempt, and the
        // warning, on every single command.
        val root = tempDir.resolve("install")
        val binDir = tempDir.resolve("bin")
        linkCommand(binDir, installVersion(root, oldChecksum))
        val messages = mutableListOf<String>()
        val installation = installation(root, oldChecksum)
        val missing = tempDir.resolve("missing-release").toString()
        val now = Instant.now()

        SelfUpdater(installation, missing, binDir, autoUpdateEnabled = true, echo = { messages.add(it) })
            .autoUpdate(now)
        SelfUpdater(installation, missing, binDir, autoUpdateEnabled = true, echo = { messages.add(it) })
            .autoUpdate(now.plusSeconds(60))

        assertEquals(1, messages.size, "the failure must be reported once per interval: $messages")
    }

    @Test
    fun `an explicit update ignores the interval`() {
        val releaseDir = tempDir.resolve("release")
        val newChecksum = prepareRelease(releaseDir, "new-launcher")
        val root = tempDir.resolve("install")
        val binDir = tempDir.resolve("bin")
        linkCommand(binDir, installVersion(root, oldChecksum))
        val installation = installation(root, oldChecksum)
        updater(installation, releaseDir, binDir).autoUpdate(Instant.now())
        Files.delete(installation.versionsDir.resolve(newChecksum).resolve(SelfUpdater.MARKER_NAME))
        val lockFile = root.resolve(SelfUpdater.UPDATE_LOCK)
        val checkTime = Files.readString(lockFile)

        val result = updater(installation, releaseDir, binDir).update()

        assertEquals(UpdateResult.Updated(newChecksum), result)
        assertEquals(checkTime, Files.readString(lockFile))
    }

    @Test
    fun `automatic update and cleanup skip while an explicit update holds the lock`() {
        val releaseDir = tempDir.resolve("release")
        val newChecksum = prepareRelease(releaseDir, "new-launcher")
        val root = tempDir.resolve("install")
        val binDir = tempDir.resolve("bin")
        linkCommand(binDir, installVersion(root, oldChecksum))
        val installation = installation(root, oldChecksum)
        val unusedLauncher = installVersion(root, "1".repeat(64))
        ageMarkers(installation)

        val downloading = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val explicit = SelfUpdater(installation, releaseDir.toString(), binDir, onStatus = {
            if (it.startsWith("Downloading ")) {
                downloading.countDown()
                check(resume.await(10, SECONDS))
            }
        })
        val maintenance = updater(installation, releaseDir, binDir)
        Executors.newSingleThreadExecutor().use { executor ->
            val result = executor.submit<UpdateResult> { explicit.update() }
            try {
                assertTrue(downloading.await(10, SECONDS), "manual update never reached the download")
                assertEquals(UpdateResult.UpToDate, maintenance.autoUpdate())
                maintenance.cleanupOldVersions()
                assertFalse(Files.exists(installation.launcher(newChecksum)))
                assertTrue(Files.exists(unusedLauncher))
            } finally {
                resume.countDown()
            }
            assertEquals(UpdateResult.Updated(newChecksum), result.get(10, SECONDS))
        }

        assertEquals("", Files.readString(root.resolve(SelfUpdater.UPDATE_LOCK)))
        maintenance.cleanupOldVersions()
        assertFalse(Files.exists(unusedLauncher))
        assertCommandPointsTo(binDir, installation.launcher(newChecksum))
        assertTrue(Files.exists(installation.launcher(newChecksum)))
    }

    @Test
    fun `autoUpdate reports a check it could not complete and keeps going`() {
        val root = tempDir.resolve("install")
        val binDir = tempDir.resolve("bin")
        linkCommand(binDir, installVersion(root, oldChecksum))
        val messages = mutableListOf<String>()
        val updater = SelfUpdater(
            installation(root, oldChecksum), tempDir.resolve("missing-release").toString(), binDir,
            autoUpdateEnabled = true, echo = { messages.add(it) },
        )

        val result = updater.autoUpdate()

        assertTrue(result is UpdateResult.UpToDate, "a failed check must not stop the command")
        assertTrue(
            messages.any { it.contains("Could not update", ignoreCase = true) },
            "a failed check must be reported: $messages",
        )
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `autoUpdate keeps the current installation when the HTTP body stalls`(stallChecksum: Boolean) {
        val root = tempDir.resolve("install")
        val binDir = tempDir.resolve("bin")
        val oldLauncher = installVersion(root, oldChecksum)
        linkCommand(binDir, oldLauncher)
        val installation = installation(root, oldChecksum)
        val newChecksum = "1".repeat(64)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val bodyStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.createContext("/") { exchange ->
            exchange.use {
                if (it.requestURI.path.endsWith(".sha256") && !stallChecksum) {
                    val bytes = newChecksum.toByteArray()
                    it.sendResponseHeaders(200, bytes.size.toLong())
                    it.responseBody.write(bytes)
                } else {
                    it.sendResponseHeaders(200, 100)
                    it.responseBody.write('1'.code)
                    it.responseBody.flush()
                    bodyStarted.countDown()
                    release.await(10, SECONDS)
                }
            }
        }
        server.start()
        try {
            val messages = mutableListOf<String>()
            val updater = SelfUpdater(
                installation, "http://127.0.0.1:${server.address.port}", binDir,
                requestTimeout = Duration.ofSeconds(1), autoUpdateEnabled = true, echo = { messages.add(it) },
            )
            assertTimeoutPreemptively(Duration.ofSeconds(5)) {
                assertEquals(UpdateResult.UpToDate, updater.autoUpdate())
            }
            assertEquals(0L, bodyStarted.count)
            assertTrue(messages.any { "Could not update" in it }, messages.toString())
            assertCommandPointsTo(binDir, oldLauncher)
            assertFalse(Files.exists(installation.launcher(newChecksum)))
        } finally {
            release.countDown()
            server.stop(0)
        }
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

        updater.autoUpdate()

        assertTrue(
            messages.any { it.contains("Could not update", ignoreCase = true) },
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
            .autoUpdate()

        assertFalse(Files.exists(installation.launcher(newChecksum)))
    }

    @Test
    fun `the release check is skipped for opt-out, help and the update command`() {
        assertTrue(SelfUpdater.skipsUpdateCheck(arrayOf(SelfUpdater.NO_UPDATE_FLAG)))
        assertTrue(SelfUpdater.skipsUpdateCheck(arrayOf("new", "demo", SelfUpdater.NO_UPDATE_FLAG)))
        assertTrue(SelfUpdater.skipsUpdateCheck(arrayOf("--help")))
        assertTrue(SelfUpdater.skipsUpdateCheck(arrayOf("new", "-h")))
        assertTrue(SelfUpdater.skipsUpdateCheck(arrayOf("update")))

        assertFalse(SelfUpdater.skipsUpdateCheck(arrayOf()))
        assertFalse(SelfUpdater.skipsUpdateCheck(arrayOf("new", "demo")))
    }

    @Test
    fun `a stamp dated in the future does not suppress checks`() {
        // A wrong clock, later corrected, must not disable updates forever.
        val releaseDir = tempDir.resolve("release")
        val newChecksum = prepareRelease(releaseDir, "new-launcher")
        val root = Files.createDirectories(tempDir.resolve("install"))
        val binDir = tempDir.resolve("bin")
        linkCommand(binDir, installVersion(root, oldChecksum))
        val now = Instant.now()
        Files.writeString(root.resolve(SelfUpdater.UPDATE_LOCK), now.plusSeconds(86_400).epochSecond.toString())

        val result = updater(installation(root, oldChecksum), releaseDir, binDir).autoUpdate(now)

        assertEquals(UpdateResult.Updated(newChecksum), result)
    }

    @Test
    fun `a corrupt stamp does not break the check`() {
        val releaseDir = tempDir.resolve("release")
        val newChecksum = prepareRelease(releaseDir, "new-launcher")
        val root = Files.createDirectories(tempDir.resolve("install"))
        val binDir = tempDir.resolve("bin")
        linkCommand(binDir, installVersion(root, oldChecksum))
        // Out of Instant range, and not a number at all.
        for (content in listOf("99999999999999999", "not-a-timestamp")) {
            Files.writeString(root.resolve(SelfUpdater.UPDATE_LOCK), content)
            runCatching { deleteVersion(root, newChecksum) }

            val result = updater(installation(root, oldChecksum), releaseDir, binDir).autoUpdate(Instant.now())

            assertEquals(UpdateResult.Updated(newChecksum), result, "stamp content: $content")
        }
    }

    @Test
    fun `restart runs the updated launcher with the original arguments`() {
        assumeFalse(isWindows)
        val root = tempDir.resolve("install")
        val checksum = "a".repeat(64)
        val recorded = tempDir.resolve("restart-args.txt")
        val launcher = installation(root, checksum).launcher(checksum)
        Files.createDirectories(launcher.parent)
        Files.writeString(launcher, "#!/bin/sh\nprintf '%s\\n' \"$@\" > $recorded\nexit 7\n")
        launcher.toFile().setExecutable(true)

        val exitCode = SelfUpdater(installation(root, oldChecksum), "unused", tempDir, echo = {})
            .restart(checksum, listOf("new", "demo", "--no-git"))

        assertEquals(7, exitCode, "the caller must exit with the new version's code")
        // The loop guard travels as an argument: an environment variable would
        // be inherited by the IDE and Gradle the new version starts.
        assertEquals(
            listOf(SelfUpdater.NO_UPDATE_FLAG, "new", "demo", "--no-git"),
            Files.readAllLines(recorded),
        )
    }

    @Test
    fun `restart reports a launcher it cannot run`() {
        val root = tempDir.resolve("install")
        val messages = mutableListOf<String>()

        val exitCode = SelfUpdater(installation(root, oldChecksum), "unused", tempDir, echo = { messages.add(it) })
            .restart("b".repeat(64), listOf("new"))

        assertNull(exitCode, "a missing launcher must not stop the current run")
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

    private fun installation(root: Path, checksum: String) =
        CliInstallation(root.toAbsolutePath().normalize(), checksum, os, arch)

    /** Creates a fake release asset pair and returns the archive checksum. */
    private fun prepareRelease(
        releaseDir: Path,
        launcherContent: String,
        installation: CliInstallation = installation(tempDir, oldChecksum),
    ): String {
        Files.createDirectories(releaseDir)
        val image = Files.createDirectories(
            Files.createTempDirectory(tempDir, "image").resolve(installation.imageName),
        )
        writeLauncher(image.resolve(installation.launcherRelativePath), launcherContent)
        val archive = releaseDir.resolve(installation.archiveName)
        archive(image, archive, windows = installation.os == "windows")
        val checksum = sha256(archive)
        Files.writeString(
            releaseDir.resolve("${installation.archiveName}.sha256"),
            "$checksum  ${installation.archiveName}\n",
        )
        return checksum
    }

    private fun deleteVersion(root: Path, checksum: String) {
        val dir = installation(root, checksum).versionsDir.resolve(checksum)
        Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
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
            val escaped = launcher.toString().replace("%", "%%")
            assertTrue(
                content.lineSequence().any { it == "\"$launcher\" %*" || it == "set \"_JMIX_LAUNCHER=$escaped\"" },
                "the wrapper must call $launcher",
            )
        } else {
            assertEquals(launcher, Files.readSymbolicLink(binDir.resolve("jmix")))
        }
    }

    private fun archive(imageDir: Path, destination: Path, windows: Boolean) {
        if (windows) {
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
