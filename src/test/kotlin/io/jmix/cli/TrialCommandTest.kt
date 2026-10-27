package io.jmix.cli

import com.github.ajalt.clikt.testing.test
import io.jmix.cli.account.Session
import io.jmix.cli.account.SessionStore
import io.jmix.cli.account.issuesTokens
import io.jmix.cli.trial.TRIAL_ROOT
import io.jmix.cli.trial.writeTrialZip
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Drives the command with Clikt's test(), whose terminal is neither interactive nor styled. */
class TrialCommandTest {

    private val today = LocalDate.of(2026, 10, 27)

    private fun signedIn(server: FakeServer, dir: Path): SessionStore =
        SessionStore(dir.resolve("session.json")).also { it.save(Session("global", server.url, server.url, "r0")) }

    @Test
    fun `without a session a non-interactive run asks for jmix login`(@TempDir dir: Path) {
        val result = TrialCommand(SessionStore(dir.resolve("none.json")), { today })
            .test(listOf("--non-interactive", "--path", dir.toString()))
        assertEquals(1, result.statusCode)
        assertEquals("Not signed in to a Jmix account. Run 'jmix login' first.\n", result.stderr)
    }

    @Test
    fun `starting a trial without consent fails plainly and starts nothing`(@TempDir dir: Path) {
        FakeServer().use { server ->
            server.issuesTokens()
            server.on("GET", "/app/rest/v3/subscriptions") { FakeServer.json(200, "[]") }
            server.on("GET", "/app/rest/v3/trial/addons/check") { FakeServer.json(200, """{"available":true}""") }
            val result = TrialCommand(signedIn(server, dir), { today })
                .test(listOf("--non-interactive", "--path", dir.resolve("out").toString()))
            assertEquals(1, result.statusCode)
            assertEquals("Starting the Enterprise+BPM trial needs confirmation. Re-run with --start-trial.\n", result.stderr)
            assertTrue(server.requests.none { it.path.endsWith("/generate") })
        }
    }

    @Test
    fun `a failed download points to the account page and leaves no files`(@TempDir dir: Path) {
        FakeServer().use { server ->
            server.issuesTokens()
            server.on("GET", "/app/rest/v3/subscriptions") {
                FakeServer.json(200, """[{"endDate":"2026-11-24","trialDownloadUrl":"${server.url}/t.zip"}]""")
            }
            server.on("GET", "/t.zip") { FakeServer.Response(403) }
            val out = dir.resolve("out")
            val result = TrialCommand(signedIn(server, dir), { today })
                .test(listOf("--non-interactive", "--path", out.toString()))
            assertEquals(1, result.statusCode)
            assertTrue("HTTP 403" in result.stderr && "https://store.jmix.io/account" in result.stderr, result.stderr)
            assertEquals(emptyList<Path>(), Files.list(out).use { it.toList() })
        }
    }

    @Test
    fun `the trial project is unpacked into a new --path directory`(@TempDir dir: Path) {
        val zip = writeTrialZip(dir.resolve("t.zip"))
        FakeServer().use { server ->
            server.issuesTokens()
            server.on("GET", "/app/rest/v3/subscriptions") {
                FakeServer.json(200, """[{"endDate":"2026-11-24","trialDownloadUrl":"${server.url}/t.zip"}]""")
            }
            server.on("GET", "/t.zip") { FakeServer.Response(200, Files.readAllBytes(zip)) }
            val out = dir.resolve("new/dir")
            val result = TrialCommand(signedIn(server, dir), { today })
                .test(listOf("--non-interactive", "--path", out.toString()))
            assertEquals(0, result.statusCode, result.stderr)
            assertTrue(Files.isDirectory(out.resolve("$TRIAL_ROOT/demo/bpm")))
            assertTrue("Trial ends on 2026-11-24." in result.stdout, result.stdout)
        }
    }

    @Test
    fun `a --path that is a file is refused before the sign-in`(@TempDir dir: Path) {
        val file = Files.writeString(dir.resolve("file.txt"), "x")
        val result = TrialCommand(SessionStore(dir.resolve("none.json")), { today })
            .test(listOf("--non-interactive", "--path", file.toString()))
        assertEquals(1, result.statusCode)
        assertEquals("$file is not a directory.\n", result.stderr)
    }

    @Test
    fun `trial help lists its options and the update flag`() {
        val help = TrialCommand().test("--help", width = 200).stdout
        assertTrue(help.contains("Download the Enterprise+BPM trial project of your Jmix account"), help)
        for (option in listOf("--path", "--start-trial", "--non-interactive", "--no-update")) {
            assertTrue(help.contains(option), help)
        }
    }
}
