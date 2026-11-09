package io.jmix.cli

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import io.jmix.cli.command.JmixCli
import io.jmix.cli.command.LoginCommand
import io.jmix.cli.command.LogoutCommand
import io.jmix.cli.command.NewCommand
import io.jmix.cli.command.ReturnToMainMenu
import io.jmix.cli.command.TrialCommand
import io.jmix.cli.command.UpdateCommand
import io.jmix.cli.repo.TemplateRepository
import io.jmix.cli.telemetry.UsageStatistics
import io.jmix.cli.update.SelfUpdater

fun main(args: Array<String>) {
    WindowsConsole.enableUtf8()
    // Before parsing: an available update is installed and the command re-runs
    // on the new version, so it never executes on a superseded build.
    SelfUpdater.runStartupMaintenance(args)
    val usageStatistics = UsageStatistics()
    val cli = JmixCli().subcommands(
        NewCommand(TemplateRepository.defaultCacheDir(), usageStatistics),
        TrialCommand(usageStatistics = usageStatistics),
        LoginCommand(usageStatistics),
        LogoutCommand(),
        UpdateCommand(),
    )
    var arguments = args
    while (true) {
        try {
            cli.main(arguments)
            return
        } catch (_: ReturnToMainMenu) {
            arguments = emptyArray()
        }
    }
}
