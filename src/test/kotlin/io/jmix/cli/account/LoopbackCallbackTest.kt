package io.jmix.cli.account

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LoopbackCallbackTest {

    @Test
    fun `callback errors do not echo arbitrary provider text`() {
        LoopbackCallback("s1", "https://sso.example/delegated").use { callback ->
            get("${callback.redirectUri}/?state=s1&error=PRIVATE&error_description=PRIVATE%1B%5B2J")
            val error = assertThrows<IOException> { callback.await(Duration.ofSeconds(5)) }
            assertTrue(!error.message!!.contains("PRIVATE") && !error.message!!.contains('\u001b'), error.message)
        }
    }

    private val noRedirects = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    private fun get(url: String) = noRedirects.send(HttpRequest.newBuilder(URI(url)).build(), HttpResponse.BodyHandlers.discarding())

    @Test
    fun `returns the code and sends the browser to the signed-in page`() {
        LoopbackCallback("s1", "https://sso.example/delegated").use { callback ->
            assertTrue(Regex("http://localhost:\\d+").matches(callback.redirectUri), callback.redirectUri)
            val response = get("${callback.redirectUri}/?state=s1&code=c1")
            assertEquals(302, response.statusCode())
            assertEquals("https://sso.example/delegated", response.headers().firstValue("Location").orElseThrow())
            assertEquals("c1", callback.await(Duration.ofSeconds(5)))
        }
    }

    @Test
    fun `reports a denied sign-in and shows the failure page`() {
        LoopbackCallback("s1", "https://sso.example/delegated").use { callback ->
            val response = get("${callback.redirectUri}/?error=access_denied&error_description=User%20cancelled&state=s1")
            assertEquals("https://sso.example/delegated?error=true", response.headers().firstValue("Location").orElseThrow())
            assertEquals("sign-in was denied in the browser", assertThrows<IOException> { callback.await(Duration.ofSeconds(5)) }.message)
        }
    }

    @Test
    fun `ignores invalid requests and still accepts the real callback`() {
        LoopbackCallback("s1", "https://sso.example/delegated").use { callback ->
            for (path in listOf(
                "/?state=other&code=c1", "/?code=c1", "/?error=access_denied&state=other",
                "/wrong?state=s1&code=c1", "/favicon.ico", "/?state=s1&state=s1&code=c1",
                "/?state=s1&code=c1&code=c2", "/?state=s1&code=c1&error=access_denied",
            )) {
                assertEquals(404, get(callback.redirectUri + path).statusCode(), path)
            }
            val post = HttpRequest.newBuilder(URI("${callback.redirectUri}/?state=s1&code=c1"))
                .POST(HttpRequest.BodyPublishers.noBody()).build()
            assertEquals(404, noRedirects.send(post, HttpResponse.BodyHandlers.discarding()).statusCode())
            assertEquals(302, get("${callback.redirectUri}/?state=s1&code=c2").statusCode())
            assertEquals("c2", callback.await(Duration.ofSeconds(5)))
        }
    }

    @Test
    fun `times out when the browser never comes back`() {
        LoopbackCallback("s1", "https://sso.example/delegated").use { callback ->
            val error = assertThrows<IOException> { callback.await(Duration.ofMillis(200)) }
            assertTrue(error.message!!.contains("timed out"), error.message)
        }
    }

    @Test
    fun `answers 404 once the sign-in has finished`() {
        LoopbackCallback("s1", "https://sso.example/delegated").use { callback ->
            assertEquals(302, get("${callback.redirectUri}/?state=s1&code=c1").statusCode())
            assertEquals(404, get("${callback.redirectUri}/?state=s1&code=c2").statusCode())
            assertEquals("c1", callback.await(Duration.ofSeconds(5)))
        }
    }

    @Test
    fun `the browser still gets its redirect when the caller closes right after await`() {
        // Closing the server cuts off a redirect that is still being written,
        // so the redirect has to be on its way before await returns.
        repeat(20) {
            val callback = LoopbackCallback("s1", "https://sso.example/delegated")
            val browser = CompletableFuture.supplyAsync { get("${callback.redirectUri}/?state=s1&code=c1").statusCode() }
            callback.use { it.await(Duration.ofSeconds(5)) }
            assertEquals(302, browser.get(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `treats blank parameters as missing`() {
        LoopbackCallback("s1", "https://sso.example/delegated").use { callback ->
            assertEquals(404, get("${callback.redirectUri}/?state=s1&code=").statusCode())
            get("${callback.redirectUri}/?state=s1&error=access_denied&error_description=")
            assertEquals("sign-in was denied in the browser", assertThrows<IOException> { callback.await(Duration.ofSeconds(5)) }.message)
        }
    }
}
