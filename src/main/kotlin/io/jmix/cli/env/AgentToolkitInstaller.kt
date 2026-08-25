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

/**
 * Runs the Jmix Agent Toolkit installer
 * (https://github.com/jmix-framework/jmix-agent-toolkit) in a generated
 * project. The toolkit ships its own interactive wizard per Jmix major
 * version — skills, guidelines, MCP servers — so the CLI downloads that
 * script, launches it in the project directory, and lets it drive the
 * terminal. The script is executed from a file with a fixed argument list;
 * no value ever passes through a shell command line.
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

    fun command(scriptFile: Path, os: String = System.getProperty("os.name")): List<String> =
        if (isWindows(os)) {
            listOf("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", scriptFile.toString())
        } else {
            listOf("bash", scriptFile.toString())
        }

    /** Runs the toolkit's interactive wizard in [projectDir] on this terminal. */
    fun run(projectDir: Path, jmixVersion: String): Int {
        val suffix = if (isWindows()) ".ps1" else ".sh"
        val script = Files.createTempFile("jmix-agent-toolkit", suffix)
        try {
            download(installerUrl(jmixVersion), script)
            return ProcessBuilder(command(script))
                .directory(projectDir.toFile())
                .inheritIO()
                .start()
                .waitFor()
        } finally {
            Files.deleteIfExists(script)
        }
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

    private const val RAW_CONTENT_BASE =
        "https://raw.githubusercontent.com/jmix-framework/jmix-agent-toolkit"
}
