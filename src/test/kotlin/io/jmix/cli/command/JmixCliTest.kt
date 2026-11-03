package io.jmix.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import com.github.ajalt.mordant.input.KeyboardEvent
import com.github.ajalt.mordant.rendering.AnsiLevel
import io.jmix.cli.account.Session
import io.jmix.cli.account.Identity
import io.jmix.cli.account.SessionStore
import io.jmix.cli.wizard.ANSI_SEQUENCE
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class JmixCliTest {

    @TempDir lateinit var dir: Path
    private val sessions get() = SessionStore(dir.resolve("session.json"))

    private class RecordingCommand(name: String, private val action: () -> Unit = {}) : CliktCommand(name = name) {
        var runs = 0
        override fun help(context: Context) = "About $commandName"
        override fun run() {
            runs++
            action()
            echo("Ran $commandName")
        }
    }

    private fun root(commands: List<RecordingCommand>) = JmixCli(sessions).subcommands(commands)
    private fun commands() = listOf("new", "trial", "login", "logout", "update").map { RecordingCommand(it) }

    @Test
    fun `account block uses two or three rows without empty or duplicated details`() {
        val session = Session("global", "https://sso.invalid", "https://store.invalid", "fixture")
        val cases = listOf(
            null to listOf("Jmix account", "Not signed in"),
            session to listOf("Jmix account [global]", "Signed in"),
            session.copy(identity = Identity("Ann", "ann@example.com")) to listOf("Jmix account [global]", "Ann", "ann@example.com"),
            session.copy(identity = Identity("Ann", null)) to listOf("Jmix account [global]", "Ann"),
            session.copy(identity = Identity(null, "ann@example.com")) to listOf("Jmix account [global]", "ann@example.com"),
        )
        for ((saved, expected) in cases) {
            assertEquals(expected, accountHeader(saved).map { row -> row.joinToString("") { it.first } })
        }
    }

    @Test
    fun `short raw menu keeps all three account rows and the commands`() {
        sessions.save(Session("global", "https://sso.invalid", "https://store.invalid", "fixture", Identity("Ann", "ann@example.com")))
        val result = root(commands()).test("", ansiLevel = AnsiLevel.TRUECOLOR, height = 10,
            inputEvents = listOf(KeyboardEvent("q")))
        val plain = ANSI_SEQUENCE.replace(result.stdout, "")
        assertEquals(0, result.statusCode, result.stderr)
        for (text in listOf("Jmix account [global]", "Ann", "ann@example.com", "What would you like to do?", "logout", "update")) {
            assertTrue(plain.contains(text), plain)
        }
        assertFalse(plain.contains("██"), plain)
    }

    @Test
    fun `menu lists commands in order with descriptions and login when signed out`() = withStdin("2\n") {
        val commands = commands()
        val result = root(commands).test("", width = 120)
        assertEquals(0, result.statusCode, result.stderr)
        assertTrue(result.stdout.contains("Jmix CLI"), result.stdout)
        assertTrue(result.stdout.contains("Not signed in"), result.stdout)
        assertTrue(result.stdout.indexOf("Jmix account") < result.stdout.indexOf("What would you like to do?"), result.stdout)
        val rows = result.stdout.lines().filter { it.contains(") ") }
        assertEquals(listOf("new", "trial", "login", "update"), rows.map { it.substringAfter(") ").substringBefore(" ") })
        assertTrue(rows.all { it.contains("About ${it.substringAfter(") ").substringBefore(" ")}") }, result.stdout)
        assertFalse(result.stdout.contains("logout"), result.stdout)
        assertTrue(result.stdout.contains("Ran trial"), result.stdout)
        assertEquals(listOf(0, 1, 0, 0, 0), commands.map { it.runs })
    }

    @Test
    fun `menu shows and executes logout when a session is saved`() = withStdin("3\n") {
        sessions.save(Session("global", "https://sso.jmix.io", "https://store-admin.jmix.io", "fixture"))
        val commands = commands()
        val result = root(commands).test("")
        assertEquals(0, result.statusCode, result.stderr)
        assertTrue(result.stdout.contains("Signed in") && result.stdout.contains("[global]"), result.stdout)
        assertTrue(result.stdout.contains("3) logout"), result.stdout)
        assertFalse(result.stdout.contains("login"), result.stdout)
        assertTrue(result.stdout.contains("Ran logout"), result.stdout)
        assertEquals(listOf(0, 0, 0, 1, 0), commands.map { it.runs })
    }

    @Test
    fun `menu shows the cached identity and region without contacting account services`() = withStdin("q\n") {
        sessions.save(Session("ru", "https://sso.invalid", "https://store.invalid", "PRIVATE", Identity("Ann Lee", "ann@example.com")))
        val result = root(commands()).test("")
        assertEquals(0, result.statusCode, result.stderr)
        assertTrue(result.stdout.contains("Ann Lee") && result.stdout.contains("ann@example.com") && result.stdout.contains("[ru]"), result.stdout)
        assertFalse(result.stdout.contains("PRIVATE"), result.stdout)
    }

    @Test
    fun `login and logout return to the menu with fresh account details and commands`() = withStdin("3\n3\n3\n2\n") {
        val commands = commands().map {
            when (it.commandName) {
                "login" -> RecordingCommand("login") {
                    sessions.save(Session("ru", "https://sso.invalid", "https://store.invalid", "fixture", Identity("Ann", "ann@example.com")))
                }
                "logout" -> RecordingCommand("logout") { sessions.delete() }
                else -> it
            }
        }
        val result = root(commands).test("", width = 120)
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(listOf(0, 1, 2, 1, 0), commands.map { it.runs })
        assertEquals(4, result.stdout.split("What would you like to do?").size - 1, result.stdout)
        assertTrue(result.stdout.substringAfter("Ran login").contains("ann@example.com"), result.stdout)
        val afterLogout = result.stdout.substringAfter("Ran logout")
        assertTrue(afterLogout.indexOf("Not signed in") < afterLogout.indexOf("3) login"), afterLogout)
    }

    @Test
    fun `failed account actions stay in the menu with a visible error`() = withStdin("3\n2\n") {
        val commands = commands().map { if (it.commandName == "login") RecordingCommand("login") {
            throw CliktError("Sign-in service is unavailable.")
        } else it }
        val result = root(commands).test("")
        assertEquals(0, result.statusCode, result.stderr)
        assertTrue(result.stdout.contains("Sign-in service is unavailable."), result.stdout)
        assertTrue(result.stdout.contains("Ran trial"), result.stdout)
        assertEquals(2, commands.sumOf { it.runs })
        val direct = root(commands).test("login")
        assertEquals(1, direct.statusCode)
        assertFalse(direct.stdout.contains("What would you like to do?"), direct.stdout)
    }

    @Test
    fun `short raw menus keep the account status when the logo does not fit`() {
        val result = root(commands()).test("", ansiLevel = AnsiLevel.TRUECOLOR, height = 12,
            inputEvents = listOf(KeyboardEvent("q")))
        assertEquals(0, result.statusCode, result.stderr)
        assertTrue(ANSI_SEQUENCE.replace(result.stdout, "").contains("Not signed in"), result.stdout)
        assertFalse(result.stdout.contains("██"), result.stdout)
    }

    @Test
    fun `account header uses distinct status identity and region styles`() = withStdin("q\nq\n") {
        sessions.save(Session("ru", "https://sso.invalid", "https://store.invalid", "fixture", Identity("Ann", "ann@example.com")))
        val named = root(commands()).test("", ansiLevel = AnsiLevel.TRUECOLOR, inputInteractive = false, outputInteractive = false)
        val line = named.stdout
        assertTrue(line.contains("\u001b[92;1mAnn"), line)
        assertTrue(Regex("\u001b\\[90(?:;22)?mann@example\\.com").containsMatchIn(line), line)
        assertTrue(line.contains("\u001b[36;1m [ru]"), line)
        sessions.delete()
        val signedOut = root(commands()).test("", ansiLevel = AnsiLevel.TRUECOLOR, inputInteractive = false, outputInteractive = false)
        assertTrue(signedOut.stdout.contains("\u001b[93;1mNot signed in"), signedOut.stdout)
    }

    @Test
    fun `project wizard can return home and be opened again with raw keys`() {
        val commands = commands().filter { it.commandName != "new" }
        val root = JmixCli(sessions).subcommands(listOf(NewCommand()) + commands)
        val result = root.test("", ansiLevel = AnsiLevel.TRUECOLOR, width = 120, inputEvents = listOf(
            KeyboardEvent("Enter"), KeyboardEvent("Escape"),
            KeyboardEvent("Enter"), KeyboardEvent("Escape"), KeyboardEvent("q"),
        ))
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(3, result.stdout.split("What would you like to do?").size - 1, result.stdout)
        assertEquals(2, result.stdout.split("Enter project name").size - 1, result.stdout)
        assertFalse(result.stdout.contains("Fetching Jmix versions"), result.stdout)
    }

    @Test
    fun `numbered project wizard accepts back to home before any downloads`() = withStdin("1\n<\nq\n") {
        val commands = commands().filter { it.commandName != "new" }
        val result = JmixCli(sessions).subcommands(listOf(NewCommand()) + commands).test("")
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(2, result.stdout.split("What would you like to do?").size - 1, result.stdout)
        assertFalse(result.stdout.contains("Fetching Jmix versions"), result.stdout)
    }

    @Test
    fun `a corrupt session offers login`() = withStdin("3\n") {
        Files.writeString(sessions.file, "{")
        val result = root(commands()).test("")
        assertEquals(0, result.statusCode, result.stderr)
        assertTrue(result.stdout.contains("Ran login"), result.stdout)
    }

    @Test
    fun `enter selects new and invalid numbers ask again`() = withStdin("5\nwrong\n\n") {
        val commands = commands()
        val result = root(commands).test("")
        assertEquals(0, result.statusCode, result.stderr)
        assertTrue(result.stdout.contains("Enter a number between 1 and 4"), result.stdout)
        assertTrue(result.stdout.contains("Ran new"), result.stdout)
        assertEquals(1, commands.sumOf { it.runs })
    }

    @Test
    fun `EOF and quit do not execute a command`() {
        for (input in listOf("", "q\n", "й\n")) withStdin(input) {
            val commands = commands()
            val result = root(commands).test("")
            assertEquals(0, result.statusCode, result.stderr)
            assertTrue(result.stdout.contains("What would you like to do?"), result.stdout)
            assertEquals(0, commands.sumOf { it.runs })
        }
    }

    @Test
    fun `raw menu supports arrows and executes the selected command`() {
        val commands = commands()
        val result = root(commands).test(
            "--no-update", ansiLevel = AnsiLevel.TRUECOLOR, width = 120,
            inputEvents = listOf(KeyboardEvent("ArrowDown"), KeyboardEvent("ArrowDown"), KeyboardEvent("ArrowDown"), KeyboardEvent("Enter")),
        )
        assertEquals(0, result.statusCode, result.stderr)
        assertTrue(result.stdout.contains("Ran update"), result.stdout)
        assertTrue(result.stdout.contains("Jmix CLI"), result.stdout)
        assertEquals(listOf(0, 0, 0, 0, 1), commands.map { it.runs })
    }

    @Test
    fun `raw menu quits without running a command`() {
        val commands = commands()
        val result = root(commands).test("", ansiLevel = AnsiLevel.TRUECOLOR, inputEvents = listOf(KeyboardEvent("q")))
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(0, commands.sumOf { it.runs })
    }

    @Test
    fun `explicit commands bypass the menu including the hidden account command`() {
        for (name in listOf("new", "trial", "login", "logout", "update")) {
            val commands = commands()
            val result = root(commands).test(name)
            assertEquals(0, result.statusCode, result.stderr)
            assertFalse(result.stdout.contains("What would you like to do?"), result.stdout)
            assertEquals("Ran $name\n", result.stdout)
            assertEquals(1, commands.sumOf { it.runs })
        }
    }

    @Test
    fun `help bypasses the menu and still lists every command`() {
        val commands = commands()
        val result = root(commands).test("--help")
        assertEquals(0, result.statusCode, result.stderr)
        assertFalse(result.stdout.contains("What would you like to do?"), result.stdout)
        commands.forEach { assertTrue(result.stdout.contains(it.commandName), result.stdout) }
        assertEquals(0, commands.sumOf { it.runs })
    }

    private fun withStdin(input: String, block: () -> Unit) {
        val original = System.`in`
        System.setIn(ByteArrayInputStream(input.toByteArray()))
        try {
            block()
        } finally {
            System.setIn(original)
        }
    }
}
