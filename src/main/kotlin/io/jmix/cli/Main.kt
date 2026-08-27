package io.jmix.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import io.jmix.cli.update.SelfUpdater

class JmixCli : CliktCommand(name = "jmix") {
    override fun help(context: Context) = "Jmix CLI"

    override val invokeWithoutSubcommand = true

    override fun run() {
        // `jmix update` performs an explicit update itself; skip the auto pass.
        if (currentContext.invokedSubcommand !is UpdateCommand) {
            SelfUpdater.runStartupMaintenance()
        }
        if (currentContext.invokedSubcommand == null) {
            NewCommand().parse(emptyList())
        }
    }
}

fun main(args: Array<String>) {
    WindowsConsole.enableUtf8()
    JmixCli()
        .subcommands(NewCommand(), UpdateCommand())
        .main(args)
}
