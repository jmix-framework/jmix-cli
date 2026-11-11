package io.jmix.cli.trial

import io.jmix.cli.env.JdkInstaller
import io.jmix.cli.env.ProjectLauncher
import io.jmix.cli.repo.TemplateRepository
import io.jmix.cli.util.BYTES_PER_MIB
import io.jmix.cli.util.Http
import io.jmix.cli.util.contentLength
import io.jmix.cli.util.copyHttpBody
import io.jmix.cli.util.isSecureOrLoopback
import io.jmix.cli.util.isZip
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE
import java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE
import java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
import java.time.Duration
import java.util.zip.ZipFile
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

internal class TrialDestinationConflict(val paths: List<Path>) : IOException()

/**
 * The Enterprise+BPM trial project as the Jmix Store ships it: a zip with one top folder that holds a composite
 * Gradle build, ten demo applications under `demo/` and a `trial_repository` Maven folder. Each demo finds its
 * add-ons in `../../trial_repository`, so nothing here renames, moves or edits anything inside a project.
 */
internal object TrialArchive {

    const val MAX_ARCHIVE_BYTES: Long = 1L shl 30
    private const val MAX_UNPACKED_BYTES: Long = 4L shl 30

    /**
     * Downloads the archive from [url] into [target]. The link is a capability: whoever holds it can download, so
     * no credentials are sent and the link never appears in an error. Fails unless the answer is HTTP 200, no
     * larger than [maxBytes] and a zip; [target] may hold the partial file then.
     */
    fun download(
        url: String,
        target: Path,
        onProgress: (Long, Long) -> Unit,
        maxBytes: Long = MAX_ARCHIVE_BYTES,
        timeout: Duration = DOWNLOAD_TIMEOUT,
    ) {
        val deadline = System.nanoTime() + timeout.toNanos()
        val response = downloadResponse(url, deadline)
        response.body().use { body ->
            val status = response.statusCode()
            if (status != 200) throw IOException("the trial download failed with HTTP $status")
            val size = response.contentLength()
            if (size > maxBytes) throw IOException("the trial archive is larger than ${maxBytes / BYTES_PER_MIB} MiB")
            try {
                copyHttpBody(body, target, size, timeout, onProgress, maxBytes)
            } catch (_: HttpTimeoutException) {
                throw timedOut()
            }
        }
        if (!isZip(target)) throw IOException("the trial download is not a zip archive")
    }

    fun prepareDestination(parentDir: Path): Staging {
        val parent = Files.createDirectories(parentDir.toAbsolutePath().normalize())
        // Staging in the parent checks write access now and allows same-filesystem publication later.
        return Staging(Files.createTempDirectory(parent, ".jmix-trial-"))
    }

    fun unpack(
        archive: Path,
        parentDir: Path,
        maxBytes: Long = MAX_UNPACKED_BYTES,
        publish: (Path, Path) -> Path = { source, target -> Files.move(source, target) },
    ): List<Path> = prepareDestination(parentDir).use { it.unpack(archive, maxBytes, publish) }

    class Staging internal constructor(private val staging: Path) : AutoCloseable {
        private var preserveStaging = false
        private var closed = false

        init {
            // Shutdown while signing in or downloading can skip close() while staging is still empty.
            staging.toFile().deleteOnExit()
        }

        /** Returns the Gradle project folders with a trial_repository, after staging the whole archive. */
        fun unpack(
            archive: Path,
            maxBytes: Long = MAX_UNPACKED_BYTES,
            publish: (Path, Path) -> Path = { source, target -> Files.move(source, target) },
        ): List<Path> {
            val parent = staging.parent
            // ZipInputStream accepts a missing central directory, which can hide a truncated download.
            ZipFile(archive.toFile()).use { }
            JdkInstaller.unpackZip(archive, staging, maxBytes)
            val names = projectNames(staging)
            if (names.isEmpty()) throw IOException("the downloaded archive does not contain a Jmix trial project")
            val occupied = names.map(parent::resolve).filter { Files.exists(it, NOFOLLOW_LINKS) }
            if (occupied.isNotEmpty()) throw TrialDestinationConflict(occupied)
            names.forEach { makeWrappersExecutable(staging.resolve(it)) }
            // A unique marker identifies our directory even on file systems without a stable file key.
            val marker = staging.fileName.takeIf { names.size > 1 }
            if (marker != null) names.forEach { Files.createFile(staging.resolve(it).resolve(marker)) }
            val moved = mutableListOf<Path>()
            try {
                names.forEach { moved.add(publish(staging.resolve(it), parent.resolve(it))) }
            } catch (e: Exception) {
                for (path in moved.asReversed()) {
                    if (marker == null || !ownsPublishedProject(path, marker)) continue
                    try {
                        val recovered = Files.move(path, staging.resolve(path.fileName))
                        if (!ownsPublishedProject(recovered, marker)) {
                            // The path changed between the ownership check and the move.
                            try {
                                Files.move(recovered, path)
                            } catch (restore: Exception) {
                                preserveStaging = true
                                throw restore
                            }
                        }
                    } catch (rollback: Exception) {
                        e.addSuppressed(rollback)
                    }
                }
                if (preserveStaging) throw IOException("could not unpack the trial; a directory was preserved at $staging", e)
                throw e
            }
            if (marker != null) moved.filter { ownsPublishedProject(it, marker) }.forEach {
                runCatching { Files.deleteIfExists(it.resolve(marker)) }
            }
            return moved
        }

        @OptIn(ExperimentalPathApi::class)
        override fun close() {
            if (closed) return
            closed = true
            // A failed cleanup must not replace the real error: on Windows, antivirus can hold fresh files.
            if (!preserveStaging) runCatching { staging.deleteRecursively() }
        }
    }

    private fun ownsPublishedProject(path: Path, marker: Path): Boolean =
        Files.isDirectory(path, NOFOLLOW_LINKS) && Files.isRegularFile(path.resolve(marker), NOFOLLOW_LINKS)

    /**
     * The Jmix version of [projectDir]: the first `bomVersion` in its root build file, then in the build files of
     * the builds under `demo/` by name. A `.trial` suffix is dropped. Null when there is none or it does not look
     * like a version. Trials run on the newest Jmix, so only the shape is checked: the template-version filter
     * would reject it.
     */
    fun jmixVersion(projectDir: Path): String? {
        val bomVersion = (listOf(projectDir) + demoDirs(projectDir))
            .flatMap { dir -> BUILD_FILES.map(dir::resolve) }
            .firstNotNullOfOrNull { file ->
                ProjectLauncher.readProjectMetadata(file)?.let { BOM_VERSION.find(it)?.groupValues?.get(1) }
            }
        return bomVersion?.removeSuffix(".trial")?.takeIf { TemplateRepository.VERSION_SHAPE.matches(it) }
    }

    /** The builds under `demo/` that have a Gradle wrapper, by name; [projectDir] itself when there are none. */
    fun runnableApps(projectDir: Path): List<Path> =
        demoDirs(projectDir).filter { Files.isRegularFile(it.resolve("gradlew")) }.ifEmpty { listOf(projectDir) }

    private fun downloadResponse(link: String, deadline: Long): HttpResponse<InputStream> {
        var uri = runCatching { URI(link) }.getOrElse { throw unusableLink() }
        repeat(MAX_REDIRECTS + 1) {
            if (!uri.isSecureOrLoopback()) throw unusableLink()
            val timeout = remaining(deadline)
            val response = try {
                Http.accountClient.send(Http.request(uri.toString(), timeout).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
            } catch (e: IllegalArgumentException) {
                throw unusableLink()
            } catch (e: HttpTimeoutException) {
                throw timedOut()
            } catch (e: IOException) {
                throw IOException("cannot reach the trial download service")
            }
            if (response.statusCode() !in REDIRECT_STATUS_CODES) return response
            response.body().close()
            val location = response.headers().firstValue("Location").orElseThrow(::unusableLink)
            val next = runCatching { uri.resolve(location) }.getOrElse { throw unusableLink() }
            if (uri.scheme.equals("https", ignoreCase = true) && !next.scheme.equals("https", ignoreCase = true)) {
                throw unusableLink()
            }
            uri = next
        }
        throw IOException("too many redirects from the trial download service")
    }

    private fun remaining(deadline: Long): Duration = (deadline - System.nanoTime()).let {
        if (it <= 0) throw timedOut()
        Duration.ofNanos(it)
    }

    private fun timedOut() = IOException("the trial download timed out")

    private fun unusableLink() = IOException("the Jmix Store returned an unusable trial download link")

    /** Names of the top-level folders of [dir] that are trial projects, by name. */
    private fun projectNames(dir: Path): List<String> =
        Files.list(dir).use { entries -> entries.filter { isTrialProject(it) }.map { it.fileName.toString() }.toList() }
            .sorted()

    private fun isTrialProject(dir: Path): Boolean =
        Files.isDirectory(dir.resolve("trial_repository")) &&
            SETTINGS_FILES.any { Files.isRegularFile(dir.resolve(it)) }

    /** Adds the execute bits to every `gradlew` below [project], whatever else its permissions say. */
    private fun makeWrappersExecutable(project: Path) {
        try {
            Files.walk(project).use { paths ->
                paths.filter { it.fileName.toString() == "gradlew" && Files.isRegularFile(it) }
                    .forEach { Files.setPosixFilePermissions(it, Files.getPosixFilePermissions(it) + EXECUTE_BITS) }
            }
        } catch (e: UnsupportedOperationException) {
            // No POSIX permissions here (Windows), and no need: the wrapper runs as gradlew.bat.
        }
    }

    private fun demoDirs(projectDir: Path): List<Path> {
        val demo = projectDir.resolve("demo")
        if (!Files.isDirectory(demo)) return emptyList()
        return Files.list(demo).use { entries -> entries.filter { Files.isDirectory(it) }.toList() }
            .sortedBy { it.fileName.toString() }
    }

    private val BUILD_FILES = listOf("build.gradle", "build.gradle.kts")
    private val SETTINGS_FILES = listOf("settings.gradle", "settings.gradle.kts")
    private val BOM_VERSION = Regex("""bomVersion\s*=\s*['"]([^'"]+)['"]""")
    private val EXECUTE_BITS = setOf(OWNER_EXECUTE, GROUP_EXECUTE, OTHERS_EXECUTE)
    private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
    private const val MAX_REDIRECTS = 5
    private val DOWNLOAD_TIMEOUT: Duration = Duration.ofSeconds(60)
}
