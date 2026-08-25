package io.jmix.cli.env

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Installs the Jmix Agent Toolkit
 * (https://github.com/jmix-framework/jmix-agent-toolkit) into a generated
 * project: guidelines files and project-local skills for every supported
 * agent, via the toolkit's non-interactive subcommands. Only the project
 * directory is written to — global steps (MCP servers, Playwright) are the
 * toolkit wizard's business, not the CLI's. The downloaded script runs from a
 * file with a fixed argument list; no value ever passes through a shell
 * command line.
 */
object AgentToolkitInstaller {

    val ALL_AGENTS = listOf("claude", "codex", "opencode", "junie")

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
            listOf("skills", "-Agents", AGENTS_CSV, "-Scope", "local")
        } else {
            listOf("skills", "--agents", AGENTS_CSV, "--scope", "local")
        }

    fun guidelinesArgs(os: String = System.getProperty("os.name")): List<String> =
        if (isWindows(os)) {
            listOf("agents-md", "-Agents", AGENTS_CSV)
        } else {
            listOf("agents-md", "--agents", AGENTS_CSV)
        }

    fun command(scriptFile: Path, args: List<String>, os: String = System.getProperty("os.name")): List<String> =
        if (isWindows(os)) {
            listOf("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", scriptFile.toString()) + args
        } else {
            listOf("bash", scriptFile.toString()) + args
        }

    /** Installs guidelines files and project-local skills into [projectDir]. */
    fun installGuidelinesAndSkills(projectDir: Path, jmixVersion: String) {
        val suffix = if (isWindows()) ".ps1" else ".sh"
        val script = Files.createTempFile("jmix-agent-toolkit", suffix)
        try {
            download(installerUrl(jmixVersion), script)
            runStep(projectDir, command(script, skillsArgs()), "skills")
            runStep(projectDir, command(script, guidelinesArgs()), "guidelines")
        } finally {
            Files.deleteIfExists(script)
        }
    }

    private fun runStep(projectDir: Path, command: List<String>, label: String) {
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
        val response = http.send(
            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        if (response.statusCode() != 200) {
            throw IOException("Failed to download the Agent Toolkit installer ($url): HTTP ${response.statusCode()}")
        }
        response.body().use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun isWindows(os: String = System.getProperty("os.name")) = os.lowercase().contains("win")

    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    private val AGENTS_CSV = ALL_AGENTS.joinToString(",")
    private const val STEP_TIMEOUT_MINUTES = 10L
    private const val RAW_CONTENT_BASE =
        "https://raw.githubusercontent.com/jmix-framework/jmix-agent-toolkit"
}
