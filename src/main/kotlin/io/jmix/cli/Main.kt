package io.jmix.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.jmix.cli.update.SelfUpdater

class JmixCli : CliktCommand(name = "jmix") {
    override fun help(context: Context) = "Jmix CLI"

    override val invokeWithoutSubcommand = true

    // Declared so the parser accepts it anywhere on the command line; the flag
    // is acted on in main(), before parsing, because the update happens first.
    @Suppress("unused")
    private val noUpdate by option(SelfUpdater.NO_UPDATE_FLAG, help = "Skip the startup update check").flag()

    override fun run() {
        if (currentContext.invokedSubcommand == null) {
            NewCommand().parse(emptyList())
        }
    }
}

fun main(args: Array<String>) {
    WindowsConsole.enableUtf8()
    // Before parsing: an available update is installed and the command re-runs
    // on the new version, so it never executes on a superseded build.
    SelfUpdater.runStartupMaintenance(args)
    JmixCli()
        .subcommands(NewCommand(), UpdateCommand())
        .main(args)
}
