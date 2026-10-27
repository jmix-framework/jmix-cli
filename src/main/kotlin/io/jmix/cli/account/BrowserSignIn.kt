package io.jmix.cli.account

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.jmix.cli.util.string
import java.time.Duration
import java.util.Base64
import kotlin.text.Charsets.UTF_8

/** Who signed in, as far as the access token says. Either part can be missing. */
internal data class Identity(val name: String?, val email: String?) {

    /** The account as a message names it: "Ann Lee (ann@example.com)", the email alone, or a generic phrase. */
    fun describe(): String = when {
        name != null && email != null -> "$name ($email)"
        else -> name ?: email ?: "your Jmix account"
    }

    companion object {
        /**
         * Reads the `name` and `email` claims from the payload of the JWT [token]. The token is not verified:
         * it came straight from the token endpoint and the claims only greet the user. Anything unexpected
         * gives an empty identity.
         */
        fun fromAccessToken(token: String): Identity = try {
            val payload = Base64.getUrlDecoder().decode(token.split(".")[1])
            val claims = JsonParser.parseString(String(payload, UTF_8)) as? JsonObject
            fun claim(name: String) = claims?.string(name)?.takeIf { it.isNotBlank() }
            Identity(claim("name"), claim("email"))
        } catch (e: Exception) {
            // Dropped on purpose: Gson's message quotes the payload, and a token we cannot read is no reason to fail.
            Identity(null, null)
        }
    }
}

/**
 * Signs in to a Jmix account in the user's browser. The identity provider sends the browser back to a loopback
 * port with a code, which is traded for tokens (PKCE, no client secret). Only the refresh token is kept.
 */
internal class BrowserSignIn(
    private val region: AccountRegion,
    private val endpoints: AccountEndpoints = AccountEndpoints.of(region),
    private val sessions: SessionStore = SessionStore(),
    private val tokenClient: TokenClient = TokenClient(endpoints),
) {

    /**
     * Starts listening for the browser. Close the result on every path, also when the browser cannot be opened:
     * the listener's thread is not a daemon, so it keeps the JVM alive.
     */
    fun start(): Pending {
        val pkce = Pkce.create()
        val state = Pkce.newState()
        val callback = LoopbackCallback(state, endpoints.signedInPageUrl)
        return Pending(pkce, callback, tokenClient.authorizationUrl(pkce, state, callback.redirectUri))
    }

    /** A sign-in that waits for the browser; [url] is the page the user has to open. */
    inner class Pending internal constructor(
        private val pkce: Pkce,
        private val callback: LoopbackCallback,
        val url: String,
    ) : AutoCloseable {

        /**
         * Waits up to [timeout] for the browser to come back, trades the code for tokens, saves the session and
         * returns who signed in. A failure surfaces as an `IOException` that says why and leaves the session saved
         * before as it was.
         */
        fun complete(timeout: Duration = SIGN_IN_TIMEOUT): Identity {
            val code = callback.await(timeout)
            val tokens = tokenClient.exchangeCode(code, pkce, callback.redirectUri)
            sessions.save(Session(region.id, endpoints.ssoUrl, endpoints.storeUrl, tokens.refreshToken))
            return Identity.fromAccessToken(tokens.accessToken)
        }

        override fun close() = callback.close()
    }

    companion object {
        val SIGN_IN_TIMEOUT: Duration = Duration.ofMinutes(10)
    }
}
