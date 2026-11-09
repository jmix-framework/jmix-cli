package io.jmix.cli.command

import io.jmix.cli.FakeServer

import com.github.ajalt.clikt.testing.test
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.mordant.input.KeyboardEvent
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.google.gson.JsonParser
import io.jmix.cli.env.ProjectLauncher
import io.jmix.cli.account.Session
import io.jmix.cli.account.SessionStore
import io.jmix.cli.account.issuesTokens
import io.jmix.cli.telemetry.UsageStatistics
import io.jmix.cli.trial.TRIAL_ROOT
import io.jmix.cli.trial.writeTrialZip
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Drives the command with Clikt's test(), whose terminal is neither interactive nor styled. */
class TrialCommandTest {

    private val today = LocalDate.of(2026, 10, 27)
    private val usagePath = "/rest/event/open"

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
            val usageStatistics = UsageStatistics(dir.resolve("installation-id"), server.url + usagePath)
            val result = TrialCommand(signedIn(server, dir), { today }, usageStatistics)
                .test(listOf("--non-interactive", "--path", out.toString()))
            assertEquals(1, result.statusCode)
            assertTrue("HTTP 403" in result.stderr && "https://store.jmix.io/account" in result.stderr, result.stderr)
            assertEquals(emptyList<Path>(), Files.list(out).use { it.toList() })
            assertTrue(server.requests.none { it.path == usagePath })
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
            server.on("POST", usagePath) { FakeServer.Response(200) }
            val out = dir.resolve("new/dir")
            val usageStatistics = UsageStatistics(dir.resolve("installation-id"), server.url + usagePath)
            val result = TrialCommand(signedIn(server, dir), { today }, usageStatistics)
                .test(listOf("--non-interactive", "--path", out.toString()))
            assertEquals(0, result.statusCode, result.stderr)
            assertTrue(Files.isDirectory(out.resolve("$TRIAL_ROOT/demo/bpm")))
            assertTrue("Trial ends on 2026-11-24." in result.stdout, result.stdout)
            assertTrue(result.stdout.contains("MODULE") && result.stdout.contains("Example (bpm):"), result.stdout)
            assertFalse(result.stdout.contains("What's next?"), result.stdout)
            val event = server.requests.filter { it.path == usagePath }.single()
            val payload = JsonParser.parseString(event.body).asJsonObject
            assertEquals("cli-trial", payload.get("ide").asString)
            assertEquals("3.1.0", payload.get("platformVersion").asString)
            assertFalse("/t.zip" in event.body || out.toString() in event.body)
            assertNull(event.headers.getFirst("Authorization"))
        }
    }

    @Test
    fun `interactive trial chooses a custom folder before downloading and offers follow-up actions`(@TempDir dir: Path) {
        val zip = writeTrialZip(dir.resolve("t.zip"))
        val out = dir.resolve("My trial")
        FakeServer().use { server ->
            server.issuesTokens()
            server.on("GET", "/app/rest/v3/subscriptions") {
                FakeServer.json(200, """[{"endDate":"2026-11-24","trialDownloadUrl":"${server.url}/t.zip"}]""")
            }
            server.on("GET", "/t.zip") { FakeServer.Response(200, Files.readAllBytes(zip)) }
            val events = List(3) { KeyboardEvent("ArrowDown") } + KeyboardEvent("Enter") +
                out.toString().map { KeyboardEvent(it.toString()) } + KeyboardEvent("Enter") + KeyboardEvent("q")
            val result = TrialCommand(signedIn(server, dir), { today }).test(
                "", ansiLevel = AnsiLevel.TRUECOLOR, width = 160, height = 40, inputEvents = events,
            )
            assertEquals(0, result.statusCode, result.stderr)
            assertTrue(Files.isDirectory(out.resolve("$TRIAL_ROOT/demo/bpm")))
            assertTrue(result.stdout.indexOf("Select project location") < result.stdout.indexOf("Checking your Enterprise+BPM trial"))
            assertTrue(result.stdout.contains("What's next?"), result.stdout)
        }
    }

    @Test
    fun `run selection offers demo modules and back returns to follow-up without launching`(@TempDir dir: Path) {
        val zip = writeTrialZip(dir.resolve("t.zip"))
        val out = dir.resolve("out")
        FakeServer().use { server ->
            server.issuesTokens()
            server.on("GET", "/app/rest/v3/subscriptions") {
                FakeServer.json(200, """[{"endDate":"2026-11-24","trialDownloadUrl":"${server.url}/t.zip"}]""")
            }
            server.on("GET", "/t.zip") { FakeServer.Response(200, Files.readAllBytes(zip)) }
            val events = buildList {
                if (ProjectLauncher.ideaOpenCommand(out.resolve(TRIAL_ROOT)) != null) add(KeyboardEvent("ArrowDown"))
                add(KeyboardEvent(" ")); add(KeyboardEvent("Enter"))
                add(KeyboardEvent("Escape")); add(KeyboardEvent("q"))
            }
            val result = TrialCommand(signedIn(server, dir), { today }).test(
                listOf("--path", out.toString()), ansiLevel = AnsiLevel.TRUECOLOR, width = 160, height = 40, inputEvents = events,
            )
            assertEquals(0, result.statusCode, result.stderr)
            assertTrue(result.stdout.contains("Select a demo module to run"), result.stdout)
            assertTrue(result.stdout.contains("Run a demo module"), result.stdout)
            assertFalse(result.stdout.contains("Run the application (./gradlew bootRun)"), result.stdout)
            assertTrue(result.stdout.contains("demo${java.io.File.separator}bpm"), result.stdout)
            assertTrue(result.stdout.contains("demo${java.io.File.separator}webdav"), result.stdout)
            assertTrue(result.stdout.lastIndexOf("What's next?") > result.stdout.indexOf("Select a demo module to run"))
            assertFalse(result.stdout.contains("Opening the project"), result.stdout)
        }
    }

    @Test
    fun `back from the trial location returns to the main menu before any account request`(@TempDir dir: Path) {
        FakeServer().use { server ->
            val sessions = signedIn(server, dir)
            val cli = JmixCli(sessions).subcommands(NewCommand(), TrialCommand(sessions, { today }))
            val result = cli.test("", ansiLevel = AnsiLevel.TRUECOLOR, width = 160, inputEvents = listOf(
                KeyboardEvent("ArrowDown"), KeyboardEvent("Enter"), KeyboardEvent("Escape"), KeyboardEvent("q"),
            ))
            assertEquals(0, result.statusCode, result.stderr)
            assertTrue(result.stdout.contains("Select project location"), result.stdout)
            assertTrue(result.stdout.lastIndexOf("What would you like to do?") > result.stdout.indexOf("Select project location"))
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test
    fun `EOF after an explicit-path download skips opening and running`(@TempDir dir: Path) {
        val zip = writeTrialZip(dir.resolve("t.zip"))
        FakeServer().use { server ->
            server.issuesTokens()
            server.on("GET", "/app/rest/v3/subscriptions") {
                FakeServer.json(200, """[{"endDate":"2026-11-24","trialDownloadUrl":"${server.url}/t.zip"}]""")
            }
            server.on("GET", "/t.zip") { FakeServer.Response(200, Files.readAllBytes(zip)) }
            val result = TrialCommand(signedIn(server, dir), { today })
                .test(listOf("--path", dir.resolve("out").toString()))
            assertEquals(0, result.statusCode, result.stderr)
            assertFalse(result.stdout.contains("Opening the project"), result.stdout)
            assertFalse(result.stdout.contains("Running ./"), result.stdout)
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
