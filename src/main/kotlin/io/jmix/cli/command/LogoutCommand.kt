package io.jmix.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.jmix.cli.account.SessionStore
import io.jmix.cli.update.SelfUpdater
import io.jmix.cli.util.describeIoError
import java.io.IOException

/**
 * Forgets the signed-in account on this computer; it makes no call to the server. Internal because the
 * constructor exposes the internal [SessionStore], which lets tests use a session file of their own.
 */
internal class LogoutCommand(private val sessions: SessionStore = SessionStore()) : CliktCommand(name = "logout") {

    override fun help(context: Context) = "Sign out of your Jmix account on this computer"

    // Acted on in main(), before parsing; declared so the parser accepts it after the command name.
    @Suppress("unused")
    private val noUpdate by option(SelfUpdater.NO_UPDATE_FLAG, help = "Skip the startup update check").flag()

    override fun run() {
        val wasSignedIn = try {
            sessions.delete()
        } catch (e: IOException) {
            throw CliktError("Could not sign out: ${describeIoError(e)}.")
        }
        echo(if (wasSignedIn) "Signed out of your Jmix account." else "You are not signed in.")
    }
}
