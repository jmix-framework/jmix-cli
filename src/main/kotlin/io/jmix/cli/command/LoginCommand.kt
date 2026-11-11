package io.jmix.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.mordant.rendering.TextColors.brightYellow
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.terminal.Terminal
import io.jmix.cli.account.AccountRegion
import io.jmix.cli.account.BrowserSignIn
import io.jmix.cli.env.ProjectLauncher
import io.jmix.cli.telemetry.UsageEvent
import io.jmix.cli.telemetry.UsageStatistics
import io.jmix.cli.update.SelfUpdater
import io.jmix.cli.util.describeIoError

/**
 * Prints the sign-in page address, tries to open it in the browser (a failure is only a warning, the user can
 * visit the printed address), waits for the sign-in to finish and names the account. A browser that still has a
 * session signs in without showing a page, so that last line is how the user learns the account, also in
 * `jmix trial` before it asks to start a trial. The wait has no spinner: it invites Ctrl+C, which ends the JVM
 * before a spinner could show the cursor again. [openUrl] is how the browser gets opened; tests replace it so
 * that no real browser starts.
 */
internal fun signInInteractively(
    terminal: Terminal,
    signIn: BrowserSignIn,
    usageStatistics: UsageStatistics? = null,
    openUrl: (String, (String) -> Unit) -> Unit = ProjectLauncher::openUrl,
) {
    val identity = signIn.start().use { pending ->
        terminal.println("Opening the Jmix sign-in page in your browser. If it does not open, visit:")
        terminal.println(cyan(pending.url))
        openUrl(pending.url) { terminal.println(brightYellow("Warning: $it")) }
        terminal.println(gray("Waiting for sign-in in the browser (Ctrl+C to cancel)..."))
        pending.complete()
    }
    terminal.println(identity.signedInMessage())
    usageStatistics?.record(UsageEvent.USER_LOGIN)
}

class LoginCommand internal constructor(private val usageStatistics: UsageStatistics?) : CliktCommand(name = "login") {

    constructor() : this(null)

    override fun help(context: Context) = "Sign in to your Jmix account in the browser"

    private val region by option("--region", help = "Jmix account region: global or ru (default: global)")
        .choice("global", "ru")
        .default("global")

    // Acted on in main(), before parsing; declared so the parser accepts it after the command name.
    @Suppress("unused")
    private val noUpdate by option(SelfUpdater.NO_UPDATE_FLAG, help = "Skip the startup update check").flag()

    override fun run() {
        try {
            signInInteractively(terminal, BrowserSignIn(AccountRegion.of(region)), usageStatistics)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CliktError("Sign-in was interrupted.")
        } catch (e: Exception) {
            // Expected failures (denied, timed out, unreachable) must stay readable; never a stack trace.
            throw CliktError("Sign-in failed: ${describeIoError(e, "the Jmix sign-in service")}.")
        }
    }
}
