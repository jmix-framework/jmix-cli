package io.jmix.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.jmix.cli.update.CacheCleaner
import io.jmix.cli.update.CliInstallation
import io.jmix.cli.update.SelfUpdater
import io.jmix.cli.update.UpdateResult

class UpdateCommand : CliktCommand(name = "update") {

    override fun help(context: Context) = "Update Jmix CLI to the latest release and remove old versions"

    // Accepted for symmetry with the other commands; `jmix update` never runs
    // the startup check anyway.
    @Suppress("unused")
    private val noUpdate by option(SelfUpdater.NO_UPDATE_FLAG, help = "Skip the startup update check").flag()

    override fun run() {
        val installation = CliInstallation.detect()
            ?: throw CliktError(
                "This Jmix CLI does not run from an installed release; nothing to update. " +
                    "Use the install command from the README instead.",
            )
        val updater = SelfUpdater(installation, echo = { echo(it) })
        val result = try {
            updater.update()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CliktError("Update was interrupted.")
        } catch (e: Exception) {
            // Expected network, permission and archive failures must stay
            // readable; never surface a Java stack trace here.
            throw CliktError("Update failed: ${SelfUpdater.describe(e)}.")
        }
        when (result) {
            is UpdateResult.UpToDate -> echo("Jmix CLI is already up to date.")
            is UpdateResult.Updated -> echo("Updated Jmix CLI to the latest release.")
            is UpdateResult.InstallerRequired -> throw CliktError(
                "The 'jmix' command was not installed by the Jmix CLI installer. " +
                    "Re-run the install command from the README to update.",
            )
        }
        runCatching { updater.cleanupOldVersions() }
        runCatching { CacheCleaner.pruneTemplateCache() }
    }
}
