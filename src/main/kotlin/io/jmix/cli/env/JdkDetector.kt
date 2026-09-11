package io.jmix.cli.env

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

data class Jdk(val majorVersion: Int, val home: Path)

/**
 * Locates JDKs on the machine: JAVA_HOME, PATH, SDKMAN, and OS-standard
 * install locations. Versions are read from the `release` file when present,
 * falling back to the version and actual home reported by the Java executable.
 */
object JdkDetector {

    fun detectJdks(): List<Jdk> {
        val candidates = buildList {
            System.getenv("JAVA_HOME")?.let { add(Path.of(it)) }
            javaFromPath()?.let { add(it) }
            addAll(sdkmanJdks())
            addAll(managedJdks())
            addAll(osStandardJdks())
        }
        return candidates
            .filter { Files.isDirectory(it) }
            .mapNotNull(::detectJdk)
            .distinctBy { it.home }
            .sortedByDescending { it.majorVersion }
    }

    private fun javaFromPath(): Path? {
        val exe = if (isWindows()) "java.exe" else "java"
        return System.getenv("PATH")?.split(java.io.File.pathSeparator)
            ?.map { Path.of(it, exe) }
            ?.firstOrNull { Files.isExecutable(it) }
            ?.let { resolveJavaHome(it) }
    }

    private fun resolveJavaHome(javaExe: Path): Path? = try {
        javaExe.toRealPath().parent?.parent
    } catch (e: Exception) {
        null
    }

    private fun sdkmanJdks(): List<Path> {
        val dir = Path.of(System.getProperty("user.home"), ".sdkman", "candidates", "java")
        return listChildren(dir).filterNot { it.fileName.toString() == "current" }
    }

    /** JDKs installed by the CLI itself (see [JdkInstaller]). */
    private fun managedJdks(): List<Path> =
        listChildren(JdkInstaller.defaultJdksDir()).map(::withMacBundleHome)

    private fun osStandardJdks(): List<Path> {
        val os = System.getProperty("os.name").lowercase()
        val roots = when {
            os.contains("mac") -> listOf(Path.of("/Library/Java/JavaVirtualMachines"))
            os.contains("win") -> listOf(
                Path.of("C:\\Program Files\\Java"),
                Path.of("C:\\Program Files\\Eclipse Adoptium"),
            )
            else -> listOf(Path.of("/usr/lib/jvm"))
        }
        return roots.flatMap { listChildren(it) }.map(::withMacBundleHome)
    }

    /** macOS JDK bundles keep the actual home under Contents/Home. */
    private fun withMacBundleHome(jdkDir: Path): Path {
        val bundled = jdkDir.resolve("Contents/Home")
        return if (Files.isDirectory(bundled)) bundled else jdkDir
    }

    private fun listChildren(dir: Path): List<Path> =
        if (Files.isDirectory(dir)) Files.list(dir).use { it.toList() } else emptyList()

    fun majorVersionOf(javaHome: Path): Int? = detectJdk(javaHome)?.majorVersion

    internal fun detectJdk(javaHome: Path): Jdk? {
        val home = runCatching { javaHome.toRealPath() }.getOrNull() ?: return null
        return versionFromReleaseFile(home)?.let { Jdk(it, home) } ?: jdkFromJavaExecutable(home)
    }

    private fun versionFromReleaseFile(javaHome: Path): Int? {
        val release = javaHome.resolve("release")
        if (!Files.isRegularFile(release)) return null
        val line = Files.readAllLines(release).firstOrNull { it.startsWith("JAVA_VERSION=") } ?: return null
        return parseMajorVersion(line.removePrefix("JAVA_VERSION=").trim('"'))
    }

    private fun jdkFromJavaExecutable(javaHome: Path): Jdk? {
        val exe = javaHome.resolve("bin").resolve(if (isWindows()) "java.exe" else "java")
        if (!Files.isExecutable(exe)) return null
        val log = Files.createTempFile("jmix-java-", ".log")
        var process: Process? = null
        return try {
            process = ProcessBuilder(exe.toString(), "-XshowSettings:properties", "-version")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start()
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) return null
            val output = Files.readString(log)
            val version = Regex("version \"([^\"]+)\"").find(output)
                ?.let { parseMajorVersion(it.groupValues[1]) } ?: return null
            // A launcher such as macOS /usr/bin/java is not itself a JDK home.
            val reportedHome = Regex("(?m)^\\s*java\\.home\\s*=\\s*(.+)$").find(output)
                ?.groupValues?.get(1)?.trim() ?: return null
            val home = Path.of(reportedHome).toRealPath()
            // Java 8 reports the nested JRE, while JAVA_HOME should point at the JDK.
            val jdkHome = if (home.fileName.toString() == "jre" && Files.isRegularFile(home.parent.resolve("release"))) {
                home.parent
            } else home
            Jdk(version, jdkHome)
        } catch (e: Exception) {
            null
        } finally {
            process?.takeIf { it.isAlive }?.destroyForcibly()?.waitFor(10, TimeUnit.SECONDS)
            Files.deleteIfExists(log)
        }
    }

    /** "21.0.1" -> 21, "1.8.0_392" -> 8, "17" -> 17 */
    fun parseMajorVersion(version: String): Int? {
        val parts = version.split('.', '_', '-', '+')
        val first = parts.firstOrNull()?.toIntOrNull() ?: return null
        return if (first == 1) parts.getOrNull(1)?.toIntOrNull() else first
    }

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")
}
