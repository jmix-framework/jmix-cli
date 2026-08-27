package io.jmix.cli.env

import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Opens a generated project and runs its Gradle wrapper after the wizard
 * finishes. IDEA detection covers the `idea` launcher on PATH (JetBrains
 * Toolbox and manual installs) and macOS application bundles; when IDEA is
 * not found the project directory opens in the OS file manager instead.
 */
object ProjectLauncher {

    enum class Opener(val displayName: String) {
        IDEA("IntelliJ IDEA"),
        FILE_MANAGER("the file manager"),
    }

    data class OpenCommand(val opener: Opener, val command: List<String>)

    /** IDEA launch command, or null when no IDEA installation was found. */
    fun ideaOpenCommand(
        projectDir: Path,
        os: String = System.getProperty("os.name"),
        pathEnv: String? = System.getenv("PATH"),
        macAppDirs: List<Path> = defaultMacAppDirs(),
    ): OpenCommand? =
        ideaCommand(projectDir, os, pathEnv, macAppDirs)?.let { OpenCommand(Opener.IDEA, it) }

    /** Always available: every desktop OS has a file manager. */
    fun fileManagerOpenCommand(
        projectDir: Path,
        os: String = System.getProperty("os.name"),
    ): OpenCommand = OpenCommand(Opener.FILE_MANAGER, fileManagerCommand(projectDir, os))

    fun ideaCommand(projectDir: Path, os: String, pathEnv: String?, macAppDirs: List<Path>): List<String>? {
        ideaLauncherOnPath(os, pathEnv)?.let { return listOf(it.toString(), projectDir.toString()) }
        if (isMac(os)) {
            macIdeaApp(macAppDirs)?.let { return listOf("open", "-a", it.toString(), projectDir.toString()) }
        }
        return null
    }

    fun fileManagerCommand(projectDir: Path, os: String = System.getProperty("os.name")): List<String> = when {
        isMac(os) -> listOf("open", projectDir.toString())
        isWindows(os) -> listOf("explorer.exe", projectDir.toString())
        else -> listOf("xdg-open", projectDir.toString())
    }

    // Relative wrapper path: the child process starts in the project directory,
    // and `cmd /c` needs a bare script name to avoid quoting issues with spaces.
    fun gradleCommand(task: String, os: String = System.getProperty("os.name")): List<String> =
        if (isWindows(os)) listOf("cmd", "/c", "gradlew.bat", task) else listOf("./gradlew", task)

    /**
     * Launches [open] detached; the IDE or file manager outlives the CLI.
     * Returns false when the command could not start or exited with an error
     * right away, so the caller can fall back to another opener.
     */
    fun open(open: OpenCommand, onWarning: (String) -> Unit): Boolean {
        val process = try {
            ProcessBuilder(open.command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (e: Exception) {
            onWarning("Failed to open the project (${open.command.first()}): ${e.message}")
            return false
        }
        // A launcher that exits immediately with an error opened nothing. Only
        // the IDE launcher's code is meaningful: explorer.exe reports a non-zero
        // code even when the window opens.
        if (open.opener != Opener.IDEA) return true
        val exited = try {
            process.waitFor(OPEN_PROBE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return true
        }
        if (exited && process.exitValue() != 0) {
            onWarning(
                "${open.opener.displayName} failed to open the project " +
                    "(${open.command.first()} exited with ${process.exitValue()}).",
            )
            return false
        }
        return true
    }

    /**
     * Runs the Gradle wrapper [task] in [projectDir], streaming output to this
     * terminal. [javaHome], when given, is exported as JAVA_HOME so the wrapper
     * uses a compatible JDK regardless of the shell environment.
     */
    fun runGradle(projectDir: Path, task: String, javaHome: Path? = null): Int {
        val builder = ProcessBuilder(gradleCommand(task))
            .directory(projectDir.toFile())
            .inheritIO()
        javaHome?.let { builder.environment()["JAVA_HOME"] = it.toString() }
        return builder.start().waitFor()
    }

    fun browserCommand(url: String, os: String = System.getProperty("os.name")): List<String> = when {
        isMac(os) -> listOf("open", url)
        // rundll32 opens the default browser without cmd.exe quoting pitfalls.
        isWindows(os) -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
        else -> listOf("xdg-open", url)
    }

    /** The generated app's HTTP port: `server.port` from application.properties, or 8080. */
    fun serverPort(projectDir: Path): Int {
        val props = projectDir.resolve("src/main/resources/application.properties")
        return runCatching {
            Files.readAllLines(props).firstNotNullOfOrNull { line ->
                SERVER_PORT_PROPERTY.matchEntire(line.trim())?.groupValues?.get(1)
                    ?.toIntOrNull()?.takeIf { it in 1..65535 }
            }
        }.getOrNull() ?: DEFAULT_SERVER_PORT
    }

    fun isPortInUse(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("localhost", port), PORT_PROBE_TIMEOUT_MS) }
        true
    } catch (e: IOException) {
        false
    }

    /**
     * Opens [url] in the default browser once [port] starts accepting
     * connections. Polls from a daemon thread so the foreground Gradle run
     * keeps the terminal; gives up silently when the app never starts, and
     * dies with the CLI when the run ends earlier.
     */
    fun openBrowserWhenReady(port: Int, url: String, onWarning: (String) -> Unit) {
        val thread = Thread {
            val deadline = System.nanoTime() + APP_STARTUP_TIMEOUT.toNanos()
            while (System.nanoTime() < deadline) {
                if (isPortInUse(port)) {
                    try {
                        ProcessBuilder(browserCommand(url))
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start()
                    } catch (e: Exception) {
                        onWarning("Failed to open $url in the browser: ${e.message}")
                    }
                    return@Thread
                }
                try {
                    Thread.sleep(APP_POLL_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    return@Thread
                }
            }
        }
        thread.isDaemon = true
        thread.name = "jmix-open-browser"
        thread.start()
    }

    fun ideaLauncherOnPath(os: String, pathEnv: String?): Path? {
        if (pathEnv.isNullOrEmpty()) return null
        val names = if (isWindows(os)) listOf("idea.cmd", "idea.bat", "idea.exe") else listOf("idea")
        return pathEnv.splitToSequence(File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .flatMap { dir -> names.map { name -> runCatching { Path.of(dir, name) }.getOrNull() } }
            .filterNotNull()
            .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
    }

    fun macIdeaApp(appDirs: List<Path>): Path? =
        appDirs.asSequence()
            .flatMap { dir -> MAC_IDEA_APPS.asSequence().map(dir::resolve) }
            .firstOrNull(Files::isDirectory)

    fun defaultMacAppDirs(): List<Path> = listOf(
        Path.of("/Applications"),
        Path.of(System.getProperty("user.home"), "Applications"),
    )

    private fun isMac(os: String) = os.lowercase().contains("mac")

    private fun isWindows(os: String) = os.lowercase().contains("win")

    private val MAC_IDEA_APPS = listOf(
        "IntelliJ IDEA.app",
        "IntelliJ IDEA Ultimate.app",
        "IntelliJ IDEA CE.app",
        "IntelliJ IDEA Community Edition.app",
    )

    private val SERVER_PORT_PROPERTY = Regex("""server\.port\s*=\s*(\d+)""")
    private const val DEFAULT_SERVER_PORT = 8080
    private const val PORT_PROBE_TIMEOUT_MS = 1000

    // Long enough to catch a launcher that fails outright, short enough not to
    // stall the wizard when the IDE process keeps running.
    private const val OPEN_PROBE_MS = 1200L
    private const val APP_POLL_INTERVAL_MS = 1000L

    // First runs download Gradle and dependencies, which can take a while.
    private val APP_STARTUP_TIMEOUT: Duration = Duration.ofMinutes(15)
}
