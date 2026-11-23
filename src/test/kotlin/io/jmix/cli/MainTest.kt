package io.jmix.cli

import io.jmix.cli.telemetry.UsageStatistics
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MainTest {

    @Test
    fun `the command menu lists the commands in their order`(@TempDir dir: Path) {
        val names = rootCommand(UsageStatistics(file = dir.resolve("installation-id")))
            .registeredSubcommands().map { it.commandName }
        assertEquals(listOf("new", "trial", "agent-toolkit", "login", "logout", "update"), names)
    }

    @Test
    fun `every command menu row fits an 80-column terminal`(@TempDir dir: Path) {
        val commands = rootCommand(UsageStatistics(file = dir.resolve("installation-id"))).registeredSubcommands()
        val titleWidth = commands.maxOf { it.commandName.length }
        // The numbered menu is the wider one: "  * 6) <name padded to the longest> — <help>".
        val prefix = "  * ${commands.size}) ".length
        for (command in commands) {
            val row = prefix + titleWidth + " — ".length + command.help(command.resetContext()).length
            assertTrue(row < 80, "${command.commandName}: $row columns")
        }
    }
}
