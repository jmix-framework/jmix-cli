package io.jmix.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import io.jmix.cli.update.CacheCleaner
import io.jmix.cli.update.CliInstallation
import io.jmix.cli.update.SelfUpdater
import io.jmix.cli.update.UpdateResult
import java.net.ConnectException
import java.net.UnknownHostException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException

class UpdateCommand : CliktCommand(name = "update") {

    override fun help(context: Context) = "Update Jmix CLI to the latest release and remove old versions"

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
            throw CliktError("Update failed: ${explain(e)}")
        }
        when (result) {
            UpdateResult.UP_TO_DATE -> echo("Jmix CLI is already up to date.")
            UpdateResult.UPDATED -> echo("Updated Jmix CLI; the new version takes effect on the next run.")
            UpdateResult.INSTALLER_REQUIRED -> throw CliktError(
                "The 'jmix' command was not installed by the Jmix CLI installer. " +
                    "Re-run the install command from the README to update.",
            )
        }
        runCatching { updater.cleanupOldVersions() }
        runCatching { CacheCleaner.pruneTemplateCache() }
    }

    private fun explain(e: Exception): String = when (e) {
        is UnknownHostException, is ConnectException ->
            "cannot reach the release server. Check your network connection."
        is AccessDeniedException -> "no permission to write ${e.file}."
        is FileSystemException -> "${SelfUpdater.describe(e)} (${e.file})"
        else -> SelfUpdater.describe(e)
    }
}
