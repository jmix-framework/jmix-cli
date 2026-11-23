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
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.mordant.rendering.TextColors.brightGreen
import com.github.ajalt.mordant.rendering.TextColors.brightRed
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.gray
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
import java.io.UncheckedIOException
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
 * [status], [currentDir] and [homeDir] let tests run it without the network, the installer or the real
 * working and home directories.
 */
class ToolkitCommand internal constructor(
    private val install: ToolkitInstall,
    private val mcpOptions: ToolkitMcpLoader,
    private val status: ToolkitStatusCheck,
    private val currentDir: () -> Path = { Path.of("").toAbsolutePath().normalize() },
    private val homeDir: () -> Path = { Path.of(System.getProperty("user.home")) },
) : CliktCommand(name = "agent-toolkit") {

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

    // The command menu shows it beside the name: kept short so the menu fits 80 columns (MainTest).
    override fun help(context: Context) = "Add or update AI Agent Toolkit: skills, guidelines, MCP"

    private val stepArgs by argument(
        "steps", help = "Steps to run: skills, guidelines, mcp (default: ask; all three with --non-interactive)",
    ).choice(STEP_IDS).multiple()
    private val path by option("--path", help = "Project directory (default: ask; current directory with --non-interactive)")
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
    private enum class Step { DIRECTORY, VERSION, STEPS, SERVERS, AGENTS }

    /** ASK: the step has a question, and questions are not open yet (see [asking]). */
    private enum class Outcome { PROMPTED, AUTO, BACK, ASK }

    /**
     * What the steps resolved so far. [status] is the state of [dir] for [branch], detected once;
     * [options] is the metadata of [branch]; the two lists are set when [steps] has the MCP step.
     */
    private data class Answers(
        val dir: Path? = null,
        val version: String? = null,
        val branch: String? = null,
        val status: AgentToolkitStatus.Status? = null,
        val steps: Set<ToolkitStep>? = null,
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

    /** The projects found nearby, looked for once per run. */
    private var projectList: List<ProjectOption>? = null

    override fun run() {
        answers = Answers()
        asking = false
        asked = emptyList()
        printedProject = null
        projectList = null
        reporter = StatusReporter(terminal)
        // A script or an agent without --non-interactive must not end with exit code 0 and nothing done.
        prompts = Prompts(terminal, exitOnEof = true, eofError = NO_INPUT) { WizardUiState(choices()) }
        rejectMcpOptionsWithout(stepArgs.toSet())
        // Before any question: the option needs no project, so a malformed one fails before the project list.
        jmixVersion?.let(::branchOf)
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
            val setup = answers
            val steps = checkNotNull(setup.steps)
            if (steps.isEmpty()) {
                terminal.println(NOTHING_SELECTED)
                return
            }
            if (asked.isNotEmpty()) terminal.println()
            reporter.run("Setting up the Agent Toolkit") { progress ->
                install(checkNotNull(setup.dir), checkNotNull(setup.version), steps, setup.servers, setup.agents, progress::relabel)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CliktError("Agent Toolkit setup was interrupted.")
        } catch (e: IOException) {
            // A failed step ends with the installer's own output, which is external text.
            val cause = describeIoError(e, hostOf(AgentToolkitInstaller.RAW_CONTENT_BASE)).filterNot(Char::isISOControl)
            throw CliktError("Agent Toolkit setup failed: $cause")
        }
        printResult(checkNotNull(answers.branch), checkNotNull(answers.steps), result)
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
                Step.STEPS -> stepSteps()
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

    /** `--path`, else the current directory in a scripted run; an interactive run always asks which project. */
    private fun stepDirectory(): Outcome {
        val previous = answers.dir
        answers = Answers()
        // Text that is no path is no project either; it is printed without its control characters.
        val given = path?.let { typedDir(it) ?: throw noProject(it) } ?: currentDir().takeIf { nonInteractive }
        if (given != null) {
            if (!isGradleProject(given)) throw noProject("$given")
            answers = answers.copy(dir = given)
            return Outcome.AUTO
        }
        if (!asking) return Outcome.ASK
        answers = answers.copy(dir = chooseProjectDir(previous) ?: return Outcome.BACK)
        return Outcome.PROMPTED
    }

    /**
     * The current directory when it is a Gradle project, the Jmix projects one level below it and in
     * ~/IdeaProjects, or a typed directory; [previous] is preselected. Null for Back. Back from the typed
     * directory shows the list again with Other... selected, unless Other... is the only entry: then the
     * list was never shown, and Back leaves the question.
     */
    private fun chooseProjectDir(previous: Path?): Path? {
        val found = projectList ?: reporter.run("Looking for Jmix projects") { projectOptions() }.also { projectList = it }
        val options = found + ProjectOption(OTHER_CHOICE, null)
        val listed = options.indexOfFirst { previous != null && it.dir == previous }
        val typedBefore = previous?.takeIf { listed < 0 }
        var defaultIndex = if (typedBefore != null) options.lastIndex else listed.coerceAtLeast(0)
        while (true) {
            val option = when (val picked = prompts.choose(
                "Select project directory", options, { it.title }, { it.description }, defaultIndex, allowBack = true,
            )) {
                is Answer.Back -> return null
                is Answer.Value -> picked.value
            }
            option.dir?.let { return it }
            val typed = prompts.ask(
                "Enter project directory", typedBefore?.toString(), allowBack = true, complete = { PathCompleter.complete(it) },
            ) {
                val dir = typedDir(it)
                when {
                    it.isBlank() -> "Path cannot be empty."
                    dir != null && isGradleProject(dir) -> null
                    else -> "No Gradle project found in ${shown(dir ?: it)}."
                }
            }
            when (typed) {
                is Answer.Value -> return typedDir(typed.value)
                is Answer.Back -> if (options.size == 1) return null else defaultIndex = options.lastIndex
            }
        }
    }

    private fun projectOptions(): List<ProjectOption> {
        val cwd = currentDir()
        val home = homeDir()
        val current = listOfNotNull(cwd.takeIf(::isGradleProject)?.let { ProjectOption(CURRENT_DIRECTORY, it, versionOrNull(it)) })
        // Not the folders of the home directory itself: on macOS, looking into Desktop or Documents asks for access.
        val parents = listOfNotNull(cwd.takeIf { realOrSelf(it) != realOrSelf(home) }, home.resolve(IDEA_PROJECTS_DIR))
        val nearby = parents
            .flatMap(::childProjects)
            .distinctBy(::realOrSelf)
            .filter { realOrSelf(it) != realOrSelf(cwd) }
            .mapNotNull { dir -> versionOrNull(dir)?.let { ProjectOption(dir.fileName.toString(), dir, it) } }
            .sortedBy { it.title.lowercase() }
        return current + nearby
    }

    /** The Gradle projects directly inside [parent], hidden folders and odd names aside; none when it cannot be listed. */
    private fun childProjects(parent: Path): List<Path> = try {
        Files.list(parent).use { entries ->
            entries.filter { child ->
                val name = child.fileName.toString()
                !name.startsWith(".") && name.none(Char::isISOControl) && Files.isDirectory(child) && isGradleProject(child)
            }.toList()
        }
    } catch (e: IOException) {
        emptyList()
    } catch (e: UncheckedIOException) {
        emptyList()
    }

    /** A folder the list cannot read is listed without a version, or not at all when only the version admits it. */
    private fun versionOrNull(dir: Path): String? = try {
        detectedVersion(dir)
    } catch (e: IOException) {
        null
    }

    private fun realOrSelf(path: Path): Path = try {
        path.toRealPath()
    } catch (e: IOException) {
        path
    }

    /** An entry of the project list; [dir] is null for Other.... */
    private class ProjectOption(val title: String, val dir: Path?, version: String? = null) {
        val description: String? = dir?.let { shown(it) + version?.let { v -> " (Jmix $v)" }.orEmpty() }
    }

    private fun stepVersion(): Outcome {
        val dir = checkNotNull(answers.dir)
        // Back to this question offers the version typed before; a project chosen again has none.
        val typedBefore = answers.version
        answers = Answers(dir = dir)
        val known = jmixVersion ?: detectedVersion(dir)
        if (known != null) {
            answers = answers.copy(version = known, branch = branchOf(known))
            return Outcome.AUTO
        }
        if (nonInteractive) throw CliktError("Cannot detect the Jmix version in ${shown(dir)}. Pass --jmix-version.")
        if (!asking) return Outcome.ASK
        printProject()
        val typed = prompts.ask("Jmix version of the project", typedBefore, allowBack = true) {
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

    /** The steps the arguments name, else all of them in a scripted run; an interactive run asks. */
    private fun stepSteps(): Outcome {
        val given = stepArgs.toSet().ifEmpty { null } ?: ToolkitStep.entries.toSet().takeIf { nonInteractive }
        val chosen = given ?: run {
            if (!asking) return Outcome.ASK
            printProject()
            when (val picked = askSteps()) {
                is Answer.Back -> return Outcome.BACK
                is Answer.Value -> picked.value
            }
        }
        // The MCP lists chosen before are offered again while the MCP step stays chosen, and dropped without it.
        val mcp = ToolkitStep.MCP in chosen
        answers = answers.copy(steps = chosen, servers = answers.servers.takeIf { mcp }, agents = answers.agents.takeIf { mcp })
        return if (given == null) Outcome.PROMPTED else Outcome.AUTO
    }

    /**
     * A checklist of the steps with the state of each. A step that is up to date starts unchecked, one whose
     * state is unknown starts checked, and Back to it keeps the earlier choice. MCP stays checked when
     * `--servers` or `--agents` is given. An empty choice is allowed: it runs nothing.
     */
    private fun askSteps(): Answer<Set<ToolkitStep>> {
        // The MCP questions reuse the metadata the status was found with.
        val status = answers.status ?: detectStatus().also { answers = answers.copy(status = it, options = it.mcpOptions) }
        val states = mapOf(ToolkitStep.SKILLS to status.skills, ToolkitStep.GUIDELINES to status.guidelines, ToolkitStep.MCP to status.mcp)
        val steps = ToolkitStep.entries
        val ids = steps.map(::stepId)
        val width = ids.maxOf { it.length }
        val locked = setOfNotNull(steps.indexOf(ToolkitStep.MCP).takeIf { servers != null || agents != null })
        val entries = steps.mapIndexed { index, step ->
            val state = states[step]
            val hint = state?.let { " · " + STATUS_HINTS.getValue(it) }.orEmpty()
            val selected = index in locked || (answers.steps?.let { step in it } ?: (state != AgentToolkitStatus.State.UP_TO_DATE))
            SelectList.Entry(ids[index].padEnd(width) + gray(" — " + STEP_HELP.getValue(step) + hint), selected = selected)
        }
        val legend = ALL_UP_TO_DATE.takeIf { entries.none { it.selected } }
        val picked = prompts.chooseMany(STEPS_QUESTION, entries, allowBack = true, lockedIndices = locked, values = ids, legend = legend)
            // Without raw mode only a searchable list falls back to a numbered checklist.
            ?: checkNotNull(prompts.chooseMany(
                STEPS_QUESTION, entries, allowBack = true, filterTexts = ids, lockedIndices = locked, values = ids, legend = legend,
            ))
        return when (picked) {
            is Answer.Back -> Answer.Back
            is Answer.Value -> Answer.Value(steps.filter { stepId(it) in picked.value }.toSet())
        }
    }

    /** The state of each step in the project; unknown when the toolkit gives no answer. */
    private fun detectStatus(): AgentToolkitStatus.Status {
        val dir = checkNotNull(answers.dir)
        val branch = checkNotNull(answers.branch)
        return try {
            reporter.run("Checking the Agent Toolkit") { status(dir, branch) }
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            null
        } ?: UNKNOWN_STATUS
    }

    private fun stepServers(): Outcome {
        val setup = answers
        if (ToolkitStep.MCP !in checkNotNull(setup.steps)) return Outcome.AUTO
        val options = setup.options ?: loadMcpOptions(checkNotNull(setup.branch), checkNotNull(setup.version))
        answers = setup.copy(options = options)
        return stepSelection(servers, options.servers, setup.servers, "MCP server") { answers = answers.copy(servers = it) }
    }

    private fun stepAgents(): Outcome {
        val setup = answers
        if (ToolkitStep.MCP !in checkNotNull(setup.steps)) return Outcome.AUTO
        return stepSelection(agents, checkNotNull(setup.options).agents, setup.agents, "AI agent") { answers = answers.copy(agents = it) }
    }

    /**
     * One MCP list: what its option names, else every offered id in a scripted run; an interactive run asks.
     * [previous] is the earlier answer, kept when Back returns to the question.
     */
    private fun stepSelection(
        raw: String?, offered: List<ToolkitChoice>, previous: List<String>?, kind: String, store: (List<String>) -> Unit,
    ): Outcome {
        if (raw != null || nonInteractive) {
            store(selection(raw, offered, kind))
            return Outcome.AUTO
        }
        if (!asking) return Outcome.ASK
        printProject()
        return when (val picked = askSelection(offered, previous, kind)) {
            is Answer.Back -> Outcome.BACK
            is Answer.Value -> {
                store(picked.value)
                Outcome.PROMPTED
            }
        }
    }

    /**
     * A checklist of the toolkit's labels with [previous] checked, or every entry when there is none; a console
     * without raw mode gets one text question in the option's syntax.
     */
    private fun askSelection(offered: List<ToolkitChoice>, previous: List<String>?, kind: String): Answer<List<String>> {
        val ids = offered.map { it.value }
        val entries = offered.map { SelectList.Entry(it.label, selected = previous?.contains(it.value) ?: true) }
        var legend: String? = null
        while (true) {
            val picked = prompts.chooseMany("Select ${kind}s", entries, allowBack = true, values = ids, legend = legend)
                ?: return askSelectionLine(ids, previous ?: ids, kind)
            // An empty pick is the one mistake a checklist allows; the question repeats with the reason above it.
            val error = (picked as? Answer.Value)?.let { selectionError(it.value.joinToString(","), ids, kind) }
                ?: return picked
            legend = brightRed(error)
        }
    }

    private fun askSelectionLine(ids: List<String>, default: List<String>, kind: String): Answer<List<String>> {
        val typed = prompts.ask("${kind}s", default.joinToString(","), allowBack = true) { selectionError(it, ids, kind) }
        return when (typed) {
            is Answer.Back -> Answer.Back
            is Answer.Value -> Answer.Value(parseSelection(typed.value, ids))
        }
    }

    private fun rejectMcpOptionsWithout(steps: Set<ToolkitStep>) {
        if (steps.isNotEmpty() && ToolkitStep.MCP !in steps && (servers != null || agents != null)) {
            throw CliktError("--servers and --agents apply only to the mcp step.")
        }
    }

    private fun isGradleProject(dir: Path) = GRADLE_FILES.any { Files.isRegularFile(dir.resolve(it)) }

    /** [dir] can be text that is no path at all; it is printed without its control characters. */
    private fun noProject(dir: String) =
        CliktError("No Gradle project found in ${shown(dir)}. Run `jmix agent-toolkit` from the project root or pass --path.")

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

    /** The toolkit branch of [version]; a version that names none fails the command. */
    private fun branchOf(version: String): String = try {
        AgentToolkitInstaller.branch(version)
    } catch (e: IOException) {
        throw CliktError(e.message?.let(::shown))
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

    /** The project with its version and branch once they are known; before that the directory alone. */
    private fun project(): WizardChoice? = with(answers) {
        dir?.let { WizardChoice("Project", shown(it) + branch?.let { b -> " (Jmix ${shown(version.orEmpty())}, toolkit $b)" }.orEmpty()) }
    }

    /** The summary line of a question; the directory and the version are in the project line. */
    private fun answer(step: Step): WizardChoice? = when (step) {
        Step.DIRECTORY, Step.VERSION -> null
        Step.STEPS -> answers.steps?.ifEmpty { null }?.let { WizardChoice("Steps", it.sorted().joinToString(", ", transform = ::stepId)) }
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

    /** The headline speaks for the [steps] that ran: a run of some of them says nothing about the others. */
    private fun printResult(branch: String, steps: Set<ToolkitStep>, result: ToolkitResult) {
        val unchanged = result.changes.isEmpty()
        val headline = if (steps == ToolkitStep.entries.toSet()) {
            "Agent Toolkit $branch " + if (unchanged) "is already up to date" else "is set up"
        } else {
            val names = ToolkitStep.entries.filter { it in steps }.joinToString(", ", transform = ::stepId)
            "Agent Toolkit $branch: $names " + if (unchanged) "already up to date" else "set up"
        }
        terminal.println(brightGreen("✓ $headline"))
        val width = maxOf(MIN_ACTION_WIDTH, result.changes.maxOfOrNull { it.action.length } ?: 0)
        for (change in result.changes) {
            val action = change.action.padEnd(width)
            terminal.println("  " + (ACTION_STYLES[change.action]?.invoke(action) ?: action) + "  " + change.path)
        }
        if (result.notes.isNotEmpty()) {
            terminal.println()
            terminal.println(bold("Notes from the Agent Toolkit:"))
            val width = noteWidth()
            result.notes.forEach { printNote(it, width) }
        }
    }

    /**
     * The width a terminal breaks notes at, under their bullet. Null for piped output, which keeps one line per
     * note for scripts, and for a terminal that reports no size.
     */
    private fun noteWidth(): Int? {
        if (!terminal.terminalInfo.outputInteractive) return null
        terminal.updateSize()
        val columns = terminal.size.width
        return if (columns > 0) (columns - 1 - NOTE_BULLET.length).coerceAtLeast(MIN_NOTE_WIDTH) else null
    }

    private fun printNote(note: String, width: Int?) {
        val lines = width?.let { wrapWords(note, it) } ?: listOf(note)
        lines.forEachIndexed { index, line -> terminal.println((if (index == 0) NOTE_BULLET else NOTE_INDENT) + line) }
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
        val STEP_IDS: Map<String, ToolkitStep> = ToolkitStep.entries.associateBy(::stepId)
        val STEP_HELP = mapOf(
            ToolkitStep.SKILLS to "Update agent skills",
            ToolkitStep.GUIDELINES to "Generate AGENTS.md guidelines",
            ToolkitStep.MCP to "Configure MCP servers",
        )
        const val STEPS_QUESTION = "What would you like to set up?"
        const val ALL_UP_TO_DATE = "Everything is up to date. Select a step to run it anyway."
        const val NOTHING_SELECTED = "Nothing selected, so nothing was changed."
        val UNKNOWN_STATUS = AgentToolkitStatus.Status(null, null, null)
        val STATUS_HINTS = mapOf(
            AgentToolkitStatus.State.NOT_INSTALLED to "not installed",
            AgentToolkitStatus.State.UP_TO_DATE to green("up to date"),
            AgentToolkitStatus.State.UPDATE_AVAILABLE to yellow("update available"),
        )
        val GRADLE_FILES = listOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")
        val ACTION_STYLES = mapOf<String, TextStyle>("created" to green, "updated" to cyan, "backed-up" to yellow)
        val MIN_ACTION_WIDTH = ACTION_STYLES.keys.maxOf { it.length }
        const val NOTE_BULLET = "  • "
        const val NOTE_INDENT = "    "
        const val MIN_NOTE_WIDTH = 20
        const val DEFAULT_REF = "HEAD"
        const val CURRENT_DIRECTORY = "Current directory"
        const val OTHER_CHOICE = "Other..."
        const val NO_INPUT = "No input to answer the questions, so nothing was set up. " +
            "Use --non-interactive, or pass the answers as arguments and options."
        val METADATA_TIMEOUT: Duration = Duration.ofSeconds(15)
        val HELP_TIMEOUT: Duration = Duration.ofSeconds(3)
    }
}

/** The word for [step] on the command line and in the summary. */
private fun stepId(step: ToolkitStep): String = step.name.lowercase()

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

/** Paths and names from the disk or the command line, printed without their control characters. */
private fun shown(text: Any): String = "$text".filterNot(Char::isISOControl)

/** [text] in lines of at most [width] characters, broken at spaces; a longer word gets a line of its own. */
internal fun wrapWords(text: String, width: Int): List<String> =
    text.split(' ').filter(String::isNotEmpty).fold(emptyList()) { lines, word ->
        val last = lines.lastOrNull()
        if (last != null && last.length + 1 + word.length <= width) lines.dropLast(1) + "$last $word" else lines + word
    }
