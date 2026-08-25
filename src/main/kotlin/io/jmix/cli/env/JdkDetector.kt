package io.jmix.cli.env

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

data class Jdk(val majorVersion: Int, val home: Path)

/**
 * Locates JDKs on the machine: JAVA_HOME, PATH, SDKMAN, and OS-standard
 * install locations. Versions are read from the `release` file when present,
 * falling back to `java -version` output.
 */
object JdkDetector {

    fun detectJdks(): List<Jdk> {
        val candidates = buildList {
            System.getenv("JAVA_HOME")?.let { add(Path.of(it)) }
            javaFromPath()?.let { add(it) }
            addAll(sdkmanJdks())
            addAll(osStandardJdks())
        }
        return candidates
            .filter { Files.isDirectory(it) }
            .mapNotNull { home -> majorVersionOf(home)?.let { Jdk(it, home.normalize()) } }
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
        return roots.flatMap { listChildren(it) }.map {
            // macOS JDK bundles keep the actual home under Contents/Home.
            val bundled = it.resolve("Contents/Home")
            if (Files.isDirectory(bundled)) bundled else it
        }
    }

    private fun listChildren(dir: Path): List<Path> =
        if (Files.isDirectory(dir)) Files.list(dir).use { it.toList() } else emptyList()

    fun majorVersionOf(javaHome: Path): Int? =
        versionFromReleaseFile(javaHome) ?: versionFromJavaExecutable(javaHome)

    private fun versionFromReleaseFile(javaHome: Path): Int? {
        val release = javaHome.resolve("release")
        if (!Files.isRegularFile(release)) return null
        val line = Files.readAllLines(release).firstOrNull { it.startsWith("JAVA_VERSION=") } ?: return null
        return parseMajorVersion(line.removePrefix("JAVA_VERSION=").trim('"'))
    }

    private fun versionFromJavaExecutable(javaHome: Path): Int? {
        val exe = javaHome.resolve("bin").resolve(if (isWindows()) "java.exe" else "java")
        if (!Files.isExecutable(exe)) return null
        return try {
            val process = ProcessBuilder(exe.toString(), "-version")
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(10, TimeUnit.SECONDS)
            Regex("version \"([^\"]+)\"").find(output)?.let { parseMajorVersion(it.groupValues[1]) }
        } catch (e: Exception) {
            null
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
