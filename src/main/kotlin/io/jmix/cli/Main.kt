package io.jmix.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands

class JmixCli : CliktCommand(name = "jmix") {
    override fun help(context: Context) = "Jmix CLI"

    override val invokeWithoutSubcommand = true

    override fun run() {
        if (currentContext.invokedSubcommand == null) {
            NewCommand().parse(emptyList())
        }
    }
}

fun main(args: Array<String>) = JmixCli()
    .subcommands(NewCommand())
    .main(args)
