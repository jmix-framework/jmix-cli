package io.jmix.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.output.HelpFormatter
import com.github.ajalt.clikt.output.MordantHelpFormatter
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.mordant.rendering.TextColors.brightGreen
import com.github.ajalt.mordant.rendering.TextColors.brightRed
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.yellow
import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.widgets.SelectList
import io.jmix.cli.env.AgentToolkitInstaller
import io.jmix.cli.env.AgentToolkitMetadata
import io.jmix.cli.env.AgentToolkitStatus
import io.jmix.cli.env.ToolkitChoice
import io.jmix.cli.env.ToolkitMcpOptions
import io.jmix.cli.env.ToolkitResult
import io.jmix.cli.env.ToolkitStep
import io.jmix.cli.trial.TrialArchive
import io.jmix.cli.update.SelfUpdater
import io.jmix.cli.util.describeIoError
import io.jmix.cli.util.hostOf
import io.jmix.cli.wizard.Answer
import io.jmix.cli.wizard.PathCompleter
import io.jmix.cli.wizard.Prompts
import io.jmix.cli.wizard.StatusReporter
import io.jmix.cli.wizard.WizardChoice
import io.jmix.cli.wizard.WizardUiState
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Duration

internal typealias ToolkitInstall = (Path, String, Set<ToolkitStep>, List<String>?, List<String>?, (String) -> Unit) -> ToolkitResult
internal typealias ToolkitMcpLoader = (ref: String, timeout: Duration) -> ToolkitMcpOptions
internal typealias ToolkitStatusCheck = (projectDir: Path, ref: String) -> AgentToolkitStatus.Status?

/**
 * Adds or updates the Jmix Agent Toolkit in an existing project. The servers and agents it accepts and lists
 * are the ones the toolkit publishes for the project's branch; none are named here. [install], [mcpOptions],
 * [status] and [currentDir] let tests run it without the network, the installer or the real working directory.
 */
class ToolkitCommand internal constructor(
    private val install: ToolkitInstall,
    private val mcpOptions: ToolkitMcpLoader,
    private val status: ToolkitStatusCheck,
    private val currentDir: () -> Path = { Path.of("").toAbsolutePath().normalize() },
) : CliktCommand(name = "ai-toolkit") {

    constructor() : this(
        AgentToolkitInstaller::install, AgentToolkitMetadata::mcpOptions, { dir, ref -> AgentToolkitStatus.detect(dir, ref) },
    )

    init {
        // Not helpEpilog: Clikt evaluates it for every usage error, and only printed help may ask the toolkit.
        context {
            val inherited = helpFormatter ?: { MordantHelpFormatter(it) }
            helpFormatter = { ctx ->
                val formatter = inherited(ctx)
                object : HelpFormatter {
                    override fun formatHelp(
                        error: UsageError?,
                        prolog: String,
                        epilog: String,
                        parameters: List<HelpFormatter.ParameterHelp>,
                        programName: String,
                    ) = formatter.formatHelp(error, prolog, if (error == null) offeredChoices() else epilog, parameters, programName)
                }
            }
        }
    }

    override fun help(context: Context) = "Add or update AI Agent Toolkit: skills, guidelines, MCP servers"

    private val action by argument(
        help = "What to set up: all, skills, guidelines or mcp (default: ask; all with --non-interactive)",
    ).choice(*ACTIONS.keys.toTypedArray()).optional()
    private val path by option("--path", help = "Project directory (default: current directory)")
    private val jmixVersion by option(
        "--jmix-version", help = "Jmix version of the project (default: detected from the build file)",
    )
    private val servers by option(
        "--servers", help = "MCP servers to configure, comma-separated (default: all the toolkit offers)",
    )
    private val agents by option(
        "--agents", help = "AI agents to configure, comma-separated (default: all the toolkit offers)",
    )
    private val nonInteractive by option("--non-interactive", help = "Never prompt; use arguments and defaults").flag()

    // Acted on in main(), before parsing; declared so the parser accepts it after the command name.
    @Suppress("unused")
    private val noUpdate by option(SelfUpdater.NO_UPDATE_FLAG, help = "Skip the startup update check").flag()

    /** In the order they are resolved; a later one depends on the earlier ones. */
    private enum class Step { DIRECTORY, VERSION, ACTION, SERVERS, AGENTS }

    /** ASK: the step has a question, and questions are not open yet (see [asking]). */
    private enum class Outcome { PROMPTED, AUTO, BACK, ASK }

    /**
     * What the steps resolved so far. [hints] are the status hints of [dir] and [branch], detected once;
     * [options] is the metadata of [branch]; the two lists are set for the MCP step only.
     */
    private data class Answers(
        val dir: Path? = null,
        val version: String? = null,
        val branch: String? = null,
        val hints: Map<String, String>? = null,
        val action: String? = null,
        val options: ToolkitMcpOptions? = null,
        val servers: List<String>? = null,
        val agents: List<String>? = null,
    )

    private lateinit var reporter: StatusReporter
    private lateinit var prompts: Prompts
    private var answers = Answers()

    /** Off while the steps that need no question are resolved; the first question turns it on. */
    private var asking = false

    /** The questions answered before the current step: Back returns to the last one, and the summary lists them. */
    private var asked = emptyList<Step>()
    private var printedProject: WizardChoice? = null

    override fun run() {
        answers = Answers()
        asking = false
        asked = emptyList()
        printedProject = null
        reporter = StatusReporter(terminal)
        prompts = Prompts(terminal, exitOnEof = true) { WizardUiState(choices()) }
        action?.let(::rejectMcpOptionsWithout)
        val result = try {
            // Resolved before anything is written: the installer gets explicit, offered ids for the MCP step.
            // The alternate screen opens for the first question; a run that asks nothing stays on the main one.
            val firstQuestion = resolve(0)
            val restate = firstQuestion < Step.entries.size && prompts.useAlternateScreen {
                asking = true
                resolve(firstQuestion)
            }
            // The frames lived on the alternate screen, which the terminal has just discarded.
            if (restate) choices().forEach(::printChoice) else printProject()
            if (asked.isNotEmpty()) terminal.println()
            val setup = answers
            reporter.run("Setting up the Agent Toolkit") { progress ->
                install(
                    checkNotNull(setup.dir), checkNotNull(setup.version), ACTIONS.getValue(checkNotNull(setup.action)),
                    setup.servers, setup.agents, progress::relabel,
                )
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CliktError("Agent Toolkit setup was interrupted.")
        } catch (e: IOException) {
            // A failed step ends with the installer's own output, which is external text.
            val cause = describeIoError(e, hostOf(AgentToolkitInstaller.RAW_CONTENT_BASE)).filterNot(Char::isISOControl)
            throw CliktError("Agent Toolkit setup failed: $cause")
        }
        printResult(checkNotNull(answers.branch), result)
    }

    /**
     * Resolves every step from its argument or option, else by asking; a scripted run takes the default or fails.
     * Back returns to the previous question that was asked, and from the first one to the command menu.
     * Starts at step [from]; returns the step that has a question while [asking] is off, else the number of steps.
     */
    private fun resolve(from: Int): Int {
        var index = from
        while (index < Step.entries.size) {
            val step = Step.entries[index]
            asked = asked.filter { it < step }
            val outcome = when (step) {
                Step.DIRECTORY -> stepDirectory()
                Step.VERSION -> stepVersion()
                Step.ACTION -> stepAction()
                Step.SERVERS -> stepServers()
                Step.AGENTS -> stepAgents()
            }
            if (outcome == Outcome.ASK) return index
            if (outcome == Outcome.BACK) {
                index = (asked.lastOrNull() ?: throw ReturnToMainMenu()).ordinal
                continue
            }
            if (outcome == Outcome.PROMPTED) {
                asked = asked + step
                if (!prompts.isAlternateScreenHeld) answer(step)?.let(::printChoice)
            }
            index++
        }
        return index
    }

    private fun stepDirectory(): Outcome {
        answers = Answers()
        // Text that is no path is no project either; it is printed without its control characters.
        val given = path?.let { typedDir(it) ?: throw noProject(it.filterNot(Char::isISOControl)) } ?: currentDir()
        if (isGradleProject(given)) {
            answers = answers.copy(dir = given)
            return Outcome.AUTO
        }
        if (path != null || nonInteractive) throw noProject(given)
        if (!asking) return Outcome.ASK
        val typed = prompts.ask("Jmix project directory", allowBack = true, complete = { PathCompleter.complete(it) }) {
            val dir = typedDir(it)
            if (dir != null && isGradleProject(dir)) null else "No Gradle project found in ${dir ?: it}."
        }
        return when (typed) {
            is Answer.Back -> Outcome.BACK
            is Answer.Value -> {
                answers = answers.copy(dir = typedDir(typed.value))
                Outcome.PROMPTED
            }
        }
    }

    private fun stepVersion(): Outcome {
        val dir = checkNotNull(answers.dir)
        answers = Answers(dir = dir)
        val known = jmixVersion ?: detectedVersion(dir)
        if (known != null) {
            val branch = try {
                AgentToolkitInstaller.branch(known)
            } catch (e: IOException) {
                throw CliktError(e.message)
            }
            answers = answers.copy(version = known, branch = branch)
            return Outcome.AUTO
        }
        if (nonInteractive) throw CliktError("Cannot detect the Jmix version in $dir. Pass --jmix-version.")
        if (!asking) return Outcome.ASK
        val typed = prompts.ask("Jmix version of the project", allowBack = true) {
            if (branchOrNull(it) == null) "Enter a Jmix version such as 3.1.0." else null
        }
        return when (typed) {
            is Answer.Back -> Outcome.BACK
            is Answer.Value -> {
                answers = answers.copy(version = typed.value, branch = branchOrNull(typed.value))
                Outcome.PROMPTED
            }
        }
    }

    private fun stepAction(): Outcome {
        val given = action ?: ALL.takeIf { nonInteractive }
        val chosen = given ?: run {
            if (!asking) return Outcome.ASK
            printProject()
            val hints = answers.hints ?: statusHints().also { answers = answers.copy(hints = it) }
            val picked = prompts.choose(
                "What would you like to set up?", ACTIONS.keys.toList(), { it },
                { ACTION_HELP.getValue(it) + hints[it].orEmpty() }, allowBack = true,
            )
            when (picked) {
                is Answer.Back -> return Outcome.BACK
                is Answer.Value -> picked.value.also(::rejectMcpOptionsWithout)
            }
        }
        // Also drops the lists that an earlier choice of the MCP action left behind.
        answers = answers.copy(action = chosen, servers = null, agents = null)
        return if (given == null) Outcome.PROMPTED else Outcome.AUTO
    }

    private fun stepServers(): Outcome {
        val setup = answers
        if (ToolkitStep.MCP !in ACTIONS.getValue(checkNotNull(setup.action))) return Outcome.AUTO
        val options = setup.options ?: loadMcpOptions(checkNotNull(setup.branch), checkNotNull(setup.version))
        answers = setup.copy(options = options)
        return stepSelection(servers, options.servers, "MCP server") { answers = answers.copy(servers = it) }
    }

    private fun stepAgents(): Outcome {
        val setup = answers
        if (ToolkitStep.MCP !in ACTIONS.getValue(checkNotNull(setup.action))) return Outcome.AUTO
        return stepSelection(agents, checkNotNull(setup.options).agents, "AI agent") { answers = answers.copy(agents = it) }
    }

    /** One MCP list: what its option names, else every offered id; only the MCP action asks. */
    private fun stepSelection(raw: String?, offered: List<ToolkitChoice>, kind: String, store: (List<String>) -> Unit): Outcome {
        if (raw != null || nonInteractive || answers.action != MCP) {
            store(selection(raw, offered, kind))
            return Outcome.AUTO
        }
        if (!asking) return Outcome.ASK
        printProject()
        return when (val picked = askSelection(offered, kind)) {
            is Answer.Back -> Outcome.BACK
            is Answer.Value -> {
                store(picked.value)
                Outcome.PROMPTED
            }
        }
    }

    /** A checklist of the toolkit's labels; a console without raw mode gets one text question in the option's syntax. */
    private fun askSelection(offered: List<ToolkitChoice>, kind: String): Answer<List<String>> {
        val ids = offered.map { it.value }
        val entries = offered.map { SelectList.Entry(it.label, selected = true) }
        var legend: String? = null
        while (true) {
            val picked = prompts.chooseMany("Select ${kind}s", entries, allowBack = true, values = ids, legend = legend)
                ?: return askSelectionLine(ids, kind)
            // An empty pick is the one mistake a checklist allows; the question repeats with the reason above it.
            val error = (picked as? Answer.Value)?.let { selectionError(it.value.joinToString(","), ids, kind) }
                ?: return picked
            legend = brightRed(error)
        }
    }

    private fun askSelectionLine(ids: List<String>, kind: String): Answer<List<String>> {
        val typed = prompts.ask("${kind}s", ids.joinToString(","), allowBack = true) { selectionError(it, ids, kind) }
        return when (typed) {
            is Answer.Back -> Answer.Back
            is Answer.Value -> Answer.Value(parseSelection(typed.value, ids))
        }
    }

    /** What the action menu appends to a description; nothing for a state that could not be detected. */
    private fun statusHints(): Map<String, String> {
        val dir = checkNotNull(answers.dir)
        val branch = checkNotNull(answers.branch)
        val detected = try {
            reporter.run("Checking the Agent Toolkit") { status(dir, branch) }
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            null
        }
        return listOf(SKILLS to detected?.skills, GUIDELINES to detected?.guidelines)
            .mapNotNull { (action, state) -> state?.let { action to " · " + STATUS_HINTS.getValue(it) } }
            .toMap()
    }

    private fun rejectMcpOptionsWithout(action: String) {
        if (ToolkitStep.MCP !in ACTIONS.getValue(action) && (servers != null || agents != null)) {
            throw CliktError("--servers and --agents apply only to the mcp and all actions.")
        }
    }

    private fun isGradleProject(dir: Path) = GRADLE_FILES.any { Files.isRegularFile(dir.resolve(it)) }

    private fun noProject(dir: Any) =
        CliktError("No Gradle project found in $dir. Run `jmix ai-toolkit` from the project root or pass --path.")

    /** The directory that typed text names; null when it is no path at all. */
    private fun typedDir(raw: String): Path? = try {
        toAbsolutePath(raw)
    } catch (e: InvalidPathException) {
        null
    }

    /** The version in the build files of [dir]; one that names no toolkit branch counts as not detected. */
    private fun detectedVersion(dir: Path): String? = TrialArchive.jmixVersion(dir)?.takeIf { branchOrNull(it) != null }

    private fun branchOrNull(version: String): String? = try {
        AgentToolkitInstaller.branch(version)
    } catch (e: IOException) {
        null
    }

    private fun loadMcpOptions(branch: String, version: String): ToolkitMcpOptions = try {
        reporter.run("Loading the Agent Toolkit metadata") { mcpOptions(branch, METADATA_TIMEOUT) }
    } catch (e: FileNotFoundException) {
        throw IOException(AgentToolkitInstaller.missingBranchMessage(version))
    }

    /** The ids of [offered] that [raw] names; all of them without the option. */
    private fun selection(raw: String?, offered: List<ToolkitChoice>, kind: String): List<String> {
        val valid = offered.map { it.value }
        if (raw == null) return valid
        selectionError(raw, valid, kind)?.let { throw CliktError(it) }
        return parseSelection(raw, valid)
    }

    private fun project(): WizardChoice? = with(answers) {
        branch?.let { WizardChoice("Project", "$dir (Jmix $version, toolkit $it)") }
    }

    /** The summary line of a question; the directory and the version are in the project line. */
    private fun answer(step: Step): WizardChoice? = when (step) {
        Step.DIRECTORY, Step.VERSION -> null
        Step.ACTION -> answers.action?.let { WizardChoice("Action", it) }
        Step.SERVERS -> answers.servers?.let { WizardChoice("MCP servers", it.joinToString(", ")) }
        Step.AGENTS -> answers.agents?.let { WizardChoice("AI agents", it.joinToString(", ")) }
    }

    private fun choices(): List<WizardChoice> = listOfNotNull(project()) + asked.mapNotNull(::answer)

    private fun printChoice(choice: WizardChoice) =
        terminal.println(brightGreen("✓ ") + choice.label + ":" + cyan(" ${choice.value}"))

    /**
     * Prints the project line above the first question that follows it, else above the run, and again only
     * when Back changed the project. A failure before either prints none. A held screen draws it in its frames.
     */
    private fun printProject() {
        val project = project()
        if (project == null || project == printedProject || prompts.isAlternateScreenHeld) return
        printChoice(project)
        printedProject = project
    }

    private fun printResult(branch: String, result: ToolkitResult) {
        val outcome = if (result.changes.isEmpty()) "is already up to date" else "is set up"
        terminal.println(brightGreen("✓ Agent Toolkit $branch $outcome"))
        val width = maxOf(MIN_ACTION_WIDTH, result.changes.maxOfOrNull { it.action.length } ?: 0)
        for (change in result.changes) {
            val action = change.action.padEnd(width)
            terminal.println("  " + (ACTION_STYLES[change.action]?.invoke(action) ?: action) + "  " + change.path)
        }
        if (result.notes.isNotEmpty()) {
            terminal.println()
            terminal.println(bold("Notes from the Agent Toolkit:"))
            result.notes.forEach { terminal.println("  • $it") }
        }
    }

    /** The last paragraph of the help: what the toolkit offers now, for the project in the current directory if any. */
    private fun offeredChoices(): String = try {
        val ref = detectedVersion(currentDir())?.let(::branchOrNull) ?: DEFAULT_REF
        val options = mcpOptions(ref, HELP_TIMEOUT)
        val toolkit = if (ref == DEFAULT_REF) "Agent Toolkit" else "Agent Toolkit $ref"
        "MCP servers in $toolkit: ${options.servers.joinToString(", ") { it.value }}. " +
            "AI agents: ${options.agents.joinToString(", ") { it.value }}."
    } catch (e: Exception) {
        if (e is InterruptedException) Thread.currentThread().interrupt()
        "The current MCP servers and AI agents are listed when the Agent Toolkit is reachable."
    }

    private companion object {
        const val ALL = "all"
        const val SKILLS = "skills"
        const val GUIDELINES = "guidelines"
        const val MCP = "mcp"
        val ACTIONS = mapOf(
            ALL to ToolkitStep.entries.toSet(),
            SKILLS to setOf(ToolkitStep.SKILLS),
            GUIDELINES to setOf(ToolkitStep.GUIDELINES),
            MCP to setOf(ToolkitStep.MCP),
        )
        val ACTION_HELP = mapOf(
            ALL to "Install or update everything",
            SKILLS to "Update agent skills",
            GUIDELINES to "Generate AGENTS.md guidelines",
            MCP to "Configure MCP servers",
        )
        val STATUS_HINTS = mapOf(
            AgentToolkitStatus.State.NOT_INSTALLED to "not installed",
            AgentToolkitStatus.State.UP_TO_DATE to green("up to date"),
            AgentToolkitStatus.State.UPDATE_AVAILABLE to yellow("update available"),
        )
        val GRADLE_FILES = listOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")
        val ACTION_STYLES = mapOf<String, TextStyle>("created" to green, "updated" to cyan, "backed-up" to yellow)
        val MIN_ACTION_WIDTH = ACTION_STYLES.keys.maxOf { it.length }
        const val DEFAULT_REF = "HEAD"
        val METADATA_TIMEOUT: Duration = Duration.ofSeconds(15)
        val HELP_TIMEOUT: Duration = Duration.ofSeconds(3)
    }
}

/** The values of [valid] that [raw] names, in the order of [valid]. */
private fun parseSelection(raw: String, valid: List<String>): List<String> {
    val named = selectionValues(raw)
    return valid.filter { it.lowercase() in named }
}

/** Null when [raw] is a non-empty comma-separated subset of [valid]; else the message. [kind] is "MCP server" or "AI agent". */
private fun selectionError(raw: String, valid: List<String>, kind: String): String? {
    val named = selectionValues(raw)
    if (named.isEmpty()) return "Select at least one $kind."
    val unknown = named.firstOrNull { name -> valid.none { it.lowercase() == name } } ?: return null
    return "Unknown $kind: $unknown. Valid values: ${valid.joinToString(", ")}."
}

private fun selectionValues(raw: String): List<String> = raw.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
