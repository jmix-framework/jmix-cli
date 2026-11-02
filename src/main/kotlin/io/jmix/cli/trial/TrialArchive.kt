package io.jmix.cli.trial

import io.jmix.cli.env.JdkInstaller
import io.jmix.cli.repo.TemplateRepository
import io.jmix.cli.util.BYTES_PER_MIB
import io.jmix.cli.util.Http
import io.jmix.cli.util.contentLength
import io.jmix.cli.util.copyWithProgress
import io.jmix.cli.util.isZip
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE
import java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE
import java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
import java.time.Duration
import java.util.zip.ZipFile
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

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
    fun download(url: String, target: Path, onProgress: (Long, Long) -> Unit, maxBytes: Long = MAX_ARCHIVE_BYTES) {
        val request = downloadRequest(url)
        val response = try {
            Http.client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: IllegalArgumentException) {
            // The JDK quotes the address of a bad link, or of a bad redirect, in this exception.
            throw unusableLink()
        } catch (e: IOException) {
            // HttpClient can wrap an invalid redirect (including the capability link) in an IOException.
            throw IOException("cannot reach the trial download service")
        }
        response.body().use { body ->
            val status = response.statusCode()
            if (status != 200) throw IOException("the trial download failed with HTTP $status")
            val size = response.contentLength()
            if (size > maxBytes) throw IOException("the trial archive is larger than ${maxBytes / BYTES_PER_MIB} MiB")
            copyWithProgress(body, target, size, onProgress, maxBytes)
        }
        if (!isZip(target)) throw IOException("the trial download is not a zip archive")
    }

    /**
     * Unpacks [archive] into [parentDir], which is created when missing, and returns the projects it holds: the
     * top folders with a Gradle settings file and a `trial_repository`. Other top-level entries are ignored.
     * The archive stores no permissions, so every `gradlew` is made executable. Nothing reaches [parentDir] unless
     * the whole archive is good and no project folder exists there yet.
     */
    @OptIn(ExperimentalPathApi::class)
    fun unpack(archive: Path, parentDir: Path, maxBytes: Long = MAX_UNPACKED_BYTES): List<Path> {
        // unpackZip compares normalized entry paths with its destination, so the destination must be normalized too.
        val parent = parentDir.toAbsolutePath().normalize()
        Files.createDirectories(parent)
        // The staging folder lies in the parent, so the final move is a rename on the same file system.
        val staging = Files.createTempDirectory(parent, ".jmix-trial-")
        try {
            // ZipInputStream accepts a missing central directory, which can hide a truncated download.
            ZipFile(archive.toFile()).use { }
            JdkInstaller.unpackZip(archive, staging, maxBytes)
            val names = projectNames(staging)
            if (names.isEmpty()) throw IOException("the downloaded archive does not contain a Jmix trial project")
            names.map(parent::resolve).firstOrNull { Files.exists(it) }?.let {
                throw IOException("$it already exists. Remove it or choose another directory with --path")
            }
            names.forEach { makeWrappersExecutable(staging.resolve(it)) }
            return names.map { Files.move(staging.resolve(it), parent.resolve(it)) }
        } finally {
            // A failed cleanup must not replace the real error: on Windows, antivirus can hold fresh files.
            // Ctrl+C during the short unpack can leave the hidden staging folder; add a shutdown hook if users report it.
            runCatching { staging.deleteRecursively() }
        }
    }

    /**
     * The Jmix version of [projectDir]: the first `bomVersion` in its root build file, then in the build files of
     * the builds under `demo/` by name. A `.trial` suffix is dropped. Null when there is none or it does not look
     * like a version. Trials run on the newest Jmix, so only the shape is checked: the template-version filter
     * would reject it.
     */
    fun jmixVersion(projectDir: Path): String? {
        val bomVersion = (listOf(projectDir) + demoDirs(projectDir))
            .flatMap { dir -> BUILD_FILES.map(dir::resolve) }
            .filter { Files.isRegularFile(it) }
            .firstNotNullOfOrNull { BOM_VERSION.find(Files.readAllBytes(it).decodeToString())?.groupValues?.get(1) }
        return bomVersion?.removeSuffix(".trial")?.takeIf { TemplateRepository.VERSION_SHAPE.matches(it) }
    }

    /** The builds under `demo/` that have a Gradle wrapper, by name; [projectDir] itself when there are none. */
    fun runnableApps(projectDir: Path): List<Path> =
        demoDirs(projectDir).filter { Files.isRegularFile(it.resolve("gradlew")) }.ifEmpty { listOf(projectDir) }

    /** The request for a trial link, or an error that does not quote the link: it is a capability. */
    private fun downloadRequest(link: String): HttpRequest {
        val uri = try {
            URI(link)
        } catch (e: URISyntaxException) {
            throw unusableLink()
        }
        val host = uri.host?.lowercase() ?: throw unusableLink()
        val scheme = uri.scheme?.lowercase()
        // Plain http is for the loopback servers of the tests only.
        if (scheme != "https" && !(scheme == "http" && host in LOOPBACK_HOSTS)) throw unusableLink()
        return Http.request(link, DOWNLOAD_TIMEOUT).GET().build()
    }

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
    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost")
    private val DOWNLOAD_TIMEOUT: Duration = Duration.ofSeconds(60)
}
