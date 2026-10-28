package io.jmix.cli.account

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.text.Charsets.UTF_8

/**
 * One-shot receiver for the browser's redirect after sign-in, listening on a loopback port the system picks.
 * The first request that carries a `code` or an `error` ends the sign-in; every other request, and every
 * request after that, gets 404. Never put the code or the query string into a message or a log.
 */
internal class LoopbackCallback(
    private val expectedState: String,
    private val signedInPageUrl: String,
) : AutoCloseable {

    private val outcome = CompletableFuture<String>()

    // Loopback only, so nothing outside this machine can reach the callback.
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    /** Where the provider sends the browser back; it goes into the authorization URL and the code exchange. */
    val redirectUri: String = "http://localhost:${server.address.port}"

    init {
        server.createContext("/") { exchange -> exchange.use { answer(it) } }
        server.start()
    }

    /**
     * Waits for the browser to come back and returns the authorization code.
     * Throws an [IOException] that says why when the sign-in failed or [timeout] passed first.
     */
    fun await(timeout: Duration): String = try {
        outcome.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        throw IOException("timed out waiting for sign-in in the browser")
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }

    override fun close() = server.stop(0)

    private fun answer(exchange: HttpExchange) {
        val query = queryOf(exchange)
        val code = query["code"].orEmpty()
        val error = query["error"]
        when {
            outcome.isDone || (code.isEmpty() && error == null) -> exchange.sendResponseHeaders(NOT_FOUND, NO_BODY)
            // Before the error: only the provider's redirect carries our state, so others cannot choose the message.
            query["state"] != expectedState -> fail(exchange, "the browser response does not match this sign-in")
            error != null -> fail(exchange, "sign-in was denied in the browser")
            else -> succeed(exchange, code)
        }
    }

    // succeed and fail answer the browser before they settle the outcome: a caller that closes the server
    // as soon as await() returns must not cut the redirect off.
    private fun succeed(exchange: HttpExchange, code: String) {
        redirect(exchange, signedInPageUrl)
        outcome.complete(code)
    }

    private fun fail(exchange: HttpExchange, reason: String) {
        redirect(exchange, "$signedInPageUrl?error=true")
        outcome.completeExceptionally(IOException(reason))
    }

    private fun redirect(exchange: HttpExchange, location: String) {
        exchange.responseHeaders.add("Location", location)
        exchange.sendResponseHeaders(FOUND, NO_BODY)
    }

    /** The decoded query parameters; a blank value counts as absent. */
    private fun queryOf(exchange: HttpExchange): Map<String, String> =
        exchange.requestURI.rawQuery.orEmpty().split("&").filter { it.isNotEmpty() }
            .associate { decode(it.substringBefore("=")) to decode(it.substringAfter("=", "")) }
            .filterValues { it.isNotBlank() }

    private fun decode(text: String): String = URLDecoder.decode(text, UTF_8)

    companion object {
        private const val FOUND = 302
        private const val NOT_FOUND = 404

        // sendResponseHeaders: -1 announces a response without a body.
        private const val NO_BODY = -1L
    }
}
