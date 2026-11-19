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
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
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
        homeDir = { home },
    )

    /** An empty home, so the project list never looks into the real ~/IdeaProjects. */
    private val home: Path get() = dir.resolve("home")

    private fun project(build: String = "plugins { id 'io.jmix' version '3.1.0' }"): Path =
        Files.createDirectories(dir.resolve("demo")).also { Files.writeString(it.resolve("build.gradle"), build) }

    private fun jmixProject(parent: Path, name: String, version: String = "3.1.0"): Path =
        Files.createDirectories(parent.resolve(name)).also {
            Files.writeString(it.resolve("build.gradle"), "plugins { id 'io.jmix' version '$version' }")
        }

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
    private fun raw(vararg args: String, keys: List<String>, cwd: Path = dir) = assertTimeoutPreemptively(Duration.ofSeconds(10)) {
        command(cwd).test(
            args.toList(), ansiLevel = AnsiLevel.TRUECOLOR, width = 100, height = 30, inputEvents = keys.map { KeyboardEvent(it) },
        )
    }

    @Test
    fun `without steps a scripted run sets up everything the toolkit offers`() {
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
    @CsvSource(
        "skills,SKILLS", "guidelines,GUIDELINES", "mcp,MCP", "mcp skills,SKILLS|MCP",
        "guidelines guidelines,GUIDELINES", "skills guidelines mcp,SKILLS|GUIDELINES|MCP",
    )
    fun `the named steps run once each and metadata is loaded only for MCP`(named: String, steps: String) {
        run(*named.split(' ').toTypedArray(), "--path", project().toString())
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
        val agents = run("--agents", "cline", "--path", project().toString())
        assertTrue(flat(agents.stderr).contains("Unknown AI agent: cline. Valid values: claude, codex, opencode, junie."))
        val empty = run("mcp", "--servers", ",", "--path", project().toString())
        assertTrue(empty.stderr.contains("Select at least one MCP server."))
        assertEquals(listOf(1, 1, 1), listOf(servers, agents, empty).map { it.statusCode })
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `servers and agents apply only to the mcp step`() {
        val run = run("skills", "guidelines", "--servers", "jmix-docs", "--path", project().toString())
        assertEquals(1, run.statusCode)
        assertEquals("--servers and --agents apply only to the mcp step.", flat(run.stderr).trim())
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
        val out = run("--path", project().toString()).stdout
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
    @ValueSource(strings = ["skills mcp", "mcp"])
    fun `a Jmix major without a toolkit branch is named when its metadata is missing`(steps: String) {
        metadataFailure = FileNotFoundException("Cannot load .studio/studio-meta-data.json from Agent Toolkit v4: HTTP 404")
        val run = run(*steps.split(' ').toTypedArray(), "--jmix-version", "4.0.0", "--path", project().toString())
        assertEquals(1, run.statusCode)
        assertEquals("Agent Toolkit setup failed: the toolkit has no v4 branch for Jmix 4 yet", run.stderr.trim())
        // The metadata is loaded while the run is resolved, before the project line.
        assertFalse(run.stdout.contains("Project:"))
        assertTrue(calls.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["skills", "guidelines"])
    fun `a step fails below the project line`(step: String) {
        failure = IOException("no script")
        val project = project()
        val run = run(step, "--jmix-version", "4.0.0", "--path", project.toString())
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
        val asked = lines("3.1.0\n$SKILLS_ONLY", "--path", odd.toString())
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
        assertTrue(flat(help.output).contains("Steps to run: skills, guidelines, mcp"))
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
    fun `numbered checklist runs the checked steps and Enter keeps the preselected ones`() {
        val project = project().toString()
        val skills = lines(SKILLS_ONLY, "--path", project)
        assertEquals(0, skills.statusCode, skills.stderr)
        assertTrue(skills.output.contains("✓ Steps: skills"))
        // Every state is unknown here, so every step starts checked; MCP then asks for its two lists.
        lines("\n\n\n", "--path", project)
        assertEquals(listOf(setOf(ToolkitStep.SKILLS), ToolkitStep.entries.toSet()), calls.map { it.steps })
        assertEquals(agentIds, calls.last().agents)
    }

    @Test
    fun `line input reads the MCP lists in option syntax and repeats an invalid one`() {
        val run = lines("1,2\n\ncontext7\nplaywright,jmix-docs\n\n", "--path", project().toString())
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
        // mcp alone, Back from the servers; the checklist keeps mcp checked, so skills on and mcp off.
        lines("1,2\n\n<\n1,3\n\n", "--path", project().toString())
        assertEquals(setOf(ToolkitStep.SKILLS), calls.single().steps)
        assertThrows<ReturnToMainMenu> { lines("<\n", "--path", project().toString()) }
        assertEquals(1, calls.size)
    }

    @Test
    fun `q quits and the end of input fails, both with nothing run`() {
        assertEquals(0, lines("q\n", "--path", project().toString()).statusCode)
        val noInput = "No input to answer the questions, so nothing was set up. " +
            "Use --non-interactive, or pass the answers as arguments and options."
        for (eof in listOf(lines("", "--path", project().toString()), lines("", "skills", cwd = project()))) {
            assertEquals(1, eof.statusCode)
            assertTrue(flat(eof.stderr).contains(noInput), eof.stderr)
        }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `the project list offers the current directory, nearby Jmix projects and Other`() {
        val work = jmixProject(dir, "work", "2.8.3")
        val inner = jmixProject(work, "inner")
        jmixProject(work, ".hidden")
        Files.createDirectories(work.resolve("plain")).also { Files.writeString(it.resolve("settings.gradle"), "") }
        val alpha = jmixProject(home.resolve("IdeaProjects"), "alpha")
        val out = flat(lines("q\n", cwd = work).output)
        val rows = listOf(
            "1) Current directory — $work (Jmix 2.8.3)", "2) alpha — $alpha (Jmix 3.1.0)", "3) inner — $inner (Jmix 3.1.0)", "4) Other...",
        ).map(out::indexOf)
        assertTrue(rows.all { it >= 0 } && rows == rows.sorted(), out)
        assertFalse(out.contains(".hidden"))
        assertFalse(out.contains("${work.resolve("plain")}"))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `with nothing to list the directory is typed until it is a Gradle project`() {
        val project = project()
        val empty = Files.createDirectories(dir.resolve("empty"))
        val run = lines("\n$empty\n$project\n$SKILLS_ONLY", cwd = empty)
        assertTrue(run.output.contains("Path cannot be empty."))
        assertTrue(flat(run.output).contains("No Gradle project found in $empty."))
        assertEquals(project, calls.single().dir)
    }

    @Test
    fun `Back from a typed directory shows the list again, and Back from the list leaves the command`() {
        val work = Files.createDirectories(dir.resolve("work"))
        val alpha = jmixProject(work, "alpha")
        // Other..., Back to the list, alpha, then skills.
        lines("2\n<\n1\n$SKILLS_ONLY", cwd = work)
        assertEquals(alpha, calls.single().dir)
        assertThrows<ReturnToMainMenu> { lines("<\n", cwd = work) }
        assertEquals(1, calls.size)
    }

    @Test
    fun `the project line follows the project chosen after going back`() {
        val work = Files.createDirectories(dir.resolve("work"))
        val alpha = jmixProject(work, "alpha")
        val beta = jmixProject(work, "beta", "2.8.3")
        val run = lines("1\n<\n2\n$SKILLS_ONLY", cwd = work)
        assertTrue(flat(run.output).contains("Project: $alpha (Jmix 3.1.0, toolkit v3)"))
        assertTrue(flat(run.output).contains("Project: $beta (Jmix 2.8.3, toolkit v2)"))
        assertEquals(beta, calls.single().dir)
    }

    @Test
    fun `going back to the project list keeps the chosen project selected`() {
        val work = Files.createDirectories(dir.resolve("work"))
        jmixProject(work, "alpha")
        val beta = jmixProject(work, "beta")
        val typed = project()
        // beta, Back from the checklist, Enter keeps beta, then skills; the folders are looked into once.
        val run = lines("2\n<\n\n$SKILLS_ONLY", cwd = work)
        assertEquals(1, Regex("Looking for Jmix projects").findAll(run.output).count(), run.output)
        // A typed directory comes back as the answer prefilled under Other....
        lines("3\n$typed\n<\n\n\n$SKILLS_ONLY", cwd = work)
        assertEquals(listOf(beta, typed), calls.map { it.dir })
    }

    @Test
    fun `raw project list picks a nearby project and Esc leaves the command`() {
        val work = Files.createDirectories(dir.resolve("work"))
        jmixProject(work, "alpha")
        val beta = jmixProject(work, "beta")
        // beta, then guidelines and mcp unchecked.
        val run = raw(keys = listOf("ArrowDown", "Enter", "ArrowDown", " ", "ArrowDown", " ", "Enter"), cwd = work)
        assertEquals(0, run.statusCode, run.stderr)
        assertTrue(run.output.contains("Select project directory"))
        assertEquals(beta to setOf(ToolkitStep.SKILLS), calls.single().let { it.dir to it.steps })
        assertThrows<ReturnToMainMenu> { raw(keys = listOf("Escape"), cwd = work) }
        assertEquals(1, calls.size)
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
    fun `MCP options keep the MCP step checked`() {
        // Toggling all three leaves mcp checked; the agents are still asked.
        val run = lines("1,2,3\n\n\n", "--servers", "jmix-docs", "--path", project().toString())
        assertEquals(0, run.statusCode, run.stderr)
        assertTrue(flat(run.output).contains("(included) mcp"))
        assertEquals(Triple(setOf(ToolkitStep.MCP), listOf("jmix-docs"), agentIds), calls.single().let { Triple(it.steps, it.servers, it.agents) })
    }

    @Test
    fun `an undetected version is asked for`() {
        val bare = Files.createDirectories(dir.resolve("bare")).also { Files.writeString(it.resolve("settings.gradle"), "") }
        val run = lines("latest\n3.1.0\n$SKILLS_ONLY", "--path", bare.toString())
        assertTrue(run.output.contains("Enter a Jmix version such as 3.1.0."))
        assertEquals("3.1.0", calls.single().version)
        // The directory is named above the question, before its version is known.
        val out = flat(run.output)
        assertTrue(out.indexOf("✓ Project: $bare") in 0 until out.indexOf("Jmix version of the project"), out)
    }

    @Test
    fun `after Back from a typed directory the list keeps Other selected`() {
        val work = Files.createDirectories(dir.resolve("work"))
        jmixProject(work, "alpha")
        val typed = project()
        // Other..., Back to the list, Enter keeps Other..., the directory, then skills.
        lines("2\n<\n\n$typed\n$SKILLS_ONLY", cwd = work)
        assertEquals(typed, calls.single().dir)
    }

    @Test
    fun `Back from the version question returns to the project list`() {
        val work = Files.createDirectories(dir.resolve("work"))
        val alpha = jmixProject(work, "alpha")
        val bare = Files.createDirectories(dir.resolve("bare")).also { Files.writeString(it.resolve("settings.gradle"), "") }
        // Other..., the project without a version, Back from its version question, then alpha and skills.
        val run = lines("2\n$bare\n<\n1\n$SKILLS_ONLY", cwd = work)
        assertTrue(run.output.contains("Jmix version of the project"))
        assertEquals(alpha, calls.single().dir)
    }

    @Test
    fun `a project below the current directory and in IdeaProjects is listed once`() {
        val ideaProjects = home.resolve("IdeaProjects")
        jmixProject(ideaProjects, "alpha")
        val out = flat(lines("q\n", cwd = ideaProjects).output)
        assertEquals(1, Regex("""\d\) alpha —""").findAll(out).count(), out)
    }

    @Test
    fun `the current directory is not listed again among the projects next to it`() {
        val ideaProjects = home.resolve("IdeaProjects")
        val demo = jmixProject(ideaProjects, "demo")
        jmixProject(ideaProjects, "alpha")
        val out = flat(lines("q\n", cwd = demo).output)
        assertTrue(out.contains("1) Current directory — $demo (Jmix 3.1.0)"), out)
        assertFalse(out.contains("demo — "), out)
        assertTrue(out.contains("2) alpha — "), out)
    }

    @Test
    fun `with Other the only entry Back leaves the command`() {
        assertThrows<ReturnToMainMenu> { lines("<\n", cwd = Files.createDirectories(dir.resolve("empty"))) }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `started in the home directory the list does not look into its folders`() {
        jmixProject(home, "pet")
        val alpha = jmixProject(home.resolve("IdeaProjects"), "alpha")
        val out = flat(lines("q\n", cwd = home).output)
        assertTrue(out.contains("alpha — $alpha"), out)
        assertFalse(out.contains("pet —"), out)
    }

    @Test
    fun `a folder whose name has a control character is not listed`() {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
        val work = Files.createDirectories(dir.resolve("work"))
        jmixProject(work, "bell\u0007")
        jmixProject(work, "alpha")
        val out = lines("q\n", cwd = work).output
        assertTrue(out.contains("alpha"))
        assertFalse(out.contains("bell"))
    }

    @Test
    fun `the checklist shows each state and leaves only the up-to-date steps unchecked`() {
        status = AgentToolkitStatus.Status(UPDATE_AVAILABLE, UP_TO_DATE, NOT_INSTALLED)
        val out = flat(lines("q\n", "--path", project().toString()).output)
        assertTrue(out.contains("[x] 1) skills — Update agent skills · update available"), out)
        assertTrue(out.contains("[ ] 2) guidelines — Generate AGENTS.md guidelines · up to date"), out)
        assertTrue(out.contains("[x] 3) mcp — Configure MCP servers · not installed"), out)
        assertFalse(out.contains("Everything is up to date"))
        status = AgentToolkitStatus.Status(NOT_INSTALLED, null, null)
        val partial = flat(lines("q\n", "--path", project().toString()).output)
        assertTrue(partial.contains("[x] 1) skills — Update agent skills · not installed"), partial)
        assertTrue(partial.contains("[x] 2) guidelines — Generate AGENTS.md guidelines [x] 3)"), partial)
        status = null
        assertFalse(lines("q\n", "--path", project().toString()).output.contains(" · "))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `Enter keeps the preselected steps, which leave out the up-to-date ones`() {
        status = AgentToolkitStatus.Status(UP_TO_DATE, UPDATE_AVAILABLE, null)
        val run = lines("\n\n\n", "--path", project().toString())
        assertEquals(0, run.statusCode, run.stderr)
        assertEquals(setOf(ToolkitStep.GUIDELINES, ToolkitStep.MCP), calls.single().steps)
    }

    @Test
    fun `the MCP lists are asked when mcp is chosen with other steps`() {
        val run = lines("\njmix-docs\nclaude\n", "--path", project().toString())
        assertEquals(0, run.statusCode, run.stderr)
        val call = calls.single()
        assertEquals(Triple(ToolkitStep.entries.toSet(), listOf("jmix-docs"), listOf("claude")), Triple(call.steps, call.servers, call.agents))
    }

    @Test
    fun `the MCP questions use the metadata the status was found with`() {
        status = AgentToolkitStatus.Status(null, null, null, options)
        lines("1,2\n\n\n\n", "--path", project().toString())
        assertEquals(setOf(ToolkitStep.MCP), calls.single().steps)
        assertTrue(refs.isEmpty(), "$refs")
    }

    @Test
    fun `with MCP options an up-to-date project gets no legend, and mcp runs alone`() {
        status = AgentToolkitStatus.Status(UP_TO_DATE, UP_TO_DATE, UP_TO_DATE)
        val run = lines("\n\n", "--servers", "jmix-docs", "--path", project().toString())
        assertEquals(0, run.statusCode, run.stderr)
        val out = flat(run.output)
        assertTrue(out.contains("[x] 3) (included) mcp"), out)
        assertFalse(out.contains("Everything is up to date"), out)
        assertEquals(setOf(ToolkitStep.MCP), calls.single().steps)
    }

    @Test
    fun `an empty choice in a raw terminal is reported after the questions`() {
        status = AgentToolkitStatus.Status(UP_TO_DATE, UP_TO_DATE, UP_TO_DATE)
        val project = project()
        val run = raw("--path", project.toString(), keys = listOf("Enter"))
        assertEquals(0, run.statusCode, run.stderr)
        val afterQuestions = ANSI_SEQUENCE.replace(run.output.substringAfterLast(EXIT_ALTERNATE_SCREEN), "")
        val positions = listOf("✓ Project: $project (Jmix 3.1.0, toolkit v3)", "Nothing selected, so nothing was changed.")
            .map(afterQuestions::indexOf)
        assertTrue(positions.all { it >= 0 } && positions == positions.sorted(), afterQuestions)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `with everything up to date nothing is checked, and an empty choice changes nothing`() {
        status = AgentToolkitStatus.Status(UP_TO_DATE, UP_TO_DATE, UP_TO_DATE)
        val run = lines("\n", "--path", project().toString())
        assertEquals(0, run.statusCode, run.stderr)
        val out = flat(run.output)
        assertTrue(out.contains("Everything is up to date. Select a step to run it anyway."), out)
        assertTrue(out.contains("Nothing selected, so nothing was changed."), out)
        assertFalse(out.contains("✓ Steps:"), out)
        assertTrue(calls.isEmpty())
        assertTrue(refs.isEmpty())
        // A step can still be run on purpose.
        lines("1\n\n", "--path", project().toString())
        assertEquals(setOf(ToolkitStep.SKILLS), calls.single().steps)
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
        assertEquals(0, statusCalls)
        assertTrue(raw("--path", project, keys = listOf("Enter", "Enter", "Enter")).output.contains(ENTER_ALTERNATE_SCREEN))
    }

    @Test
    fun `raw Esc returns to the previous question and from the first one to the command menu`() {
        val project = project().toString()
        // mcp alone, the servers; Esc from the agents, Esc from the servers; the checklist keeps mcp, so skills instead.
        val keys = listOf(
            " ", "ArrowDown", " ", "Enter", "Enter", "Escape", "Escape", " ", "ArrowDown", "ArrowDown", " ", "Enter",
        )
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
        val keys = listOf(" ", "ArrowDown", " ", "Enter", "Enter", "ArrowDown", " ", "Enter")
        val run = raw("--path", project.toString(), keys = keys)
        val afterQuestions = ANSI_SEQUENCE.replace(run.output.substringAfterLast(EXIT_ALTERNATE_SCREEN), "")
        val positions = listOf(
            "✓ Project: $project (Jmix 3.1.0, toolkit v3)", "✓ Steps: mcp", "✓ MCP servers: jetbrains, jmix-docs, playwright",
            "✓ AI agents: claude, opencode, junie", "✓ Agent Toolkit v3 is already up to date",
        ).map(afterQuestions::indexOf)
        assertTrue(positions.all { it >= 0 } && positions == positions.sorted(), afterQuestions)
    }

    @Test
    fun `back to the checklist does not detect the status again`() {
        lines("1,2\n\n<\n1,3\n\n", "--path", project().toString())
        assertEquals(setOf(ToolkitStep.SKILLS), calls.single().steps)
        assertEquals(1, statusCalls)
    }

    @Test
    fun `a status check that throws still shows the checklist, without hints`() {
        statusFailure = IllegalStateException("boom")
        val run = lines(SKILLS_ONLY, "--path", project().toString())
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
        /** In the numbered checklist with every step checked: unchecks guidelines and mcp, then confirms. */
        const val SKILLS_ONLY = "2,3\n\n"
        val UP_TO_DATE = AgentToolkitStatus.State.UP_TO_DATE
        val UPDATE_AVAILABLE = AgentToolkitStatus.State.UPDATE_AVAILABLE
        val NOT_INSTALLED = AgentToolkitStatus.State.NOT_INSTALLED
        const val ENTER_ALTERNATE_SCREEN = "\u001B[?1049h"
        const val EXIT_ALTERNATE_SCREEN = "\u001B[?1049l"
    }
}
