package io.jmix.cli.env

import io.jmix.cli.util.Http
import io.jmix.cli.util.hostOf
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Installs the Jmix Agent Toolkit
 * (https://github.com/jmix-framework/jmix-agent-toolkit) into a generated
 * project: shared guidelines, local skills, and project MCP configuration.
 * The downloaded script runs from a file with a fixed argument list.
 */
object AgentToolkitInstaller {

    /** Toolkit branch for a Jmix version; majors are the only accepted shape. */
    fun branch(jmixVersion: String): String {
        val major = jmixVersion.substringBefore('.').toIntOrNull()
            ?: throw IOException("Unexpected Jmix version format: $jmixVersion")
        return "v$major"
    }

    fun installerUrl(jmixVersion: String, os: String = System.getProperty("os.name")): String {
        val script = if (isWindows(os)) "install.ps1" else "install.sh"
        return "$RAW_CONTENT_BASE/${branch(jmixVersion)}/$script"
    }

    fun skillsArgs(os: String = System.getProperty("os.name")): List<String> =
        if (isWindows(os)) {
            listOf("skills", "-Scope", "local")
        } else {
            listOf("skills", "--scope", "local")
        }

    fun guidelinesArgs(): List<String> = listOf("agents-md")

    internal fun mcpArgs(os: String = System.getProperty("os.name")): List<String> =
        if (isWindows(os)) {
            listOf("mcp", "-Servers", MCP_SERVERS, "-Agents", MCP_AGENTS, "-Scope", "local")
        } else {
            listOf("mcp", "--servers", MCP_SERVERS, "--agents", MCP_AGENTS, "--scope", "local")
        }

    fun command(scriptFile: Path, args: List<String>, os: String = System.getProperty("os.name")): List<String> =
        if (isWindows(os)) {
            listOf("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", scriptFile.toString()) + args
        } else {
            listOf("bash", scriptFile.toString()) + args
        }

    /**
     * Installs the Toolkit into [projectDir].
     * [onStatus] names each phase before it starts, for a progress indicator.
     */
    fun install(projectDir: Path, jmixVersion: String, onStatus: (String) -> Unit = {}) {
        val suffix = if (isWindows()) ".ps1" else ".sh"
        val script = Files.createTempFile("jmix-agent-toolkit", suffix)
        try {
            val url = installerUrl(jmixVersion)
            onStatus("Downloading the Agent Toolkit installer from ${hostOf(url)}")
            download(url, script)
            onStatus("Installing Agent Toolkit project-local skills")
            runStep(projectDir, command(script, skillsArgs()), "skills")
            onStatus("Installing Agent Toolkit shared guidelines")
            runStep(projectDir, command(script, guidelinesArgs()), "guidelines")
            onStatus("Configuring project MCP servers")
            installProjectMcp(projectDir, script)
        } finally {
            Files.deleteIfExists(script)
        }
    }

    private fun installProjectMcp(projectDir: Path, script: Path) {
        val root = projectDir.toRealPath()
        // Toolkit writes through symlinks, so reject redirected project config paths.
        for (relative in listOf(
            ".mcp.json", ".codex", ".codex/config.toml", "opencode.json",
            ".junie", ".junie/mcp", ".junie/mcp/mcp.json",
        )) {
            val path = root.resolve(relative)
            if (Files.isSymbolicLink(path) || (Files.exists(path, NOFOLLOW_LINKS) && path.toRealPath() != path)) {
                throw IOException("Cannot configure project MCP servers through a linked path: $relative.")
            }
        }
        val profile = Files.createTempDirectory("jmix-toolkit-mcp-profile")
        try {
            runStep(root, command(script, mcpArgs()), "MCP", profile)
        } finally {
            Files.deleteIfExists(profile)
        }
    }

    private fun isolateClaudeDesktop(environment: MutableMap<String, String>, profile: Path) {
        // Toolkit checks for Claude Desktop in the user profile even with local scope.
        val isolatedProfile = profile.toString()
        when {
            isWindows() -> {
                environment["APPDATA"] = isolatedProfile
                environment["LOCALAPPDATA"] = isolatedProfile
            }
            System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> {
                // Keep Toolkit's read-only Codex transport conflict check on the real profile.
                val userHome = environment["HOME"] ?: System.getProperty("user.home")
                environment.putIfAbsent("CODEX_HOME", Path.of(userHome, ".codex").toString())
                environment["HOME"] = isolatedProfile
            }
            else -> environment["XDG_CONFIG_HOME"] = isolatedProfile
        }
    }

    private fun runStep(projectDir: Path, command: List<String>, label: String, mcpProfile: Path? = null) {
        // A file sink instead of a pipe: no deadlock however much the step logs.
        val log = Files.createTempFile("jmix-agent-toolkit", ".log")
        try {
            val builder = ProcessBuilder(command)
                .directory(projectDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
            // A pwsh-flavored PSModulePath inherited from the parent (pwsh
            // terminals, GitHub Actions) breaks Windows PowerShell 5.1 module
            // autoloading (Get-FileHash etc.); let the shell rebuild its own.
            builder.environment().remove("PSModulePath")
            if (mcpProfile != null) isolateClaudeDesktop(builder.environment(), mcpProfile)
            val process = builder.start()
            if (!process.waitFor(STEP_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                // Wait out the kill so the log file is closed before cleanup;
                // otherwise Windows masks the timeout with a file-lock error.
                process.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
                throw IOException("Timed out installing the Agent Toolkit $label.")
            }
            if (process.exitValue() != 0) {
                throw IOException("Agent Toolkit $label step failed: ${logTail(log)}")
            }
        } finally {
            Files.deleteIfExists(log)
        }
    }

    private fun logTail(log: Path): String = try {
        Files.readAllLines(log).takeLast(12).joinToString(" ") { it.trim() }
            .trim().take(700).ifEmpty { "no output" }
    } catch (e: IOException) {
        "no output"
    }

    private fun download(url: String, target: Path) {
        val response = Http.sendBytes(
            http,
            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
            MAX_INSTALLER_BYTES,
        )
        if (response.statusCode() != 200) {
            throw IOException("Failed to download the Agent Toolkit installer ($url): HTTP ${response.statusCode()}")
        }
        Files.write(target, response.body())
    }

    private fun isWindows(os: String = System.getProperty("os.name")) = os.lowercase().contains("win")

    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    private const val STEP_TIMEOUT_MINUTES = 10L
    private const val MAX_INSTALLER_BYTES = 2 * 1024 * 1024
    private const val MCP_SERVERS = "jetbrains,jmix-docs,playwright"
    private const val MCP_AGENTS = "claude,codex,opencode,junie"
    private const val RAW_CONTENT_BASE =
        "https://raw.githubusercontent.com/jmix-framework/jmix-agent-toolkit"
}
