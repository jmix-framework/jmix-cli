package io.jmix.cli

import io.jmix.cli.telemetry.UsageStatistics
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MainTest {

    @Test
    fun `the command menu lists the commands in their order`(@TempDir dir: Path) {
        val names = rootCommand(UsageStatistics(file = dir.resolve("installation-id")))
            .registeredSubcommands().map { it.commandName }
        assertEquals(listOf("new", "trial", "agent-toolkit", "login", "logout", "update"), names)
    }
}
