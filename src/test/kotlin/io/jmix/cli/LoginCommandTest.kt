package io.jmix.cli

import com.github.ajalt.clikt.testing.test
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import io.jmix.cli.account.AccountEndpoints
import io.jmix.cli.account.AccountRegion
import io.jmix.cli.account.BrowserSignIn
import io.jmix.cli.account.SessionStore
import io.jmix.cli.account.jwt
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import kotlin.text.Charsets.UTF_8
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class LoginCommandTest {

    private val tokenPath = "/auth/realms/jmix-cuba/protocol/openid-connect/token"

    // Narrower than the page address on purpose: the address must still print on one line to be copyable.
    private val recorder = TerminalRecorder(ansiLevel = AnsiLevel.NONE, width = 40)
    private val terminal = Terminal(ansiLevel = AnsiLevel.NONE, width = 40, terminalInterface = recorder)

    private fun queryOf(url: String) =
        URI(url).rawQuery.split("&").associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), UTF_8) }

    private fun get(url: String) =
        HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI(url)).build(), HttpResponse.BodyHandlers.discarding())

    /** Plays the browser: Keycloak redirects back to the loopback port with a code. */
    private fun comeBack(signInUrl: String) {
        val query = queryOf(signInUrl)
        get("${query["redirect_uri"]}/?state=${query["state"]}&code=c1")
    }

    private fun signIn(sso: FakeServer, dir: Path) = BrowserSignIn(
        AccountRegion.GLOBAL,
        AccountEndpoints(sso.url, "https://store.example"),
        SessionStore(dir.resolve("session.json")),
    )

    @Test
    fun `sign-in prints the page address on one line, opens it and names the account`(@TempDir dir: Path) {
        FakeServer().use { sso ->
            val accessToken = jwt("""{"name":"Ann Lee","email":"ann@example.com"}""")
            sso.on("POST", tokenPath) { FakeServer.json(200, """{"access_token":"$accessToken","refresh_token":"r1"}""") }
            val opened = mutableListOf<String>()

            signInInteractively(terminal, signIn(sso, dir)) { url, _ ->
                opened += url
                comeBack(url)
            }

            // `jmix trial` signs in through here too: a browser that still has a session shows no page, so only
            // the last line tells which account the trial question is about.
            assertEquals(
                listOf(
                    "Opening the Jmix sign-in page in your browser. If it does not open, visit:",
                    opened.single(),
                    "Waiting for sign-in in the browser (Ctrl+C to cancel)...",
                    "Signed in to your Jmix account as Ann Lee (ann@example.com).",
                ),
                recorder.output().trimEnd().lines(),
            )
        }
    }

    @Test
    fun `a browser that cannot be opened is only a warning and the sign-in still completes`(@TempDir dir: Path) {
        FakeServer().use { sso ->
            sso.on("POST", tokenPath) { FakeServer.json(200, """{"access_token":"a1","refresh_token":"r1"}""") }

            signInInteractively(terminal, signIn(sso, dir)) { url, onWarning ->
                onWarning("Failed to open the page in the browser: no launcher")
                comeBack(url) // The user pastes the printed address into a browser instead.
            }

            val output = recorder.output()
            assertTrue(output.contains("Warning: Failed to open the page in the browser: no launcher\n"), output)
            assertTrue(output.endsWith("Signed in to your Jmix account.\n"), output) // An opaque token names nobody.
        }
    }

    @Test
    fun `the redirect listener is closed when the sign-in fails`(@TempDir dir: Path) {
        FakeServer().use { sso ->
            sso.on("POST", tokenPath) { FakeServer.json(400, """{"error":"invalid_grant","error_description":"Code not valid"}""") }
            var redirectUri = ""

            val error = assertThrows<IOException> {
                signInInteractively(terminal, signIn(sso, dir)) { url, _ ->
                    redirectUri = queryOf(url).getValue("redirect_uri")
                    comeBack(url)
                }
            }

            assertEquals("Code not valid", error.message)
            // Its thread is not a daemon, so a listener left open would keep the CLI from exiting.
            assertThrows<IOException> { get(redirectUri) }
        }
    }

    @Test
    fun `login help describes the region choice and the update flag`() {
        val help = LoginCommand().test("--help", width = 200).stdout

        assertTrue(help.contains("Sign in to your Jmix account in the browser"), help)
        assertTrue(help.contains("--region=(global|ru)"), help)
        assertTrue(help.contains("Jmix account region: global or ru (default: global)"), help)
        assertTrue(help.contains("--no-update"), help)
    }
}
