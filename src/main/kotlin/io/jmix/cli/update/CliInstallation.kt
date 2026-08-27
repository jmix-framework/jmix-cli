package io.jmix.cli.update

import java.nio.file.Path
import kotlin.io.path.name

/**
 * A release installation laid out by install.sh / install.ps1:
 * `<installRoot>/versions/<archive-sha256>/<image files>`. Detected from the
 * running launcher's path; absent for source runs (gradlew run, run.sh), so
 * self-update never touches development builds.
 */
data class CliInstallation(
    val installRoot: Path,
    val currentChecksum: String,
    val os: String,
    val arch: String,
) {
    val versionsDir: Path get() = installRoot.resolve("versions")

    /** Release asset name, matching the releaseBundle Gradle task. */
    val archiveName: String
        get() = "jmix-cli-$os-$arch." + if (os == "windows") "zip" else "tar.gz"

    /** Directory name at the archive root (the jpackage image). */
    val imageName: String get() = if (os == "macos") "jmix.app" else "jmix"

    /** Launcher path inside a version directory, matching the installers. */
    val launcherRelativePath: String
        get() = when (os) {
            "macos" -> "Contents/MacOS/jmix"
            "windows" -> "jmix.exe"
            else -> "bin/jmix"
        }

    fun launcher(checksum: String): Path =
        versionsDir.resolve(checksum).resolve(launcherRelativePath)

    /** A `<installRoot>/versions/<checksum>/...` path split into its parts. */
    data class VersionRef(val versionsDir: Path, val checksum: String)

    companion object {
        internal val CHECKSUM_NAME = Regex("[0-9a-f]{64}")

        /**
         * Locates the `versions/<checksum>` pair [path] lives under. Purely
         * syntactic: the checksum is read from the path structure rather than
         * matched anywhere in the string, so an install root that itself
         * contains a hex-named directory is never mistaken for a version.
         */
        fun versionRef(path: Path): VersionRef? {
            var dir: Path? = path.normalize()
            while (dir != null) {
                val parent = dir.parent
                if (parent != null && parent.name == "versions" && CHECKSUM_NAME.matches(dir.name)) {
                    return VersionRef(parent, dir.name)
                }
                dir = parent
            }
            return null
        }

        fun detect(
            launcherPath: Path? = currentProcessPath(),
            os: String? = currentOs(),
            arch: String? = currentArch(),
        ): CliInstallation? {
            if (launcherPath == null || os == null || arch == null) return null
            // Resolve symlinks so a `~/.local/bin/jmix` argv path still maps
            // to the versioned image it points at.
            val real = runCatching { launcherPath.toRealPath() }
                .getOrElse { launcherPath.toAbsolutePath().normalize() }
            val ref = versionRef(real.parent ?: return null) ?: return null
            val root = ref.versionsDir.parent ?: return null
            return CliInstallation(root, ref.checksum, os, arch)
        }

        fun currentProcessPath(): Path? =
            ProcessHandle.current().info().command().map { Path.of(it) }.orElse(null)

        fun currentOs(): String? {
            val name = System.getProperty("os.name") ?: return null
            return when {
                name.startsWith("Mac", ignoreCase = true) -> "macos"
                name.startsWith("Windows", ignoreCase = true) -> "windows"
                name.startsWith("Linux", ignoreCase = true) -> "linux"
                else -> null
            }
        }

        fun currentArch(): String? = when (System.getProperty("os.arch")?.lowercase()) {
            "aarch64", "arm64" -> "arm64"
            "amd64", "x86_64", "x64" -> "x64"
            else -> null
        }
    }
}
