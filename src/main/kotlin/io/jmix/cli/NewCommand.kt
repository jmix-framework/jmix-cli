package io.jmix.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.rendering.TextColors.brightGreen
import com.github.ajalt.mordant.rendering.TextColors.brightMagenta
import com.github.ajalt.mordant.rendering.TextColors.brightYellow
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.SelectList
import io.jmix.cli.addon.AddonCatalog
import io.jmix.cli.addon.AddonProjectProfile
import io.jmix.cli.addon.ResolvedAddon
import io.jmix.cli.addon.translationAddonIds
import io.jmix.cli.env.AgentToolkitInstaller
import io.jmix.cli.env.EnvironmentCheck
import io.jmix.cli.env.JdkInstaller
import io.jmix.cli.env.ProjectLauncher
import io.jmix.cli.generator.JmixLocale
import io.jmix.cli.generator.ProjectCreationInfo
import io.jmix.cli.generator.ProjectGenerator
import io.jmix.cli.generator.AddonInstaller
import io.jmix.cli.generator.Repository
import io.jmix.cli.repo.TemplateRepository
import io.jmix.cli.repo.AddonRepository
import io.jmix.cli.template.Template
import io.jmix.cli.template.TemplateCatalog
import io.jmix.cli.template.TemplateParams
import io.jmix.cli.update.SelfUpdater
import io.jmix.cli.util.PlatformVersions
import io.jmix.cli.util.hostOf
import io.jmix.cli.wizard.Answer
import io.jmix.cli.wizard.Banner
import io.jmix.cli.wizard.PathCompleter
import io.jmix.cli.wizard.Prompts
import io.jmix.cli.wizard.StatusReporter
import io.jmix.cli.wizard.Validation
import io.jmix.cli.wizard.WizardChoice
import io.jmix.cli.wizard.WizardStage
import io.jmix.cli.wizard.WizardUiState
import io.jmix.cli.wizard.requireValue
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

internal fun projectLocationOptions(projectName: String, currentDir: Path, homeDir: Path): List<Pair<String, Path>> =
    listOf(
        "Current directory" to currentDir,
        "Subdirectory" to currentDir.resolve(projectName).normalize(),
        "IdeaProjects" to homeDir.resolve("IdeaProjects").resolve(projectName).normalize(),
    )

private val PAID_ADDON_BADGE = brightMagenta(bold("[$]"))

private fun addonTitle(addon: ResolvedAddon): String =
    if (addon.addon.commercial) "$PAID_ADDON_BADGE ${addon.addon.name}" else addon.addon.name

internal fun addonSelectionQuestion(addons: List<ResolvedAddon>, unavailable: Set<String> = emptySet()): String {
    val compatibility = if (unavailable.isEmpty()) "" else " (no longer compatible: ${unavailable.joinToString(", ")})"
    val legend = if (addons.any { it.addon.commercial }) gray(" (") + PAID_ADDON_BADGE + gray(" paid add-on)") else ""
    return "Select add-ons$compatibility$legend"
}

internal fun addonEntry(addon: ResolvedAddon, selected: Boolean = false): SelectList.Entry {
    val description = addon.addon.about.ifBlank { addon.addon.description }
    val license = if (addon.addon.commercial) "Requires a commercial license. " else ""
    return SelectList.Entry(addonTitle(addon), (license + description).trim().takeIf(String::isNotBlank), selected)
}

/**
 * The `jmix new` command line that recreates [info] without prompting, run from
 * [currentDir]. Options equal to the non-interactive defaults are left out; the
 * Jmix version is always pinned because "latest" moves.
 */
internal fun nonInteractiveCommand(
    info: ProjectCreationInfo,
    templateId: String,
    installToolkit: Boolean,
    currentDir: Path,
    os: String = System.getProperty("os.name"),
): String {
    val templateProjectId = info.templateMetadata.param(TemplateParams.PROJECT_ID)?.defaultValue ?: ""
    val addonIds = info.addons.filterNot { it.included }.map { it.id }
    val repositoryUrl = info.repositories.firstOrNull()?.url
    val args = buildList {
        add("jmix"); add("new"); add(info.name); add("--non-interactive")
        add("--jmix-version"); add(info.jmixVersion)
        add("--template"); add(templateId)
        add("--package"); add(info.rootPackage)
        if (info.projectId.isNotEmpty()) { add("--project-id"); add(info.projectId) }
        else if (templateProjectId.isNotEmpty()) add("--project-id=")
        if (info.projectTheme.isNotEmpty()) { add("--theme"); add(info.projectTheme) }
        add("--locales"); add(info.locales.joinToString(",") { it.code })
        if (addonIds.isNotEmpty()) { add("--addons"); add(addonIds.joinToString(",")) }
        if (info.targetDir != currentDir.resolve(info.name).normalize()) { add("--path"); add(info.targetDir.toString()) }
        if (repositoryUrl != null && repositoryUrl != ProjectCreationInfo.DEFAULT_REPOSITORY_URL) {
            add("--repository"); add(repositoryUrl)
        }
        if (!info.createGitRepository) add("--no-git")
        if (!installToolkit) add("--no-agents-toolkit")
    }
    return args.joinToString(" ") { shellQuote(it, os) }
}

/** Quotes native command arguments for PowerShell on Windows and POSIX shells elsewhere. */
internal fun shellQuote(arg: String, os: String = System.getProperty("os.name")): String =
    if (arg.isNotEmpty() && arg.all { it.isLetterOrDigit() || it in "-_./:@%+=," }) arg
    else "'" + arg.replace("'", if (os.startsWith("Windows", ignoreCase = true)) "''" else "'\\''") + "'"

class NewCommand : CliktCommand(name = "new") {

    override fun help(context: Context) = "Create a new Jmix project"

    private val nameArg by argument(name = "name", help = "Project name").optional()

    private val templateOpt by option("--template", help = "Template id (e.g. application, application-kotlin)")
    private val jmixVersionOpt by option("--jmix-version", help = "Jmix platform version (default: latest)")
    private val packageOpt by option("--package", help = "Base package (default: com.company.<name>)")
    private val projectIdOpt by option("--project-id", help = "Project id — prefix for entity, table and bean names (max 7 chars)")
    private val themeOpt by option("--theme", help = "UI theme (aura or lumo)")
    private val localesOpt by option("--locales", help = "Comma-separated locale codes (default: en)")
    private val addonsOpt by option("--addons", help = "Comma-separated add-on IDs (default: none; e.g. quartz,bpm). Commercial add-ons require a license and premium repository credentials.")
    private val pathOpt by option("--path", help = "Target directory (default: ./<name>)")
    private val repositoryOpt by option("--repository", help = "Maven repository URL (default: ${ProjectCreationInfo.DEFAULT_REPOSITORY_URL})")
    private val noGit by option("--no-git", help = "Skip git repository initialization").flag()
    private val noAgentsToolkit by option(
        "--no-agents-toolkit", help = "Skip installing the Jmix Agent Toolkit (guidelines and skills for AI coding agents)",
    ).flag()
    private val includeUnstable by option("--include-unstable", help = "Offer unstable (RC/snapshot) Jmix versions").flag()
    private val force by option("--force", help = "Generate into a non-empty directory without asking").flag()
    private val nonInteractive by option("--non-interactive", help = "Never prompt; use flags and defaults").flag()

    // Handled in main() before parsing; declared so it is accepted here too.
    @Suppress("unused")
    private val noUpdate by option(SelfUpdater.NO_UPDATE_FLAG, help = "Skip the startup update check").flag()

    /** Answers collected so far; previous answers become defaults on revisit. */
    private data class WizardState(
        val name: String? = null,
        val repositoryUrl: String? = null,
        val jmixVersion: String? = null,
        val template: Template? = null,
        val rootPackage: String? = null,
        val projectId: String? = null,
        val theme: String? = null,
        val localeCodes: String? = null,
        val addons: List<ResolvedAddon>? = null,
        val suggestedTranslationIds: Set<String> = emptySet(),
        val autoSelectedTranslationIds: Set<String> = emptySet(),
        val targetDir: Path? = null,
        val createGit: Boolean? = null,
        val installToolkit: Boolean? = null,
    ) {
        fun toUiState(activeStepIndex: Int, stage: WizardStage? = null): WizardUiState {
            val choices = listOf(
                name?.let { WizardChoice("Project name", it) },
                repositoryUrl?.let { WizardChoice("Repository", it) },
                jmixVersion?.let { WizardChoice("Jmix version", it) },
                template?.let { WizardChoice("Template", "${it.id} — ${it.displayName}") },
                rootPackage?.let { WizardChoice("Base package", it) },
                projectId?.let { WizardChoice("Project id", it.ifEmpty { "(none)" }) },
                theme?.takeIf { it.isNotEmpty() }?.let { WizardChoice("Theme", it) },
                localeCodes?.let { WizardChoice("Locales", it) },
                addons?.let { selected -> WizardChoice("Add-ons", selected.joinToString(", ", transform = ::addonTitle).ifEmpty { "(none)" }) },
                targetDir?.let { WizardChoice("Location", it.toString()) },
                if (createGit != null && installToolkit != null) {
                    WizardChoice(SETUP_LABEL, setupSummary(createGit, installToolkit))
                } else null,
            )
            return WizardUiState(choices.take(activeStepIndex).filterNotNull(), stage)
        }
    }

    private val terminal = Terminal()
    private val status = StatusReporter(terminal)
    private var state = WizardState()
    private var activeStepIndex = 0
    private var wizardStage: WizardStage? = null
    private val prompts = Prompts(
        terminal,
        wizardUiState = { state.toUiState(activeStepIndex, wizardStage) },
        // The banner stays on screen as the wizard's header instead of being
        // wiped by the first frame.
        header = { width -> Banner.lines(width, cliVersion()) },
    )

    // Prompt unless told not to; EOF on a non-terminal stdin aborts cleanly,
    // so scripts and pipes never hang.
    private val interactive: Boolean
        get() = !nonInteractive

    private enum class Outcome { PROMPTED, AUTO, BACK }

    private lateinit var repo: TemplateRepository
    private lateinit var repositoryUrl: String
    private var catalog: TemplateCatalog? = null
    private var catalogVersion: String? = null
    private var addonCatalog: AddonCatalog? = null

    override fun run() {
        try {
            createProject()
        } catch (e: java.io.IOException) {
            // Network/filesystem failures deserve a one-line message, not a stack trace.
            throw CliktError(e.message ?: e.toString())
        } finally {
            catalog?.close()
        }
    }

    private fun createProject() {
        // Consoles that render prompts line by line get the banner once, up
        // front; full-screen terminals get it as the header of every frame.
        if (interactive && terminal.terminalInfo.outputInteractive && !prompts.usesAlternateScreen) {
            Banner.print(terminal, cliVersion())
        }
        // One alternate screen for the whole wizard, with the banner as its
        // header: switching per step made the banner and the answers so far
        // flash between every question.
        val usedAlternateScreen = prompts.useAlternateScreen { runWizardSteps() }
        if (usedAlternateScreen) {
            // The frames lived on the alternate screen, which the terminal has
            // just discarded; restate the answers on the restored one.
            state.toUiState(activeStepIndex).choices.forEach { summary(it.label, it.value) }
        }

        val targetDir = state.targetDir!!
        checkJdkEnvironment(state.jmixVersion!!)
        if (state.addons.orEmpty().any { !it.included }) AddonInstaller.requireJavaHome(state.jmixVersion!!)
        checkTargetDir(targetDir)

        val info = ProjectCreationInfo(
            name = state.name!!,
            targetDir = targetDir,
            rootPackage = state.rootPackage!!,
            projectId = state.projectId!!,
            projectTheme = state.theme!!,
            locales = parseLocales(state.localeCodes!!),
            repositories = listOf(Repository(repositoryUrl)),
            jmixVersion = state.jmixVersion!!,
            templateMetadata = state.template!!.metadata,
            createGitRepository = state.createGit!!,
            addons = state.addons.orEmpty(),
        )

        wizardStage = WizardStage.GENERATION.takeIf { interactive }
        prompts.printProgress()
        val templateRoot = catalogFor(info.jmixVersion).templateRoot(state.template!!.id)
        try {
            status.run("Generating the project") { progress ->
                ProjectGenerator(
                    onWarning = { progress.println(brightYellow("Warning: $it")) },
                    onStatus = progress::relabel,
                ).generate(templateRoot, info)
            }
        } catch (e: RuntimeException) {
            // Groovy rendering errors (e.g. a missing binding) are template
            // bugs — fail with a message, not a stack trace.
            throw CliktError("Project generation failed: ${e.message ?: e.toString()}")
        }

        if (state.installToolkit == true) installAgentToolkit(info)
        printSuccess(info)
        wizardStage = null
        offerOpenAndRun(info)
    }

    /**
     * Linear wizard with back navigation. Steps resolved from flags or
     * defaults are skipped when walking backwards.
     */
    private fun runWizardSteps() {
        val steps = listOf(
            WizardStage.GENERAL to ::stepName,
            WizardStage.GENERAL to ::stepRepository,
            WizardStage.GENERAL to ::stepVersion,
            WizardStage.GENERAL to ::stepTemplate,
            WizardStage.GENERAL to ::stepPackage,
            WizardStage.GENERAL to ::stepProjectId,
            WizardStage.GENERAL to ::stepTheme,
            WizardStage.LOCALIZATION to ::stepLocales,
            WizardStage.ADDONS to ::stepAddons,
            WizardStage.LOCATION to ::stepPath,
            WizardStage.LOCATION to ::stepSetup,
        )
        val prompted = BooleanArray(steps.size)
        var i = 0
        while (i < steps.size) {
            activeStepIndex = i
            val (stage, step) = steps[i]
            wizardStage = stage.takeIf { interactive }
            val outcome = step()
            prompted[i] = outcome == Outcome.PROMPTED
            if (outcome == Outcome.BACK) {
                var j = i - 1
                while (j >= 0 && !prompted[j]) j--
                i = if (j >= 0) j else i
            } else {
                i++
            }
        }
        activeStepIndex = steps.size
    }

    private fun summary(label: String, value: String) {
        // Inside the wizard's held screen every frame already lists the answers;
        // printing here would land on top of the frame on screen.
        if (prompts.isAlternateScreenHeld) return
        terminal.println(brightGreen("✓ ") + label + ":" + cyan(" $value"))
    }

    private fun catalogFor(version: String): TemplateCatalog {
        if (catalogVersion != version) {
            catalog?.close()
            catalog = status.run("Loading templates $version from ${hostOf(repositoryUrl)}") { progress ->
                TemplateCatalog(repo.templatesJar(version, progress::progress))
            }
            catalogVersion = version
        }
        return catalog!!
    }

    // --- Steps ---------------------------------------------------------------

    private fun stepName(): Outcome {
        nameArg?.let { name ->
            Validation.validateProjectName(name)?.let { throw CliktError(it) }
            state = state.copy(name = name)
            return Outcome.AUTO
        }
        if (!interactive) throw CliktError("Project name is required. Usage: jmix new <name>")
        val default = state.name ?: defaultProjectName()
        return when (val answer = prompts.ask("Enter project name", default, validate = Validation::validateProjectName)) {
            is Answer.Back -> Outcome.BACK
            is Answer.Value -> {
                state = state.copy(name = answer.value)
                summary("Project name", answer.value)
                Outcome.PROMPTED
            }
        }
    }

    private fun defaultProjectName(): String {
        val cwd = Path.of("").toAbsolutePath()
        if (!Files.exists(cwd.resolve("untitled"))) return "untitled"
        return generateSequence(1) { it + 1 }
            .map { "untitled$it" }
            .first { !Files.exists(cwd.resolve(it)) }
    }

    private fun stepRepository(): Outcome {
        repositoryOpt?.let { url ->
            Validation.validateRepositoryUrl(url)?.let { throw CliktError(it) }
            applyRepository(url)
            return Outcome.AUTO
        }
        val default = ProjectCreationInfo.DEFAULT_REPOSITORY_URL
        if (!interactive) {
            applyRepository(default)
            return Outcome.AUTO
        }
        val known = listOf(default, BACKUP_REPOSITORY_URL)
        val previousCustom = state.repositoryUrl?.takeIf { it !in known }
        val url = when (val picked = prompts.choose(
            "Select artifact repository", known + OTHER_CHOICE, { it }, allowBack = true,
        )) {
            is Answer.Back -> return Outcome.BACK
            is Answer.Value -> if (picked.value == OTHER_CHOICE) {
                when (val typed = prompts.ask(
                    "Enter Maven repository URL", previousCustom, allowBack = true,
                    validate = Validation::validateRepositoryUrl,
                )) {
                    is Answer.Back -> return Outcome.BACK
                    is Answer.Value -> typed.value
                }
            } else picked.value
        }
        applyRepository(url)
        summary("Repository", url)
        return Outcome.PROMPTED
    }

    /** Switches the template source; a changed URL drops version and catalog caches. */
    private fun applyRepository(url: String) {
        if (state.repositoryUrl != url) {
            repo = TemplateRepository(url)
            versionsCache = null
            catalog?.close()
            catalog = null
            catalogVersion = null
        }
        repositoryUrl = url
        state = state.copy(repositoryUrl = url)
    }

    private fun stepVersion(): Outcome {
        val versions = fetchVersionsOnce()

        jmixVersionOpt?.let { requested ->
            if (requested !in repo.fetchVersions(includeUnstable = true)) {
                throw CliktError(
                    "Unknown Jmix version '$requested'. Available versions: " +
                        repo.fetchVersions(includeUnstable = true).takeLast(15).joinToString(", "),
                )
            }
            state = state.copy(jmixVersion = requested)
            return Outcome.AUTO
        }
        if (!interactive) {
            state = state.copy(jmixVersion = versions.last())
            return Outcome.AUTO
        }
        // Latest patch of each of the newest minor lines (3.0.z, 2.8.y, ...).
        val recent = PlatformVersions.latestPatchPerMinor(versions).take(MINOR_VERSIONS_SHOWN)
        val version = when (val picked = prompts.choose(
            "Select Jmix version", recent + OTHER_CHOICE, { it }, allowBack = true,
        )) {
            is Answer.Back -> return Outcome.BACK
            is Answer.Value -> if (picked.value == OTHER_CHOICE) {
                when (val typed = prompts.ask("Enter Jmix version", state.jmixVersion ?: versions.last(), allowBack = true) { input ->
                    if (input in repo.fetchVersions(includeUnstable = true)) null
                    else "Unknown version. Recent versions: ${versions.takeLast(8).joinToString(", ")}"
                }) {
                    is Answer.Back -> return Outcome.BACK
                    is Answer.Value -> typed.value
                }
            } else picked.value
        }
        state = state.copy(jmixVersion = version)
        summary("Jmix version", version)
        return Outcome.PROMPTED
    }

    private var versionsCache: List<String>? = null

    private fun fetchVersionsOnce(): List<String> = versionsCache
        ?: status.run("Fetching Jmix versions from ${hostOf(repositoryUrl)}") { repo.fetchVersions(includeUnstable) }
            .also { versionsCache = it }

    private fun stepTemplate(): Outcome {
        val templates = catalogFor(state.jmixVersion!!).projectTemplates()
        if (templates.isEmpty()) throw CliktError("No project templates found in the templates artifact.")

        templateOpt?.let { requested ->
            val template = templates.find { it.id.equals(requested, ignoreCase = true) }
                ?: throw CliktError(
                    "Unknown template '$requested'. Available: " + templates.joinToString(", ") { it.id },
                )
            state = state.copy(template = template)
            return Outcome.AUTO
        }
        if (!interactive || templates.size == 1) {
            state = state.copy(template = templates.first())
            return Outcome.AUTO
        }
        return when (val answer = prompts.choose(
            "Select project template", templates, { it.id }, { it.displayName }, allowBack = true,
        )) {
            is Answer.Back -> Outcome.BACK
            is Answer.Value -> {
                state = state.copy(template = answer.value)
                summary("Template", "${answer.value.id} — ${answer.value.displayName}")
                Outcome.PROMPTED
            }
        }
    }

    private fun stepPackage(): Outcome {
        val metadata = state.template!!.metadata
        val prefix = metadata.param(TemplateParams.ROOT_PACKAGE)?.defaultValue ?: "com.company"
        val derived = "$prefix.${Validation.transformProjectNamespace(state.name!!)}"

        packageOpt?.let { pkg ->
            Validation.validateRootPackage(pkg)?.let { throw CliktError(it) }
            state = state.copy(rootPackage = pkg)
            return Outcome.AUTO
        }
        if (!interactive || !metadata.isVisibleParam(TemplateParams.ROOT_PACKAGE)) {
            state = state.copy(rootPackage = derived)
            return Outcome.AUTO
        }
        return when (val answer = prompts.ask(
            "Enter base package", state.rootPackage ?: derived, allowBack = true,
            validate = Validation::validateRootPackage,
        )) {
            is Answer.Back -> Outcome.BACK
            is Answer.Value -> {
                state = state.copy(rootPackage = answer.value)
                summary("Base package", answer.value)
                Outcome.PROMPTED
            }
        }
    }

    private fun stepProjectId(): Outcome {
        val metadata = state.template!!.metadata
        val mandatory = metadata.isMandatoryParam(TemplateParams.PROJECT_ID)
        val default = metadata.param(TemplateParams.PROJECT_ID)?.defaultValue ?: ""

        projectIdOpt?.let { id ->
            Validation.validateProjectId(id, mandatory)?.let { throw CliktError(it) }
            state = state.copy(projectId = id)
            return Outcome.AUTO
        }
        if (!interactive || !metadata.isVisibleParam(TemplateParams.PROJECT_ID)) {
            Validation.validateProjectId(default, mandatory)?.let {
                throw CliktError("$it Pass it with --project-id.")
            }
            state = state.copy(projectId = default)
            return Outcome.AUTO
        }
        val question = "Enter project id — prefix for entity, table and bean names" +
            if (mandatory) "" else " (optional)"
        val previous = state.projectId?.ifEmpty { null }
        return when (val answer = prompts.ask(
            question, previous ?: default.ifEmpty { if (mandatory) null else "" }, allowBack = true,
        ) { Validation.validateProjectId(it, mandatory) }) {
            is Answer.Back -> Outcome.BACK
            is Answer.Value -> {
                state = state.copy(projectId = answer.value)
                summary("Project id", answer.value.ifEmpty { "(none)" })
                Outcome.PROMPTED
            }
        }
    }

    private fun stepTheme(): Outcome {
        val jmixVersion = state.jmixVersion!!
        val metadata = state.template!!.metadata
        // Studio: theme field hidden below Jmix 2.0 and unless the template declares the param.
        val visible = metadata.isVisibleParam(TemplateParams.PROJECT_THEME, defaultIfNotPresent = false) &&
            PlatformVersions.isJmix2Plus(jmixVersion)
        val options = if (PlatformVersions.isJmix3Plus(jmixVersion)) listOf("aura", "lumo") else listOf("lumo")

        themeOpt?.let { theme ->
            if (!visible) {
                throw CliktError(
                    "--theme is not applicable: template '${state.template!!.id}' with Jmix $jmixVersion has no theme setting.",
                )
            }
            if (theme !in options) throw CliktError("Unknown theme '$theme'. Available: ${options.joinToString(", ")}")
            state = state.copy(theme = theme)
            return Outcome.AUTO
        }
        if (!visible) {
            state = state.copy(theme = "")
            return Outcome.AUTO
        }
        if (!interactive || options.size == 1) {
            state = state.copy(theme = options.first())
            return Outcome.AUTO
        }
        return when (val answer = prompts.choose("Select project theme", options, { it }, allowBack = true)) {
            is Answer.Back -> Outcome.BACK
            is Answer.Value -> {
                state = state.copy(theme = answer.value)
                summary("Theme", answer.value)
                Outcome.PROMPTED
            }
        }
    }

    private fun stepLocales(): Outcome {
        val visible = state.template!!.metadata.isVisibleParam(TemplateParams.LOCALES)
        localesOpt?.let {
            state = state.copy(localeCodes = it)
            return Outcome.AUTO
        }
        if (!interactive || !visible) {
            state = state.copy(localeCodes = state.localeCodes ?: "en")
            return Outcome.AUTO
        }

        fun askTyped(): Answer<String> = prompts.ask(
            "Enter locale codes (comma-separated)", state.localeCodes ?: "en", allowBack = true,
        ) { input ->
            if (input.split(',').all { it.trim().isNotEmpty() }) null else "Locale codes cannot be empty"
        }

        val previous = state.localeCodes?.split(',')?.map { it.trim() }?.toSet() ?: setOf("en")
        val entries = COMMON_LOCALES.map { (code, name) ->
            SelectList.Entry("$code — $name", null as String?, code in previous)
        } + SelectList.Entry(OTHER_CHOICE)

        val codes = when (val picked = prompts.chooseMany(
            "Select locales",
            entries,
            allowBack = true,
            maxVisibleEntries = LOCALE_OPTIONS_SHOWN,
        )) {
            null -> when (val typed = askTyped()) {
                is Answer.Back -> return Outcome.BACK
                is Answer.Value -> typed.value
            }
            is Answer.Back -> return Outcome.BACK
            is Answer.Value ->
                if (picked.value.any { it == OTHER_CHOICE }) {
                    when (val typed = askTyped()) {
                        is Answer.Back -> return Outcome.BACK
                        is Answer.Value -> typed.value
                    }
                } else {
                    picked.value.map { it.substringBefore(" ") }.ifEmpty { listOf("en") }.joinToString(",")
                }
        }
        state = state.copy(localeCodes = codes)
        summary("Locales", codes)
        return Outcome.PROMPTED
    }

    private fun parseLocales(codes: String): List<JmixLocale> =
        codes.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .mapIndexed { index, code ->
                JmixLocale(code = code, displayName = localeDisplayName(code), default = index == 0)
            }
            .ifEmpty { listOf(JmixLocale("en", "English", default = true)) }

    private fun stepAddons(): Outcome {
        val explicitIds = addonsOpt?.let { value ->
            if (value.isBlank()) emptyList() else value.split(',').map(String::trim).also {
                if (it.any(String::isEmpty)) throw CliktError("Add-on IDs cannot be empty. Use --addons quartz,reports.")
            }
        }
        if (explicitIds?.isEmpty() == true || (explicitIds == null && (!interactive || prompts.isInputExhausted))) {
            state = state.copy(addons = emptyList())
            return Outcome.AUTO
        }
        val profile = AddonProjectProfile.from(catalogFor(state.jmixVersion!!).templateRoot(state.template!!.id))
        if (profile == null) {
            if (!explicitIds.isNullOrEmpty()) throw CliktError("This template has no application or add-on module to install add-ons into.")
            state = state.copy(addons = emptyList())
            return Outcome.AUTO
        }
        while (addonCatalog == null) {
            try {
                addonCatalog = status.run("Loading the add-on catalog from ${hostOf(AddonRepository.DEFAULT_CATALOG_URL)}") {
                    AddonRepository().catalog()
                }
            } catch (e: java.io.IOException) {
                if (explicitIds != null) throw e
                when (val answer = prompts.choose(
                    "Add-on catalog unavailable: ${e.message}",
                    listOf("Retry", "Continue without additional add-ons"), { it }, allowBack = true,
                )) {
                    is Answer.Back -> return Outcome.BACK
                    is Answer.Value -> if (answer.value != "Retry" || prompts.isInputExhausted) {
                        state = state.copy(addons = emptyList())
                        return Outcome.PROMPTED
                    }
                }
            }
        }
        val catalog = addonCatalog!!
        if (explicitIds != null) {
            state = state.copy(addons = catalog.select(explicitIds, state.jmixVersion!!, profile))
            return Outcome.AUTO
        }
        val compatible = catalog.available(state.jmixVersion!!, profile)
        val available = compatible.filterNot { it.included }
        if (available.isEmpty()) {
            state = state.copy(addons = emptyList())
            summary("Add-ons", "No additional compatible add-ons")
            return Outcome.AUTO
        }
        val previous = state.addons.orEmpty().map { it.id }.toSet()
        val suggestedTranslations = translationAddonIds(available, state.localeCodes.orEmpty().split(','))
        // Only add new suggestions: revisiting the step must respect unchecked translations.
        val newSuggestions = suggestedTranslations - state.suggestedTranslationIds - previous
        val automatic = (state.autoSelectedTranslationIds intersect suggestedTranslations) + newSuggestions
        val selected = previous - (state.autoSelectedTranslationIds - suggestedTranslations) + newSuggestions
        val unavailable = previous - compatible.map { it.id }.toSet()
        val question = addonSelectionQuestion(available, unavailable)
        val entries = available.map { addonEntry(it, it.id in selected) }
        return when (val answer = prompts.chooseMany(
            question, entries, allowBack = true, maxVisibleEntries = 10,
            filterTexts = available.map {
                "${it.id} ${it.addon.name} ${if (it.addon.commercial) "paid commercial $" else ""} ${it.addon.about} ${it.addon.description} ${it.addon.tags.joinToString(" ")} ${it.addon.group.title} ${it.addon.vendor}"
            },
            groups = available.map { it.addon.group.title },
            values = available.map { it.id },
        )) {
            is Answer.Back -> Outcome.BACK
            is Answer.Value -> {
                val chosen = available.filter { it.id in answer.value }
                state = state.copy(addons = chosen, suggestedTranslationIds = suggestedTranslations,
                    autoSelectedTranslationIds = automatic intersect chosen.map { it.id }.toSet())
                summary("Add-ons", chosen.joinToString(", ", transform = ::addonTitle).ifEmpty { "(none)" })
                Outcome.PROMPTED
            }
            null -> error("Searchable selection must support line input")
        }
    }

    private fun localeDisplayName(code: String): String =
        Locale.forLanguageTag(code.replace('_', '-'))
            .getDisplayName(Locale.ENGLISH)
            .ifEmpty { code }

    private fun stepPath(): Outcome {
        pathOpt?.let {
            state = state.copy(targetDir = toAbsolutePath(it))
            return Outcome.AUTO
        }
        val currentDir = Path.of("").toAbsolutePath().normalize()
        val subdirectory = currentDir.resolve(state.name!!).normalize()
        if (!interactive) {
            // Scripts get the conventional CWD-relative default.
            state = state.copy(targetDir = state.targetDir ?: subdirectory)
            return Outcome.AUTO
        }
        val builtInOptions = projectLocationOptions(
            state.name!!,
            currentDir,
            Path.of(System.getProperty("user.home")),
        )
        val options: List<Pair<String, Path?>> = builtInOptions + (OTHER_CHOICE to null)
        val targetDir = when (val picked = prompts.choose(
            "Select project location", options, { it.first },
            { it.second?.toString() }, allowBack = true,
        )) {
            is Answer.Back -> return Outcome.BACK
            is Answer.Value -> picked.value.second ?: run {
                val builtInPaths = builtInOptions.map { it.second }
                val previousCustom = state.targetDir?.takeIf { it !in builtInPaths }
                when (val typed = prompts.ask(
                    "Enter project location", previousCustom?.toString(), allowBack = true,
                    complete = { PathCompleter.complete(it) },
                    validate = { if (it.isBlank()) "Path cannot be empty." else null },
                )) {
                    is Answer.Back -> return Outcome.BACK
                    is Answer.Value -> toAbsolutePath(typed.value)
                }
            }
        }
        state = state.copy(targetDir = targetDir)
        summary("Location", targetDir.toString())
        return Outcome.PROMPTED
    }

    private fun toAbsolutePath(raw: String): Path {
        val expanded = if (raw.startsWith("~/")) {
            System.getProperty("user.home") + raw.substring(1)
        } else raw
        return Path.of(expanded).toAbsolutePath().normalize()
    }

    /** Optional setup after generation, offered as one checklist; both are on by default. */
    private enum class SetupOption(val title: String, val description: String) {
        GIT("Initialize Git repository", "git init and stage the generated files"),
        TOOLKIT("Install Jmix Agent Toolkit", "guidelines and skills for AI coding agents"),
    }

    private fun stepSetup(): Outcome {
        val gitAvailable = !noGit && EnvironmentCheck.isGitAvailable()
        if (!noGit && !gitAvailable) {
            terminal.println(brightYellow("git is not available — skipping repository initialization."))
        }
        // Decided by flags or the environment; null means the wizard asks.
        val git: Boolean? = if (gitAvailable) null else false
        val toolkit: Boolean? = if (noAgentsToolkit) false else null
        if (!interactive || (git != null && toolkit != null)) {
            state = state.copy(createGit = git ?: true, installToolkit = toolkit ?: true)
            return Outcome.AUTO
        }

        val offered = listOfNotNull(SetupOption.GIT.takeIf { git == null }, SetupOption.TOOLKIT.takeIf { toolkit == null })
        val previous = mapOf(SetupOption.GIT to state.createGit, SetupOption.TOOLKIT to state.installToolkit)
        fun default(option: SetupOption) = previous[option] ?: true
        val entries = offered.map { SelectList.Entry(it.title, it.description, default(it)) }
        val picked: Set<SetupOption> = when (val answer = prompts.chooseMany(
            "Select project setup", entries, allowBack = true, values = offered.map { it.name },
        )) {
            // No arrow-key widget: one yes/no question per option instead.
            null -> offered.filterTo(linkedSetOf()) { option ->
                when (val yesNo = prompts.askYesNo("${option.title}?", default(option), allowBack = true)) {
                    is Answer.Back -> return Outcome.BACK
                    is Answer.Value -> {
                        state = when (option) {
                            SetupOption.GIT -> state.copy(createGit = yesNo.value)
                            SetupOption.TOOLKIT -> state.copy(installToolkit = yesNo.value)
                        }
                        yesNo.value
                    }
                }
            }
            is Answer.Back -> return Outcome.BACK
            is Answer.Value -> answer.value.map(SetupOption::valueOf).toSet()
        }
        val createGit = git ?: (SetupOption.GIT in picked)
        val installToolkit = toolkit ?: (SetupOption.TOOLKIT in picked)
        state = state.copy(createGit = createGit, installToolkit = installToolkit)
        summary(SETUP_LABEL, setupSummary(createGit, installToolkit))
        return Outcome.PROMPTED
    }

    // --- Post-wizard checks and output ----------------------------------------

    private fun checkJdkEnvironment(jmixVersion: String) {
        val check = status.run("Detecting installed JDKs") { EnvironmentCheck.checkJdk(jmixVersion) }
        if (check.compatible.isNotEmpty()) {
            val jdk = check.compatible.first()
            terminal.println(gray("Found compatible JDK ${jdk.majorVersion} at ${jdk.home}"))
            return
        }
        val majorMinor = PlatformVersions.majorMinor(jmixVersion)
        terminal.println(brightYellow(EnvironmentCheck.jdkMismatchMessage(majorMinor, check.supportedVersions)))
        if (check.all.isEmpty()) {
            terminal.println(brightYellow("No JDK was found on this machine."))
        } else {
            terminal.println(
                brightYellow("Detected JDKs: ${check.all.joinToString(", ") { it.majorVersion.toString() }}"),
            )
        }
        terminal.println(brightYellow(EnvironmentCheck.installHint(check.supportedVersions)))
    }

    private fun checkTargetDir(targetDir: Path) {
        if (Files.exists(targetDir) && !Files.isDirectory(targetDir)) {
            throw CliktError("$targetDir already exists and is not a directory.")
        }
        if (!Files.isDirectory(targetDir)) return
        val isEmpty = Files.list(targetDir).use { it.findFirst().isEmpty }
        if (isEmpty || force) return
        if (interactive &&
            prompts.askYesNo(
                "Directory $targetDir is not empty. Existing files may be overwritten. Continue?",
                false,
            ).requireValue()
        ) return
        throw CliktError("Directory $targetDir is not empty. Use --force to generate anyway.")
    }

    private fun printSuccess(info: ProjectCreationInfo) {
        val dir = info.projectDir
        val relative = runCatching { Path.of("").toAbsolutePath().relativize(dir) }
            .map { if (it.startsWith("..")) dir else it }
            .getOrDefault(dir).toString().ifEmpty { "." }
        val runTask = runTaskFor(info)

        terminal.println()
        terminal.println(brightGreen("✓ Project '${info.name}' created at $dir"))
        terminal.println()
        terminal.println(bold("Next steps:"))
        terminal.println("  1. ${cyan("cd $relative")}")
        if (runTask != null) {
            terminal.println("  2. ${cyan("./gradlew $runTask")}")
        } else {
            terminal.println("  2. Read README.md for instructions on adding subprojects.")
        }
        terminal.println()
        terminal.println(bold("CLI command:"))
        val currentDir = Path.of("").toAbsolutePath().normalize()
        terminal.println("  " + cyan(nonInteractiveCommand(info, state.template!!.id, state.installToolkit!!, currentDir)))
        terminal.println()
        terminal.println(bold("Useful links:"))
        val labelWidth = USEFUL_LINKS.maxOf { it.label.length }
        USEFUL_LINKS.forEach { link ->
            terminal.println("  ${link.emoji} ${link.label.padEnd(labelWidth)}  ${cyan(link.url)}")
        }
    }

    /** Release version from the jar manifest; absent in some dev runs. */
    private fun cliVersion(): String? =
        NewCommand::class.java.`package`?.implementationVersion?.takeIf { it.isNotBlank() }

    // Composite aggregator projects (marked hideForSubproject) have no runnable
    // Gradle task — their README explains how to attach subprojects.
    private fun runTaskFor(info: ProjectCreationInfo): String? = when {
        info.templateMetadata.hideForSubproject -> null
        info.templateMetadata.addon -> "build"
        else -> "bootRun"
    }

    private fun offerOpenAndRun(info: ProjectCreationInfo) {
        // Only when a human can answer. Piped stdin would hit EOF here and
        // abort with a non-zero exit after the project was already created.
        if (!interactive || !terminal.terminalInfo.inputInteractive) return
        val runTask = runTaskFor(info)
        // Offered only when an IDE is actually installed; the file manager is
        // never a question — it is the fallback below.
        val ideaOpen = ProjectLauncher.ideaOpenCommand(info.projectDir)
        val openChoice = ideaOpen?.let { "Open the project in ${it.opener.displayName}" }
        // Composite aggregator projects have no runnable task — offer only the open.
        val runChoice = runTask?.let { "Run the application (./gradlew $it)" }

        val entries = listOfNotNull(openChoice, runChoice).map { SelectList.Entry(it) }
        val picked = when {
            entries.isEmpty() -> emptyList()
            else -> when (val answer = prompts.chooseMany("What's next?", entries)) {
                // No arrow-key widget: one yes/no question per option instead.
                null -> listOfNotNull(
                    openChoice?.takeIf { prompts.askYesNo("$it?", default = false).requireValue() },
                    runChoice?.takeIf { prompts.askYesNo("$it?", default = false).requireValue() },
                )
                is Answer.Back -> emptyList()
                is Answer.Value -> answer.value
            }
        }

        openProject(info, ideaOpen.takeIf { openChoice != null && openChoice in picked })
        if (runChoice != null && runChoice in picked) {
            runApplication(info, runTask)
        } else if (runTask != null) {
            offerJdkInstall(info)
        }
    }

    /**
     * Opens the generated project: in the IDE when the user asked for it, and
     * in the file manager otherwise — including when the IDE failed to start,
     * so a finished project is always shown somewhere.
     */
    private fun openProject(info: ProjectCreationInfo, ideaOpen: ProjectLauncher.OpenCommand?) {
        val warn: (String) -> Unit = { terminal.println(brightYellow("Warning: $it")) }
        if (ideaOpen != null) {
            terminal.println(gray("Opening the project in ${ideaOpen.opener.displayName}..."))
            if (ProjectLauncher.open(ideaOpen, warn)) return
        }
        terminal.println(gray("Opening the project folder..."))
        ProjectLauncher.open(ProjectLauncher.fileManagerOpenCommand(info.projectDir), warn)
    }

    private fun runApplication(info: ProjectCreationInfo, runTask: String) {
        val javaHome = compatibleJdkHome(info) ?: installJdk(info) ?: run {
            terminal.println(brightYellow("Skipping the run — no compatible JDK is available."))
            return
        }

        terminal.println(gray("Running ./gradlew $runTask (Ctrl+C to stop)..."))
        if (runTask == "bootRun") {
            openAppInBrowserWhenReady(info)
        }
        val exitCode = ProjectLauncher.runGradle(info.projectDir, runTask, javaHome)
        if (exitCode != 0) {
            terminal.println(brightYellow("Gradle finished with exit code $exitCode."))
        }
    }

    /**
     * Installs the Jmix Agent Toolkit automatically: guidelines files and
     * project-local skills. A failure must never
     * fail the already-generated project — warn and move on.
     */
    private fun installAgentToolkit(info: ProjectCreationInfo) {
        try {
            status.run("Installing the Jmix Agent Toolkit") { progress ->
                AgentToolkitInstaller.installGuidelinesAndSkills(info.projectDir, info.jmixVersion, progress::relabel)
            }
            summary(
                "Agent Toolkit",
                "guidelines for ${AgentToolkitInstaller.ALL_AGENTS.joinToString(", ")}; " +
                    "local skills for ${AgentToolkitInstaller.SKILL_AGENTS.joinToString(", ")}",
            )
        } catch (e: Exception) {
            terminal.println(brightYellow("Warning: Agent Toolkit installation failed: ${e.message}"))
            terminal.println(brightYellow("Install it later: https://github.com/jmix-framework/jmix-agent-toolkit"))
        }
    }

    /** Offered when the user declines the run: install what a later run needs. */
    private fun offerJdkInstall(info: ProjectCreationInfo) {
        if (compatibleJdkHome(info) != null) return
        val major = jdkVersionToInstall(info)
        val question = "No compatible JDK was found. Install JDK $major (Temurin) now so the project can run later?"
        if (prompts.askYesNo(question, default = true).requireValue()) {
            installJdk(info)
        }
    }

    private fun compatibleJdkHome(info: ProjectCreationInfo): Path? =
        EnvironmentCheck.checkJdk(info.jmixVersion).compatible.firstOrNull()?.home

    private fun jdkVersionToInstall(info: ProjectCreationInfo): Int =
        EnvironmentCheck.checkJdk(info.jmixVersion).supportedVersions.max()

    private fun installJdk(info: ProjectCreationInfo): Path? {
        val major = jdkVersionToInstall(info)
        return try {
            val home = status.run("Installing JDK $major (Temurin)") { progress ->
                JdkInstaller.install(major, onStatus = progress::relabel, onProgress = progress::progress)
            }
            terminal.println(brightGreen("✓ ") + "Installed JDK $major at " + cyan(home.toString()))
            home
        } catch (e: Exception) {
            // Also covers JSON parse errors from an unexpected API response —
            // never a stack trace after the project was already generated.
            terminal.println(brightYellow("Warning: JDK installation failed: ${e.message}"))
            terminal.println(brightYellow(EnvironmentCheck.installHint(setOf(major))))
            null
        }
    }

    private fun openAppInBrowserWhenReady(info: ProjectCreationInfo) {
        val port = ProjectLauncher.serverPort(info.projectDir)
        val url = "http://localhost:$port"
        // A port that is busy before the app starts belongs to something else;
        // opening it would show a foreign app (and bootRun will fail anyway).
        if (ProjectLauncher.isPortInUse(port)) {
            terminal.println(brightYellow("Warning: port $port is already in use — not opening the browser."))
            return
        }
        terminal.println(gray("The app will open at $url in your browser once it starts."))
        ProjectLauncher.openBrowserWhenReady(port, url) { terminal.println(brightYellow("Warning: $it")) }
    }

    private data class UsefulLink(val emoji: String, val label: String, val url: String)

    private companion object {
        const val MINOR_VERSIONS_SHOWN = 4
        const val LOCALE_OPTIONS_SHOWN = 6
        const val OTHER_CHOICE = "Other..."
        const val SETUP_LABEL = "Setup"

        fun setupSummary(createGit: Boolean, installToolkit: Boolean): String =
            listOfNotNull("Git repository".takeIf { createGit }, "Agent Toolkit".takeIf { installToolkit })
                .joinToString(", ").ifEmpty { "(none)" }

        // Studio's RepoConfigurationItem offers the same two Jmix repositories.
        const val BACKUP_REPOSITORY_URL = "https://nexus.jmix.io/repository/public"

        val USEFUL_LINKS = listOf(
            UsefulLink("📖", "Documentation", "https://docs.jmix.io"),
            UsefulLink("🤖", "AI Assistant", "https://ai-assistant.jmix.io"),
            UsefulLink("🧰", "Agent Toolkit", "https://github.com/jmix-framework/jmix-agent-toolkit"),
            UsefulLink("🧩", "Demo Applications", "https://www.jmix.io/live-demo"),
            UsefulLink("💬", "Forum", "https://forum.jmix.io"),
        )

        val COMMON_LOCALES = listOf(
            "en" to "English", "ru" to "Russian", "de" to "German", "fr" to "French",
            "es" to "Spanish", "it" to "Italian", "pt" to "Portuguese", "nl" to "Dutch",
            "pl" to "Polish", "cs" to "Czech", "tr" to "Turkish", "uk" to "Ukrainian",
            "ar" to "Arabic", "zh" to "Chinese", "ja" to "Japanese", "ko" to "Korean",
        )
    }
}
