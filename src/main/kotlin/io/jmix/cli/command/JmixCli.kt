package io.jmix.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.rendering.TextColors.brightRed
import com.github.ajalt.mordant.rendering.TextColors.brightGreen
import com.github.ajalt.mordant.rendering.TextColors.brightYellow
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.rendering.TextStyles.bold
import io.jmix.cli.account.Session
import io.jmix.cli.account.SessionStore
import io.jmix.cli.update.SelfUpdater
import io.jmix.cli.util.cliVersion
import io.jmix.cli.wizard.Banner
import io.jmix.cli.wizard.Prompts
import io.jmix.cli.wizard.requireValue
import kotlin.time.Duration
import kotlin.time.TimeSource

/** Navigation back to the menu, distinct from quitting the application. */
internal class ReturnToMainMenu : RuntimeException()

internal fun accountHeader(session: Session?): List<List<Pair<String, TextStyle>>> = buildList {
    add(buildList {
        add("Jmix account" to gray)
        if (session != null) add(" [${session.region}]" to (cyan + bold))
    })
    when {
        session == null -> add(listOf("Not signed in" to (brightYellow + bold)))
        session.identity.displayName == null -> add(listOf("Signed in" to (brightGreen + bold)))
        else -> {
            add(listOf((session.identity.name ?: session.identity.email.orEmpty()) to (brightGreen + bold)))
            if (session.identity.name != null && session.identity.email != null) {
                add(listOf(session.identity.email to gray))
            }
        }
    }
}

internal class JmixCli(private val sessions: SessionStore = SessionStore()) : CliktCommand(name = "jmix") {
    override fun help(context: Context) = "Jmix CLI"

    override val invokeWithoutSubcommand = true

    // Declared so the parser accepts it anywhere on the command line; the flag
    // is acted on in main(), before parsing, because the update happens first.
    @Suppress("unused")
    private val noUpdate by option(SelfUpdater.NO_UPDATE_FLAG, help = "Skip the startup update check").flag()

    override fun run() {
        if (currentContext.invokedSubcommand != null) return
        var firstMenu = true
        var failure: String? = null
        while (true) {
            val session = sessions.load()
            val hiddenCommand = if (session == null) "logout" else "login"
            val commands = registeredSubcommands().filter { it.commandName != hiddenCommand }
            val account = accountHeader(session)
            val animate = firstMenu && terminal.terminalInfo.inputInteractive && terminal.terminalInfo.outputInteractive &&
                !terminal.terminalInfo.supportsAnsiCursor && Banner.supportsBlocks() &&
                System.getenv("NO_COLOR") == null && System.getenv("TERM") != "dumb" && System.getenv("CI").isNullOrEmpty()
            val duration = if (animate) Banner.INTRO_DURATION else Duration.ZERO
            val started = TimeSource.Monotonic.markNow()
            val prompts = Prompts(
                terminal,
                header = { width ->
                    Banner.lines(
                        width, cliVersion(), account = account,
                        introProgress = if (animate) started.elapsedNow() / duration else 1.0,
                    )
                },
                compactHeader = { width -> Banner.accountLines(width, account) },
                headerAnimationDuration = duration,
                exitOnEof = true,
            )
            if (!prompts.usesAlternateScreen) Banner.print(terminal, cliVersion(), account = account)
            lateinit var selected: String
            prompts.useAlternateScreen {
                selected = prompts.choose(
                    question = "What would you like to do?",
                    items = commands,
                    title = { it.commandName },
                    description = { it.help(currentContext) },
                    legend = failure?.let { brightRed(it) },
                ).requireValue().commandName
            }
            firstMenu = false
            failure = null
            // Parse through the root so the selected command inherits the same terminal and context settings.
            if (selected != "login" && selected != "logout") {
                try {
                    parse(listOf(selected))
                } catch (_: ReturnToMainMenu) {
                    continue
                }
                return
            }
            try {
                parse(listOf(selected))
            } catch (e: CliktError) {
                if (e.statusCode == 0 || Thread.currentThread().isInterrupted) throw e
                failure = e.message ?: "Could not change the Jmix account."
            }
        }
    }
}
