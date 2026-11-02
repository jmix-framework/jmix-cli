package io.jmix.cli.account

import io.jmix.cli.FakeServer
import io.jmix.cli.util.Http
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpResponse
import kotlin.text.Charsets.UTF_8
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TokenClientTest {

    @Test
    fun `token posts never follow redirects to another service`() {
        FakeServer().use { sso ->
            FakeServer().use { other ->
                other.on("POST", "/tokens") { FakeServer.json(200, """{"access_token":"a","refresh_token":"r"}""") }
                sso.on("POST", TOKEN_PATH) { FakeServer.Response(307, headers = mapOf("Location" to "${other.url}/tokens")) }
                assertThrows<IOException> { TokenClient(AccountEndpoints(sso.url, sso.url)).refresh("PRIVATE-REFRESH") }
                assertTrue(other.requests.isEmpty(), "The refresh token must not leave the sign-in service")
            }
        }
    }

    @Test
    fun `a token with invalid header characters is rejected without quoting it`() {
        FakeServer().use { server ->
            server.on("POST", TOKEN_PATH) {
                FakeServer.json(200, """{"access_token":"PRIVATE\r\nvalue","refresh_token":"r1"}""")
            }
            val error = assertThrows<IOException> { TokenClient(AccountEndpoints(server.url, server.url)).refresh("r0") }
            assertFalse(error.message!!.contains("PRIVATE"), error.message)
        }
    }

    private fun endpoints(server: FakeServer) = AccountEndpoints(server.url, server.url)
    private val authPath = "/auth/realms/jmix-cuba/protocol/openid-connect/auth"
    private val tokenPath = "/auth/realms/jmix-cuba/protocol/openid-connect/token"

    @Test
    fun `authorization url carries pkce, state and the loopback redirect`() {
        val pkce = Pkce.create()
        val url = URI(TokenClient(AccountEndpoints("https://sso.jmix.io", "x")).authorizationUrl(pkce, "s1", "http://localhost:1234"))
        val query = url.rawQuery.split("&").associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), UTF_8) }
        assertEquals("/auth/realms/jmix-cuba/protocol/openid-connect/auth", url.path)
        assertEquals(
            mapOf(
                "response_type" to "code", "client_id" to "site-console", "redirect_uri" to "http://localhost:1234",
                "scope" to "openid", "state" to "s1", "code_challenge" to pkce.challenge, "code_challenge_method" to "S256",
            ),
            query,
        )
    }

    @Test
    fun `authorization url is a valid request whose query decodes back`() {
        FakeServer().use { server ->
            server.on("GET", authPath) { FakeServer.Response(200) }
            val url = TokenClient(endpoints(server)).authorizationUrl(Pkce.create(), "a b&c=d+e", "http://localhost:1234")
            val response = Http.client.send(Http.request(url).build(), HttpResponse.BodyHandlers.discarding())
            assertEquals(200, response.statusCode())
            val query = server.requests.single().query()
            assertEquals("a b&c=d+e", query["state"])
            assertEquals("http://localhost:1234", query["redirect_uri"])
        }
    }

    @Test
    fun `code exchange posts the verifier as a public client`() {
        FakeServer().use { server ->
            server.on("POST", tokenPath) { FakeServer.json(200, """{"access_token":"a1","refresh_token":"r1"}""") }
            val pkce = Pkce.create()
            val tokens = TokenClient(endpoints(server)).exchangeCode("c1", pkce, "http://localhost:1234")
            val request = server.requests.single()
            assertEquals(Tokens("a1", "r1"), tokens)
            assertEquals(
                mapOf(
                    "grant_type" to "authorization_code", "code" to "c1", "redirect_uri" to "http://localhost:1234",
                    "code_verifier" to pkce.verifier, "client_id" to "site-console",
                ),
                request.form(),
            )
            assertNull(request.headers.getFirst("Authorization"))
            assertTrue(request.headers.getFirst("User-Agent").startsWith("jmix-cli/"))
        }
    }

    @Test
    fun `refresh returns the rotated tokens`() {
        FakeServer().use { server ->
            server.on("POST", tokenPath) { FakeServer.json(200, """{"access_token":"a2","refresh_token":"r2","expires_in":300}""") }
            assertEquals(Tokens("a2", "r2"), TokenClient(endpoints(server)).refresh("r1"))
            assertEquals(
                mapOf("grant_type" to "refresh_token", "refresh_token" to "r1", "client_id" to "site-console"),
                server.requests.single().form(),
            )
        }
    }

    @Test
    fun `token requests are form-encoded posts that keep reserved characters intact`() {
        FakeServer().use { server ->
            server.on("POST", tokenPath) { FakeServer.json(200, """{"access_token":"a1","refresh_token":"r1"}""") }
            val client = TokenClient(endpoints(server))
            client.exchangeCode("a b&c=d+e/f", Pkce.create(), "http://localhost:1234")
            client.refresh("x&y=z")
            val (exchange, refresh) = server.requests
            assertEquals("a b&c=d+e/f", exchange.form()["code"])
            assertEquals("x&y=z", refresh.form()["refresh_token"])
            for (request in server.requests) {
                assertEquals("POST", request.method)
                assertEquals("application/x-www-form-urlencoded", request.headers.getFirst("Content-Type"))
            }
        }
    }

    @Test
    fun `invalid_grant on refresh means the session expired`() {
        FakeServer().use { server ->
            server.on("POST", tokenPath) { FakeServer.json(400, """{"error":"invalid_grant","error_description":"Session not active"}""") }
            assertThrows<SessionExpiredException> { TokenClient(endpoints(server)).refresh("r1") }
        }
    }

    @Test
    fun `an unauthorized invalid_grant expires the session too`() {
        FakeServer().use { server ->
            server.on("POST", tokenPath) { FakeServer.json(401, """{"error":"invalid_grant"}""") }
            assertThrows<SessionExpiredException> { TokenClient(endpoints(server)).refresh("r1") }
        }
    }

    @Test
    fun `a rejected code asks for a new sign-in without echoing the service description`() {
        FakeServer().use { server ->
            server.on("POST", tokenPath) { FakeServer.json(400, """{"error":"invalid_grant","error_description":"Code PRIVATE-CODE not valid"}""") }
            val error = assertThrows<IOException> { TokenClient(endpoints(server)).exchangeCode("PRIVATE-CODE", Pkce.create(), "http://localhost:1") }
            assertFalse(error is SessionExpiredException)
            assertEquals("the sign-in code is no longer valid; run 'jmix login' again", error.message)
            assertFalse(error.message!!.contains("PRIVATE-CODE"), error.message)
        }
        FakeServer().use { server ->
            server.on("POST", tokenPath) { FakeServer.json(400, """{"error":"invalid_client"}""") }
            val error = assertThrows<IOException> { TokenClient(endpoints(server)).refresh("r1") }
            assertEquals("the Jmix sign-in service returned HTTP 400", error.message)
        }
    }

    @Test
    fun `server errors and incomplete responses fail with a readable message`() {
        FakeServer().use { server ->
            server.on("POST", tokenPath) { FakeServer.json(503, "") }
            val error = assertThrows<IOException> { TokenClient(endpoints(server)).refresh("r1") }
            assertTrue(error.message!!.contains("HTTP 503"), error.message)
        }
        FakeServer().use { server ->
            server.on("POST", tokenPath) { FakeServer.json(200, """{"access_token":"a1"}""") }
            assertThrows<IOException> { TokenClient(endpoints(server)).exchangeCode("c1", Pkce.create(), "http://localhost:1") }
        }
        FakeServer().use { server ->
            server.on("POST", tokenPath) { FakeServer.Response(502, "<html>bad gateway</html>".toByteArray()) }
            val error = assertThrows<IOException> { TokenClient(endpoints(server)).exchangeCode("c1", Pkce.create(), "http://localhost:1") }
            assertTrue(error.message!!.contains("HTTP 502"), error.message)
        }
        FakeServer().use { server ->
            server.on("POST", tokenPath) { FakeServer.Response(200, "<html>login</html>".toByteArray()) }
            val error = assertThrows<IOException> { TokenClient(endpoints(server)).refresh("r1") }
            assertTrue(error.message!!.contains("unexpected response"), error.message)
        }
    }

    @Test
    fun `a 200 needs two non-blank string tokens`() {
        val bodies = listOf(
            """{"access_token":"","refresh_token":"r1"}""",
            """{"access_token":"a1","refresh_token":" "}""",
            """{"access_token":"a1","refresh_token":null}""",
            """{"access_token":["a1"],"refresh_token":"r1"}""",
            """{"access_token":"a1","refresh_token":7}""",
            """{"refresh_token":"r1"}""",
            """["a1","r1"]""",
            "null",
            "",
        )
        for (body in bodies) {
            FakeServer().use { server ->
                server.on("POST", tokenPath) { FakeServer.json(200, body) }
                val error = assertThrows<IOException>(body) { TokenClient(endpoints(server)).refresh("r1") }
                assertTrue(error.message!!.contains("unexpected response (HTTP 200)"), error.message)
            }
        }
    }

    @Test
    fun `error messages never quote the response body`() {
        val secret = "SECRET-TOKEN"
        val bodies = listOf(
            200 to """{"access_token":"$secret","refresh_token":5}""",
            200 to """{"access_token":"$secret""",
            200 to """["$secret"]""",
            400 to """oops $secret""",
            502 to """{"error":5,"access_token":"$secret""",
        )
        for ((status, body) in bodies) {
            FakeServer().use { server ->
                server.on("POST", tokenPath) { FakeServer.json(status, body) }
                val error = assertThrows<IOException> { TokenClient(endpoints(server)).refresh("r1") }
                assertFalse(error.message!!.contains(secret), "HTTP $status $body -> ${error.message}")
            }
        }
    }

    @Test
    fun `tokens text hides both tokens`() {
        val text = Tokens("access-secret", "refresh-secret").toString()
        assertFalse("access-secret" in text || "refresh-secret" in text, text)
    }

}
