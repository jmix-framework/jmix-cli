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

    @Test
    fun `premium key is returned only for an active commercial license`(@TempDir dir: Path) {
        FakeServer().use { server ->
            server.issuesTokens()
            val client = client(server, SessionStore(dir.resolve("s.json")))
            for ((body, expected) in listOf(
                """{"activeLicense":true,"sid":"repo-secret"}""" to "repo-secret",
                """{"activeLicense":false,"sid":"repo-secret"}""" to null,
                """{"activeLicense":true,"sid":null}""" to null,
            )) {
                server.on("GET", "/app/rest/v3/studio/license/check") { FakeServer.json(200, body) }
                assertEquals(expected, client.premiumLicenseKey())
            }
            assertTrue(server.requests.filter { it.method == "GET" }.all {
                it.headers.getFirst("Authorization") == "Bearer a1"
            })
        }
    }

    @Test
    fun `invalid license responses do not expose the key`(@TempDir dir: Path) {
        FakeServer().use { server ->
            server.issuesTokens()
            val client = client(server, SessionStore(dir.resolve("s.json")))
            for (body in listOf("[]", """{"activeLicense":"true","sid":"PRIVATE"}""",
                """{"error":"PRIVATE"}""")) {
                server.on("GET", "/app/rest/v3/studio/license/check") { FakeServer.json(200, body) }
                val error = assertThrows<IOException> { client.premiumLicenseKey() }
                assertFalse(error.message!!.contains("PRIVATE"))
            }
        }
    }

    @Test
    fun `refresh caches account details and keeps them when later tokens omit them`(@TempDir dir: Path) {
        FakeServer().use { server ->
            val token = jwt("""{"name":"Ann Lee","email":"ann@example.com"}""")
            var accessToken = token
            server.on("POST", TOKEN_PATH) { FakeServer.json(200, """{"access_token":"$accessToken","refresh_token":"r1"}""") }
            val sessions = SessionStore(dir.resolve("s.json"))
            val session = Session("global", server.url, server.url, "r0")
            sessions.save(session)
            val tokens = AccessTokens(sessions, session)
            assertEquals(token, tokens.current())
            assertEquals(Identity("Ann Lee", "ann@example.com"), sessions.load()?.identity)
            accessToken = "opaque-access-token"
            tokens.refresh()
            assertEquals(Identity("Ann Lee", "ann@example.com"), sessions.load()?.identity)
        }
    }

    @Test
    fun `store requests never forward the bearer token on redirects`(@TempDir dir: Path) {
        FakeServer().use { store ->
            FakeServer().use { other ->
                store.issuesTokens()
                store.on("GET", "/app/rest/v3/subscriptions") {
                    FakeServer.Response(302, headers = mapOf("Location" to "${other.url}/subscriptions"))
                }
                other.on("GET", "/subscriptions") { FakeServer.json(200, "[]") }
                assertThrows<IOException> { client(store, SessionStore(dir.resolve("s.json"))).subscriptions() }
                assertTrue(other.requests.isEmpty(), "The bearer token must not leave the store")
            }
        }
    }

    @Test
    fun `store error fields cannot expose download links or terminal controls`(@TempDir dir: Path) {
        for (status in listOf(200, 400)) {
            FakeServer().use { server ->
                server.issuesTokens()
                server.on("GET", "/app/rest/v3/subscriptions") {
                    FakeServer.json(status, """{"error":"https://dl.example/PRIVATE","error_description":"https://dl.example/PRIVATE\u001b[2J","message":"https://dl.example/PRIVATE"}""")
                }
                val error = assertThrows<IOException> { client(server, SessionStore(dir.resolve("s.json"))).subscriptions() }
                assertFalse(error.message!!.contains("PRIVATE") || error.message!!.contains('\u001b'), error.message)
            }
        }
    }

    @Test
    fun `date numbers cannot wrap or lose their fractional part`() {
        for (fields in listOf(
            """"year":4294969322,"monthValue":11,"dayOfMonth":24""",
            """"year":2026,"monthValue":11.9,"dayOfMonth":24""",
            """"year":2026,"monthValue":11,"dayOfMonth":4294967320""",
        )) {
            assertNull(parseStoreDate(JsonParser.parseString("{$fields}")), fields)
        }
    }

    private val tokenPath = TOKEN_PATH
    private fun client(server: FakeServer, sessions: SessionStore) = storeClient(server, sessions)

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
            assertEquals("the Jmix Store returned an unexpected response (HTTP 200)", error.message)
        }
    }

    @Test
    fun `a store error code in a successful start response is not a started trial`(@TempDir dir: Path) {
        FakeServer().use { server ->
            server.issuesTokens()
            server.on("POST", "/app/rest/v3/trial/addons/generate") {
                FakeServer.json(200, """{"errorCode":"TRIAL_USED","message":"PRIVATE"}""")
            }
            val error = assertThrows<IOException> { client(server, SessionStore(dir.resolve("s.json"))).startTrial() }
            assertEquals("the Jmix Store returned an unexpected response (HTTP 200)", error.message)
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
    fun `store failures report the status without echoing server messages`(@TempDir dir: Path) {
        FakeServer().use { server ->
            server.issuesTokens()
            server.on("POST", "/app/rest/v3/trial/addons/generate") {
                FakeServer.json(409, """{"errorCode":"TRIAL_USED","message":"Trial already used"}""")
            }
            val error = assertThrows<IOException> { client(server, SessionStore(dir.resolve("s.json"))).startTrial() }
            assertEquals("the Jmix Store returned HTTP 409", error.message)
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
            val sessions = SessionStore(dir.resolve("s.json"))
            val client = client(server, sessions)
            assertEquals(emptyList<Subscription>(), client.subscriptions())
            client.subscriptions()
            assertEquals(1, server.requests.count { it.path == tokenPath })
            assertEquals("r1", sessions.load()?.refreshToken)
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
            assertEquals(2, calls)
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
            assertEquals(2, server.requests.count { it.path.endsWith("/subscriptions") })
        }
    }

    @Test
    fun `a failed call reports only the status`(@TempDir dir: Path) {
        val cases = listOf(
            Triple(400, """{"error":"invalid_request","error_description":"Bad input","message":"ignored"}""", "the Jmix Store returned HTTP 400"),
            Triple(400, """{"error":"invalid_request","message":"ignored"}""", "the Jmix Store returned HTTP 400"),
            Triple(409, """{"errorCode":"TRIAL_USED","message":"Trial already used"}""", "the Jmix Store returned HTTP 409"),
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
        val responses = listOf(FakeServer.Response(200), FakeServer.Response(204), FakeServer.json(200, "{}"),
            FakeServer.json(200, "[]"), FakeServer.json(200, "null"))
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
