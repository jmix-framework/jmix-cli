package io.jmix.cli.account

import io.jmix.cli.FakeServer
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import kotlin.text.Charsets.UTF_8
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class BrowserSignInTest {

    private val tokenPath = "/auth/realms/jmix-cuba/protocol/openid-connect/token"

    private fun queryOf(url: String) =
        URI(url).rawQuery.split("&").associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), UTF_8) }

    private fun get(url: String) =
        HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI(url)).build(), HttpResponse.BodyHandlers.discarding())

    private fun signIn(sso: FakeServer, dir: Path, sessions: SessionStore = SessionStore(dir.resolve("session.json"))) =
        BrowserSignIn(AccountRegion.GLOBAL, AccountEndpoints(sso.url, "https://store.example"), sessions)

    @Test
    fun `identity tolerates missing claims and malformed tokens`() {
        assertEquals(Identity("Ann Lee", "ann@example.com"), Identity.fromAccessToken(jwt("""{"name":"Ann Lee","email":"ann@example.com"}""")))
        assertEquals(Identity(null, "ann@example.com"), Identity.fromAccessToken(jwt("""{"email":"ann@example.com"}""")))
        assertEquals(Identity(null, null), Identity.fromAccessToken("not-a-jwt"))
        assertEquals(
            "Signed in to your Jmix account as Ann Lee (ann@example.com).",
            Identity("Ann Lee", "ann@example.com").signedInMessage(),
        )
        assertEquals("Signed in to your Jmix account.", Identity(null, null).signedInMessage())
    }

    @Test
    fun `identity reads only non-blank text claims from a well-formed payload`() {
        assertEquals(Identity("Ann Lee", null), Identity.fromAccessToken(jwt("""{"name":"Ann Lee"}""")))
        assertEquals(Identity(null, null), Identity.fromAccessToken(jwt("""{"name":5,"email":["ann@example.com"]}""")))
        assertEquals(Identity(null, null), Identity.fromAccessToken(jwt("""{"name":" ","email":""}""")))
        assertEquals(Identity(null, null), Identity.fromAccessToken(jwt("""["Ann Lee"]""")))
        assertEquals(Identity(null, null), Identity.fromAccessToken(jwt("this is not json")))
        assertEquals(Identity(null, null), Identity.fromAccessToken("e30.!!.sig"))
        assertEquals(Identity("Ann", "ann@example.com"), Identity.fromAccessToken(jwt("""{"name":" Ann\u001b\n","email":"ann@example.com\r"}""")))
        assertEquals("Signed in to your Jmix account as Ann Lee.", Identity("Ann Lee", null).signedInMessage())
        assertEquals("Signed in to your Jmix account as ann@example.com.", Identity(null, "ann@example.com").signedInMessage())
    }

    @Test
    fun `browser sign-in saves the session and returns who signed in`(@TempDir dir: Path) {
        val accessToken = jwt("""{"name":"Ann Lee","email":"ann@example.com"}""")
        FakeServer().use { sso ->
            sso.on("POST", "/auth/realms/jmix-cuba/protocol/openid-connect/token") {
                FakeServer.json(200, """{"access_token":"$accessToken","refresh_token":"r1"}""")
            }
            val sessions = SessionStore(dir.resolve("session.json"))
            val endpoints = AccountEndpoints(sso.url, "https://store.example")
            BrowserSignIn(AccountRegion.GLOBAL, endpoints, sessions).start().use { pending ->
                val query = URI(pending.url).rawQuery.split("&").associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), UTF_8) }
                // Plays the browser: Keycloak redirects back with the code.
                HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI("${query["redirect_uri"]}/?state=${query["state"]}&code=c1")).build(),
                    HttpResponse.BodyHandlers.discarding(),
                )
                assertEquals(Identity("Ann Lee", "ann@example.com"), pending.complete(Duration.ofSeconds(5)))
                val form = sso.requests.single().form()
                assertEquals("c1", form["code"])
                assertEquals(query["code_challenge"], Pkce.challengeOf(form.getValue("code_verifier")))
            }
            assertEquals(Session("global", sso.url, "https://store.example", "r1", Identity("Ann Lee", "ann@example.com")), sessions.load())
        }
    }

    @Test
    fun `a rejected code fails the sign-in and keeps the session saved before`(@TempDir dir: Path) {
        val earlier = Session("ru", "https://sso.jmix.ru", "https://store-admin.jmix.ru", "earlier")
        FakeServer().use { sso ->
            sso.on("POST", tokenPath) { FakeServer.json(400, """{"error":"invalid_grant","error_description":"Code not valid"}""") }
            val sessions = SessionStore(dir.resolve("session.json")).also { it.save(earlier) }
            signIn(sso, dir, sessions).start().use { pending ->
                val query = queryOf(pending.url)
                get("${query["redirect_uri"]}/?state=${query["state"]}&code=c1")
                val error = assertThrows<IOException> { pending.complete(Duration.ofSeconds(5)) }
                assertEquals("the sign-in code is no longer valid; run 'jmix login' again", error.message)
            }
            assertEquals(earlier, sessions.load())
        }
    }

    @Test
    fun `no tokens are requested when the browser never comes back`(@TempDir dir: Path) {
        FakeServer().use { sso ->
            signIn(sso, dir).start().use { pending ->
                val error = assertThrows<IOException> { pending.complete(Duration.ofMillis(200)) }
                assertTrue(error.message!!.contains("timed out"), error.message)
            }
            assertTrue(sso.requests.isEmpty())
        }
    }

    @Test
    fun `closing a pending sign-in stops listening for the browser`(@TempDir dir: Path) {
        FakeServer().use { sso ->
            val pending = signIn(sso, dir).start()
            val redirectUri = queryOf(pending.url).getValue("redirect_uri")
            assertEquals(404, get(redirectUri).statusCode())
            pending.close()
            assertThrows<IOException> { get(redirectUri) }
        }
    }
}
