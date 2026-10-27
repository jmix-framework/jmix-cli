package io.jmix.cli.account

import com.google.gson.JsonParser
import io.jmix.cli.FakeServer
import java.io.IOException
import java.nio.file.Path
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class StoreClientTest {

    private val tokenPath = TOKEN_PATH
    private fun client(server: FakeServer, sessions: SessionStore) = storeClient(server, sessions)

    @Test
    fun `api calls carry the bearer token and the rotated refresh token is kept`(@TempDir dir: Path) {
        FakeServer().use { server ->
            server.issuesTokens()
            server.on("GET", "/app/rest/v3/subscriptions") { FakeServer.json(200, "[]") }
            val sessions = SessionStore(dir.resolve("s.json"))
            assertEquals(emptyList<Subscription>(), client(server, sessions).subscriptions())
            assertEquals("Bearer a1", server.requests.last().headers.getFirst("Authorization"))
            assertEquals("r1", sessions.load()?.refreshToken)
        }
    }

    @Test
    fun `a rejected token is refreshed once and the call retried once`(@TempDir dir: Path) {
        FakeServer().use { server ->
            server.issuesTokens()
            var calls = 0
            server.on("GET", "/app/rest/v3/trial/addons/check") {
                calls++
                if (calls == 1) FakeServer.json(401, "") else FakeServer.json(200, """{"available":true}""")
            }
            assertTrue(client(server, SessionStore(dir.resolve("s.json"))).trialAvailable())
            assertEquals(2, calls)
        }
        FakeServer().use { server ->
            server.issuesTokens()
            server.on("GET", "/app/rest/v3/trial/addons/check") { FakeServer.json(401, "") }
            assertThrows<IOException> { client(server, SessionStore(dir.resolve("t.json"))).trialAvailable() }
            assertEquals(2, server.requests.count { it.path.endsWith("/check") })
        }
    }

    @Test
    fun `an expired session is deleted`(@TempDir dir: Path) {
        FakeServer().use { server ->
            server.on("POST", tokenPath) { FakeServer.json(400, """{"error":"invalid_grant"}""") }
            val sessions = SessionStore(dir.resolve("s.json"))
            assertThrows<SessionExpiredException> { client(server, sessions).subscriptions() }
            assertNull(sessions.load())
        }
    }

    @Test
    fun `subscriptions keep trial links and read both date shapes`(@TempDir dir: Path) {
        FakeServer().use { server ->
            server.issuesTokens()
            server.on("GET", "/app/rest/v3/subscriptions") {
                FakeServer.json(
                    200,
                    """[{"name":"Trial Enterprise+BPM License","endDate":{"year":2026,"month":"NOVEMBER","dayOfMonth":24,"monthValue":11},
                         "trialDownloadUrl":"https://dl.example/t.zip","status":"IN_TRIAL"},
                        {"endDate":"2027-01-31","trialDownloadUrl":null}]""",
                )
            }
            assertEquals(
                listOf(
                    Subscription(LocalDate.of(2026, 11, 24), "https://dl.example/t.zip"),
                    Subscription(LocalDate.of(2027, 1, 31), null),
                ),
                client(server, SessionStore(dir.resolve("s.json"))).subscriptions(),
            )
        }
    }

    @Test
    fun `starting a trial posts no body and reads the link`(@TempDir dir: Path) {
        FakeServer().use { server ->
            server.issuesTokens()
            server.on("POST", "/app/rest/v3/trial/addons/generate") {
                FakeServer.json(200, """{"link":"https://dl.example/t.zip","subscriptionId":"42","expiryDate":{"year":2026,"month":"NOVEMBER","dayOfMonth":24}}""")
            }
            val started = client(server, SessionStore(dir.resolve("s.json"))).startTrial()
            val request = server.requests.last()
            assertEquals(StartedTrial("https://dl.example/t.zip", LocalDate.of(2026, 11, 24)), started)
            assertEquals("", request.body)
            assertNull(request.headers.getFirst("Content-Type"))
        }
    }

    @Test
    fun `an error field in a successful response is a failure`(@TempDir dir: Path) {
        FakeServer().use { server ->
            server.issuesTokens()
            server.on("GET", "/app/rest/v3/trial/addons/check") { FakeServer.json(200, """{"error":"not_eligible","error_description":"Trial already used"}""") }
            val error = assertThrows<IOException> { client(server, SessionStore(dir.resolve("s.json"))).trialAvailable() }
            assertTrue(error.message!!.contains("Trial already used"), error.message)
        }
    }

    @Test
    fun `unexpected bodies fail without echoing them`(@TempDir dir: Path) {
        FakeServer().use { server ->
            server.issuesTokens()
            server.on("GET", "/app/rest/v3/subscriptions") {
                FakeServer.json(200, """{"content":[{"trialDownloadUrl":"https://dl.example/SECRET"}]}""")
            }
            val error = assertThrows<IOException> { client(server, SessionStore(dir.resolve("s.json"))).subscriptions() }
            assertTrue(error.message!!.contains("unexpected response"), error.message)
            assertFalse(error.message!!.contains("SECRET"), error.message)
        }
    }

    @Test
    fun `an empty generate response means no link yet`(@TempDir dir: Path) {
        FakeServer().use { server ->
            server.issuesTokens()
            server.on("POST", "/app/rest/v3/trial/addons/generate") { FakeServer.Response(200) }
            assertEquals(StartedTrial(null, null), client(server, SessionStore(dir.resolve("s.json"))).startTrial())
        }
    }

    @Test
    fun `store error messages reach the user`(@TempDir dir: Path) {
        FakeServer().use { server ->
            server.issuesTokens()
            server.on("POST", "/app/rest/v3/trial/addons/generate") {
                FakeServer.json(409, """{"errorCode":"TRIAL_USED","message":"Trial already used"}""")
            }
            val error = assertThrows<IOException> { client(server, SessionStore(dir.resolve("s.json"))).startTrial() }
            assertTrue(error.message!!.contains("Trial already used"), error.message)
        }
    }

    @Test
    fun `store dates accept month names, month numbers and ISO strings`() {
        assertEquals(LocalDate.of(2026, 7, 30), parseStoreDate(JsonParser.parseString("""{"year":2026,"month":"JULY","dayOfMonth":30}""")))
        assertEquals(LocalDate.of(2026, 7, 30), parseStoreDate(JsonParser.parseString("""{"year":2026,"monthValue":7,"dayOfMonth":30}""")))
        assertEquals(LocalDate.of(2026, 7, 30), parseStoreDate(JsonParser.parseString("\"2026-07-30\"")))
        assertNull(parseStoreDate(JsonParser.parseString("\"30 Jul 2026\"")))
        assertNull(parseStoreDate(null))
    }

    @Test
    fun `store dates that cannot be read are null`() {
        val unreadable = listOf(
            """{"year":2026,"month":"JULY"}""",
            """{"year":2026,"month":"Jul","dayOfMonth":30}""",
            """{"year":2026,"monthValue":2,"dayOfMonth":30}""",
            """{"year":"2026","monthValue":7,"dayOfMonth":30}""",
            "\"2026-02-30\"",
            "\"\"",
            "20260730",
            "true",
            "null",
            "[]",
        )
        for (json in unreadable) {
            assertNull(parseStoreDate(JsonParser.parseString(json)), json)
        }
    }

    @Test
    fun `the access token is fetched once and reused`(@TempDir dir: Path) {
        FakeServer().use { server ->
            server.issuesTokens()
            server.on("GET", "/app/rest/v3/subscriptions") { FakeServer.json(200, "[]") }
            val client = client(server, SessionStore(dir.resolve("s.json")))
            client.subscriptions()
            client.subscriptions()
            assertEquals(1, server.requests.count { it.path == tokenPath })
            val tokens = server.requests.filter { it.method == "GET" }.map { it.headers.getFirst("Authorization") }
            assertEquals(listOf("Bearer a1", "Bearer a1"), tokens)
        }
    }

    @Test
    fun `each refresh spends the refresh token the previous one returned`(@TempDir dir: Path) {
        FakeServer().use { server ->
            server.issuesTokens()
            var calls = 0
            server.on("GET", "/app/rest/v3/trial/addons/check") {
                calls++
                if (calls == 1) FakeServer.json(401, "") else FakeServer.json(200, """{"available":true}""")
            }
            val sessions = SessionStore(dir.resolve("s.json"))
            assertTrue(client(server, sessions).trialAvailable())
            assertEquals(listOf("r0", "r1"), server.requests.filter { it.path == tokenPath }.map { it.form()["refresh_token"] })
            assertEquals(listOf("Bearer a1", "Bearer a2"), server.requests.filter { it.method == "GET" }.map { it.headers.getFirst("Authorization") })
            assertEquals("r2", sessions.load()?.refreshToken)
        }
    }

    @Test
    fun `a second rejection says the store refused the sign-in`(@TempDir dir: Path) {
        FakeServer().use { server ->
            server.issuesTokens()
            server.on("GET", "/app/rest/v3/subscriptions") { FakeServer.json(401, """{"message":"SECRET"}""") }
            val error = assertThrows<IOException> { client(server, SessionStore(dir.resolve("s.json"))).subscriptions() }
            assertEquals("the Jmix Store rejected the sign-in (HTTP 401)", error.message)
        }
    }

    @Test
    fun `a failed call reports the store's own message, else the status`(@TempDir dir: Path) {
        val cases = listOf(
            Triple(400, """{"error":"invalid_request","error_description":"Bad input","message":"ignored"}""", "Bad input"),
            Triple(400, """{"error":"invalid_request","message":"ignored"}""", "invalid_request"),
            Triple(409, """{"errorCode":"TRIAL_USED","message":"Trial already used"}""", "Trial already used"),
            Triple(500, """{"message":" "}""", "the Jmix Store returned HTTP 500"),
            Triple(403, """["Trial already used"]""", "the Jmix Store returned HTTP 403"),
            Triple(502, "<html>bad gateway</html>", "the Jmix Store returned HTTP 502"),
            Triple(503, "", "the Jmix Store returned HTTP 503"),
        )
        for ((status, body, message) in cases) {
            FakeServer().use { server ->
                server.issuesTokens()
                server.on("GET", "/app/rest/v3/subscriptions") { FakeServer.json(status, body) }
                val error = assertThrows<IOException>("HTTP $status $body") {
                    client(server, SessionStore(dir.resolve("s.json"))).subscriptions()
                }
                assertEquals(message, error.message)
            }
        }
    }

    @Test
    fun `a response that is not JSON fails without echoing it`(@TempDir dir: Path) {
        val bodies = listOf("""{"trialDownloadUrl":"https://dl.example/SECRET""", "<html>SECRET</html>", "SECRET SECRET", "\"SECRET\"")
        for (body in bodies) {
            FakeServer().use { server ->
                server.issuesTokens()
                server.on("GET", "/app/rest/v3/subscriptions") { FakeServer.json(200, body) }
                val error = assertThrows<IOException>(body) { client(server, SessionStore(dir.resolve("s.json"))).subscriptions() }
                assertEquals("the Jmix Store returned an unexpected response (HTTP 200)", error.message)
            }
        }
    }

    @Test
    fun `trial availability defaults to false`(@TempDir dir: Path) {
        for (body in listOf("""{"available":false}""", "{}", """{"available":"true"}""", """{"available":null}""", "[]", "")) {
            FakeServer().use { server ->
                server.issuesTokens()
                server.on("GET", "/app/rest/v3/trial/addons/check") { FakeServer.json(200, body) }
                assertFalse(client(server, SessionStore(dir.resolve("s.json"))).trialAvailable(), body)
            }
        }
    }

    @Test
    fun `subscriptions skip entries that are not objects and ignore unusable fields`(@TempDir dir: Path) {
        FakeServer().use { server ->
            server.issuesTokens()
            server.on("GET", "/app/rest/v3/subscriptions") {
                FakeServer.json(200, """[1,"x",null,[],{"trialDownloadUrl":" ","endDate":"soon"},{"trialDownloadUrl":5,"endDate":null}]""")
            }
            assertEquals(
                listOf(Subscription(null, null), Subscription(null, null)),
                client(server, SessionStore(dir.resolve("s.json"))).subscriptions(),
            )
        }
    }

    @Test
    fun `a started trial without a usable body has no link yet`(@TempDir dir: Path) {
        val responses = listOf(FakeServer.Response(204), FakeServer.json(200, "{}"), FakeServer.json(200, "[]"), FakeServer.json(200, "null"))
        for (response in responses) {
            FakeServer().use { server ->
                server.issuesTokens()
                server.on("POST", "/app/rest/v3/trial/addons/generate") { response }
                val started = client(server, SessionStore(dir.resolve("s.json"))).startTrial()
                assertEquals(StartedTrial(null, null), started, "HTTP ${response.status}")
            }
        }
    }

    @Test
    fun `subscription and trial text hide the download link`() {
        val date = LocalDate.of(2026, 11, 24)
        val text = Subscription(date, "https://dl.example/SECRET").toString() + StartedTrial("https://dl.example/SECRET", date)
        assertFalse(text.contains("SECRET"), text)
        assertTrue(text.contains("2026-11-24"), text)
    }
}
