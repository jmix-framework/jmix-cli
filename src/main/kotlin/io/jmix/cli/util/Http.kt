package io.jmix.cli.util

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit.NANOSECONDS
import java.util.concurrent.TimeoutException

/** Release version from the jar manifest; absent in some dev runs. */
internal fun cliVersion(): String? =
    Http::class.java.`package`?.implementationVersion?.takeIf { it.isNotBlank() }

private val LOOPBACK_HTTP_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")

internal fun URI.isSecureOrLoopback(): Boolean {
    val hostname = host?.lowercase() ?: return false
    return userInfo == null && (scheme.equals("https", ignoreCase = true) ||
        (scheme.equals("http", ignoreCase = true) && hostname in LOOPBACK_HTTP_HOSTS))
}

/** Shared HTTP client and request builder, so every call identifies itself as the CLI. */
internal object Http {

    private const val CONNECT_TIMEOUT_SECONDS = 10L
    private const val REQUEST_TIMEOUT_SECONDS = 30L

    val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    // A redirect must never forward a bearer token, refresh token or PKCE verifier to another endpoint.
    val accountClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    fun request(url: String, timeout: Duration = Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS)): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout)
            .header("User-Agent", "jmix-cli/${cliVersion() ?: "dev"}")

    /** Bounds the complete response, including a stalled body after the headers have arrived. */
    fun sendText(client: HttpClient, request: HttpRequest): HttpResponse<String> {
        val timeout = request.timeout().orElseThrow()
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        try {
            return response.get(timeout.toNanos(), NANOSECONDS)
        } catch (e: TimeoutException) {
            response.cancel(true)
            throw HttpTimeoutException("The HTTP response timed out")
        } catch (e: InterruptedException) {
            response.cancel(true)
            throw e
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }
}
