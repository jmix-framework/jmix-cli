package io.jmix.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.terminal
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
import io.jmix.cli.account.PremiumRepository
import io.jmix.cli.account.PremiumConflictAction
import io.jmix.cli.env.AgentToolkitInstaller
import io.jmix.cli.env.EnvironmentCheck
import io.jmix.cli.generator.JmixLocale
import io.jmix.cli.generator.ProjectCreationInfo
import io.jmix.cli.generator.ProjectGenerator
import io.jmix.cli.generator.AddonInstaller
import io.jmix.cli.generator.Repository
import io.jmix.cli.generator.projectTargetHasFiles
import io.jmix.cli.repo.TemplateRepository
import io.jmix.cli.repo.AddonRepository
import io.jmix.cli.repo.TemplateVersionCatalog
import io.jmix.cli.telemetry.UsageEvent
import io.jmix.cli.telemetry.UsageStatistics
import io.jmix.cli.template.Template
import io.jmix.cli.template.TemplateCatalog
import io.jmix.cli.template.TemplateParams
import io.jmix.cli.update.SelfUpdater
import io.jmix.cli.util.PlatformVersions
import io.jmix.cli.util.hostOf
import io.jmix.cli.wizard.Answer
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

private val PAID_ADDON_BADGE = brightMagenta(bold("[$]"))

private fun addonTitle(addon: ResolvedAddon): String =
    if (addon.addon.commercial) "${addon.addon.name} $PAID_ADDON_BADGE" else addon.addon.name

internal fun addonSelectionQuestion(unavailable: Set<String> = emptySet()): String {
    val compatibility = if (unavailable.isEmpty()) "" else " (no longer compatible: ${unavailable.joinToString(", ")})"
    return "Select add-ons$compatibility"
}

internal fun addonSelectionLegend(addons: List<ResolvedAddon>): String? =
    if (addons.any { it.addon.commercial }) PAID_ADDON_BADGE + gray(" - commercial add-on") else null

internal fun addonEntry(addon: ResolvedAddon, selected: Boolean = false): SelectList.Entry {
    val description = addon.addon.about.ifBlank { addon.addon.description }
    val license = if (addon.addon.commercial) "Requires a commercial license. " else ""
    return SelectList.Entry(addonTitle(addon), (license + description).trim().takeIf(String::isNotBlank), selected)
}

internal fun configurePremiumRepository(
    terminal: Terminal,
    prompts: Prompts,
    interactive: Boolean,
    repository: PremiumRepository = PremiumRepository(),
): Pair<String, String> {
    // No spinner: its redraws would overwrite the conflict prompt.
    terminal.println(gray("Checking premium repository access..."))
    return repository.prepare(
        onConflict = { file, canReplace ->
            if (!interactive) PremiumConflictAction.KEEP else {
                terminal.println(brightYellow("Configured premium repository credentials differ from your account license ($file)."))
                val actions = buildList {
                    add(PremiumConflictAction.KEEP)
                    if (canReplace) add(PremiumConflictAction.REPLACE)
                    add(PremiumConflictAction.SEPARATE)
                }
                prompts.choose("How should premium credentials be handled?", actions, {
                    when (it) {
                        PremiumConflictAction.KEEP -> "Keep existing credentials"
                        PremiumConflictAction.REPLACE -> "Replace existing credentials"
                        PremiumConflictAction.SEPARATE -> "Save account credentials separately"
                    }
                }).requireValue()
            }
        },
        onSaved = { file -> terminal.println(brightGreen("✓ Premium repository credentials saved to $file.")) },
    )
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

/** A user-typed path as an absolute, normalized one; a leading `~/` means the home directory. */
internal fun toAbsolutePath(raw: String): Path {
    val expanded = if (raw.startsWith("~/")) {
        System.getProperty("user.home") + raw.substring(1)
    } else raw
    return Path.of(expanded).toAbsolutePath().normalize()
}

/** Reports JDK compatibility and returns the home of the preferred compatible JDK, if found. */
internal fun reportJdkCheck(terminal: Terminal, status: StatusReporter, jmixVersion: String): Path? {
    val check = status.run("Detecting installed JDKs") { EnvironmentCheck.checkJdk(jmixVersion) }
    val jdk = check.compatible.firstOrNull()
    if (jdk != null) {
        terminal.println(gray("Found compatible JDK ${jdk.majorVersion} at ${jdk.home}"))
        return jdk.home
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
    return null
}

class NewCommand internal constructor(
    private val templateCacheDir: Path,
    private val usageStatistics: UsageStatistics? = null,
) : CliktCommand(name = "new") {

    constructor() : this(TemplateRepository.defaultCacheDir())

    override fun help(context: Context) = "Create a new Jmix project"

    private val nameArg by argument(name = "name", help = "Project name").optional()

    private val templateOpt by option("--template", help = "Template id (e.g. application, application-kotlin)")
    private val jmixVersionOpt by option("--jmix-version", help = "Jmix platform version (default: latest)")
    private val packageOpt by option("--package", help = "Base package (default: com.company.<name>)")
    private val projectIdOpt by option("--project-id", help = "Project id — prefix for entity, table and bean names (max 7 chars)")
    private val themeOpt by option("--theme", help = "UI theme (aura or lumo)")
    private val localesOpt by option("--locales", help = "Comma-separated locale codes (default: en)")
    private val addonsOpt by option("--addons", help = "Comma-separated add-on IDs (default: none; e.g. quartz,bpm). Commercial add-ons use configured premium credentials or the signed-in account's license.")
    private val pathOpt by option("--path", help = "Target directory (default: ./<name>)")
    private val repositoryOpt by option("--repository", help = "Maven repository URL (default: ${ProjectCreationInfo.DEFAULT_REPOSITORY_URL})")
    private val noGit by option("--no-git", help = "Skip git repository initialization").flag()
    private val noAgentsToolkit by option(
        "--no-agents-toolkit", help = "Skip the Jmix Agent Toolkit (guidelines, skills, and project MCP servers)",
    ).flag()
    private val includeUnstable by option("--include-unstable", help = "Offer unstable (RC/snapshot) Jmix versions").flag()
    private val force by option("--force", help = "Allow overwriting matching files in a non-empty directory without asking").flag()
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
        val addonProfile: AddonProjectProfile? = null,
        val suggestedTranslationIds: Set<String> = emptySet(),
        val autoSelectedTranslationIds: Set<String> = emptySet(),
        val targetDir: Path? = null,
        val overwriteConfirmed: Boolean = false,
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

    private lateinit var status: StatusReporter
    private var state = WizardState()
    private var activeStepIndex = 0
    private var wizardStage: WizardStage? = null
    private lateinit var prompts: Prompts

    // Prompt unless told not to; EOF on a non-terminal stdin aborts cleanly,
    // so scripts and pipes never hang.
    private val interactive: Boolean
        get() = !nonInteractive

    private enum class Outcome { PROMPTED, AUTO, BACK }

    private class OpenCatalog(val version: String, val catalog: TemplateCatalog)

    private lateinit var repo: TemplateRepository
    private val repositoryHost: String
        get() = hostOf(checkNotNull(state.repositoryUrl))
    private var openCatalog: OpenCatalog? = null
    private var addonCatalog: AddonCatalog? = null

    override fun run() {
        state = WizardState()
        activeStepIndex = 0
        wizardStage = null
        versionsCache = null
        addonCatalog = null
        status = StatusReporter(terminal)
        prompts = Prompts(terminal, wizardUiState = { state.toUiState(activeStepIndex, wizardStage) })
        try {
            createProject()
        } catch (e: java.io.IOException) {
            // Network/filesystem failures deserve a one-line message, not a stack trace.
            throw CliktError(e.message ?: e.toString())
        } finally {
            closeCatalog()
        }
    }

    private fun createProject() {
        // One alternate screen for the whole wizard, with the banner as its
        // header: switching per step made the banner and the answers so far
        // flash between every question.
        val usedAlternateScreen = try {
            prompts.useAlternateScreen { runWizardSteps() }
        } finally {
            if (prompts.usesAlternateScreen && versionsCache?.stale == true) terminal.println(staleVersionsWarning())
        }
        val answers = state
        if (usedAlternateScreen) {
            // The frames lived on the alternate screen, which the terminal has
            // just discarded; restate the answers on the restored one.
            answers.toUiState(activeStepIndex).choices.forEach { summary(it.label, it.value) }
        }

        val (info, addonJavaHome) = prepareProjectCreation(answers)
        val templateId = checkNotNull(answers.template).id
        val installToolkit = checkNotNull(answers.installToolkit)
        val overwriteExisting = force || answers.overwriteConfirmed

        wizardStage = WizardStage.GENERATION.takeIf { interactive }
        prompts.printProgress()
        val templateRoot = catalogFor(info.jmixVersion).templateRoot(templateId)
        try {
            status.run("Generating the project") { progress ->
                ProjectGenerator(
                    onWarning = { progress.println(brightYellow("Warning: $it")) },
                    onStatus = progress::relabel,
                ).generate(
                    templateRoot, info, overwriteExisting = overwriteExisting,
                    addonJavaHome = addonJavaHome, addonProfile = answers.addonProfile,
                ) {
                    if (installToolkit) installAgentToolkit(info, progress)
                }
            }
        } catch (e: RuntimeException) {
            // Groovy rendering errors (e.g. a missing binding) are template
            // bugs — fail with a message, not a stack trace.
            throw CliktError("Project generation failed: ${e.message ?: e.toString()}")
        }

        val runTask = runTaskFor(info)
        printSuccess(info, templateId, installToolkit, runTask)
        usageStatistics?.record(UsageEvent.PROJECT_CREATED, info.jmixVersion, addonIds = info.addons.map { it.id })
        wizardStage = null
        if (interactive && terminal.terminalInfo.inputInteractive) {
            ProjectActions(info.projectDir, info.jmixVersion, terminal, prompts, status).offer(runTask)
        }
    }

    private fun prepareProjectCreation(answers: WizardState): Pair<ProjectCreationInfo, Path?> {
        val jmixVersion = checkNotNull(answers.jmixVersion)
        val addons = answers.addons.orEmpty()
        val javaHome = reportJdkCheck(terminal, status, jmixVersion)
        val addonJavaHome = if (addons.any { !it.included }) AddonInstaller.requireJavaHome(jmixVersion, javaHome) else null

        val (premiumUser, premiumPass) = if (addons.any { !it.included && it.addon.commercial }) {
            configurePremiumRepository(terminal, prompts, interactive)
        } else PremiumRepository.USER to PremiumRepository.PASS

        val info = ProjectCreationInfo(
            name = checkNotNull(answers.name),
            targetDir = checkNotNull(answers.targetDir),
            rootPackage = checkNotNull(answers.rootPackage),
            projectId = checkNotNull(answers.projectId),
            projectTheme = checkNotNull(answers.theme),
            locales = parseLocales(checkNotNull(answers.localeCodes)),
            repositories = listOf(Repository(checkNotNull(answers.repositoryUrl))),
            jmixVersion = jmixVersion,
            templateMetadata = checkNotNull(answers.template).metadata,
            createGitRepository = checkNotNull(answers.createGit),
            addons = addons,
            premiumRepoUserProperty = premiumUser,
            premiumRepoPassProperty = premiumPass,
        )
        return info to addonJavaHome
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
                if (j < 0) throw ReturnToMainMenu()
                i = j
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
        openCatalog?.let { if (it.version == version) return it.catalog }
        closeCatalog()
        return status.run("Loading templates $version from $repositoryHost") { progress ->
            val catalog = TemplateCatalog(repo.templatesJar(version, progress::progress))
            openCatalog = OpenCatalog(version, catalog)
            catalog
        }
    }

    private fun closeCatalog() {
        openCatalog?.catalog?.close()
        openCatalog = null
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
        return when (val answer = prompts.ask("Enter project name", default, allowBack = true, validate = Validation::validateProjectName)) {
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
        val previous = state.repositoryUrl
        val previousCustom = previous?.takeIf { it !in known }
        val options = known + OTHER_CHOICE + listOfNotNull(previousCustom)
        val url = when (val picked = prompts.choose(
            "Select artifact repository", options, { it },
            defaultIndex = options.indexOf(previous).coerceAtLeast(0), allowBack = true,
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
        if (state.repositoryUrl == url) return
        repo = TemplateRepository(url, templateCacheDir)
        versionsCache = null
        closeCatalog()
        state = state.copy(repositoryUrl = url)
    }

    private fun stepVersion(): Outcome {
        jmixVersionOpt?.let { requested ->
            val versions = versionCatalog(requested).versions
            if (requested !in versions) {
                throw CliktError(
                    "Unknown Jmix version '$requested'. Available versions: " +
                        versions.takeLast(15).joinToString(", "),
                )
            }
            state = state.copy(jmixVersion = requested)
            return Outcome.AUTO
        }
        val catalog = versionCatalog()
        val versions = catalog.available(includeUnstable)
        if (!interactive) {
            state = state.copy(jmixVersion = versions.last())
            return Outcome.AUTO
        }
        // Latest patch of each of the newest minor lines (3.0.z, 2.8.y, ...).
        val recent = PlatformVersions.latestPatchPerMinor(versions).take(MINOR_VERSIONS_SHOWN)
        val previous = state.jmixVersion?.takeIf { it in catalog.versions }
        val options = recent + OTHER_CHOICE + listOfNotNull(previous?.takeIf { it !in recent })
        val version = when (val picked = prompts.choose(
            "Select Jmix version", options, { it },
            defaultIndex = options.indexOf(previous).coerceAtLeast(0), allowBack = true,
        )) {
            is Answer.Back -> return Outcome.BACK
            is Answer.Value -> if (picked.value == OTHER_CHOICE) {
                when (val typed = prompts.ask("Enter Jmix version", previous ?: versions.last(), allowBack = true) { input ->
                    val available = versionCatalog(input).versions
                    if (input in available) null
                    else "Unknown version. Recent versions: ${available.takeLast(8).joinToString(", ")}"
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

    private var versionsCache: TemplateVersionCatalog? = null

    private fun versionCatalog(requested: String? = null): TemplateVersionCatalog {
        versionsCache?.let { cached ->
            if (requested == null || requested in cached.versions || !cached.fromFreshCache) return cached
        }
        return status.run("Fetching Jmix versions from $repositoryHost") { progress ->
            repo.versionCatalog(requested).also { result ->
                if (result.stale && !prompts.isAlternateScreenHeld) progress.println(staleVersionsWarning())
                versionsCache = result
            }
        }
    }

    private fun staleVersionsWarning(): String = brightYellow(
        "Warning: Could not refresh Jmix versions from $repositoryHost. Using the cached version list.",
    )

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
        val previousTemplateId = state.template?.id
        val defaultIndex = templates.indexOfFirst { it.id == previousTemplateId }.coerceAtLeast(0)
        return when (val answer = prompts.choose(
            "Select project template", templates, { it.id }, { it.displayName },
            defaultIndex = defaultIndex, allowBack = true,
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
            Validation.validateRootPackage(derived)?.let {
                throw CliktError("$it Pass a valid package with --package.")
            }
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
        return when (val answer = prompts.choose(
            "Select project theme", options, { it },
            defaultIndex = options.indexOf(state.theme).coerceAtLeast(0), allowBack = true,
        )) {
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
            state = state.copy(addons = emptyList(), addonProfile = null)
            return Outcome.AUTO
        }
        val profile = AddonProjectProfile.from(catalogFor(state.jmixVersion!!).templateRoot(state.template!!.id))
        if (profile == null) {
            if (!explicitIds.isNullOrEmpty()) throw CliktError("This template has no application or add-on module to install add-ons into.")
            state = state.copy(addons = emptyList(), addonProfile = null)
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
                        state = state.copy(addons = emptyList(), addonProfile = null)
                        return Outcome.PROMPTED
                    }
                }
            }
        }
        val catalog = addonCatalog!!
        if (explicitIds != null) {
            state = state.copy(addons = catalog.select(explicitIds, state.jmixVersion!!, profile), addonProfile = profile)
            return Outcome.AUTO
        }
        val compatible = catalog.available(state.jmixVersion!!, profile)
        val available = compatible.filterNot { it.included }
        if (available.isEmpty()) {
            state = state.copy(addons = emptyList(), addonProfile = null)
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
        val question = addonSelectionQuestion(unavailable)
        val entries = available.map { addonEntry(it, it.id in selected) }
        return when (val answer = prompts.chooseMany(
            question, entries, allowBack = true, maxVisibleEntries = 10,
            filterTexts = available.map {
                "${it.id} ${it.addon.name} ${if (it.addon.commercial) "paid commercial $" else ""} ${it.addon.about} ${it.addon.description} ${it.addon.tags.joinToString(" ")} ${it.addon.group.title} ${it.addon.vendor}"
            },
            groups = available.map { it.addon.group.title },
            values = available.map { it.id },
            legend = addonSelectionLegend(available),
        )) {
            is Answer.Back -> Outcome.BACK
            is Answer.Value -> {
                val chosen = available.filter { it.id in answer.value }
                state = state.copy(addons = chosen, addonProfile = profile, suggestedTranslationIds = suggestedTranslations,
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
        if (pathOpt != null || !interactive) {
            val targetDir = pathOpt?.let(::toAbsolutePath)
                ?: Path.of("").toAbsolutePath().resolve(state.name!!).normalize()
            if (!acceptProjectTarget(targetDir)) cancelForExistingTarget(targetDir)
            return Outcome.AUTO
        }

        var previous = state.targetDir
        while (true) {
            val targetDir = when (val picked = chooseProjectLocation(prompts, state.name!!, previous)) {
                is Answer.Back -> return Outcome.BACK
                is Answer.Value -> picked.value
            }
            if (acceptProjectTarget(targetDir)) {
                summary("Location", targetDir.toString())
                return Outcome.PROMPTED
            }
            if (prompts.isInputExhausted) cancelForExistingTarget(targetDir)
            previous = targetDir
        }
    }

    private fun acceptProjectTarget(targetDir: Path): Boolean {
        val needsConfirmation = projectTargetHasFiles(targetDir) && !force
        if (needsConfirmation && !(state.targetDir == targetDir && state.overwriteConfirmed)) {
            if (!interactive) {
                throw CliktError("Directory $targetDir is not empty. Choose another folder with --path or use --force.")
            }
            val answer = prompts.askYesNo(
                "Overwrite matching files in the non-empty directory?",
                default = false,
                allowBack = pathOpt == null,
                legend = targetDir.toString(),
            )
            if (answer != Answer.Value(true)) return false
        }
        state = state.copy(targetDir = targetDir, overwriteConfirmed = needsConfirmation)
        return true
    }

    private fun cancelForExistingTarget(targetDir: Path): Nothing =
        throw CliktError("Directory $targetDir is not empty. Choose another folder with --path.")

    /** Optional setup after generation, offered as one checklist; both are on by default. */
    private enum class SetupOption(val title: String, val description: String) {
        GIT("Initialize Git repository", "git init and stage the generated files"),
        TOOLKIT("Install Jmix Agent Toolkit", "guidelines, skills, and project MCP servers"),
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

    private fun printSuccess(info: ProjectCreationInfo, templateId: String, installToolkit: Boolean, runTask: String?) {
        val dir = info.projectDir
        val currentDir = Path.of("").toAbsolutePath().normalize()
        terminal.println()
        terminal.println(brightGreen("✓ Project '${info.name}' created at $dir"))
        terminal.println()
        projectNextSteps(dir, currentDir, runTask).forEach { line ->
            terminal.println(if (line == "Next steps:") bold(line) else cyan(line))
        }
        terminal.println()
        terminal.println(bold("CLI command:"))
        terminal.println("  " + cyan(nonInteractiveCommand(info, templateId, installToolkit, currentDir)))
        if (info.premiumRepoUserProperty != PremiumRepository.USER || info.premiumRepoPassProperty != PremiumRepository.PASS) {
            terminal.println(gray("To reuse separately saved premium credential names, you may need to generate interactively again."))
        }
        terminal.println()
        terminal.println(bold("Useful links:"))
        val labelWidth = USEFUL_LINKS.maxOf { it.label.length }
        USEFUL_LINKS.forEach { link ->
            terminal.println("  ${link.emoji} ${link.label.padEnd(labelWidth)}  ${cyan(link.url)}")
        }
    }

    // Composite aggregator projects (marked hideForSubproject) have no runnable
    // Gradle task — their README explains how to attach subprojects.
    private fun runTaskFor(info: ProjectCreationInfo): String? = when {
        info.templateMetadata.hideForSubproject -> null
        info.templateMetadata.addon -> "build"
        else -> "bootRun"
    }

    /**
     * Installs the Jmix Agent Toolkit automatically: guidelines files and
     * project-local skills, and MCP configs. Warn and continue if setup fails.
     */
    private fun installAgentToolkit(info: ProjectCreationInfo, progress: StatusReporter.Handle) {
        try {
            progress.relabel("Installing the Jmix Agent Toolkit")
            AgentToolkitInstaller.install(info.projectDir, info.jmixVersion, progress::relabel)
            progress.println(
                brightGreen("✓ ") + "Agent Toolkit:" + cyan(" local skills, shared guidelines, and project MCP setup"),
            )
        } catch (e: Exception) {
            progress.println(brightYellow("Warning: Agent Toolkit installation failed: ${e.message}"))
            progress.println(brightYellow("Install it later: https://github.com/jmix-framework/jmix-agent-toolkit"))
        }
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
