package io.jmix.cli.env

import io.jmix.cli.WindowsConsole
import io.jmix.cli.util.Http
import io.jmix.cli.util.hostOf
import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

enum class ToolkitStep { SKILLS, GUIDELINES, MCP }

/** [action] is the toolkit's word, such as created, updated or backed-up; [path] is relative to the project with `/` separators when inside it. */
data class ToolkitChange(val action: String, val path: String)

/** [notes] are the toolkit's own guidance lines. */
data class ToolkitResult(val changes: List<ToolkitChange>, val notes: List<String>)

/**
 * Installs the Jmix Agent Toolkit
 * (https://github.com/jmix-framework/jmix-agent-toolkit) into a
 * project: shared guidelines, local skills, and project MCP configuration.
 * The downloaded script runs from a file with a complete argument list, never through a shell string.
 */
object AgentToolkitInstaller {

    /** Toolkit branch for a Jmix version; majors are the only accepted shape. */
    fun branch(jmixVersion: String): String {
        // ASCII digits only: toIntOrNull alone also takes a sign and the digits of other scripts.
        val major = jmixVersion.substringBefore('.').takeIf { it.all { digit -> digit in '0'..'9' } }?.toIntOrNull()
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

    internal fun mcpArgs(servers: List<String>, agents: List<String>, os: String = System.getProperty("os.name")): List<String> =
        if (isWindows(os)) {
            listOf("mcp", "-Servers", servers.joinToString(","), "-Agents", agents.joinToString(","), "-Scope", "local")
        } else {
            listOf("mcp", "--servers", servers.joinToString(","), "--agents", agents.joinToString(","), "--scope", "local")
        }

    /** "the toolkit has no v4 branch for Jmix 4 yet" for [jmixVersion] 4.x. */
    internal fun missingBranchMessage(jmixVersion: String): String {
        val branch = branch(jmixVersion)
        return "the toolkit has no $branch branch for Jmix ${branch.removePrefix("v")} yet"
    }

    fun command(scriptFile: Path, args: List<String>, os: String = System.getProperty("os.name")): List<String> =
        if (isWindows(os)) {
            // The absolute path: Windows looks for a bare name in the current directory, the project, first.
            val powershell = "${System.getenv("SystemRoot") ?: "C:\\Windows"}\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"
            listOf(powershell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", scriptFile.toString()) + args
        } else {
            listOf(bash, scriptFile.toString()) + args
        }

    /**
     * PATH without empty or relative entries. Steps run in the project, so such entries would let the
     * project's own files stand in for the programs the installer calls.
     */
    internal fun trustedPath(path: String?): String =
        path.orEmpty().split(File.pathSeparator)
            // bash expands a leading ~ in a PATH entry; the real home keeps such an entry working.
            .map { entry -> if (entry == "~" || entry.startsWith("~/")) System.getProperty("user.home") + entry.drop(1) else entry }
            .filter { entry -> entry.isNotEmpty() && runCatching { Path.of(entry).isAbsolute }.getOrDefault(false) }
            .joinToString(File.pathSeparator)

    /**
     * Applies [trustedPath] to the PATH variable in any letter case: Windows names it Path, and the map is
     * case-sensitive. Outside Windows a missing or emptied PATH becomes [FALLBACK_PATH]: bash looks a command up
     * in the current directory, the project, when PATH is empty, and its built-in default ends with `.`.
     */
    internal fun trustPathVariable(environment: MutableMap<String, String>, os: String = System.getProperty("os.name")) {
        val keys = environment.keys.filter { it.equals("PATH", ignoreCase = true) }
        keys.forEach { key -> environment[key] = trustedPath(environment[key]) }
        if (!isWindows(os) && keys.all { environment[it].isNullOrEmpty() }) {
            keys.forEach(environment::remove)
            environment["PATH"] = FALLBACK_PATH
        }
    }

    /**
     * The environment of a step, changed in place: change markers on, a trusted PATH, no inherited
     * PSModulePath, and for the MCP step ([mcpProfile]) Claude Desktop's user profile moved to that folder.
     */
    internal fun prepareEnvironment(environment: MutableMap<String, String>, mcpProfile: Path?, os: String = System.getProperty("os.name")) {
        // A pwsh-flavored PSModulePath inherited from the parent (pwsh
        // terminals, GitHub Actions) breaks Windows PowerShell 5.1 module
        // autoloading (Get-FileHash etc.); let the shell rebuild its own.
        environment.remove("PSModulePath")
        environment["JMIX_EMIT_CHANGE_MARKERS"] = "1"
        trustPathVariable(environment, os)
        if (mcpProfile != null) isolateClaudeDesktop(environment, mcpProfile, os)
    }

    /** bash by its absolute path: the JVM looks a bare name up in PATH entries that resolve inside the project. */
    private val bash: String by lazy {
        trustedPath(System.getenv("PATH")).split(File.pathSeparator).filter { it.isNotEmpty() }
            .map { Path.of(it, "bash") }
            .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
            ?.toString() ?: "/bin/bash"
    }

    /**
     * Runs the selected [steps] of the Toolkit in [projectDir], in the order skills, guidelines, MCP.
     * [servers] and [agents] apply to the MCP step; null means every choice the toolkit metadata offers.
     * They are passed to the installer unchecked, so they must be ids that metadata offers ([AgentToolkitMetadata.mcpOptions]).
     * [onStatus] names each phase before it starts, for a progress indicator.
     * The link guard ([rejectLinkedPaths]) runs for all selected steps before anything is downloaded or written.
     */
    fun install(
        projectDir: Path,
        jmixVersion: String,
        steps: Set<ToolkitStep> = ToolkitStep.entries.toSet(),
        servers: List<String>? = null,
        agents: List<String>? = null,
        onStatus: (String) -> Unit = {},
    ): ToolkitResult {
        val branch = branch(jmixVersion)
        val root = projectDir.toRealPath()
        rejectLinkedPaths(root, steps)
        val suffix = if (isWindows()) ".ps1" else ".sh"
        val script = Files.createTempFile("jmix-agent-toolkit", suffix)
        try {
            val url = installerUrl(jmixVersion)
            onStatus("Downloading the Agent Toolkit installer from ${hostOf(url)}")
            download(url, script, jmixVersion)
            // Everything a step needs is loaded before the first one runs, so a run never stops halfway for it.
            val mcpStepArgs = when {
                ToolkitStep.MCP !in steps -> null
                servers != null && agents != null -> mcpArgs(servers, agents)
                else -> {
                    onStatus("Loading the Agent Toolkit metadata from ${hostOf(url)}")
                    val options = AgentToolkitMetadata.mcpOptions(branch)
                    mcpArgs(servers ?: options.servers.map { it.value }, agents ?: options.agents.map { it.value })
                }
            }
            val skills = if (ToolkitStep.SKILLS in steps) {
                onStatus("Installing Agent Toolkit project-local skills")
                runStep(root, command(script, skillsArgs()), "skills")
            } else {
                emptyList()
            }
            val guidelines = if (ToolkitStep.GUIDELINES in steps) {
                onStatus("Installing Agent Toolkit shared guidelines")
                runStep(root, command(script, guidelinesArgs()), "guidelines")
            } else {
                emptyList()
            }
            val mcp = if (mcpStepArgs != null) {
                onStatus("Configuring project MCP servers")
                installProjectMcp(root, script, mcpStepArgs)
            } else {
                emptyList()
            }
            return ToolkitResult(
                parseChanges(skills + guidelines + mcp, root),
                toolkitNotes(skills + guidelines, mcp = false) + toolkitNotes(mcp, mcp = true),
            )
        } finally {
            Files.deleteIfExists(script)
        }
    }

    /**
     * The lines of a step's log; empty when it cannot be read, so that never fails a step that succeeded. Valid
     * UTF-8 is read as UTF-8. Anything else is read in [fallback]: on Windows the OEM code page, as Windows
     * PowerShell 5.1 writes in the code page of the new console Java starts it in; elsewhere bad bytes are replaced.
     * UTF-8 goes first so a console set to UTF-8 (a `CodePage` of 65001 in the registry) still reads right; the
     * price is rare OEM text that is also valid UTF-8, such as `сок` under code page 866, read as UTF-8.
     */
    internal fun readLog(log: Path, fallback: Charset? = WindowsConsole.oemCharset): List<String> = try {
        val bytes = Files.readAllBytes(log)
        val text = try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, fallback ?: Charsets.UTF_8)
        }
        text.removePrefix("﻿").reader().readLines()
    } catch (e: IOException) {
        emptyList()
    }

    /** The paths the toolkit reports as written, with the first action of each. */
    internal fun parseChanges(output: List<String>, projectDir: Path): List<ToolkitChange> {
        val root = projectDir.toRealPath()
        return output.filter { it.startsWith(CHANGE_MARKER) }.mapNotNull { line ->
            val fields = line.split('\t').drop(1).associate { it.substringBefore('=') to it.substringAfter('=', "") }
            val action = fields["action"]?.takeIf { CHANGE_ACTION.matches(it) } ?: return@mapNotNull null
            val path = fields["path"]?.filterNot(Char::isISOControl)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            ToolkitChange(action, projectRelative(path, root))
        }.distinctBy { it.path }
    }

    private fun projectRelative(path: String, root: Path): String {
        val normalized = try {
            Path.of(path).normalize()
        } catch (e: InvalidPathException) {
            return path
        }
        return if (normalized != root && normalized.startsWith(root)) root.relativize(normalized).joinToString("/") else path
    }

    /**
     * The toolkit's guidance in [output]: lines marked `Note:`, and in the MCP step ([mcp]) every line
     * that starts at column 0 and is neither an `[Server MCP] Agent` heading nor a change marker.
     */
    internal fun toolkitNotes(output: List<String>, mcp: Boolean): List<String> =
        output.mapNotNull { line ->
            val text = line.filterNot(Char::isISOControl).trim()
            val isGuidance = mcp && line.firstOrNull()?.isWhitespace() == false &&
                !text.startsWith("[") && !text.startsWith(CHANGE_MARKER)
            text.takeIf { it.isNotEmpty() && (it.startsWith(NOTE_PREFIX) || isGuidance) }
        }.distinct()

    /**
     * The toolkit writes through links, and [root] can be a project this CLI did not create. Fails when a path
     * one of [steps] writes is a link out of the project, or, for an MCP config, a link at all. The toolkit itself
     * links inside the project: each skill into the Claude skills folder, and it supports `CLAUDE.md -> AGENTS.md`,
     * so a link, dangling or not, that stays in the project passes.
     */
    internal fun rejectLinkedPaths(root: Path, steps: Set<ToolkitStep>) {
        val project = root.toRealPath()
        if (ToolkitStep.SKILLS in steps) {
            // A local skills run also tidies the `jmix*` entries of every `.<any>/skills` (Junie's old links,
            // legacy backups), following links to it. Listed names stay paths: a name that is no valid text
            // in the JVM's encoding still resolves to the same entry.
            val agentSkills = topLevelEntries(project).filter { it.toString().startsWith(".") }
                .map { it.resolve("skills") }.filter { hasToolkitEntries(project.resolve(it)) }
            rejectLinksOutside(project, (SKILLS_PATHS.map(Path::of) + agentSkills).distinct(), "skills")
        }
        if (ToolkitStep.GUIDELINES in steps) {
            // A backup goes to the first free `<file>.bak`, `.bak1`, ..., and a dangling link there counts as free.
            val backups = topLevelEntries(project)
                .filter { entry -> GUIDELINES_PATHS.any { entry.toString().startsWith("$it.bak", ignoreCase = true) } }
            rejectLinksOutside(project, GUIDELINES_PATHS.map(Path::of) + backups, "guidelines")
        }
        if (ToolkitStep.MCP in steps) {
            for (relative in MCP_PATHS) {
                val path = project.resolve(relative)
                if (Files.isSymbolicLink(path) || (Files.exists(path, NOFOLLOW_LINKS) && !isPlainEntry(path))) {
                    throw IOException("Cannot configure project MCP servers through a linked path: $relative.")
                }
            }
        }
    }

    /**
     * Whether the existing [path] is itself where its real path is. A Windows junction is no symbolic link to
     * Java, but its real path is elsewhere. Not `toRealPath() == path`: on a case-insensitive disk the real name
     * can differ in letter case alone.
     */
    private fun isPlainEntry(path: Path): Boolean {
        val real = path.toRealPath()
        return real.parent == path.parent.toRealPath() && real.fileName.toString().equals(path.fileName.toString(), ignoreCase = true)
    }

    /** The entry names in [project], sorted; a folder that fails while it is listed fails like one that cannot be opened. */
    private fun topLevelEntries(project: Path): List<Path> = try {
        Files.list(project).use { entries -> entries.map { it.fileName }.sorted().toList() }
    } catch (e: UncheckedIOException) {
        throw e.cause ?: e
    }

    /**
     * Whether the folder [dir] holds an entry the toolkit tidies in `.<any>/skills`: `jmix`, `jmix-*` or `jmix.*`,
     * in any letter case to be safe. A folder that cannot be listed counts, so the guard still checks it.
     */
    private fun hasToolkitEntries(dir: Path): Boolean {
        if (!Files.isDirectory(dir)) return false
        fun isToolkitEntry(name: String) =
            name.equals("jmix", ignoreCase = true) || name.startsWith("jmix-", ignoreCase = true) ||
                name.startsWith("jmix.", ignoreCase = true)
        return try {
            Files.list(dir).use { entries -> entries.anyMatch { isToolkitEntry(it.fileName.toString()) } }
        } catch (e: IOException) {
            true
        } catch (e: UncheckedIOException) {
            true
        }
    }

    private fun rejectLinksOutside(project: Path, paths: List<Path>, label: String) {
        for (relative in paths) {
            val path = project.resolve(relative)
            if (!Files.exists(path, NOFOLLOW_LINKS)) continue
            val inside = try {
                path.toRealPath().startsWith(project)
            } catch (e: IOException) {
                missingTargetInside(path, project)
            }
            if (!inside) {
                val shown = relative.joinToString("/")
                throw IOException("Cannot install Agent Toolkit $label through a path linked outside the project: $shown.")
            }
        }
    }

    /**
     * Whether the missing target of the dangling link [link] lies in [project], where writing through the link
     * would create it. The part of the target on disk is taken by its real path and must not be a link itself.
     * Only leading `..` elements are allowed: after a name, one could climb out of a linked folder.
     */
    private fun missingTargetInside(link: Path, project: Path): Boolean {
        val target = try {
            Files.readSymbolicLink(link)
        } catch (e: IOException) {
            return false
        } catch (e: UnsupportedOperationException) {
            return false
        }
        if (target.dropWhile { it.toString() == ".." }.any { it.toString() == ".." }) return false
        return try {
            val wanted = link.parent.toRealPath().resolve(target).normalize()
            var onDisk = wanted
            while (!Files.exists(onDisk, NOFOLLOW_LINKS)) onDisk = onDisk.parent ?: return false
            // The target itself on disk is another dangling link: refused rather than followed.
            onDisk != wanted && onDisk.toRealPath().resolve(onDisk.relativize(wanted)).startsWith(project)
        } catch (e: IOException) {
            false
        }
    }

    private fun installProjectMcp(root: Path, script: Path, args: List<String>): List<String> {
        val profile = Files.createTempDirectory("jmix-toolkit-mcp-profile")
        try {
            return runStep(root, command(script, args), "MCP", profile)
        } finally {
            Files.deleteIfExists(profile)
        }
    }

    private fun isolateClaudeDesktop(environment: MutableMap<String, String>, profile: Path, os: String) {
        // Toolkit checks for Claude Desktop in the user profile even with local scope.
        val isolatedProfile = profile.toString()
        when {
            isWindows(os) -> {
                environment["APPDATA"] = isolatedProfile
                environment["LOCALAPPDATA"] = isolatedProfile
            }
            os.startsWith("Mac", ignoreCase = true) -> {
                // Keep Toolkit's read-only Codex transport conflict check on the real profile.
                val userHome = environment["HOME"] ?: System.getProperty("user.home")
                environment.putIfAbsent("CODEX_HOME", Path.of(userHome, ".codex").toString())
                environment["HOME"] = isolatedProfile
            }
            else -> environment["XDG_CONFIG_HOME"] = isolatedProfile
        }
    }

    internal fun runStep(projectDir: Path, command: List<String>, label: String, mcpProfile: Path? = null): List<String> {
        // A file sink instead of a pipe: no deadlock however much the step logs.
        val log = Files.createTempFile("jmix-agent-toolkit", ".log")
        try {
            // No stream is inherited: Java then starts Windows PowerShell in a console of its own (CREATE_NO_WINDOW),
            // whose code page readLog decodes, never in the user's console.
            val builder = ProcessBuilder(command)
                .directory(projectDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
            prepareEnvironment(builder.environment(), mcpProfile)
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
            return readLog(log)
        } finally {
            Files.deleteIfExists(log)
        }
    }

    /**
     * What a failed step said, for the error line: its `error:` lines, as both installers mark errors and the MCP
     * step goes on after one, else the end of its output. External text, so control characters go.
     */
    internal fun logTail(log: Path): String {
        val lines = readLog(log).map { it.filterNot(Char::isISOControl).trim() }
            .filter { it.isNotEmpty() && !it.startsWith(CHANGE_MARKER) }
        return lines.filter { it.startsWith(ERROR_PREFIX) }.ifEmpty { lines.takeLast(TAIL_LINES) }
            .joinToString(" ").take(700).ifEmpty { "no output" }
    }

    private fun download(url: String, target: Path, jmixVersion: String) {
        val response = Http.sendBytes(
            http,
            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
            MAX_INSTALLER_BYTES,
        )
        if (response.statusCode() == 404) throw IOException(missingBranchMessage(jmixVersion))
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

    // The project paths each step writes: safety guards that must work without the network, and so the one
    // place where the toolkit's file names are repeated.
    private val SKILLS_PATHS = listOf(
        ".agents", ".agents/skills", ".agents/.jmix", ".agents/.jmix/backups", ".claude", ".claude/skills",
    )
    private val GUIDELINES_PATHS = listOf("AGENTS.md", "CLAUDE.md")
    internal val MCP_PATHS = listOf(
        ".mcp.json", ".codex", ".codex/config.toml", "opencode.json",
        ".junie", ".junie/mcp", ".junie/mcp/mcp.json",
    )
    private const val FALLBACK_PATH = "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
    private const val STEP_TIMEOUT_MINUTES = 10L
    private const val TAIL_LINES = 12
    private const val MAX_INSTALLER_BYTES = 2 * 1024 * 1024
    private const val CHANGE_MARKER = "@@JMIX_CHANGE@@"
    private const val NOTE_PREFIX = "Note:"
    private const val ERROR_PREFIX = "error:"
    private val CHANGE_ACTION = Regex("[A-Za-z0-9_-]+")
    internal const val RAW_CONTENT_BASE =
        "https://raw.githubusercontent.com/jmix-framework/jmix-agent-toolkit"
}
