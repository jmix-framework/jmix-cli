package io.jmix.cli.command

import com.github.ajalt.clikt.testing.test
import com.github.ajalt.mordant.input.KeyboardEvent
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.rendering.TextColors.brightRed
import io.jmix.cli.env.AgentToolkitStatus
import io.jmix.cli.env.ToolkitChange
import io.jmix.cli.env.ToolkitChoice
import io.jmix.cli.env.ToolkitMcpOptions
import io.jmix.cli.env.ToolkitResult
import io.jmix.cli.env.ToolkitStep
import io.jmix.cli.wizard.ANSI_SEQUENCE
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class ToolkitCommandTest {
    @TempDir lateinit var dir: Path

    private class Call(val dir: Path, val version: String, val steps: Set<ToolkitStep>, val servers: List<String>?, val agents: List<String>?)
    private val calls = mutableListOf<Call>()
    private val refs = mutableListOf<String>()
    private val timeouts = mutableListOf<Duration>()
    private var result = ToolkitResult(emptyList(), emptyList())
    private var failure: IOException? = null
    private var metadataFailure: IOException? = null
    private var status: AgentToolkitStatus.Status? = null
    private var statusFailure: Exception? = null
    private var statusCalls = 0
    private val options = ToolkitMcpOptions(
        servers = listOf(ToolkitChoice("jetbrains", "JetBrains"), ToolkitChoice("jmix-docs", "Jmix Docs"), ToolkitChoice("playwright", "Playwright")),
        agents = listOf(ToolkitChoice("claude", "Claude Code"), ToolkitChoice("codex", "Codex"), ToolkitChoice("opencode", "OpenCode"), ToolkitChoice("junie", "Junie")),
    )
    private val serverIds get() = options.servers.map { it.value }
    private val agentIds get() = options.agents.map { it.value }

    private fun command(cwd: Path = dir) = ToolkitCommand(
        install = { projectDir, version, steps, servers, agents, _ ->
            failure?.let { throw it }
            calls += Call(projectDir, version, steps, servers, agents)
            result
        },
        mcpOptions = { ref, timeout ->
            refs += ref
            timeouts += timeout
            metadataFailure?.let { throw it }
            options
        },
        status = { _, _ ->
            statusCalls++
            statusFailure?.let { throw it }
            status
        },
        currentDir = { cwd },
    )

    private fun project(build: String = "plugins { id 'io.jmix' version '3.1.0' }"): Path =
        Files.createDirectories(dir.resolve("demo")).also { Files.writeString(it.resolve("build.gradle"), build) }

    private fun run(vararg args: String) = command().test(args.toList() + "--non-interactive")

    private fun flat(text: String) = text.replace(Regex("\\s+"), " ")

    private fun <T> withStdin(input: String, block: () -> T): T {
        val original = System.`in`
        System.setIn(ByteArrayInputStream(input.toByteArray()))
        try {
            return block()
        } finally {
            System.setIn(original)
        }
    }

    private fun lines(input: String, vararg args: String, cwd: Path = dir) = withStdin(input) {
        command(cwd).test(args.toList(), inputInteractive = false, outputInteractive = false)
    }

    /** A raw terminal; a question that runs out of [keys] would wait forever, hence the timeout. */
    private fun raw(vararg args: String, keys: List<String>) = assertTimeoutPreemptively(Duration.ofSeconds(10)) {
        command().test(
            args.toList(), ansiLevel = AnsiLevel.TRUECOLOR, width = 100, height = 30, inputEvents = keys.map { KeyboardEvent(it) },
        )
    }

    @Test
    fun `without an action a scripted run sets up everything the toolkit offers`() {
        val project = project()
        val run = run("--path", project.toString())
        assertEquals(0, run.statusCode, run.stderr)
        val call = calls.single()
        assertEquals(project, call.dir)
        assertEquals("3.1.0", call.version)
        assertEquals(ToolkitStep.entries.toSet(), call.steps)
        assertEquals(serverIds, call.servers)
        assertEquals(agentIds, call.agents)
        assertEquals(listOf("v3"), refs)
        assertEquals(listOf(Duration.ofSeconds(15)), timeouts)
        assertTrue(run.stdout.contains("Project: $project (Jmix 3.1.0, toolkit v3)"))
    }

    @ParameterizedTest
    @CsvSource("all,SKILLS|GUIDELINES|MCP", "skills,SKILLS", "guidelines,GUIDELINES", "mcp,MCP")
    fun `an action runs its steps and loads metadata only for MCP`(action: String, steps: String) {
        run(action, "--path", project().toString())
        val expected = steps.split('|').map(ToolkitStep::valueOf).toSet()
        assertEquals(expected, calls.single().steps)
        val mcp = ToolkitStep.MCP in expected
        assertEquals(if (mcp) listOf("v3") else emptyList(), refs)
        assertEquals(if (mcp) serverIds else null, calls.single().servers)
        assertEquals(if (mcp) agentIds else null, calls.single().agents)
    }

    @Test
    fun `mcp passes the selected servers and agents in toolkit order`() {
        run("mcp", "--servers", "playwright, JetBrains", "--agents", "junie,claude", "--path", project().toString())
        assertEquals(listOf("jetbrains", "playwright"), calls.single().servers)
        assertEquals(listOf("claude", "junie"), calls.single().agents)
    }

    @Test
    fun `unknown servers and agents are rejected with the offered values`() {
        val servers = run("mcp", "--servers", "context7", "--path", project().toString())
        assertTrue(flat(servers.stderr).contains("Unknown MCP server: context7. Valid values: jetbrains, jmix-docs, playwright."))
        val agents = run("all", "--agents", "cline", "--path", project().toString())
        assertTrue(flat(agents.stderr).contains("Unknown AI agent: cline. Valid values: claude, codex, opencode, junie."))
        val empty = run("mcp", "--servers", ",", "--path", project().toString())
        assertTrue(empty.stderr.contains("Select at least one MCP server."))
        assertEquals(listOf(1, 1, 1), listOf(servers, agents, empty).map { it.statusCode })
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `servers and agents apply only to mcp and all`() {
        val run = run("skills", "--servers", "jmix-docs", "--path", project().toString())
        assertEquals(1, run.statusCode)
        assertEquals("--servers and --agents apply only to the mcp and all actions.", flat(run.stderr).trim())
        assertTrue(calls.isEmpty())
        assertTrue(refs.isEmpty())
    }

    @Test
    fun `a usage error does not load the toolkit metadata`() {
        val unknownOption = command(cwd = project()).test(listOf("--bogus"))
        val unknownAction = command(cwd = project()).test(listOf("everything", "--non-interactive"))
        assertEquals(listOf(1, 1), listOf(unknownOption, unknownAction).map { it.statusCode })
        assertTrue(refs.isEmpty())
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `fails outside a Gradle project without running the toolkit`() {
        val empty = Files.createDirectories(dir.resolve("empty"))
        val run = command(cwd = empty).test(listOf("skills", "--non-interactive"))
        assertEquals(1, run.statusCode)
        assertTrue(flat(run.stderr).contains("No Gradle project found in $empty. Run `jmix ai-toolkit` from the project root or pass --path."))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `the version option overrides detection and an undetected version fails`() {
        run("skills", "--jmix-version", "2.8.3", "--path", project().toString())
        assertEquals("2.8.3", calls.single().version)
        val bare = Files.createDirectories(dir.resolve("bare")).also { Files.writeString(it.resolve("settings.gradle"), "") }
        val run = run("skills", "--path", bare.toString())
        assertEquals(1, run.statusCode)
        assertTrue(flat(run.stderr).contains("Cannot detect the Jmix version in $bare. Pass --jmix-version."))
        assertEquals(1, calls.size)
    }

    @Test
    fun `prints the changes, the backup and the toolkit's notes`() {
        result = ToolkitResult(
            changes = listOf(
                ToolkitChange("updated", ".agents/skills"), ToolkitChange("backed-up", "AGENTS.md.bak"),
                ToolkitChange("updated", "AGENTS.md"), ToolkitChange("created", ".mcp.json"),
            ),
            notes = listOf("Approve the servers.", "Trust the folder."),
        )
        val out = run("all", "--path", project().toString()).stdout
        assertTrue(out.contains("✓ Agent Toolkit v3 is set up"))
        assertTrue(out.contains("  updated    .agents/skills"))
        assertTrue(out.contains("  backed-up  AGENTS.md.bak"))
        assertTrue(out.contains("  created    .mcp.json"))
        assertTrue(out.contains("Notes from the Agent Toolkit:"))
        assertTrue(out.indexOf("  • Approve the servers.") in 0 until out.indexOf("  • Trust the folder."))
    }

    @Test
    fun `an action this version does not know is still listed`() {
        result = ToolkitResult(listOf(ToolkitChange("created", "a.txt"), ToolkitChange("moved-aside", "b.txt")), emptyList())
        val out = run("skills", "--path", project().toString()).stdout
        assertTrue(out.contains("  created      a.txt"))
        assertTrue(out.contains("  moved-aside  b.txt"))
    }

    @Test
    fun `an unchanged project is reported as up to date`() {
        val quiet = run("guidelines", "--path", project().toString()).stdout
        assertTrue(quiet.contains("✓ Agent Toolkit v3 is already up to date"))
        assertFalse(quiet.contains("Notes from the Agent Toolkit:"))
        result = ToolkitResult(emptyList(), listOf("Approve the servers."))
        val noted = run("mcp", "--path", project().toString()).stdout
        assertTrue(noted.contains("✓ Agent Toolkit v3 is already up to date"))
        assertTrue(noted.contains("  • Approve the servers."))
    }

    @Test
    fun `installer and metadata failures stay one concise line`() {
        failure = IOException("Agent Toolkit skills step failed: boom")
        val failed = run("skills", "--path", project().toString())
        assertEquals(1, failed.statusCode)
        assertEquals("Agent Toolkit setup failed: Agent Toolkit skills step failed: boom", failed.stderr.trim())
        failure = null
        metadataFailure = IOException("Unexpected Agent Toolkit metadata format.")
        val broken = run("mcp", "--path", project().toString())
        assertEquals(1, broken.statusCode)
        assertEquals("Agent Toolkit setup failed: Unexpected Agent Toolkit metadata format.", broken.stderr.trim())
        assertTrue(calls.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["all", "mcp"])
    fun `a Jmix major without a toolkit branch is named when its metadata is missing`(action: String) {
        metadataFailure = FileNotFoundException("Cannot load .studio/studio-meta-data.json from Agent Toolkit v4: HTTP 404")
        val run = run(action, "--jmix-version", "4.0.0", "--path", project().toString())
        assertEquals(1, run.statusCode)
        assertEquals("Agent Toolkit setup failed: the toolkit has no v4 branch for Jmix 4 yet", run.stderr.trim())
        // The metadata is loaded while the run is resolved, before the project line.
        assertFalse(run.stdout.contains("Project:"))
        assertTrue(calls.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["skills", "guidelines"])
    fun `a step fails below the project line`(action: String) {
        failure = IOException("no script")
        val project = project()
        val run = run(action, "--jmix-version", "4.0.0", "--path", project.toString())
        assertEquals(1, run.statusCode)
        assertTrue(run.stdout.contains("✓ Project: $project (Jmix 4.0.0, toolkit v4)"))
        assertTrue(run.output.indexOf("✓ Project:") < run.output.indexOf("Agent Toolkit setup failed:"))
    }

    @Test
    fun `control characters are removed from the failure line`() {
        failure = IOException("Agent Toolkit skills step failed: \u001B[31mboom\u0007\r\nnext")
        val failed = run("skills", "--path", project().toString())
        assertEquals("Agent Toolkit setup failed: Agent Toolkit skills step failed: [31mboomnext", failed.stderr.trim())
    }

    @Test
    fun `a path the platform cannot parse fails like a directory without a project`() {
        val runs = listOf(run("skills", "--path", "no\u0000path"), lines("2\n", "--path", "no\u0000path"))
        for (run in runs) {
            assertEquals(1, run.statusCode)
            assertEquals(
                "No Gradle project found in nopath. Run `jmix ai-toolkit` from the project root or pass --path.",
                flat(run.stderr).trim(),
            )
        }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `a detected version without a numeric major counts as not detected`() {
        val odd = project("jmix { bomVersion = 'abc' }")
        val scripted = run("skills", "--path", odd.toString())
        assertEquals(1, scripted.statusCode)
        assertEquals("Cannot detect the Jmix version in $odd. Pass --jmix-version.", flat(scripted.stderr).trim())
        assertTrue(calls.isEmpty())
        val asked = lines("3.1.0\n2\n", "--path", odd.toString())
        assertTrue(asked.output.contains("Jmix version of the project"))
        assertEquals("3.1.0", calls.single().version)
        val help = command(cwd = odd).test(listOf("--help"))
        assertTrue(flat(help.output).contains("MCP servers in Agent Toolkit: jetbrains, jmix-docs, playwright."))
        assertEquals("HEAD", refs.last())
    }

    @Test
    fun `an explicit version without a numeric major fails with the branch error`() {
        val project = project().toString()
        val runs = listOf(
            run("skills", "--jmix-version", "latest", "--path", project),
            lines("2\n", "--jmix-version", "latest", "--path", project),
        )
        for (run in runs) {
            assertEquals(1, run.statusCode)
            assertEquals("Unexpected Jmix version format: latest", run.stderr.trim())
        }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `typed ids match without case`() {
        val project = project().toString()
        run("mcp", "--servers", "JMIX-DOCS", "--agents", "Claude", "--path", project)
        lines("PLAYWRIGHT\nJunie\n", "mcp", "--path", project)
        assertEquals(listOf(listOf("jmix-docs") to listOf("claude"), listOf("playwright") to listOf("junie")), calls.map { it.servers to it.agents })
    }

    @Test
    fun `help lists what the toolkit offers for the current project`() {
        val help = command(cwd = project()).test(listOf("--help"))
        assertEquals(0, help.statusCode)
        assertTrue(flat(help.output).contains("MCP servers in Agent Toolkit v3: jetbrains, jmix-docs, playwright. AI agents: claude, codex, opencode, junie."))
        assertTrue(flat(help.output).contains("What to set up: all, skills, guidelines or mcp"))
        assertEquals(listOf("v3"), refs.distinct())
        val outside = command(cwd = Files.createDirectories(dir.resolve("empty"))).test(listOf("--help"))
        assertTrue(flat(outside.output).contains("MCP servers in Agent Toolkit: jetbrains, jmix-docs, playwright. AI agents: claude, codex, opencode, junie."))
        assertEquals("HEAD", refs.last())
        assertEquals(listOf(Duration.ofSeconds(3)), timeouts.distinct())
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `help survives an unreachable toolkit`() {
        metadataFailure = IOException("offline")
        val help = command(cwd = project()).test(listOf("--help"))
        assertEquals(0, help.statusCode)
        assertTrue(flat(help.output).contains("Usage: ai-toolkit"))
        assertTrue(flat(help.output).contains("The current MCP servers and AI agents are listed when the Agent Toolkit is reachable."))
    }

    @Test
    fun `numbered menu runs the chosen action and Enter runs all`() {
        val project = project().toString()
        assertEquals(0, lines("2\n", "--path", project).statusCode)
        lines("\n", "--path", project)
        assertEquals(listOf(setOf(ToolkitStep.SKILLS), ToolkitStep.entries.toSet()), calls.map { it.steps })
        assertEquals(agentIds, calls.last().agents)
    }

    @Test
    fun `line input reads the MCP lists in option syntax and repeats an invalid one`() {
        val run = lines("4\ncontext7\nplaywright,jmix-docs\n\n", "--path", project().toString())
        assertTrue(run.output.contains("MCP servers (jetbrains,jmix-docs,playwright)"))
        assertTrue(flat(run.output).contains("Unknown MCP server: context7. Valid values: jetbrains, jmix-docs, playwright."))
        assertEquals(listOf("jmix-docs", "playwright"), calls.single().servers)
        assertEquals(agentIds, calls.single().agents)
    }

    @Test
    fun `options hide the questions they answer`() {
        lines("jmix-docs\n", "mcp", "--agents", "claude", "--path", project().toString())
        assertEquals(listOf("jmix-docs") to listOf("claude"), calls.single().let { it.servers to it.agents })
    }

    @Test
    fun `raw mcp flow shows the toolkit's labels and toggles an agent off`() {
        val run = raw("mcp", "--path", project().toString(), keys = listOf("Enter", "ArrowDown", " ", "Enter", "q"))
        assertTrue(run.output.contains("Jmix Docs"))
        assertTrue(run.output.contains("Claude Code"))
        assertEquals(serverIds, calls.single().servers)
        assertEquals(listOf("claude", "opencode", "junie"), calls.single().agents)
    }

    @Test
    fun `back leaves the MCP questions and then the command`() {
        lines("4\n<\n2\n", "--path", project().toString())
        assertEquals(setOf(ToolkitStep.SKILLS), calls.single().steps)
        assertThrows<ReturnToMainMenu> { lines("<\n", "--path", project().toString()) }
        assertEquals(1, calls.size)
    }

    @Test
    fun `quit and end of input run nothing`() {
        assertEquals(0, lines("q\n", "--path", project().toString()).statusCode)
        assertEquals(0, lines("", "--path", project().toString()).statusCode)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `outside a project the directory is asked for until it is a Gradle project`() {
        val project = project()
        val empty = Files.createDirectories(dir.resolve("empty"))
        val run = lines("$empty\n$project\n2\n", cwd = empty)
        assertTrue(flat(run.output).contains("No Gradle project found in $empty."))
        assertEquals(project, calls.single().dir)
    }

    @Test
    fun `the project line follows the directory chosen after going back`() {
        val project = project()
        val bare = Files.createDirectories(dir.resolve("bare")).also { Files.writeString(it.resolve("settings.gradle"), "") }
        val run = lines("$bare\n2.8.3\n<\n<\n$project\n2\n", cwd = Files.createDirectories(dir.resolve("empty")))
        assertTrue(flat(run.output).contains("Project: $bare (Jmix 2.8.3, toolkit v2)"))
        assertTrue(flat(run.output).contains("Project: $project (Jmix 3.1.0, toolkit v3)"))
        assertEquals(project, calls.single().dir)
    }

    @Test
    fun `an explicit path that is no project fails instead of asking`() {
        val empty = Files.createDirectories(dir.resolve("empty"))
        val run = lines("2\n", "--path", empty.toString())
        assertEquals(1, run.statusCode)
        assertTrue(flat(run.stderr).contains("No Gradle project found in $empty. Run `jmix ai-toolkit` from the project root or pass --path."))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `MCP options with a menu choice that has no MCP step are rejected`() {
        val run = lines("2\n", "--servers", "jmix-docs", "--path", project().toString())
        assertEquals(1, run.statusCode)
        assertTrue(flat(run.stderr).contains("--servers and --agents apply only to the mcp and all actions."))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `an undetected version is asked for`() {
        val bare = Files.createDirectories(dir.resolve("bare")).also { Files.writeString(it.resolve("settings.gradle"), "") }
        val run = lines("latest\n3.1.0\n2\n", "--path", bare.toString())
        assertTrue(run.output.contains("Enter a Jmix version such as 3.1.0."))
        assertEquals("3.1.0", calls.single().version)
    }

    @Test
    fun `the menu labels skills and guidelines with their state`() {
        status = AgentToolkitStatus.Status(AgentToolkitStatus.State.UPDATE_AVAILABLE, AgentToolkitStatus.State.UP_TO_DATE)
        val out = lines("q\n", "--path", project().toString()).output
        assertTrue(out.contains("Update agent skills · update available"))
        assertTrue(out.contains("Generate AGENTS.md guidelines · up to date"))
        assertTrue(out.contains("Configure MCP servers"))
        assertFalse(out.contains("Configure MCP servers ·"))
        status = AgentToolkitStatus.Status(AgentToolkitStatus.State.NOT_INSTALLED, null)
        val partial = lines("q\n", "--path", project().toString()).output
        assertTrue(partial.contains("Update agent skills · not installed"))
        assertFalse(partial.contains("Generate AGENTS.md guidelines ·"))
        status = null
        assertFalse(lines("q\n", "--path", project().toString()).output.contains(" · "))
    }

    @Test
    fun `an interactive run that asks nothing stays on the main screen and needs no key`() {
        val project = project().toString()
        val quiet = listOf(
            raw("skills", "--path", project, keys = emptyList()),
            raw("mcp", "--servers", "jmix-docs", "--agents", "claude", "--path", project, keys = emptyList()),
        )
        for (run in quiet) {
            assertEquals(0, run.statusCode, run.stderr)
            assertFalse(run.output.contains(ENTER_ALTERNATE_SCREEN))
        }
        assertEquals(listOf(setOf(ToolkitStep.SKILLS), setOf(ToolkitStep.MCP)), calls.map { it.steps })
        assertTrue(raw("--path", project, keys = listOf("Enter")).output.contains(ENTER_ALTERNATE_SCREEN))
    }

    @Test
    fun `raw Esc returns to the previous question and from the first one to the command menu`() {
        val project = project().toString()
        // The menu, mcp, the servers; Esc from the agents, Esc from the servers, and the menu again.
        val keys = listOf("ArrowDown", "ArrowDown", "ArrowDown", "Enter", "Enter", "Escape", "Escape", "ArrowDown", "Enter")
        assertEquals(0, raw("--path", project, keys = keys).statusCode)
        assertEquals(setOf(ToolkitStep.SKILLS), calls.single().steps)
        assertEquals(1, statusCalls)
        assertThrows<ReturnToMainMenu> { raw("--path", project, keys = listOf("Escape")) }
        assertEquals(1, calls.size)
    }

    @Test
    fun `raw q quits from a list with nothing run`() {
        val project = project().toString()
        assertEquals(0, raw("--path", project, keys = listOf("q")).statusCode)
        assertEquals(0, raw("mcp", "--path", project, keys = listOf("Enter", "q")).statusCode)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `a checklist confirmed empty asks again with the reason in red`() {
        val keys = listOf(" ", "ArrowDown", " ", "ArrowDown", " ", "Enter", "Enter", "Enter")
        val run = raw("mcp", "--path", project().toString(), keys = keys)
        assertTrue(run.output.contains(brightRed("Select at least one MCP server.")))
        assertEquals(serverIds to agentIds, calls.single().let { it.servers to it.agents })
    }

    @Test
    fun `the answers are printed again after the questions of a raw terminal`() {
        val project = project()
        val keys = listOf("ArrowDown", "ArrowDown", "ArrowDown", "Enter", "Enter", "ArrowDown", " ", "Enter")
        val run = raw("--path", project.toString(), keys = keys)
        val afterQuestions = ANSI_SEQUENCE.replace(run.output.substringAfterLast(EXIT_ALTERNATE_SCREEN), "")
        val positions = listOf(
            "✓ Project: $project (Jmix 3.1.0, toolkit v3)", "✓ Action: mcp", "✓ MCP servers: jetbrains, jmix-docs, playwright",
            "✓ AI agents: claude, opencode, junie", "✓ Agent Toolkit v3 is already up to date",
        ).map(afterQuestions::indexOf)
        assertTrue(positions.all { it >= 0 } && positions == positions.sorted(), afterQuestions)
    }

    @Test
    fun `back to the menu does not detect the status again`() {
        lines("4\n<\n2\n", "--path", project().toString())
        assertEquals(setOf(ToolkitStep.SKILLS), calls.single().steps)
        assertEquals(1, statusCalls)
    }

    @Test
    fun `a status check that throws still shows the menu, without hints`() {
        statusFailure = IllegalStateException("boom")
        val run = lines("2\n", "--path", project().toString())
        assertEquals(0, run.statusCode, run.stderr)
        assertTrue(run.output.contains("Update agent skills"))
        assertFalse(run.output.contains(" · "))
        assertEquals(setOf(ToolkitStep.SKILLS), calls.single().steps)
        assertEquals(1, statusCalls)
    }

    @Test
    fun `scripted runs do not ask for the status`() {
        var asked = 0
        ToolkitCommand(
            install = { _, _, _, _, _, _ -> result }, mcpOptions = { _, _ -> options },
            status = { _, _ -> asked++; null }, currentDir = { dir },
        ).test(listOf("skills", "--non-interactive", "--path", project().toString()))
        assertEquals(0, asked)
    }

    private companion object {
        const val ENTER_ALTERNATE_SCREEN = "\u001B[?1049h"
        const val EXIT_ALTERNATE_SCREEN = "\u001B[?1049l"
    }
}
