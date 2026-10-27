package io.jmix.cli.util

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.time.Duration

/** Release version from the jar manifest; absent in some dev runs. */
internal fun cliVersion(): String? =
    Http::class.java.`package`?.implementationVersion?.takeIf { it.isNotBlank() }

/** Shared HTTP client and request builder, so every call identifies itself as the CLI. */
internal object Http {

    private const val CONNECT_TIMEOUT_SECONDS = 10L
    private const val REQUEST_TIMEOUT_SECONDS = 30L

    val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun request(url: String, timeout: Duration = Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS)): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout)
            .header("User-Agent", "jmix-cli/${cliVersion() ?: "dev"}")
}
