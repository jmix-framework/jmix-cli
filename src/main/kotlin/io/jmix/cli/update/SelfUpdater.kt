package io.jmix.cli.update

import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.io.path.name
import kotlin.system.exitProcess

sealed interface UpdateResult {
    data object UpToDate : UpdateResult

    /** Installed and linked; [checksum] identifies the version to restart into. */
    data class Updated(val checksum: String) : UpdateResult

    /** A newer release exists but the `jmix` command is not installer-managed. */
    data object InstallerRequired : UpdateResult
}

/**
 * Self-update and cleanup for release installations. Compares the published
 * archive checksum with the running version's directory name; on a mismatch it
 * downloads, verifies, and installs the new image next to the current one and
 * repoints the installer-managed `jmix` command. The caller then restarts the
 * typed command on the new version — see [runStartupMaintenance].
 */
class SelfUpdater(
    private val installation: CliInstallation,
    private val releaseBaseUrl: String =
        System.getenv("JMIX_CLI_RELEASE_BASE_URL")?.takeIf { it.isNotBlank() } ?: DEFAULT_RELEASE_BASE_URL,
    binDir: Path = resolveBinDir(installation),
    /** Auto-update uses short timeouts so a stalled network cannot delay a command. */
    private val requestTimeout: Duration = EXPLICIT_REQUEST_TIMEOUT,
    private val autoUpdateEnabled: Boolean = autoUpdateAllowed(),
    private val echo: (String) -> Unit = ::println,
) {
    private val binDir: Path = binDir.toAbsolutePath().normalize()

    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(minOf(requestTimeout, CONNECT_TIMEOUT))
            .build()
    }

    /**
     * Startup auto-update: every run checks for a new release, installs it and
     * reports the outcome so the caller can restart into it. Disabled with
     * JMIX_CLI_NO_AUTO_UPDATE=1 or in CI. A check that cannot complete is
     * reported and never fails the command. Messages go to stderr so they never
     * pollute piped stdout.
     */
    fun autoUpdate(now: Instant = Instant.now()): UpdateResult {
        if (!autoUpdateEnabled) return UpdateResult.UpToDate
        // Skipped when another process is already updating, or when the last
        // check is younger than CHECK_INTERVAL — a short window that keeps a
        // burst of commands from repeating the same request.
        val lock = claimUpdateCheck(now) ?: return UpdateResult.UpToDate
        val result = try {
            lock.use { update() }
        } catch (e: Exception) {
            echo("Could not update Jmix CLI (${describe(e)}); continuing with the current version.")
            return UpdateResult.UpToDate
        }
        if (result is UpdateResult.InstallerRequired) {
            echo("A new Jmix CLI release is available. Re-run the install command from the README to update.")
        }
        return result
    }

    fun update(): UpdateResult {
        val latest = fetchLatestChecksum()
        // Already switched by an earlier run: the current process still runs the
        // old image, so its checksum alone cannot decide this.
        if (latest == installation.currentChecksum) return UpdateResult.UpToDate
        val commandPath = managedCommandPath() ?: return UpdateResult.InstallerRequired
        // Only a version carrying its install marker may be reused: an aborted
        // cleanup can leave a gutted image whose launcher file still exists.
        if (!isComplete(latest)) {
            runCatching {
                deleteRecursively(installation.versionsDir.resolve(latest), installation.installRoot)
            }
            installVersion(latest)
        }
        if (managedTargetChecksum() != latest) {
            repoint(commandPath, installation.launcher(latest))
        }
        return UpdateResult.Updated(latest)
    }

    /**
     * Replaces this process with [checksum]'s launcher, passing [args] through
     * and keeping the terminal, so the command the user typed runs on the new
     * version. Returns the launcher's exit code, or null when it could not be
     * started — the caller then continues on the current version.
     */
    fun restart(checksum: String, args: List<String>): Int? {
        val launcher = installation.launcher(checksum)
        if (!Files.isExecutable(launcher)) return null
        // The guard against an update loop is an argument, not an environment
        // variable: the child launches the IDE, the file manager and Gradle,
        // and an inherited variable would silently disable updates in every
        // process they in turn spawn.
        val command = listOf(launcher.toString(), NO_UPDATE_FLAG) + args
        return try {
            ProcessBuilder(command).inheritIO().start().waitFor()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (e: Exception) {
            echo("Could not start the updated Jmix CLI (${describe(e)}); continuing with the current version.")
            null
        }
    }

    /**
     * Removes versions that are neither running nor linked and have gone unused
     * for [UNUSED_AFTER], incomplete installations, and interrupted downloads.
     * Usage is tracked by the install marker, which every run touches for its
     * own version, so a second command using this install root keeps its version.
     */
    fun cleanupOldVersions(now: Instant = Instant.now()) {
        // ponytail: cleanup only runs when the managed command is visible; an
        // unmanaged custom link could otherwise be left dangling.
        val linked = managedTargetChecksum() ?: return
        val keep = setOf(installation.currentChecksum, linked)
        val unusedBefore = now.minus(UNUSED_AFTER)
        // A directory without a marker is either an interrupted install or a
        // partially deleted one; the grace period keeps a concurrent install safe.
        val incompleteBefore = now.minus(RECENT_GRACE)
        if (Files.isDirectory(installation.versionsDir)) {
            val stale = Files.list(installation.versionsDir).use { entries ->
                entries.toList().filter {
                    Files.isDirectory(it) &&
                        CliInstallation.CHECKSUM_NAME.matches(it.name) &&
                        it.name !in keep &&
                        isRemovable(it, unusedBefore, incompleteBefore)
                }
            }
            // Locked files (a still-running old version on Windows) are retried
            // on the next startup.
            stale.forEach { runCatching { deleteRecursively(it, installation.installRoot) } }
        }
        // Downloads interrupted by Ctrl-C or a crash leave a temp directory
        // holding a full archive, and an aborted delete leaves a trash directory;
        // nothing else ever removes them.
        runCatching {
            Files.list(installation.installRoot).use { entries ->
                entries.toList().filter {
                    Files.isDirectory(it) &&
                        (it.name.startsWith(TEMP_PREFIX) || it.name.startsWith(TRASH_PREFIX)) &&
                        isOlderThan(it, incompleteBefore)
                }
            }
        }.getOrDefault(emptyList()).forEach { runCatching { deleteRecursively(it) } }
    }

    /** Records that this version is in use, so cleanup keeps it. */
    fun markInUse(now: Instant = Instant.now()) {
        val marker = installation.versionsDir.resolve(installation.currentChecksum).resolve(MARKER_NAME)
        runCatching {
            if (Files.exists(marker)) {
                Files.setLastModifiedTime(marker, FileTime.from(now))
            } else {
                Files.writeString(marker, "")
            }
        }
    }

    private fun isComplete(checksum: String): Boolean =
        Files.isRegularFile(installation.versionsDir.resolve(checksum).resolve(MARKER_NAME)) &&
            Files.isRegularFile(installation.launcher(checksum))

    private fun isRemovable(versionDir: Path, unusedBefore: Instant, incompleteBefore: Instant): Boolean {
        val marker = versionDir.resolve(MARKER_NAME)
        // Timestamps come from the marker, written locally: a release archive
        // carries fixed entry timestamps, so directory mtime says nothing about
        // when the version was installed or last used.
        if (!Files.isRegularFile(marker)) return isOlderThan(versionDir, incompleteBefore)
        return isOlderThan(marker, unusedBefore)
    }

    /**
     * Claims the right to check for a release now, returning the held lock, or
     * null when another process is checking or the previous check is still
     * fresh. The lock file also stores that check time, so one file carries
     * both. Never waits.
     *
     * The time is recorded before the check runs, so a failing check is
     * retried on the same schedule instead of on every command.
     */
    private fun claimUpdateCheck(now: Instant): AutoCloseable? {
        var channel: FileChannel? = null
        var lock: java.nio.channels.FileLock? = null
        var claimed = false
        try {
            Files.createDirectories(installation.installRoot)
            channel = FileChannel.open(
                installation.installRoot.resolve(UPDATE_LOCK),
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE,
            )
            lock = channel.tryLock() ?: return null
            if (isCheckFresh(readInstant(channel), now)) return null
            writeInstant(channel, now)
            claimed = true
            val held = lock
            val open = channel
            return AutoCloseable {
                runCatching { held.release() }
                runCatching { open.close() }
            }
        } catch (e: Exception) {
            // Read-only or shared install root: skip updating, never block the command.
            return null
        } finally {
            // Anything that did not hand the lock to the caller must release it
            // here; a leaked lock would block every later run in this process.
            if (!claimed) {
                runCatching { lock?.release() }
                runCatching { channel?.close() }
            }
        }
    }

    /**
     * True when [last] is recent enough to skip a check. A timestamp in the
     * future — a clock that was wrong, or moved back — is treated as stale so
     * it cannot suppress updates forever.
     */
    private fun isCheckFresh(last: Instant?, now: Instant): Boolean =
        last != null && !last.isAfter(now) && last.isAfter(now.minus(CHECK_INTERVAL))

    private fun readInstant(channel: FileChannel): Instant? {
        val buffer = ByteBuffer.allocate(32)
        channel.read(buffer, 0)
        val seconds = buffer.array().decodeToString(0, buffer.position()).trim().toLongOrNull()
        // A hand-edited or corrupt file must not throw out of the update path.
        return seconds?.let { runCatching { Instant.ofEpochSecond(it) }.getOrNull() }
    }

    private fun writeInstant(channel: FileChannel, value: Instant) {
        channel.truncate(0)
        channel.write(ByteBuffer.wrap(value.epochSecond.toString().toByteArray()), 0)
    }

    private fun fetchLatestChecksum(): String {
        val text = fetchText("${installation.archiveName}.sha256")
        val checksum = text.trim().split(Regex("\\s+")).first().lowercase()
        if (!CliInstallation.CHECKSUM_NAME.matches(checksum)) {
            throw IOException("Invalid checksum file for ${installation.archiveName}")
        }
        return checksum
    }

    private fun installVersion(checksum: String) {
        echo("Downloading the latest Jmix CLI release...")
        Files.createDirectories(installation.installRoot)
        // Temp dir inside the install root so the final move stays on one filesystem.
        val tempDir = Files.createTempDirectory(installation.installRoot, TEMP_PREFIX)
        try {
            val archive = tempDir.resolve(installation.archiveName)
            fetchTo(installation.archiveName, archive)
            verifyChecksum(archive, checksum)
            val extractDir = Files.createDirectory(tempDir.resolve("extracted"))
            extract(archive, extractDir)
            val image = extractDir.resolve(installation.imageName)
            val launcher = image.resolve(installation.launcherRelativePath)
            if (!Files.isRegularFile(launcher) ||
                (installation.os != "windows" && !Files.isExecutable(launcher))
            ) {
                throw IOException("Release archive has an unexpected layout")
            }
            // Written before the move so the installed version is never visible
            // without its completeness marker.
            Files.writeString(image.resolve(MARKER_NAME), "")
            Files.createDirectories(installation.versionsDir)
            try {
                Files.move(image, installation.versionsDir.resolve(checksum), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: FileSystemException) {
                // Lost a race with a concurrent install of the same version.
                if (!isComplete(checksum)) throw e
            }
        } finally {
            runCatching { deleteRecursively(tempDir) }
        }
    }

    private fun repoint(commandPath: Path, launcher: Path) {
        if (installation.os == "windows") {
            val content = "$WRAPPER_MARKER\r\n@echo off\r\n\"$launcher\" %*\r\n"
            val tmp = Files.createTempFile(commandPath.parent, "jmix", ".cmd.tmp")
            try {
                // install.ps1 writes this wrapper as ASCII with replacement; match
                // it so a non-Latin-1 install path cannot fail the update.
                Files.write(tmp, encodeAscii(content))
                Files.move(
                    tmp, commandPath,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
                )
            } finally {
                // A failed write must not leave staging files in the bin directory.
                runCatching { Files.deleteIfExists(tmp) }
            }
        } else {
            val tmp = commandPath.parent.resolve(".jmix-update-${ProcessHandle.current().pid()}")
            Files.deleteIfExists(tmp)
            Files.createSymbolicLink(tmp, launcher)
            Files.move(tmp, commandPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    /** The installer-managed `jmix` command, or null when absent or foreign. */
    private fun managedCommandPath(): Path? {
        val command = binDir.resolve(if (installation.os == "windows") "jmix.cmd" else "jmix")
        val target = commandTarget(command) ?: return null
        val ref = CliInstallation.versionRef(target) ?: return null
        // Compare the versions directories themselves: the installer writes the
        // path as typed, while the running launcher resolves to a real path, so
        // a symlinked install root makes plain string comparison fail.
        return command.takeIf { isSameLocation(ref.versionsDir, installation.versionsDir) }
    }

    /** Launcher path the managed command points at, or null when foreign. */
    private fun commandTarget(command: Path): Path? {
        if (installation.os == "windows") {
            if (!Files.isRegularFile(command)) return null
            val content = runCatching { Files.readString(command, StandardCharsets.ISO_8859_1) }.getOrNull()
            if (content?.startsWith(WRAPPER_MARKER) != true) return null
            val quoted = Regex("\"([^\"]+)\"").find(content)?.groupValues?.get(1) ?: return null
            return runCatching { Path.of(quoted) }.getOrNull()
        }
        if (!Files.isSymbolicLink(command)) return null
        return runCatching {
            command.parent.resolve(Files.readSymbolicLink(command)).normalize()
        }.getOrNull()
    }

    private fun managedTargetChecksum(): String? {
        val command = managedCommandPath() ?: return null
        return commandTarget(command)?.let { CliInstallation.versionRef(it)?.checksum }
    }

    private fun isSameLocation(a: Path, b: Path): Boolean =
        a.normalize() == b.normalize() || runCatching { Files.isSameFile(a, b) }.getOrDefault(false)

    private fun isOlderThan(path: Path, cutoff: Instant): Boolean = runCatching {
        Files.getLastModifiedTime(path).toInstant().isBefore(cutoff)
    }.getOrDefault(false)

    private fun verifyChecksum(file: Path, expected: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        if (HexFormat.of().formatHex(digest.digest()) != expected) {
            throw IOException("Checksum verification failed for ${installation.archiveName}")
        }
    }

    private fun extract(archive: Path, into: Path) {
        if (installation.os == "windows") extractZip(archive, into) else extractTar(archive, into)
    }

    private fun extractTar(archive: Path, into: Path) {
        // System tar preserves the launcher's executable bits; the JDK has no
        // tar support, and tar ships with every supported macOS/Linux system.
        val log = Files.createTempFile(into.parent, "tar", ".log")
        // Output is redirected to a file rather than read inline: reading the
        // pipe to EOF would block past the timeout when tar hangs.
        val process = ProcessBuilder("tar", "-xzf", archive.toString(), "-C", into.toString())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start()
        try {
            if (!process.waitFor(EXTRACT_TIMEOUT.toMinutes(), TimeUnit.MINUTES)) {
                process.destroyForcibly()
                throw IOException("Timed out extracting the update archive")
            }
            if (process.exitValue() != 0) {
                val output = runCatching { Files.readString(log).trim().take(200) }.getOrDefault("")
                throw IOException("Failed to extract the update archive: $output")
            }
        } finally {
            runCatching { Files.deleteIfExists(log) }
        }
    }

    private fun extractZip(archive: Path, into: Path) {
        ZipInputStream(Files.newInputStream(archive).buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                val target = try {
                    into.resolve(name).normalize()
                } catch (e: InvalidPathException) {
                    throw IOException("Unsupported entry in the update archive: $name")
                }
                // Zip-slip guard: never write outside the extraction directory.
                if (!target.startsWith(into)) {
                    throw IOException("Unsafe entry in the update archive: $name")
                }
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING)
                }
                entry = zip.nextEntry
            }
        }
    }

    private fun fetchText(name: String): String {
        if (!isHttpBase()) return Files.readString(localAsset(name))
        val response = http.send(request(name), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw IOException("HTTP ${response.statusCode()} from ${assetUrl(name)}")
        }
        return response.body()
    }

    private fun fetchTo(name: String, destination: Path) {
        if (!isHttpBase()) {
            Files.copy(localAsset(name), destination, StandardCopyOption.REPLACE_EXISTING)
            return
        }
        val response = http.send(request(name), HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) {
            response.body().close()
            throw IOException("HTTP ${response.statusCode()} from ${assetUrl(name)}")
        }
        response.body().use { Files.copy(it, destination, StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun request(name: String): HttpRequest {
        val url = assetUrl(name)
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            throw IOException("Invalid release URL: $url")
        }
        // A startup download must not stall a command for long; an explicit
        // update may take as long as the connection needs.
        val downloadTimeout =
            if (requestTimeout == EXPLICIT_REQUEST_TIMEOUT) DOWNLOAD_TIMEOUT else AUTO_DOWNLOAD_TIMEOUT
        val timeout = if (name.endsWith(".sha256")) requestTimeout else downloadTimeout
        return HttpRequest.newBuilder(uri).timeout(timeout).GET().build()
    }

    private fun assetUrl(name: String) = "${releaseBaseUrl.trimEnd('/')}/$name"

    private fun isHttpBase() =
        releaseBaseUrl.startsWith("http://") || releaseBaseUrl.startsWith("https://")

    private fun localAsset(name: String): Path {
        val base = try {
            if (releaseBaseUrl.startsWith("file://")) Path.of(URI.create(releaseBaseUrl))
            else Path.of(releaseBaseUrl)
        } catch (e: IllegalArgumentException) {
            throw IOException("Invalid release location: $releaseBaseUrl")
        } catch (e: InvalidPathException) {
            throw IOException("Invalid release location: $releaseBaseUrl")
        }
        return base.resolve(name)
    }

    companion object {
        const val DEFAULT_RELEASE_BASE_URL =
            "https://github.com/jmix-framework/jmix-cli/releases/latest/download"

        /** Must match the wrapper marker written by install.ps1. */
        const val WRAPPER_MARKER = "@rem Managed by the Jmix CLI installer"

        /** File where the installers record the bin directory they used. */
        const val BIN_DIR_FILE = "bin-dir"

        /** Lock file; its content is the last release-check time. */
        internal const val UPDATE_LOCK = "update.lock"

        /** Written into a version directory once its install is complete. */
        const val MARKER_NAME = ".jmix-installed"

        private const val TEMP_PREFIX = ".update-"
        private const val TRASH_PREFIX = ".trash-"
        // Short enough that a new release reaches users within minutes, long
        // enough that a burst of commands does not repeat the request.
        internal val CHECK_INTERVAL: Duration = Duration.ofMinutes(10)
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
        private val EXPLICIT_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)
        private val AUTO_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(5)
        private val DOWNLOAD_TIMEOUT: Duration = Duration.ofMinutes(10)
        private val AUTO_DOWNLOAD_TIMEOUT: Duration = Duration.ofMinutes(2)
        private val EXTRACT_TIMEOUT: Duration = Duration.ofMinutes(5)
        private val RECENT_GRACE: Duration = Duration.ofHours(1)

        /** How long a superseded version is kept after its last use. */
        internal val UNUSED_AFTER: Duration = Duration.ofDays(7)

        /**
         * Auto-update is opt-out through JMIX_CLI_NO_AUTO_UPDATE and off in CI,
         * where a mid-pipeline version swap would be surprising.
         */
        fun autoUpdateAllowed(env: (String) -> String? = { System.getenv(it) }): Boolean =
            env("JMIX_CLI_NO_AUTO_UPDATE") != "1" && env("CI").isNullOrBlank()

        /**
         * Startup hook: prunes stale caches and old versions, then checks for a
         * new release. When one is installed the command is re-run on it and
         * this process exits with the new version's exit code — the user never
         * works on a superseded build. Never fails the actual command.
         */
        fun runStartupMaintenance(args: Array<String>) {
            runCatching { CacheCleaner.pruneTemplateCache() }
            val installation = CliInstallation.detect() ?: return
            // Maintenance notices must not enter the stdout of scripted runs.
            val updater = SelfUpdater(
                installation,
                requestTimeout = AUTO_REQUEST_TIMEOUT,
                echo = { System.err.println(it) },
            )
            // Local bookkeeping runs even when the release check is skipped:
            // marking this version in use is what keeps cleanup from pruning a
            // version whose only runs pass --no-update.
            runCatching { updater.markInUse() }
            runCatching { updater.cleanupOldVersions() }
            if (skipsUpdateCheck(args)) return
            val result = runCatching { updater.autoUpdate() }.getOrDefault(UpdateResult.UpToDate)
            if (result !is UpdateResult.Updated) return
            System.err.println("Jmix CLI was updated; restarting on the new version...")
            val exitCode = runCatching { updater.restart(result.checksum, args.toList()) }.getOrNull()
                ?: return // Could not start the new version: carry on with this one.
            exitProcess(exitCode)
        }

        /** Opt-out flag, recognized before the command line is parsed. */
        const val NO_UPDATE_FLAG = "--no-update"

        /**
         * Runs that must not pay for a release check: the explicit opt-out,
         * commands answered locally and instantly (a network round trip would
         * add nothing to `jmix --help`), and `jmix update`, which updates
         * itself.
         */
        fun skipsUpdateCheck(args: Array<String>): Boolean =
            args.any { it == NO_UPDATE_FLAG || it == "-h" || it == "--help" } ||
                args.firstOrNull() == "update"

        /**
         * Bin directory holding the managed `jmix` command: the environment
         * override, the directory recorded by the installer, or the platform
         * default — in that order.
         */
        fun resolveBinDir(installation: CliInstallation): Path {
            System.getenv("JMIX_CLI_BIN_DIR")?.takeIf { it.isNotBlank() }
                ?.let { runCatching { return Path.of(it) } }
            val recorded = installation.installRoot.resolve(BIN_DIR_FILE)
            runCatching {
                if (Files.isRegularFile(recorded)) {
                    readRecordedPath(recorded)?.let { return Path.of(it) }
                }
            }
            return defaultBinDir(installation.os)
        }

        fun defaultBinDir(os: String): Path = if (os == "windows") {
            val localAppData = System.getenv("LOCALAPPDATA")
                ?: (System.getProperty("user.home") + "\\AppData\\Local")
            Path.of(localAppData, "Jmix", "bin")
        } else {
            Path.of(System.getProperty("user.home"), ".local", "bin")
        }

        /**
         * Reads a path recorded by an installer. Windows PowerShell writes text
         * files in the console codepage or UTF-16, so decoding must tolerate
         * anything rather than silently discard a valid custom directory.
         */
        internal fun readRecordedPath(file: Path): String? {
            val bytes = Files.readAllBytes(file)
            val text = when {
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                    String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
                bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                    String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
                bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
                    bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                    String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
                // Lenient UTF-8: undecodable bytes become replacement characters
                // rather than failing the whole read.
                else -> String(bytes, StandardCharsets.UTF_8)
            }
            return text.trim().lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        }

        /**
         * Human-readable cause for messages that must not show a stack trace.
         * Network and permission failures are the expected ones, and their
         * exception messages alone (a bare host name, a bare path, or nothing)
         * do not explain anything.
         */
        fun describe(e: Throwable): String = when (e) {
            is UnknownHostException, is ConnectException, is HttpTimeoutException, is SocketException ->
                "cannot reach the release server"
            is AccessDeniedException -> "no permission to write ${e.file}"
            is FileSystemException -> "${e.reason ?: "file system error"}: ${e.file}"
            else -> e.message?.takeIf { it.isNotBlank() }?.let {
                if (e is IOException) it else "${e::class.simpleName}: $it"
            } ?: (e::class.simpleName ?: "unknown error")
        }

        /** ASCII with replacement, matching install.ps1's `Set-Content -Encoding Ascii`. */
        private fun encodeAscii(text: String): ByteArray {
            val encoder = StandardCharsets.US_ASCII.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
            return try {
                val buffer = encoder.encode(java.nio.CharBuffer.wrap(text))
                ByteArray(buffer.remaining()).also { buffer.get(it) }
            } catch (e: CharacterCodingException) {
                text.toByteArray(StandardCharsets.US_ASCII)
            }
        }

        private fun deleteRecursively(root: Path, trashParent: Path = root.parent) {
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
            // Move aside first: a delete that aborts halfway (a locked file on
            // Windows) must never leave a half-image under its version name,
            // where the next update would mistake it for a usable install.
            val doomed = runCatching {
                trashParent.resolve("$TRASH_PREFIX${root.fileName}-${ProcessHandle.current().pid()}")
                    .also { Files.move(root, it, StandardCopyOption.ATOMIC_MOVE) }
            }.getOrDefault(root)
            Files.walk(doomed).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
