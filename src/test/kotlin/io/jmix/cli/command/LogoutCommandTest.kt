package io.jmix.cli.command

import com.github.ajalt.clikt.testing.test
import io.jmix.cli.account.Session
import io.jmix.cli.account.SessionStore
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LogoutCommandTest {

    @Test
    fun `logout deletes the session and says so`(@TempDir dir: Path) {
        val sessions = SessionStore(dir.resolve("session.json"))
        assertEquals("You are not signed in.\n", LogoutCommand(sessions).test("").stdout)
        sessions.save(Session("global", "https://sso.jmix.io", "https://store-admin.jmix.io", "r1"))
        assertEquals("Signed out of your Jmix account.\n", LogoutCommand(sessions).test("").stdout)
        assertNull(sessions.load())
    }

    @Test
    fun `logout accepts the update flag that main acts on before parsing`(@TempDir dir: Path) {
        val result = LogoutCommand(SessionStore(dir.resolve("session.json"))).test("--no-update")
        assertEquals(0, result.statusCode)
        assertEquals("You are not signed in.\n", result.stdout)
    }

    @Test
    fun `logout reports a session file it cannot delete without a stack trace`(@TempDir dir: Path) {
        // A directory with content stands in for a session file that cannot be removed.
        val file = dir.resolve("session.json")
        Files.createDirectories(file.resolve("nested"))

        val result = LogoutCommand(SessionStore(file)).test("")

        assertEquals(1, result.statusCode)
        assertEquals("", result.stdout)
        assertTrue(result.stderr.startsWith("Could not sign out: "), result.stderr)
        assertTrue(result.stderr.trimEnd().endsWith("."), result.stderr)
        assertFalse(result.stderr.contains("Exception") || result.stderr.contains("\tat "), result.stderr)
    }
}
