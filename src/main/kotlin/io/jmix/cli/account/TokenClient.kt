package io.jmix.cli.account

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.jmix.cli.util.Http
import java.io.IOException
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.text.Charsets.UTF_8

/** The tokens of one sign-in. */
internal data class Tokens(val accessToken: String, val refreshToken: String) {

    /** Hides both tokens, so a value that reaches a message or a log does not leak them. */
    override fun toString(): String = "Tokens(accessToken=<hidden>, refreshToken=<hidden>)"
}

/** The saved sign-in can no longer be refreshed, so the user has to sign in again. */
internal class SessionExpiredException :
    IOException("Your Jmix account session has expired. Run 'jmix login' to sign in again.")

/** Talks to the identity provider as a public client: PKCE proves the caller, and there is no client secret. */
internal class TokenClient(private val endpoints: AccountEndpoints, private val http: HttpClient = Http.client) {

    /** The page where the user signs in; the provider then redirects to [redirectUri] with a code and [state]. */
    fun authorizationUrl(pkce: Pkce, state: String, redirectUri: String): String =
        endpoints.authorizeUrl + "?" + formEncode(
            mapOf(
                "response_type" to "code",
                "client_id" to AccountEndpoints.CLIENT_ID,
                "redirect_uri" to redirectUri,
                "scope" to "openid",
                "state" to state,
                "code_challenge" to pkce.challenge,
                "code_challenge_method" to "S256",
            ),
        )

    /** Trades the authorization [code] for tokens; the PKCE verifier proves this is the client that asked for it. */
    fun exchangeCode(code: String, pkce: Pkce, redirectUri: String): Tokens = requestTokens(
        mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "code_verifier" to pkce.verifier,
            "client_id" to AccountEndpoints.CLIENT_ID,
        ),
        isRefresh = false,
    )

    /**
     * Trades [refreshToken] for new tokens. Refresh tokens rotate, so the returned one replaces it.
     * Throws [SessionExpiredException] when the provider has ended the session.
     */
    fun refresh(refreshToken: String): Tokens = requestTokens(
        mapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to AccountEndpoints.CLIENT_ID,
        ),
        isRefresh = true,
    )

    private fun requestTokens(form: Map<String, String>, isRefresh: Boolean): Tokens {
        val request = Http.request(endpoints.tokenUrl)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(formEncode(form)))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        val status = response.statusCode()
        val json = parseObject(response.body())
        if (status == 200) return tokensOf(json) ?: throw unexpectedResponse(status)

        val error = json?.string("error")
        // On a refresh, invalid_grant means the session is gone; on a code exchange it only means a bad code.
        if (isRefresh && (status == 400 || status == 401) && error == "invalid_grant") throw SessionExpiredException()
        val message = json?.string("error_description") ?: error ?: "the Jmix sign-in service returned HTTP $status"
        throw IOException(message)
    }

    private fun tokensOf(json: JsonObject?): Tokens? {
        val accessToken = json?.string("access_token") ?: return null
        val refreshToken = json.string("refresh_token") ?: return null
        return Tokens(accessToken, refreshToken)
    }

    /** The body as a JSON object, or null when it is anything else. */
    private fun parseObject(body: String): JsonObject? = try {
        JsonParser.parseString(body) as? JsonObject
    } catch (e: JsonParseException) {
        // Dropped on purpose: Gson's message quotes the body, which can hold tokens.
        null
    }

    private fun JsonObject.string(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.asString?.takeIf { it.isNotBlank() }

    // Never add the body: it can hold tokens.
    private fun unexpectedResponse(status: Int) =
        IOException("the Jmix sign-in service returned an unexpected response (HTTP $status)")

    private fun formEncode(fields: Map<String, String>): String =
        fields.entries.joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }

    private fun encode(text: String): String = URLEncoder.encode(text, UTF_8)
}
