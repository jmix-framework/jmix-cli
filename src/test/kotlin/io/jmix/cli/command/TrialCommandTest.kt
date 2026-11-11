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
import io.jmix.cli.account.TOKEN_PATH
import io.jmix.cli.account.issuesTokens
import io.jmix.cli.telemetry.UsageStatistics
import io.jmix.cli.trial.TRIAL_ROOT
import io.jmix.cli.trial.writeTrialZip
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
import java.nio.file.attribute.PosixFilePermission.OWNER_READ
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Drives the command with Clikt's test(), whose terminal is neither interactive nor styled. */
class TrialCommandTest {

    private val today = LocalDate.of(2026, 10, 27)
    private val usagePath = "/rest/event/open"

    private fun signedIn(server: FakeServer, dir: Path): SessionStore =
        SessionStore(dir.resolve("session.json")).also { it.save(Session("global", server.url, server.url, "r0")) }

    private fun FakeServer.serveTrialZip(zip: Path) {
        issuesTokens()
        on("GET", "/app/rest/v3/subscriptions") {
            FakeServer.json(200, """[{"endDate":"2026-11-24","trialDownloadUrl":"${url}/t.zip"}]""")
        }
        on("GET", "/t.zip") { FakeServer.Response(200, Files.readAllBytes(zip)) }
    }

    @Test
    fun `without a session a non-interactive run asks for jmix login`(@TempDir dir: Path) {
        val result = TrialCommand(SessionStore(dir.resolve("none.json")), { today })
            .test(listOf("--non-interactive", "--path", dir.toString()))
        assertEquals(1, result.statusCode)
        assertEquals("Not signed in to a Jmix account. Run 'jmix login' first.\n", result.stderr)
    }

    @Test
    fun `starting a trial without consent fails plainly and starts nothing`(@TempDir dir: Path) {
        val out = dir.resolve("out")
        val stagingAtFirstRequest = AtomicReference(emptyList<Path>())
        FakeServer().use { server ->
            server.on("POST", TOKEN_PATH) {
                stagingAtFirstRequest.set(Files.list(out).use { it.toList() })
                FakeServer.json(200, """{"access_token":"a1","refresh_token":"r1"}""")
            }
            server.on("GET", "/app/rest/v3/subscriptions") { FakeServer.json(200, "[]") }
            server.on("GET", "/app/rest/v3/trial/addons/check") { FakeServer.json(200, """{"available":true}""") }
            val result = TrialCommand(signedIn(server, dir), { today })
                .test(listOf("--non-interactive", "--path", out.toString()))
            assertEquals(1, result.statusCode)
            assertEquals("Starting the Enterprise+BPM trial needs confirmation. Re-run with --start-trial.\n", result.stderr)
            assertTrue(server.requests.none { it.path.endsWith("/generate") })
            assertTrue(stagingAtFirstRequest.get().single().fileName.toString().startsWith(".jmix-trial-"))
            assertEquals(emptyList<Path>(), Files.list(out).use { it.toList() })
        }
    }

    @Test
    fun `an existing unwritable directory blocks a trial before the first account request`(@TempDir dir: Path) {
        val out = Files.createDirectory(dir.resolve("out"))
        assumeTrue(Files.getFileStore(out).supportsFileAttributeView("posix"))
        val permissions = Files.getPosixFilePermissions(out)
        try {
            Files.setPosixFilePermissions(out, setOf(OWNER_READ, OWNER_EXECUTE))
            assumeTrue(!Files.isWritable(out))
            FakeServer().use { server ->
                server.issuesTokens()
                server.on("GET", "/app/rest/v3/subscriptions") { FakeServer.json(200, "[]") }
                server.on("GET", "/app/rest/v3/trial/addons/check") { FakeServer.json(200, """{"available":true}""") }
                val result = TrialCommand(signedIn(server, dir), { today })
                    .test(listOf("--non-interactive", "--start-trial", "--path", out.toString()))
                assertEquals(1, result.statusCode)
                assertTrue(result.stderr.startsWith("Cannot use $out as the trial directory:"), result.stderr)
                assertTrue(server.requests.isEmpty())
            }
        } finally {
            Files.setPosixFilePermissions(out, permissions)
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

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `trial chooses another directory without downloading again`(raw: Boolean, @TempDir dir: Path) {
        val original = dir.resolve("original")
        val marker = Files.createDirectories(original.resolve(TRIAL_ROOT)).resolve("mine.txt")
        Files.writeString(marker, "keep")
        val alternative = dir.resolve("New trial")
        FakeServer().use { server ->
            server.serveTrialZip(writeTrialZip(dir.resolve("t.zip")))
            val command = TrialCommand(signedIn(server, dir), { today })
            val args = listOf("--path", original.toString())
            val result = if (raw) {
                val events = listOf(KeyboardEvent("ArrowDown"), KeyboardEvent("Enter")) +
                    List(3) { KeyboardEvent("ArrowDown") } + KeyboardEvent("Enter") +
                    alternative.toString().map { KeyboardEvent(it.toString()) } + KeyboardEvent("Enter") + KeyboardEvent("q")
                command.test(args, ansiLevel = AnsiLevel.TRUECOLOR, width = 160, height = 40, inputEvents = events)
            } else {
                val stdin = System.`in`
                try {
                    System.setIn(ByteArrayInputStream("y\n4\n$alternative\n".toByteArray()))
                    command.test(args, inputInteractive = false, outputInteractive = false)
                } finally {
                    System.setIn(stdin)
                }
            }
            assertEquals(0, result.statusCode, result.stderr)
            assertTrue(result.stdout.contains("Choose another directory?"), result.stdout)
            assertTrue(result.stdout.contains("${original.resolve(TRIAL_ROOT)} already exists."), result.stdout)
            assertTrue(Files.isDirectory(alternative.resolve("$TRIAL_ROOT/demo/bpm")))
            assertEquals("keep", Files.readString(marker))
            assertEquals(listOf(original.resolve(TRIAL_ROOT)), Files.list(original).use { it.toList() })
            assertEquals(listOf(alternative.resolve(TRIAL_ROOT)), Files.list(alternative).use { it.toList() })
            assertEquals(1, server.requests.count { it.path == "/t.zip" })
            assertEquals(1, server.requests.count { it.path == "/app/rest/v3/subscriptions" })
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `trial leaves an occupied destination alone when prompting is disabled or input ends`(
        nonInteractive: Boolean,
        @TempDir dir: Path,
    ) {
        val original = dir.resolve("original")
        val project = Files.createDirectories(original.resolve(TRIAL_ROOT))
        FakeServer().use { server ->
            server.serveTrialZip(writeTrialZip(dir.resolve("t.zip")))
            val args = listOf("--path", original.toString()) + if (nonInteractive) listOf("--non-interactive") else emptyList()
            val stdin = System.`in`
            try {
                System.setIn(ByteArrayInputStream(byteArrayOf()))
                val result = TrialCommand(signedIn(server, dir), { today })
                    .test(args, inputInteractive = false, outputInteractive = false)
                assertEquals(1, result.statusCode, result.stderr)
                assertTrue(result.stderr.contains("Choose another directory with --path."), result.stderr)
                assertEquals(!nonInteractive, result.stdout.contains("Choose another directory?"), result.stdout)
                assertTrue(Files.list(project).use { it.findAny().isEmpty })
                assertEquals(listOf(project), Files.list(original).use { it.toList() })
            } finally {
                System.setIn(stdin)
            }
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
