package io.jmix.cli.trial

import io.jmix.cli.FakeServer
import io.jmix.cli.account.AccountRegion
import io.jmix.cli.account.SessionStore
import io.jmix.cli.account.issuesTokens
import io.jmix.cli.account.storeClient
import io.jmix.cli.command.shellQuote
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.fail
import org.junit.jupiter.api.io.TempDir

/** FakeServer plays SSO and store; storeClient()/issuesTokens() come from AccountFixtures.kt. */
class TrialFinderTest {

    @Test
    fun `summary quotes a demo path containing spaces and apostrophes`(@TempDir dir: Path) {
        val project = Files.createDirectories(dir.resolve("Ann's trials/$TRIAL_ROOT"))
        val app = Files.createDirectories(project.resolve("demo/bpm"))
        Files.writeString(app.resolve("gradlew"), "#!/bin/sh")
        val lines = trialSummary(listOf(project), null, dir)
        assertTrue("  1. cd ${shellQuote(dir.relativize(project).toString())}" in lines, lines.toString())
    }

    private val today = LocalDate.of(2026, 10, 27)
    private val account = "https://store.jmix.io/account"
    private fun date(d: LocalDate) = """{"year":${d.year},"month":"${d.month}","dayOfMonth":${d.dayOfMonth}}"""
    private fun trialItem(end: LocalDate, url: String = "https://dl.example/t.zip") = """{"endDate":${date(end)},"trialDownloadUrl":"$url"}"""

    @Test
    fun `an active trial is downloaded without starting a new one`(@TempDir dir: Path) {
        FakeServer().use { server ->
            server.issuesTokens()
            server.on("GET", "/app/rest/v3/subscriptions") {
                FakeServer.json(200, "[${trialItem(today.plusDays(3), "https://dl.example/old.zip")},${trialItem(today.plusDays(20))}]")
            }
            val download = findOrStartTrial(storeClient(server, SessionStore(dir.resolve("s"))), today, AccountRegion.GLOBAL) { fail("must not ask") }
            assertEquals(TrialDownload("https://dl.example/t.zip", today.plusDays(20)), download)
            assertTrue(server.requests.none { it.path.contains("/trial/addons/") })
        }
    }

    @Test
    fun `the end date itself counts as expired`(@TempDir dir: Path) {
        FakeServer().use { server ->
            server.issuesTokens()
            server.on("GET", "/app/rest/v3/subscriptions") { FakeServer.json(200, "[${trialItem(today)}]") }
            server.on("GET", "/app/rest/v3/trial/addons/check") { FakeServer.json(200, """{"available":false}""") }
            val error = assertThrows<TrialException> { findOrStartTrial(storeClient(server, SessionStore(dir.resolve("s"))), today, AccountRegion.GLOBAL) { true } }
            assertEquals(trialExpiredMessage("https://www.jmix.io/contacts/"), error.message)
        }
    }

    @Test
    fun `no trial and none available points to the account page`(@TempDir dir: Path) {
        FakeServer().use { server ->
            server.issuesTokens()
            server.on("GET", "/app/rest/v3/subscriptions") { FakeServer.json(200, "[]") }
            server.on("GET", "/app/rest/v3/trial/addons/check") { FakeServer.json(200, """{"available":false}""") }
            val error = assertThrows<TrialException> { findOrStartTrial(storeClient(server, SessionStore(dir.resolve("s"))), today, AccountRegion.GLOBAL) { true } }
            assertEquals("No Enterprise+BPM trial is available for this account. Check your subscriptions at $account.", error.message)
        }
    }

    @Test
    fun `a new trial starts only after consent and is read back from the subscriptions`(@TempDir dir: Path) {
        FakeServer().use { server ->
            server.issuesTokens()
            var reads = 0
            // Like the web, the CLI re-reads /subscriptions after generate; the generate body may be empty.
            server.on("GET", "/app/rest/v3/subscriptions") {
                reads++
                FakeServer.json(200, if (reads <= 2) "[]" else "[${trialItem(today.plusDays(28))}]")
            }
            server.on("GET", "/app/rest/v3/trial/addons/check") { FakeServer.json(200, """{"available":true}""") }
            server.on("POST", "/app/rest/v3/trial/addons/generate") { FakeServer.Response(200) }
            val store = storeClient(server, SessionStore(dir.resolve("s")))
            assertEquals(
                "The trial was not started.",
                assertThrows<TrialException> { findOrStartTrial(store, today, AccountRegion.GLOBAL) { false } }.message,
            )
            assertTrue(server.requests.none { it.path.endsWith("/generate") })
            assertEquals(TrialDownload("https://dl.example/t.zip", today.plusDays(28)), findOrStartTrial(store, today, AccountRegion.GLOBAL) { true })
            assertEquals(3, server.requests.count { it.path.endsWith("/subscriptions") })
        }
    }

    @Test
    fun `the generated link is used when the subscriptions do not list the trial yet`(@TempDir dir: Path) {
        FakeServer().use { server ->
            server.issuesTokens()
            server.on("GET", "/app/rest/v3/subscriptions") { FakeServer.json(200, "[]") }
            server.on("GET", "/app/rest/v3/trial/addons/check") { FakeServer.json(200, """{"available":true}""") }
            var body = """{"link":"https://dl.example/new.zip","expiryDate":${date(today.plusDays(28))}}"""
            server.on("POST", "/app/rest/v3/trial/addons/generate") { FakeServer.json(200, body) }
            val store = storeClient(server, SessionStore(dir.resolve("s")))
            assertEquals(TrialDownload("https://dl.example/new.zip", today.plusDays(28)), findOrStartTrial(store, today, AccountRegion.GLOBAL) { true })
            body = """{"link":null,"subscriptionId":null,"expiryDate":null}"""
            val error = assertThrows<TrialException> { findOrStartTrial(store, today, AccountRegion.GLOBAL) { true } }
            assertTrue(error.message!!.contains("not ready yet"), error.message)
        }
    }

    @Test
    fun `a trial without an end date counts as active`(@TempDir dir: Path) {
        FakeServer().use { server ->
            server.issuesTokens()
            server.on("GET", "/app/rest/v3/subscriptions") { FakeServer.json(200, """[{"trialDownloadUrl":"https://dl.example/t.zip"}]""") }
            assertEquals(TrialDownload("https://dl.example/t.zip", null), findOrStartTrial(storeClient(server, SessionStore(dir.resolve("s"))), today, AccountRegion.GLOBAL) { true })
        }
    }

    @Test
    fun `summary shows where the project is, when the trial ends and how to run a demo`() {
        val cwd = Path.of("/work").toAbsolutePath()
        val project = cwd.resolve("jmix-commercial-addons-demo-3.1.0")
        val lines = trialSummary(listOf(project), LocalDate.of(2026, 11, 24), cwd)
        assertEquals("✓ Enterprise+BPM trial project unpacked to $project", lines.first())
        assertTrue("Trial ends on 2026-11-24. When it ends, the demo applications stop starting." in lines, lines.toString())
        assertTrue(lines.any { it.startsWith("If a demo stops starting earlier") && it.contains("jmix trial --path") }, lines.toString())
        assertTrue("Guide: https://docs.jmix.io/jmix/account-management.html#enterprise-trial" in lines, lines.toString())
        assertTrue(trialSummary(listOf(project), null, cwd).any { it.startsWith("Trial end date is unknown.") })
    }

    @Test
    fun `summary gives the demo run command and port`(@TempDir dir: Path) {
        val project = Files.createDirectories(dir.resolve("jmix-commercial-addons-demo-3.1.0"))
        val bpm = Files.createDirectories(project.resolve("demo/bpm/src/main/resources"))
        Files.writeString(bpm.resolve("application.properties"), "server.port=8101")
        Files.writeString(project.resolve("demo/bpm/gradlew"), "#!/bin/sh")
        val lines = trialSummary(listOf(project), null, dir)
        val relative = Path.of("jmix-commercial-addons-demo-3.1.0")
        assertTrue(lines.any { it.trimEnd().endsWith("cd $relative") }, lines.toString())
        assertTrue(lines.any { it.contains("gradlew") && it.contains("bootRun") }, lines.toString())
        assertTrue(lines.any { it.contains("http://localhost:8101") }, lines.toString())
    }

    @Test
    fun `summary prints one module command template and one real example`(@TempDir dir: Path) {
        val project = Files.createDirectories(dir.resolve("My trial"))
        for ((module, port) in listOf("bpm" to 8101, "webdav" to 8180)) {
            val resources = Files.createDirectories(project.resolve("demo/$module/src/main/resources"))
            Files.writeString(resources.resolve("application.properties"), "server.port=$port")
            Files.writeString(project.resolve("demo/$module/gradlew"), "#!/bin/sh")
        }

        val lines = trialSummary(listOf(project), null, dir)
        val commands = lines.filter { it.contains("bootRun") }
        assertEquals(2, commands.size, lines.toString())
        assertTrue(commands.any { it.contains("MODULE") && it.contains("-p") }, lines.toString())
        assertTrue(commands.any { it.contains("Example (bpm):") && it.contains("-p") }, lines.toString())
        assertTrue(lines.none { it.contains("webdav") }, lines.toString())
        assertTrue(lines.any { it.contains("cd 'My trial'") }, lines.toString())
    }
}
