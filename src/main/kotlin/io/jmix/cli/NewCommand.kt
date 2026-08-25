package io.jmix.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.rendering.TextColors.brightGreen
import com.github.ajalt.mordant.rendering.TextColors.brightYellow
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.SelectList
import io.jmix.cli.env.EnvironmentCheck
import io.jmix.cli.env.JdkInstaller
import io.jmix.cli.env.ProjectLauncher
import io.jmix.cli.generator.JmixLocale
import io.jmix.cli.generator.ProjectCreationInfo
import io.jmix.cli.generator.ProjectGenerator
import io.jmix.cli.generator.Repository
import io.jmix.cli.repo.TemplateRepository
import io.jmix.cli.template.Template
import io.jmix.cli.template.TemplateCatalog
import io.jmix.cli.template.TemplateParams
import io.jmix.cli.util.PlatformVersions
import io.jmix.cli.wizard.Answer
import io.jmix.cli.wizard.PathCompleter
import io.jmix.cli.wizard.Prompts
import io.jmix.cli.wizard.Validation
import io.jmix.cli.wizard.WizardChoice
import io.jmix.cli.wizard.WizardUiState
import io.jmix.cli.wizard.requireValue
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

class NewCommand : CliktCommand(name = "new") {

    override fun help(context: Context) = "Create a new Jmix project"

    private val nameArg by argument(name = "name", help = "Project name").optional()

    private val templateOpt by option("--template", help = "Template id (e.g. application, application-kotlin)")
    private val jmixVersionOpt by option("--jmix-version", help = "Jmix platform version (default: latest)")
    private val packageOpt by option("--package", help = "Base package (default: com.company.<name>)")
    private val projectIdOpt by option("--project-id", help = "Project id — prefix for entity, table and bean names (max 7 chars)")
    private val themeOpt by option("--theme", help = "UI theme (aura or lumo)")
    private val localesOpt by option("--locales", help = "Comma-separated locale codes (default: en)")
    private val pathOpt by option("--path", help = "Target directory (default: ./<name>)")
    private val repositoryOpt by option("--repository", help = "Maven repository URL (default: ${ProjectCreationInfo.DEFAULT_REPOSITORY_URL})")
    private val noGit by option("--no-git", help = "Skip git repository initialization").flag()
    private val includeUnstable by option("--include-unstable", help = "Offer unstable (RC/snapshot) Jmix versions").flag()
    private val force by option("--force", help = "Generate into a non-empty directory without asking").flag()
    private val nonInteractive by option("--non-interactive", help = "Never prompt; use flags and defaults").flag()

    /** Answers collected so far; previous answers become defaults on revisit. */
    private data class WizardState(
        val name: String? = null,
        val jmixVersion: String? = null,
        val template: Template? = null,
        val rootPackage: String? = null,
        val projectId: String? = null,
        val theme: String? = null,
        val localeCodes: String? = null,
        val targetDir: Path? = null,
        val createGit: Boolean? = null,
    ) {
        fun toUiState(activeStepIndex: Int): WizardUiState {
            val choices = listOf(
                name?.let { WizardChoice("Project name", it) },
                jmixVersion?.let { WizardChoice("Jmix version", it) },
                template?.let { WizardChoice("Template", "${it.id} — ${it.displayName}") },
                rootPackage?.let { WizardChoice("Base package", it) },
                projectId?.let { WizardChoice("Project id", it.ifEmpty { "(none)" }) },
                theme?.takeIf { it.isNotEmpty() }?.let { WizardChoice("Theme", it) },
                localeCodes?.let { WizardChoice("Locales", it) },
                targetDir?.let { WizardChoice("Location", it.toString()) },
                createGit?.let { WizardChoice("Git repository", if (it) "yes" else "no") },
            )
            return WizardUiState(choices.take(activeStepIndex).filterNotNull())
        }
    }

    private val terminal = Terminal()
    private var state = WizardState()
    private var activeStepIndex = 0
    private val prompts = Prompts(terminal) { state.toUiState(activeStepIndex) }

    // Prompt unless told not to; EOF on a non-terminal stdin aborts cleanly,
    // so scripts and pipes never hang.
    private val interactive: Boolean
        get() = !nonInteractive

    private enum class Outcome { PROMPTED, AUTO, BACK }

    private lateinit var repo: TemplateRepository
    private lateinit var repositoryUrl: String
    private var catalog: TemplateCatalog? = null
    private var catalogVersion: String? = null

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
        repositoryUrl = repositoryOpt ?: ProjectCreationInfo.DEFAULT_REPOSITORY_URL
        repo = TemplateRepository(repositoryUrl)

        runWizardSteps()

        val targetDir = state.targetDir!!
        checkJdkEnvironment(state.jmixVersion!!)
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
        )

        terminal.println(gray("Generating project..."))
        try {
            ProjectGenerator { terminal.println(brightYellow("Warning: $it")) }
                .generate(catalogFor(info.jmixVersion).templateRoot(state.template!!.id), info)
        } catch (e: RuntimeException) {
            // Groovy rendering errors (e.g. a missing binding) are template
            // bugs — fail with a message, not a stack trace.
            throw CliktError("Project generation failed: ${e.message ?: e.toString()}")
        }

        printSuccess(info)
        offerOpenAndRun(info)
    }

    /**
     * Linear wizard with back navigation. Steps resolved from flags or
     * defaults are skipped when walking backwards.
     */
    private fun runWizardSteps() {
        val steps = listOf(
            ::stepName, ::stepVersion, ::stepTemplate, ::stepPackage,
            ::stepProjectId, ::stepTheme, ::stepLocales, ::stepPath, ::stepGit,
        )
        val prompted = BooleanArray(steps.size)
        var i = 0
        while (i < steps.size) {
            activeStepIndex = i
            val outcome = steps[i]()
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
        terminal.println(brightGreen("✓ ") + label + ": " + cyan(value))
    }

    private fun catalogFor(version: String): TemplateCatalog {
        if (catalogVersion != version) {
            catalog?.close()
            terminal.println(gray("Loading templates $version..."))
            catalog = TemplateCatalog(repo.templatesJar(version))
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

    private fun fetchVersionsOnce(): List<String> = versionsCache ?: run {
        terminal.println(gray("Fetching available Jmix versions..."))
        repo.fetchVersions(includeUnstable).also { versionsCache = it }
    }

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

    private fun localeDisplayName(code: String): String =
        Locale.forLanguageTag(code.replace('_', '-'))
            .getDisplayName(Locale.ENGLISH)
            .ifEmpty { code }

    private fun stepPath(): Outcome {
        pathOpt?.let {
            state = state.copy(targetDir = toAbsolutePath(it))
            return Outcome.AUTO
        }
        if (!interactive) {
            // Scripts get the conventional CWD-relative default.
            val default = state.targetDir?.toString()
                ?: Path.of("").toAbsolutePath().resolve(state.name!!).toString()
            state = state.copy(targetDir = toAbsolutePath(default))
            return Outcome.AUTO
        }
        // The wizard suggests the JetBrains-style projects directory. Piped
        // input falling back to defaults keeps the script-friendly CWD path.
        val humanAtKeyboard = terminal.terminalInfo.inputInteractive && !prompts.isInputExhausted
        val default = state.targetDir?.toString()
            ?: if (humanAtKeyboard) {
                Path.of(System.getProperty("user.home"), "IdeaProjects", state.name!!).toString()
            } else {
                Path.of("").toAbsolutePath().resolve(state.name!!).toString()
            }
        return when (
            val answer = prompts.ask(
                "Enter project location", default, allowBack = true,
                complete = { PathCompleter.complete(it) },
            )
        ) {
            is Answer.Back -> Outcome.BACK
            is Answer.Value -> {
                val targetDir = toAbsolutePath(answer.value)
                state = state.copy(targetDir = targetDir)
                summary("Location", targetDir.toString())
                Outcome.PROMPTED
            }
        }
    }

    private fun toAbsolutePath(raw: String): Path {
        val expanded = if (raw.startsWith("~/")) {
            System.getProperty("user.home") + raw.substring(1)
        } else raw
        return Path.of(expanded).toAbsolutePath().normalize()
    }

    private fun stepGit(): Outcome {
        if (noGit) {
            state = state.copy(createGit = false)
            return Outcome.AUTO
        }
        if (!EnvironmentCheck.isGitAvailable()) {
            terminal.println(brightYellow("git is not available — skipping repository initialization."))
            state = state.copy(createGit = false)
            return Outcome.AUTO
        }
        if (!interactive) {
            state = state.copy(createGit = true)
            return Outcome.AUTO
        }
        return when (val answer = prompts.askYesNo("Create Git repository?", state.createGit ?: true, allowBack = true)) {
            is Answer.Back -> Outcome.BACK
            is Answer.Value -> {
                state = state.copy(createGit = answer.value)
                summary("Git repository", if (answer.value) "yes" else "no")
                Outcome.PROMPTED
            }
        }
    }

    // --- Post-wizard checks and output ----------------------------------------

    private fun checkJdkEnvironment(jmixVersion: String) {
        val check = EnvironmentCheck.checkJdk(jmixVersion)
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

    private fun offerOpenAndRun(info: ProjectCreationInfo) {
        val runTask = runTaskFor(info) ?: return
        // Only when a human can answer. Piped stdin would hit EOF here and
        // abort with a non-zero exit after the project was already created.
        if (!interactive || !terminal.terminalInfo.inputInteractive) return
        val question = "Open the project and run ./gradlew $runTask now?"
        if (!prompts.askYesNo(question, default = false).requireValue()) {
            offerJdkInstall(info)
            return
        }

        val open = ProjectLauncher.openCommand(info.projectDir)
        terminal.println(gray("Opening the project in ${open.opener.displayName}..."))
        ProjectLauncher.open(open) { terminal.println(brightYellow("Warning: $it")) }

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
        terminal.println(gray("Downloading JDK $major (Temurin)..."))
        return try {
            var lastPercent = -1
            val home = JdkInstaller.install(major) { done, total ->
                if (total > 0) {
                    val percent = (done * 100 / total).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        terminal.rawPrint("\r  $percent% of ${total / MEGABYTE} MB")
                    }
                }
            }
            terminal.println()
            terminal.println(brightGreen("✓ ") + "Installed JDK $major at " + cyan(home.toString()))
            home
        } catch (e: Exception) {
            // Also covers JSON parse errors from an unexpected API response —
            // never a stack trace after the project was already generated.
            terminal.println()
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
        const val MEGABYTE = 1024L * 1024

        val USEFUL_LINKS = listOf(
            UsefulLink("📖", "Documentation", "https://docs.jmix.io/jmix/intro.html"),
            UsefulLink("🤖", "AI Assistant", "https://ai-assistant.jmix.io/"),
            UsefulLink("🧩", "Samples", "https://github.com/jmix-framework/jmix-samples-2"),
            UsefulLink("💬", "Forum", "https://forum.jmix.io/"),
        )

        val COMMON_LOCALES = listOf(
            "en" to "English", "de" to "German", "fr" to "French", "es" to "Spanish",
            "it" to "Italian", "pt" to "Portuguese", "nl" to "Dutch", "pl" to "Polish",
            "cs" to "Czech", "tr" to "Turkish", "uk" to "Ukrainian", "ru" to "Russian",
            "ar" to "Arabic", "zh" to "Chinese", "ja" to "Japanese", "ko" to "Korean",
        )
    }
}
