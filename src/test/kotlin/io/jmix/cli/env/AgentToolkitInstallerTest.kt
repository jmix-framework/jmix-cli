package io.jmix.cli.env

import io.jmix.cli.WindowsConsole
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AgentToolkitInstallerTest {

    @Test
    fun `installer branch follows the jmix major version`() {
        assertEquals("v3", AgentToolkitInstaller.branch("3.0.1"))
        assertEquals("v2", AgentToolkitInstaller.branch("2.8.3"))
        assertEquals(
            "https://raw.githubusercontent.com/jmix-framework/jmix-agent-toolkit/v3/install.sh",
            AgentToolkitInstaller.installerUrl("3.0.1", os = "Linux"),
        )
        assertEquals(
            "https://raw.githubusercontent.com/jmix-framework/jmix-agent-toolkit/v3/install.ps1",
            AgentToolkitInstaller.installerUrl("3.0.1", os = "Windows 11"),
        )
    }

    @Test
    fun `rejects versions that do not start with a numeric major`() {
        assertThrows(IOException::class.java) { AgentToolkitInstaller.branch("evil'; touch pwned #") }
        assertThrows(IOException::class.java) { AgentToolkitInstaller.branch("latest") }
        for (version in listOf("-1.0", "+3.x", "\u0663.0", "", ".5", "99999999999.0")) {
            val error = assertThrows(IOException::class.java, { AgentToolkitInstaller.branch(version) }, version)
            assertEquals("Unexpected Jmix version format: $version", error.message)
        }
        assertEquals("v3", AgentToolkitInstaller.branch("3"))
    }

    @Test
    fun `installs local skills and guidelines with default Claude support`() {
        assertEquals(
            listOf("skills", "--scope", "local"),
            AgentToolkitInstaller.skillsArgs(os = "Linux"),
        )
        assertEquals(
            listOf("skills", "-Scope", "local"),
            AgentToolkitInstaller.skillsArgs(os = "Windows 11"),
        )
        assertEquals(
            listOf("agents-md"),
            AgentToolkitInstaller.guidelinesArgs(),
        )
    }

    @Test
    fun `steps get a PATH without empty or relative entries`(@TempDir dir: Path) {
        val absolute = dir.toAbsolutePath().toString()
        val path = listOf(absolute, "", ".", "bin", absolute).joinToString(File.pathSeparator)
        assertEquals(listOf(absolute, absolute).joinToString(File.pathSeparator), AgentToolkitInstaller.trustedPath(path))
        assertEquals("", AgentToolkitInstaller.trustedPath(null))
        val home = System.getProperty("user.home")
        assertEquals("$home/bin", AgentToolkitInstaller.trustedPath("~/bin"))
        // Windows names the variable Path, and the environment map of a process is case-sensitive.
        val environment = mutableMapOf("Path" to listOf(absolute, ".").joinToString(File.pathSeparator), "OTHER" to ".")
        AgentToolkitInstaller.trustPathVariable(environment)
        assertEquals(mapOf("Path" to absolute, "OTHER" to "."), environment)
    }

    @Test
    fun `runs the downloaded script as a file argument, never through a shell string`() {
        val script = Path.of("tmp", "toolkit.sh")
        val powershell = "${System.getenv("SystemRoot") ?: "C:\\Windows"}\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"
        val unix = AgentToolkitInstaller.command(script, AgentToolkitInstaller.guidelinesArgs(), os = "Linux")
        // Never the bare name either: the JVM looks it up in PATH entries that can resolve inside the project.
        assertTrue(unix.first() != "bash" && unix.first().endsWith("bash"), unix.first())
        assertEquals(listOf(script.toString(), "agents-md"), unix.drop(1))
        assertEquals(
            listOf(powershell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString()) +
                AgentToolkitInstaller.skillsArgs(os = "Windows 11"),
            AgentToolkitInstaller.command(script, AgentToolkitInstaller.skillsArgs(os = "Windows 11"), os = "Windows 11"),
        )
        // Never the bare name: Windows looks for that in the current directory, the project, first.
        assertTrue(Regex("""[A-Za-z]:\\.*\\System32\\WindowsPowerShell\\v1\.0\\powershell\.exe""").matches(powershell), powershell)
    }

    @Test
    fun `mcp arguments carry the selected servers and agents`() {
        assertEquals(
            listOf("mcp", "--servers", "jmix-docs", "--agents", "claude,codex", "--scope", "local"),
            AgentToolkitInstaller.mcpArgs(listOf("jmix-docs"), listOf("claude", "codex"), os = "Linux"),
        )
        assertEquals(
            listOf("mcp", "-Servers", "jmix-docs", "-Agents", "claude,codex", "-Scope", "local"),
            AgentToolkitInstaller.mcpArgs(listOf("jmix-docs"), listOf("claude", "codex"), os = "Windows 11"),
        )
        assertEquals("the toolkit has no v4 branch for Jmix 4 yet", AgentToolkitInstaller.missingBranchMessage("4.0.1"))
    }

    @Test
    fun `change markers become project-relative changes and the first action per path wins`(@TempDir dir: Path) {
        val root = dir.toRealPath()
        fun marker(action: String, path: Path) = "@@JMIX_CHANGE@@\taction=$action\ttype=file\tpath=$path"
        val output = listOf(
            "Installing skills into store",
            marker("created", root.resolve(".mcp.json")),
            marker("updated", root.resolve(".mcp.json")),
            marker("backed-up", root.resolve("AGENTS.md.bak")),
            marker("updated", root.resolve(".codex").resolve("config.toml")),
            marker("moved-aside", root.resolve("a=b.txt")),
            marker("moved_aside", root.resolve("Moved2.txt")),
            marker("updated", root.resolveSibling("outside.json")),
            marker("DROP TABLE", root.resolve("ignored.txt")),
            "@@JMIX_CHANGE@@\taction=created",
        )
        assertEquals(
            listOf(
                ToolkitChange("created", ".mcp.json"),
                ToolkitChange("backed-up", "AGENTS.md.bak"),
                ToolkitChange("updated", ".codex/config.toml"),
                ToolkitChange("moved-aside", "a=b.txt"),
                ToolkitChange("moved_aside", "Moved2.txt"),
                ToolkitChange("updated", root.resolveSibling("outside.json").toString()),
            ),
            AgentToolkitInstaller.parseChanges(output, dir),
        )
    }

    @Test
    fun `a change path is cleaned, kept outside when it leaves the project, and dropped when empty`(@TempDir dir: Path) {
        val root = dir.toRealPath()
        val leaving = "$root/sub/../../outside"
        val output = listOf(
            "@@JMIX_CHANGE@@\taction=created\ttype=file\tpath=$root/a\u0007b\u001B.txt",
            "@@JMIX_CHANGE@@\taction=updated\ttype=file\tpath=$leaving",
            "@@JMIX_CHANGE@@\taction=created\ttype=file\tpath=",
            "@@JMIX_CHANGE@@\taction=created\ttype=file\tpath=\u0007",
        )
        assertEquals(
            listOf(ToolkitChange("created", "ab.txt"), ToolkitChange("updated", leaving)),
            AgentToolkitInstaller.parseChanges(output, dir),
        )
    }

    @Test
    fun `notes are the toolkit's own guidance lines`() {
        val reach = "IntelliJ IDEA MCP server is not reachable on port 64342; assuming IntelliJ IDEA 2026.1+."
        val npx = "Note: Playwright starts through npx, which is not on PATH here. Install Node.js 20+ (https://nodejs.org) before using it."
        val approve = "Claude Code asks to approve the servers in .mcp.json on its next start in this project."
        val mcp = listOf(
            "", reach, "",
            "[JetBrains MCP] Claude CLI", "  Created: /p/.mcp.json",
            "@@JMIX_CHANGE@@\taction=created\ttype=file\tpath=/p/.mcp.json",
            "", npx, approve, approve, "Ring\u0007 the bell.",
            "\u001B@@JMIX_CHANGE@@\taction=created\ttype=file\tpath=/p/opencode.json",
        )
        assertEquals(listOf(reach, npx, approve, "Ring the bell."), AgentToolkitInstaller.toolkitNotes(mcp, mcp = true))

        val override = "Note: Codex reads /p/AGENTS.override.md instead of AGENTS.md there, so it does not see the Jmix block. Copy the block into it."
        val guidelines = listOf("Project guidelines target directory: /p", "  Installed: /p/AGENTS.md", "  $override")
        assertEquals(listOf(override), AgentToolkitInstaller.toolkitNotes(guidelines, mcp = false))
    }

    private val links get() = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

    private fun rejected(root: Path, vararg steps: ToolkitStep): String? =
        assertThrows(IOException::class.java) { AgentToolkitInstaller.rejectLinkedPaths(root, steps.toSet()) }.message

    @Test
    fun `a guidelines or skills path linked outside the project is rejected, for the selected steps only`(
        @TempDir dir: Path, @TempDir outside: Path,
    ) {
        assumeTrue(links)
        Files.createSymbolicLink(dir.resolve("CLAUDE.md"), Files.writeString(outside.resolve("credentials"), "secret"))
        assertEquals(
            "Cannot install Agent Toolkit guidelines through a path linked outside the project: CLAUDE.md.",
            rejected(dir, ToolkitStep.GUIDELINES),
        )
        AgentToolkitInstaller.rejectLinkedPaths(dir, setOf(ToolkitStep.SKILLS, ToolkitStep.MCP))

        Files.createSymbolicLink(Files.createDirectories(dir.resolve(".agents")).resolve("skills"), outside)
        assertEquals(
            "Cannot install Agent Toolkit skills through a path linked outside the project: .agents/skills.",
            rejected(dir, ToolkitStep.SKILLS),
        )
        assertEquals(
            "Cannot install Agent Toolkit skills through a path linked outside the project: .agents/skills.",
            rejected(dir, *ToolkitStep.entries.toTypedArray()),
        )
        AgentToolkitInstaller.rejectLinkedPaths(dir, setOf(ToolkitStep.MCP))
        assertEquals("secret", Files.readString(outside.resolve("credentials")))
    }

    @Test
    fun `a dangling link is judged by the target it would create`(@TempDir dir: Path, @TempDir outside: Path) {
        assumeTrue(links)
        // The toolkit's own layouts: CLAUDE.md -> AGENTS.md before AGENTS.md exists, Claude's skills after .agents is gone.
        Files.createSymbolicLink(dir.resolve("CLAUDE.md"), Path.of("AGENTS.md"))
        Files.createSymbolicLink(Files.createDirectories(dir.resolve(".claude")).resolve("skills"), Path.of("../.agents/skills"))
        AgentToolkitInstaller.rejectLinkedPaths(dir, setOf(ToolkitStep.SKILLS, ToolkitStep.GUIDELINES))
        Files.delete(dir.resolve("CLAUDE.md"))
        Files.createSymbolicLink(dir.resolve("AGENTS.md"), dir.resolve("missing.md"))
        AgentToolkitInstaller.rejectLinkedPaths(dir, setOf(ToolkitStep.GUIDELINES))

        Files.delete(dir.resolve("AGENTS.md"))
        Files.createSymbolicLink(dir.resolve("AGENTS.md"), outside.resolve("missing.md"))
        val agents = "Cannot install Agent Toolkit guidelines through a path linked outside the project: AGENTS.md."
        assertEquals(agents, rejected(dir, ToolkitStep.GUIDELINES))
        // A name followed by `..` could climb out of a linked folder, and a dangling link to one is not followed.
        Files.createSymbolicLink(dir.resolve("linked"), outside)
        for (target in listOf("linked/../missing.md", "next.md")) {
            Files.delete(dir.resolve("AGENTS.md"))
            Files.createSymbolicLink(dir.resolve("AGENTS.md"), Path.of(target))
            Files.deleteIfExists(dir.resolve("next.md"))
            if (target == "next.md") Files.createSymbolicLink(dir.resolve("next.md"), Path.of("missing.md"))
            assertEquals(agents, rejected(dir, ToolkitStep.GUIDELINES), target)
        }
        Files.delete(dir.resolve(".claude/skills"))
        Files.createSymbolicLink(dir.resolve(".claude/skills"), Path.of("../linked/skills"))
        assertEquals(
            "Cannot install Agent Toolkit skills through a path linked outside the project: .claude/skills.",
            rejected(dir, ToolkitStep.SKILLS),
        )
    }

    @Test
    fun `the skills guard covers every hidden folder's skills that the toolkit tidies`(@TempDir dir: Path, @TempDir outside: Path) {
        assumeTrue(links)
        // The toolkit tidies only `jmix*` entries there, so a linked folder without them is left alone.
        Files.createDirectories(outside.resolve("junie/skills"))
        Files.createDirectories(outside.resolve("team/skills/review"))
        Files.createDirectories(outside.resolve("m2"))
        Files.createSymbolicLink(dir.resolve(".m2"), outside.resolve("m2"))
        Files.createSymbolicLink(dir.resolve(".team"), outside.resolve("team"))
        Files.createDirectories(dir.resolve(".cursor/skills/jmix-old.bak1"))
        Files.createSymbolicLink(dir.resolve(".junie"), outside.resolve("junie"))
        AgentToolkitInstaller.rejectLinkedPaths(dir, setOf(ToolkitStep.SKILLS))
        Files.createDirectories(outside.resolve("junie/skills/jmix-old.bak1"))
        assertEquals(
            "Cannot install Agent Toolkit skills through a path linked outside the project: .junie/skills.",
            rejected(dir, ToolkitStep.SKILLS),
        )
        AgentToolkitInstaller.rejectLinkedPaths(dir, setOf(ToolkitStep.GUIDELINES))
        // install.ps1 also sweeps names that start with `..`.
        Files.delete(dir.resolve(".junie"))
        Files.createSymbolicLink(dir.resolve("..junie"), outside.resolve("junie"))
        assertEquals(
            "Cannot install Agent Toolkit skills through a path linked outside the project: ..junie/skills.",
            rejected(dir, ToolkitStep.SKILLS),
        )
    }

    @Test
    fun `an MCP config name that differs only in letter case is no link`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("Opencode.json"), "{}")
        // Only a case-insensitive disk (macOS, Windows) finds these under the toolkit's names.
        assumeTrue(Files.exists(dir.resolve("opencode.json")))
        Files.createDirectories(dir.resolve(".Junie/MCP"))
        AgentToolkitInstaller.rejectLinkedPaths(dir, ToolkitStep.entries.toSet())
    }

    @Test
    fun `a Windows junction is a link to the guard`(@TempDir dir: Path, @TempDir outside: Path) {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        // The toolkit links folders with junctions on Windows, and to Java a junction is no symbolic link.
        val junctions = mutableListOf<Path>()
        fun junction(link: Path, target: Path) {
            val process = ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toAbsolutePath().toString())
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), output)
            junctions.add(link)
        }
        try {
            junction(Files.createDirectories(dir.resolve(".agents")).resolve("skills"), outside)
            assertEquals(
                "Cannot install Agent Toolkit skills through a path linked outside the project: .agents/skills.",
                rejected(dir, ToolkitStep.SKILLS),
            )
            junction(dir.resolve(".junie"), Files.createDirectories(dir.resolve("real/mcp")).parent)
            assertEquals("Cannot configure project MCP servers through a linked path: .junie.", rejected(dir, ToolkitStep.MCP))
            Files.delete(junctions.removeAt(0))
            junction(Files.createDirectories(dir.resolve(".claude")).resolve("skills"), Files.createDirectories(dir.resolve(".agents/skills")))
            AgentToolkitInstaller.rejectLinkedPaths(dir, setOf(ToolkitStep.SKILLS))
        } finally {
            // The junctions themselves go, so cleaning up the temporary folders never walks through them.
            junctions.forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun `the MCP guard covers every project config of the toolkit's metadata and its folders`() {
        val json = checkNotNull(javaClass.getResource("/toolkit/studio-meta-data-v3.json")).readText()
        val configs = AgentToolkitMetadata.parseMcpOptions(json).agents.mapNotNull { it.projectConfig }
        assertTrue(configs.isNotEmpty())
        val guarded = configs.flatMap { config -> config.split('/').runningReduce { folder, name -> "$folder/$name" } }
        assertEquals(emptyList<String>(), guarded.filterNot { it in AgentToolkitInstaller.MCP_PATHS })
    }

    @Test
    fun `outside Windows a step without a usable PATH gets a fixed one, never the current directory`() {
        for (environment in listOf(mutableMapOf("HOME" to "/h"), mutableMapOf("PATH" to ""), mutableMapOf("PATH" to ".${File.pathSeparator}bin"))) {
            AgentToolkitInstaller.trustPathVariable(environment, os = "Linux")
            assertEquals("/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin", environment["PATH"], "$environment")
        }
        // Windows PowerShell never runs a bare name from the current directory, and Windows always has a Path.
        val windows = mutableMapOf("Path" to ".")
        AgentToolkitInstaller.trustPathVariable(windows, os = "Windows 11")
        assertEquals(mapOf("Path" to ""), windows)
    }

    @Test
    fun `a step's environment has the markers, a trusted PATH, and Claude Desktop moved for MCP`(@TempDir profile: Path) {
        fun prepared(os: String, mcp: Boolean) = mutableMapOf("PATH" to ".", "HOME" to "/home/u", "PSModulePath" to "pwsh").also {
            AgentToolkitInstaller.prepareEnvironment(it, profile.takeIf { mcp }, os)
        }
        val skills = prepared("Linux", mcp = false)
        assertEquals(mapOf("PATH" to "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin", "HOME" to "/home/u", "JMIX_EMIT_CHANGE_MARKERS" to "1"), skills)
        assertEquals("$profile", prepared("Linux", mcp = true)["XDG_CONFIG_HOME"])
        val mac = prepared("Mac OS X", mcp = true)
        assertEquals("$profile", mac["HOME"])
        // The Codex conflict check keeps reading the real profile.
        assertEquals(Path.of("/home/u", ".codex").toString(), mac["CODEX_HOME"])
        val windows = prepared("Windows 11", mcp = true)
        assertEquals(listOf("$profile", "$profile"), listOf(windows["APPDATA"], windows["LOCALAPPDATA"]))
        assertEquals("/home/u", windows["HOME"])
    }

    @Test
    fun `a dangling link where the guidelines step would put a backup is rejected`(@TempDir dir: Path, @TempDir outside: Path) {
        assumeTrue(links)
        Files.writeString(dir.resolve("AGENTS.md"), "# Own rules\n")
        Files.writeString(dir.resolve("AGENTS.md.bak"), "# Older rules\n")
        AgentToolkitInstaller.rejectLinkedPaths(dir, setOf(ToolkitStep.GUIDELINES))
        Files.createSymbolicLink(dir.resolve("AGENTS.md.bak1"), outside.resolve("profile"))
        assertEquals(
            "Cannot install Agent Toolkit guidelines through a path linked outside the project: AGENTS.md.bak1.",
            rejected(dir, ToolkitStep.GUIDELINES),
        )
    }

    @Test
    fun `links that stay inside the project pass the skills and guidelines guard`(@TempDir dir: Path) {
        assumeTrue(links)
        Files.writeString(dir.resolve("AGENTS.md"), "# Own rules\n")
        Files.createSymbolicLink(dir.resolve("CLAUDE.md"), Path.of("AGENTS.md"))
        Files.createDirectories(dir.resolve(".agents/skills"))
        Files.createSymbolicLink(Files.createDirectories(dir.resolve(".claude")).resolve("skills"), Path.of("../.agents/skills"))
        AgentToolkitInstaller.rejectLinkedPaths(dir, setOf(ToolkitStep.SKILLS, ToolkitStep.GUIDELINES))
        // Nothing to check in an empty project either.
        AgentToolkitInstaller.rejectLinkedPaths(Files.createDirectories(dir.resolve("empty")), ToolkitStep.entries.toSet())
    }

    @Test
    fun `any linked MCP config path is rejected, also when it stays inside the project`(@TempDir dir: Path) {
        assumeTrue(links)
        Files.createSymbolicLink(dir.resolve(".mcp.json"), Files.writeString(dir.resolve("real.json"), "{}"))
        val message = "Cannot configure project MCP servers through a linked path: .mcp.json."
        assertEquals(message, rejected(dir, ToolkitStep.MCP))
        assertEquals(message, rejected(dir, *ToolkitStep.entries.toTypedArray()))
        AgentToolkitInstaller.rejectLinkedPaths(dir, setOf(ToolkitStep.SKILLS, ToolkitStep.GUIDELINES))

        Files.delete(dir.resolve(".mcp.json"))
        Files.createSymbolicLink(dir.resolve(".junie"), Files.createDirectories(dir.resolve("real/mcp")).parent)
        assertEquals("Cannot configure project MCP servers through a linked path: .junie.", rejected(dir, ToolkitStep.MCP))
    }

    @Test
    fun `install rejects linked paths before the download and the first step`(@TempDir dir: Path, @TempDir outside: Path) {
        assumeTrue(links)
        // Every phase is announced before it starts, so a run that got past the guard stops here, off the network.
        fun rejectedInstall(steps: Set<ToolkitStep>): String? = assertThrows(IOException::class.java) {
            AgentToolkitInstaller.install(dir, "3.0.1", steps = steps, onStatus = { throw AssertionError("Not rejected before: $it") })
        }.message

        Files.createSymbolicLink(dir.resolve("CLAUDE.md"), Files.writeString(outside.resolve("credentials"), "secret"))
        assertEquals(
            "Cannot install Agent Toolkit guidelines through a path linked outside the project: CLAUDE.md.",
            rejectedInstall(setOf(ToolkitStep.GUIDELINES)),
        )

        Files.delete(dir.resolve("CLAUDE.md"))
        Files.createSymbolicLink(dir.resolve(".mcp.json"), Files.writeString(dir.resolve("real.json"), "{}"))
        assertEquals(
            "Cannot configure project MCP servers through a linked path: .mcp.json.",
            rejectedInstall(ToolkitStep.entries.toSet()),
        )
    }

    @Test
    fun `a step log that is not valid UTF-8 is still read`(@TempDir dir: Path) {
        val log = dir.resolve("step.log")
        Files.write(log, byteArrayOf('o'.code.toByte(), 'k'.code.toByte(), '\n'.code.toByte(), 0xFF.toByte(), 'x'.code.toByte(), '\n'.code.toByte()))
        val lines = AgentToolkitInstaller.readLog(log)
        assertEquals(2, lines.size)
        assertEquals("ok", lines[0])
        assertTrue(lines[1].endsWith("x"))
        assertEquals(emptyList<String>(), AgentToolkitInstaller.readLog(dir.resolve("missing.log")))
    }

    @Test
    fun `a step log is read as UTF-8 when it is valid UTF-8, else in the fallback code page`(@TempDir dir: Path) {
        val log = dir.resolve("step.log")
        val oem = Charset.forName("IBM437")
        Files.writeString(log, "café\n", Charsets.UTF_8)
        assertEquals(listOf("café"), AgentToolkitInstaller.readLog(log, oem))
        // é is 0x82 in code page 437, no valid UTF-8.
        Files.writeString(log, "café\n", oem)
        assertEquals(listOf("café"), AgentToolkitInstaller.readLog(log, oem))
        assertEquals(listOf("caf�"), AgentToolkitInstaller.readLog(log, fallback = null))
        Files.write(log, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "ok\n".toByteArray())
        assertEquals(listOf("ok"), AgentToolkitInstaller.readLog(log, oem))
    }

    @Test
    fun `a Windows PowerShell step's non-ASCII output is read in the code page it was written in`(@TempDir dir: Path) {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        // ASCII like install.ps1: the non-ASCII text comes from the project path, the working directory.
        val script = Files.writeString(
            dir.resolve("step.ps1"),
            """
            Write-Output "cp=$([Console]::OutputEncoding.CodePage)"
            Write-Output "@@JMIX_CHANGE@@`taction=created`ttype=file`tpath=$(Join-Path (Get-Location).Path '.mcp.json')"
            Write-Output "Note: Codex reads $((Get-Location).Path)\AGENTS.override.md"
            if (${'$'}args -contains 'fail') { [Console]::Error.WriteLine("error: cannot update $((Get-Location).Path)"); exit 1 }
            """.trimIndent(),
        )
        // A machine whose policy blocks scripts (AllSigned, Constrained Language) cannot run this check.
        val probe = Files.writeString(dir.resolve("probe.ps1"), "[Console]::Error.WriteLine('ok')")
        val runs = runCatching { AgentToolkitInstaller.runStep(dir, AgentToolkitInstaller.command(probe, emptyList()), "probe") }
        assumeTrue(runs.getOrNull() == listOf("ok"), "PowerShell cannot run the test script here: $runs")
        val project = Files.createDirectories(dir.resolve("café")).toRealPath()
        val output = AgentToolkitInstaller.runStep(project, AgentToolkitInstaller.command(script, emptyList()), "test")
        val codePage = output.first().removePrefix("cp=").toInt()
        // A UTF-8 console has nothing to decode, and a code page without é loses it before the CLI reads it.
        assumeTrue(codePage != 65001, "the child's console is UTF-8")
        assumeTrue(WindowsConsole.charsetOf(codePage)?.newEncoder()?.canEncode('é') == true, "code page $codePage has no é")
        // The one inferred link: Java's windowless child console starts in the OEM code page.
        assertEquals(WindowsConsole.charsetOf(codePage), WindowsConsole.oemCharset, "child console code page $codePage vs GetOEMCP")
        assertEquals(listOf(ToolkitChange("created", ".mcp.json")), AgentToolkitInstaller.parseChanges(output, project))
        assertEquals(listOf("Note: Codex reads $project\\AGENTS.override.md"), AgentToolkitInstaller.toolkitNotes(output, mcp = false))
        val failure = assertThrows(IOException::class.java) {
            AgentToolkitInstaller.runStep(project, AgentToolkitInstaller.command(script, listOf("fail")), "test")
        }
        assertEquals("Agent Toolkit test step failed: error: cannot update $project", failure.message)
    }

    @Test
    fun `the failure tail of a step log carries no control characters`(@TempDir dir: Path) {
        val log = dir.resolve("step.log")
        Files.writeString(log, "Installing\n@@JMIX_CHANGE@@\taction=created\n\u001B[31mERROR:\u001B[0m no git\u0007\r\n")
        assertEquals("Installing [31mERROR:[0m no git", AgentToolkitInstaller.logTail(log))
        assertEquals("no output", AgentToolkitInstaller.logTail(dir.resolve("missing.log")))
    }

    @Test
    fun `a failed step is described by its error lines`(@TempDir dir: Path) {
        val log = dir.resolve("step.log")
        val followUps = (1..14).joinToString("\n") { "Follow-up $it" }
        Files.writeString(
            log,
            "[Docs MCP] Codex\n  \u001B[31merror: cannot parse /p/.codex/config.toml\u0007\r\n[Docs MCP] Junie\n  Created: /p/x\n" +
                "@@JMIX_CHANGE@@\taction=created\ttype=file\tpath=/p/x\nerror: second\n\n$followUps\n",
        )
        // The escape character goes, so the first line no longer starts with the prefix: only real error lines count.
        assertEquals("error: second", AgentToolkitInstaller.logTail(log))
        Files.writeString(log, "[Docs MCP] Codex\n  error: cannot parse /p/.codex/config.toml\u0007\r\nerror: second\n\n$followUps\n")
        assertEquals("error: cannot parse /p/.codex/config.toml error: second", AgentToolkitInstaller.logTail(log))
        Files.writeString(log, "error: " + "x".repeat(800) + "\n")
        assertEquals(700, AgentToolkitInstaller.logTail(log).length)
    }

    @Test
    fun `a failed step without error lines is described by its last twelve non-blank lines`(@TempDir dir: Path) {
        val log = dir.resolve("step.log")
        Files.writeString(log, "first\n\n" + (1..12).joinToString("\n\n") { "line $it" } + "\n@@JMIX_CHANGE@@\taction=created\n\n")
        assertEquals((1..12).joinToString(" ") { "line $it" }, AgentToolkitInstaller.logTail(log))
    }
}
