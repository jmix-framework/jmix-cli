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
    fun `reports the provider error and shows the failure page`() {
        LoopbackCallback("s1", "https://sso.example/delegated").use { callback ->
            val response = get("${callback.redirectUri}/?error=access_denied&error_description=User%20cancelled&state=s1")
            assertEquals("https://sso.example/delegated?error=true", response.headers().firstValue("Location").orElseThrow())
            assertEquals("User cancelled", assertThrows<IOException> { callback.await(Duration.ofSeconds(5)) }.message)
        }
    }

    @Test
    fun `rejects a response for another sign-in`() {
        LoopbackCallback("s1", "https://sso.example/delegated").use { callback ->
            get("${callback.redirectUri}/?state=other&code=c1")
            val error = assertThrows<IOException> { callback.await(Duration.ofSeconds(5)) }
            assertTrue(error.message!!.contains("does not match"), error.message)
        }
    }

    @Test
    fun `ignores requests without a code and keeps waiting`() {
        LoopbackCallback("s1", "https://sso.example/delegated").use { callback ->
            assertEquals(404, get("${callback.redirectUri}/favicon.ico").statusCode())
            get("${callback.redirectUri}/?state=s1&code=c2")
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
    fun `does not trust a provider error that carries another sign-in's state`() {
        LoopbackCallback("s1", "https://sso.example/delegated").use { callback ->
            val response = get("${callback.redirectUri}/?error=access_denied&error_description=Call%20us&state=other")
            assertEquals("https://sso.example/delegated?error=true", response.headers().firstValue("Location").orElseThrow())
            val error = assertThrows<IOException> { callback.await(Duration.ofSeconds(5)) }
            assertEquals("the browser response does not match this sign-in", error.message)
        }
    }

    @Test
    fun `treats blank parameters as missing`() {
        LoopbackCallback("s1", "https://sso.example/delegated").use { callback ->
            assertEquals(404, get("${callback.redirectUri}/?state=s1&code=").statusCode())
            get("${callback.redirectUri}/?state=s1&error=access_denied&error_description=")
            assertEquals("access_denied", assertThrows<IOException> { callback.await(Duration.ofSeconds(5)) }.message)
        }
    }
}
