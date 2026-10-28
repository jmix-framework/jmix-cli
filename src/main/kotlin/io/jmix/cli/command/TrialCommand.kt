package io.jmix.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.rendering.TextColors.brightGreen
import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.rendering.TextStyles.bold
import io.jmix.cli.account.AccessTokens
import io.jmix.cli.account.AccountEndpoints
import io.jmix.cli.account.AccountRegion
import io.jmix.cli.account.BrowserSignIn
import io.jmix.cli.account.Session
import io.jmix.cli.account.SessionExpiredException
import io.jmix.cli.account.SessionStore
import io.jmix.cli.account.StoreClient
import io.jmix.cli.trial.TrialArchive
import io.jmix.cli.trial.TrialException
import io.jmix.cli.trial.findOrStartTrial
import io.jmix.cli.trial.trialSummary
import io.jmix.cli.update.SelfUpdater
import io.jmix.cli.wizard.Prompts
import io.jmix.cli.wizard.StatusReporter
import io.jmix.cli.wizard.requireValue
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * Downloads the Enterprise+BPM trial project of the signed-in account and unpacks it. Internal because the
 * constructor exposes the internal [SessionStore]; [sessions] and [today] let tests run it without the real
 * session or clock. All output goes through the command's terminal, so tests capture it.
 */
internal class TrialCommand(
    private val sessions: SessionStore = SessionStore(),
    private val today: () -> LocalDate = LocalDate::now,
) : CliktCommand(name = "trial") {

    override fun help(context: Context) = "Download the Enterprise+BPM trial project of your Jmix account"

    private val path by option(
        "--path", help = "Directory to unpack the trial project into (default: current directory)",
    )
    private val startTrial by option(
        "--start-trial", help = "Start the trial without asking when the account has none yet",
    ).flag()
    private val nonInteractive by option(
        "--non-interactive", help = "Never prompt or open a browser; fail instead",
    ).flag()

    // Acted on in main(), before parsing; declared so the parser accepts it after the command name.
    @Suppress("unused")
    private val noUpdate by option(SelfUpdater.NO_UPDATE_FLAG, help = "Skip the startup update check").flag()

    /** Prompts and the browser need a person at a terminal; scripts and pipes get an error instead. */
    private val interactive: Boolean
        get() = !nonInteractive && terminal.terminalInfo.inputInteractive

    override fun run() {
        // Until the session names the region, failures point to the global account page.
        var region = AccountRegion.GLOBAL
        try {
            val parent = trialDirectory()
            val session = sessions.load() ?: signIn()
            region = AccountRegion.of(session.region)
            val store = StoreClient(
                AccountEndpoints(session.ssoUrl, session.storeUrl).storeApiUrl,
                AccessTokens(sessions, session),
            )
            terminal.println(gray("Checking your Enterprise+BPM trial..."))
            // Not inside status.run: its spinner thread would draw over the consent prompt.
            val trial = findOrStartTrial(store, today(), region, ::confirmStart)
            val status = StatusReporter(terminal)
            val projects = downloadAndUnpack(trial.url, parent, status)
            projects.mapNotNull(TrialArchive::jmixVersion).forEach { reportJdkCheck(terminal, status, it) }
            printSummary(trialSummary(projects, trial.endDate, Path.of("").toAbsolutePath()))
        } catch (e: CliktError) {
            // Already says what to do; also passes the prompt's quit (exit 0) and Ctrl+C ("Aborted.") through.
            throw e
        } catch (e: TrialException) {
            throw CliktError(e.message)
        } catch (e: SessionExpiredException) {
            throw CliktError(e.message)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CliktError("Interrupted.")
        } catch (e: Exception) {
            // Expected network and file failures must stay readable; describe() never quotes the download link.
            throw CliktError(
                "Could not get the trial project: ${SelfUpdater.describe(e, "the Jmix Store")}. " +
                    "You can also download it from ${region.accountPageUrl}.",
            )
        }
    }

    /** The --path directory, created before anything else so a bad path fails before a trial can be started. */
    private fun trialDirectory(): Path {
        val parent = path?.let(::toAbsolutePath) ?: Path.of("").toAbsolutePath()
        if (Files.exists(parent) && !Files.isDirectory(parent)) throw CliktError("$parent is not a directory.")
        try {
            Files.createDirectories(parent)
        } catch (e: IOException) {
            throw CliktError("Cannot use $parent as the trial directory: ${SelfUpdater.describe(e)}.")
        }
        return parent
    }

    /** Signs in in the browser, as `jmix login` does, when a person is there to do it. */
    private fun signIn(): Session {
        if (!interactive) throw CliktError(NOT_SIGNED_IN)
        signInInteractively(terminal, BrowserSignIn(AccountRegion.GLOBAL, sessions = sessions))
        return sessions.load() ?: throw CliktError(NOT_SIGNED_IN)
    }

    /** Starting uses the account's trial up, so it takes --start-trial or an explicit yes; the default is no. */
    private fun confirmStart(): Boolean = when {
        startTrial -> true
        interactive -> Prompts(terminal)
            .askYesNo("Start the 28-day Enterprise+BPM trial for your Jmix account now?", default = false)
            .requireValue()
        else -> throw CliktError("Starting the Enterprise+BPM trial needs confirmation. Re-run with --start-trial.")
    }

    /** Downloads into the system temp directory, so an interrupted run leaves nothing in [parent], then unpacks. */
    private fun downloadAndUnpack(url: String, parent: Path, status: StatusReporter): List<Path> {
        val archive = Files.createTempFile("jmix-trial-", ".zip")
        // Ctrl+C ends the JVM without running the finally below; deleteOnExit still runs then.
        archive.toFile().deleteOnExit()
        try {
            status.run("Downloading the trial project") { p -> TrialArchive.download(url, archive, p::progress) }
            return TrialArchive.unpack(archive, parent)
        } finally {
            runCatching { Files.deleteIfExists(archive) }
        }
    }

    private fun printSummary(lines: List<String>) {
        terminal.println()
        lines.forEach { line ->
            when {
                line.startsWith("✓") -> terminal.println(brightGreen(line))
                line == "Next steps:" -> {
                    terminal.println()
                    terminal.println(bold(line))
                }
                else -> terminal.println(line)
            }
        }
    }

    private companion object {
        const val NOT_SIGNED_IN = "Not signed in to a Jmix account. Run 'jmix login' first."
    }
}
