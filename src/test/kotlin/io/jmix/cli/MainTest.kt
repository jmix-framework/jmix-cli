package io.jmix.cli

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import io.jmix.cli.account.Session
import io.jmix.cli.account.SessionStore
import io.jmix.cli.command.JmixCli
import io.jmix.cli.telemetry.UsageStatistics
import java.io.ByteArrayInputStream
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
        val sessions = SessionStore(dir.resolve("session.json"))
        val shown = mutableSetOf<String>()
        // The numbered menu, the wider one; signed out it offers login, signed in logout.
        for (signedIn in listOf(false, true)) {
            if (signedIn) sessions.save(Session("global", "https://sso.invalid", "https://store.invalid", "fixture"))
            val rows = menu(JmixCli(sessions).subcommands(commands)).lines()
            for (command in commands) {
                val help = " — " + command.help(command.resetContext())
                val row = rows.firstOrNull { it.contains(") ${command.commandName} ") } ?: continue
                // The captured output never wraps, so the length is what catches a row that does not fit.
                assertTrue(row.endsWith(help) && row.length < 80, "${row.length} columns: $row")
                shown += command.commandName
            }
        }
        assertEquals(commands.map { it.commandName }.toSet(), shown)
    }

    private fun menu(root: JmixCli): String {
        val original = System.`in`
        System.setIn(ByteArrayInputStream("q\n".toByteArray()))
        try {
            return root.test("", width = 80).stdout
        } finally {
            System.setIn(original)
        }
    }
}
