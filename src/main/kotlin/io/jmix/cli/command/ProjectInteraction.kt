package io.jmix.cli.command

import com.github.ajalt.mordant.rendering.TextColors.brightGreen
import com.github.ajalt.mordant.rendering.TextColors.brightYellow
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.SelectList
import io.jmix.cli.env.EnvironmentCheck
import io.jmix.cli.env.JdkInstaller
import io.jmix.cli.env.JdkVersions
import io.jmix.cli.env.ProjectLauncher
import io.jmix.cli.wizard.Answer
import io.jmix.cli.wizard.PathCompleter
import io.jmix.cli.wizard.Prompts
import io.jmix.cli.wizard.StatusReporter
import io.jmix.cli.wizard.requireValue
import java.nio.file.Path

internal fun projectLocationOptions(projectName: String, currentDir: Path, homeDir: Path): List<Pair<String, Path>> =
    listOf(
        "Current directory" to currentDir,
        "Subdirectory" to currentDir.resolve(projectName).normalize(),
        "IdeaProjects" to homeDir.resolve("IdeaProjects").resolve(projectName).normalize(),
    )

internal fun chooseProjectLocation(
    prompts: Prompts,
    projectName: String,
    previous: Path? = null,
    currentDir: Path = Path.of("").toAbsolutePath().normalize(),
    homeDir: Path = Path.of(System.getProperty("user.home")),
): Answer<Path> {
    val builtInOptions = projectLocationOptions(projectName, currentDir, homeDir)
    val options: List<Pair<String, Path?>> = builtInOptions + ("Other..." to null)
    return when (val picked = prompts.choose(
        "Select project location", options, { it.first }, { it.second?.toString() }, allowBack = true,
    )) {
        is Answer.Back -> Answer.Back
        is Answer.Value -> picked.value.second?.let { Answer.Value(it) } ?: run {
            val previousCustom = previous?.takeIf { path -> builtInOptions.none { it.second == path } }
            when (val typed = prompts.ask(
                "Enter project location", previousCustom?.toString(), allowBack = true,
                complete = { PathCompleter.complete(it) },
                validate = { if (it.isBlank()) "Path cannot be empty." else null },
            )) {
                is Answer.Back -> Answer.Back
                is Answer.Value -> Answer.Value(toAbsolutePath(typed.value))
            }
        }
    }
}

internal fun displayPath(path: Path, currentDir: Path): String =
    runCatching { currentDir.relativize(path) }
        .map { if (it.startsWith("..")) path else it }
        .getOrDefault(path).toString().ifEmpty { "." }

/** A wrapper command that also works when the runnable build is inside a composite project. */
internal fun projectRunCommand(
    projectDir: Path,
    runDir: Path,
    task: String,
    os: String = System.getProperty("os.name"),
): String {
    val wrapper = if (os.startsWith("Windows", ignoreCase = true)) "gradlew.bat" else "gradlew"
    val relative = projectDir.relativize(runDir)
    val args = if (relative.toString().isEmpty()) listOf("./$wrapper", task)
    else listOf("./${relative.resolve(wrapper)}", "-p", relative.toString(), task)
    val command = args.joinToString(" ") { shellQuote(it, os) }
    return if (os.startsWith("Windows", ignoreCase = true) && command.startsWith("'")) "& $command" else command
}

internal fun projectNextSteps(
    projectDir: Path,
    currentDir: Path,
    runTask: String?,
    runDir: Path = projectDir,
): List<String> = listOf(
    "Next steps:",
    "  1. cd ${shellQuote(displayPath(projectDir, currentDir))}",
    if (runTask == null) "  2. Read README.md for instructions on adding subprojects."
    else "  2. ${projectRunCommand(projectDir, runDir, runTask)}",
)

/** Shared follow-up for generated projects and downloaded trials. */
internal class ProjectActions(
    private val projectDir: Path,
    private val jmixVersion: String?,
    private val terminal: Terminal,
    private val prompts: Prompts,
    private val status: StatusReporter,
) {
    fun offer(runTask: String?, runDirectories: List<Path> = listOf(projectDir)) {
        val ideaOpen = ProjectLauncher.ideaOpenCommand(projectDir)
        val actionLabels = buildMap {
            if (ideaOpen != null) put(OPEN_IN_IDE, "Open the project in ${ideaOpen.opener.displayName}")
            if (runTask != null) {
                val label = if (runDirectories.size > 1) "Run a demo module"
                else "Run the application (${projectRunCommand(projectDir, runDirectories.single(), runTask)})"
                put(RUN, label)
            }
        }
        val entries = actionLabels.values.map { SelectList.Entry(it) }
        val actionIds = actionLabels.keys.toList()
        while (true) {
            val picked = when {
                entries.isEmpty() -> emptyList()
                else -> when (val answer = prompts.chooseMany("What's next?", entries, values = actionIds)) {
                    null -> actionLabels.mapNotNull { (id, label) ->
                        id.takeIf { prompts.askYesNo("$label?", default = false).requireValue() }
                    }
                    is Answer.Back -> emptyList()
                    is Answer.Value -> answer.value
                }
            }
            if (prompts.isInputExhausted) return
            val runDir = if (runTask != null && RUN in picked) {
                if (runDirectories.size == 1) runDirectories.single()
                else when (val module = prompts.choose(
                    "Select a demo module to run", runDirectories,
                    { projectDir.relativize(it).toString() },
                    { "http://localhost:${ProjectLauncher.serverPort(it)}" }, allowBack = true,
                )) {
                    is Answer.Back -> continue
                    is Answer.Value -> module.value
                }
            } else null
            if (prompts.isInputExhausted) return

            openProject(ideaOpen.takeIf { OPEN_IN_IDE in picked })
            if (runTask != null) {
                val javaHome = jmixVersion?.let { EnvironmentCheck.checkJdk(it).compatible.firstOrNull()?.home }
                if (runDir != null) runApplication(runDir, runTask, javaHome)
                else if (javaHome == null) offerJdkInstall()
            }
            return
        }
    }

    private fun openProject(ideaOpen: ProjectLauncher.OpenCommand?) {
        val warn: (String) -> Unit = { terminal.println(brightYellow("Warning: $it")) }
        if (ideaOpen != null) {
            terminal.println(gray("Opening the project in ${ideaOpen.opener.displayName}..."))
            if (ProjectLauncher.open(ideaOpen, warn)) return
        }
        terminal.println(gray("Opening the project folder..."))
        ProjectLauncher.open(ProjectLauncher.fileManagerOpenCommand(projectDir), warn)
    }

    private fun runApplication(runDir: Path, runTask: String, compatibleJavaHome: Path?) {
        val javaHome = compatibleJavaHome ?: installJdk(jdkVersionToInstall()) ?: run {
            terminal.println(brightYellow("Skipping the run — no compatible JDK is available."))
            return
        }
        terminal.println(gray("Running ${projectRunCommand(projectDir, runDir, runTask)} (Ctrl+C to stop)..."))
        if (runTask == "bootRun") openAppInBrowserWhenReady(runDir)
        val exitCode = ProjectLauncher.runGradle(runDir, runTask, javaHome)
        if (exitCode != 0) terminal.println(brightYellow("Gradle finished with exit code $exitCode."))
    }

    private fun offerJdkInstall() {
        val major = jdkVersionToInstall() ?: return
        if (prompts.askYesNo(
                "No compatible JDK was found. Install JDK $major (Temurin) now so the project can run later?",
                default = true,
            ).requireValue()) installJdk(major)
    }

    private fun jdkVersionToInstall(): Int? = jmixVersion?.let { JdkVersions.supportedJdks(it).max() }

    private fun installJdk(major: Int?): Path? {
        if (major == null) {
            terminal.println(brightYellow("Cannot determine the trial's Jmix version. Run the demo manually with a compatible JDK."))
            return null
        }
        return try {
            val home = status.run("Installing JDK $major (Temurin)") { progress ->
                JdkInstaller.install(major, onStatus = progress::relabel, onProgress = progress::progress)
            }
            terminal.println(brightGreen("✓ ") + "Installed JDK $major at " + cyan(home.toString()))
            home
        } catch (e: Exception) {
            terminal.println(brightYellow("Warning: JDK installation failed: ${e.message}"))
            terminal.println(brightYellow(EnvironmentCheck.installHint(setOf(major))))
            null
        }
    }

    private fun openAppInBrowserWhenReady(runDir: Path) {
        val port = ProjectLauncher.serverPort(runDir)
        val url = "http://localhost:$port"
        // A listener that predates the Gradle run belongs to another application.
        if (ProjectLauncher.isPortInUse(port)) {
            terminal.println(brightYellow("Warning: port $port is already in use — not opening the browser."))
            return
        }
        terminal.println(gray("The app will open at $url in your browser once it starts."))
        ProjectLauncher.openBrowserWhenReady(port, url) { terminal.println(brightYellow("Warning: $it")) }
    }

    private companion object {
        const val OPEN_IN_IDE = "open-in-ide"
        const val RUN = "run"
    }
}
