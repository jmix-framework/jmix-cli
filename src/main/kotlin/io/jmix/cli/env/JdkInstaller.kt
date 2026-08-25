package io.jmix.cli.env

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Downloads and unpacks an Eclipse Temurin JDK into `~/.jmix/jdks/` for the
 * generated project (the CLI itself runs on its bundled runtime). Archives are
 * verified against the SHA-256 checksum published by the Adoptium API before
 * unpacking, and a corrupt or partial download is never installed.
 */
object JdkInstaller {

    /**
     * Installs the latest GA Temurin JDK for [majorVersion] and returns its
     * home. A JDK already present in [jdksDir] is reused without downloading.
     * [onProgress] receives (downloadedBytes, totalBytes).
     */
    fun install(
        majorVersion: Int,
        jdksDir: Path = defaultJdksDir(),
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Path {
        val asset = resolveAsset(majorVersion)
        val targetDir = jdksDir.resolve(asset.releaseName)
        validJdkHome(targetDir, majorVersion)?.let { return it }

        Files.createDirectories(jdksDir)
        var archive: Path? = null
        var unpackDir: Path? = null
        try {
            archive = Files.createTempFile(jdksDir, "jdk-download", ".tmp")
            unpackDir = Files.createTempDirectory(jdksDir, "jdk-unpack")
            download(asset, archive, onProgress)
            verifyChecksum(archive, asset)
            unpack(archive, unpackDir, asset.pkg.name)

            // Archives contain a single root directory such as jdk-25.0.3+9.
            val extractedRoot = Files.list(unpackDir).use { it.toList() }
                .singleOrNull { Files.isDirectory(it) }
                ?: throw IOException("Unexpected JDK archive layout (${asset.pkg.name})")

            // A directory left by an interrupted or broken install is replaced;
            // a concurrent installer that won the race is reused instead.
            if (Files.isDirectory(targetDir)) {
                deleteRecursively(targetDir)
            }
            try {
                Files.move(extractedRoot, targetDir)
            } catch (e: IOException) {
                validJdkHome(targetDir, majorVersion)?.let { return it }
                throw e
            }

            return validJdkHome(targetDir, majorVersion) ?: run {
                deleteRecursively(targetDir)
                throw IOException("Downloaded JDK is not a valid JDK $majorVersion (${asset.releaseName})")
            }
        } finally {
            archive?.let { Files.deleteIfExists(it) }
            unpackDir?.let { deleteRecursively(it) }
        }
    }

    /** The java home in [jdkDir] when it holds a working JDK [majorVersion], else null. */
    private fun validJdkHome(jdkDir: Path, majorVersion: Int): Path? =
        javaHomeIn(jdkDir)?.takeIf { JdkDetector.majorVersionOf(it) == majorVersion }

    fun defaultJdksDir(): Path = Path.of(System.getProperty("user.home"), ".jmix", "jdks")

    /** The java home inside an installed JDK directory, or null when absent. */
    fun javaHomeIn(jdkDir: Path): Path? {
        val candidates = listOf(jdkDir, jdkDir.resolve("Contents/Home"))
        return candidates.firstOrNull { home ->
            val exe = home.resolve("bin").resolve(if (isWindows()) "java.exe" else "java")
            Files.isRegularFile(exe)
        }
    }

    fun assetsUrl(
        majorVersion: Int,
        os: String = adoptiumOs(),
        arch: String = adoptiumArch(),
    ): String = "$ADOPTIUM_API/v3/assets/latest/$majorVersion/hotspot" +
        "?architecture=$arch&image_type=jdk&os=$os&vendor=eclipse"

    fun adoptiumOs(os: String = System.getProperty("os.name")): String = when {
        os.lowercase().contains("mac") -> "mac"
        os.lowercase().contains("win") -> "windows"
        else -> "linux"
    }

    fun adoptiumArch(arch: String = System.getProperty("os.arch")): String = when (arch.lowercase()) {
        "aarch64", "arm64" -> "aarch64"
        "x86_64", "amd64" -> "x64"
        else -> arch
    }

    // --- Adoptium API ----------------------------------------------------------

    internal data class AdoptiumAsset(
        @SerializedName("release_name") val releaseName: String,
        @SerializedName("binary") val binary: AdoptiumBinary,
    ) {
        val pkg: AdoptiumPackage get() = binary.pkg
    }

    internal data class AdoptiumBinary(
        @SerializedName("image_type") val imageType: String?,
        @SerializedName("package") val pkg: AdoptiumPackage,
    )

    internal data class AdoptiumPackage(
        @SerializedName("link") val link: String,
        @SerializedName("checksum") val checksum: String,
        @SerializedName("name") val name: String,
        @SerializedName("size") val size: Long,
    )

    internal fun pickAsset(json: String): AdoptiumAsset {
        val assets = Gson().fromJson(json, Array<AdoptiumAsset>::class.java) ?: emptyArray()
        return assets.firstOrNull { it.binary.imageType == "jdk" }
            ?: throw IOException("No Temurin JDK build is available for this platform.")
    }

    private fun resolveAsset(majorVersion: Int): AdoptiumAsset {
        val url = assetsUrl(majorVersion)
        val response = http.send(
            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (response.statusCode() != 200) {
            throw IOException("Failed to query the Adoptium API ($url): HTTP ${response.statusCode()}")
        }
        return pickAsset(response.body())
    }

    private fun download(asset: AdoptiumAsset, target: Path, onProgress: (Long, Long) -> Unit) {
        val response = http.send(
            HttpRequest.newBuilder(URI.create(asset.pkg.link)).timeout(Duration.ofSeconds(60)).GET().build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        if (response.statusCode() != 200) {
            throw IOException("Failed to download JDK (${asset.pkg.link}): HTTP ${response.statusCode()}")
        }
        val total = asset.pkg.size
        var done = 0L
        response.body().use { input ->
            Files.newOutputStream(target).use { output ->
                val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    done += read
                    onProgress(done, total)
                }
            }
        }
    }

    private fun verifyChecksum(archive: Path, asset: AdoptiumAsset) {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(archive).use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(asset.pkg.checksum, ignoreCase = true)) {
            throw IOException("JDK checksum verification failed (${asset.pkg.name}).")
        }
    }

    private fun unpack(archive: Path, destDir: Path, archiveName: String) {
        if (archiveName.endsWith(".zip")) {
            unpackZip(archive, destDir)
        } else {
            // macOS and Linux ship tar; it preserves the symlinks and
            // executable bits that java.util.zip cannot represent.
            unpackTar(archive, destDir)
        }
    }

    internal fun unpackZip(archive: Path, destDir: Path) {
        ZipInputStream(Files.newInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = destDir.resolve(entry.name).normalize()
                if (!target.startsWith(destDir)) {
                    throw IOException("Invalid archive entry: ${entry.name}")
                }
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun unpackTar(archive: Path, destDir: Path) {
        val process = ProcessBuilder("tar", "-xzf", archive.toString(), "-C", destDir.toString())
            .redirectErrorStream(true)
            .start()
        // Wait before reading: tar's diagnostics fit the pipe buffer, and
        // reading first would block forever on a hung process.
        if (!process.waitFor(UNPACK_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            throw IOException("Timed out unpacking the JDK archive.")
        }
        val output = process.inputStream.bufferedReader().readText()
        if (process.exitValue() != 0) {
            throw IOException("Failed to unpack JDK archive: ${output.trim().take(200)}")
        }
    }

    private fun deleteRecursively(dir: Path) {
        if (!Files.isDirectory(dir)) return
        Files.walk(dir).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")

    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    private const val ADOPTIUM_API = "https://api.adoptium.net"
    private const val DOWNLOAD_BUFFER_SIZE = 64 * 1024
    private const val UNPACK_TIMEOUT_MINUTES = 5L
}
