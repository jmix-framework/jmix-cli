package io.jmix.cli.env

import java.io.File
import java.io.IOException
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
    fun `a dangling link on a guidelines or skills path is rejected`(@TempDir dir: Path, @TempDir outside: Path) {
        assumeTrue(links)
        Files.createSymbolicLink(dir.resolve("AGENTS.md"), dir.resolve("missing.md"))
        assertEquals(
            "Cannot install Agent Toolkit guidelines through a path linked outside the project: AGENTS.md.",
            rejected(dir, ToolkitStep.GUIDELINES),
        )
        Files.createSymbolicLink(dir.resolve(".claude"), outside.resolve("missing"))
        assertEquals(
            "Cannot install Agent Toolkit skills through a path linked outside the project: .claude.",
            rejected(dir, ToolkitStep.SKILLS),
        )
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
