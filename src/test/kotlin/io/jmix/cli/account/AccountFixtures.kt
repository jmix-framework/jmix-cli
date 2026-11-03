package io.jmix.cli.account

import io.jmix.cli.FakeServer
import java.util.Base64

internal const val TOKEN_PATH = "/auth/realms/jmix-cuba/protocol/openid-connect/token"

/** An unsigned JWT whose claims are [payload]; the CLI reads access-token claims without verifying them. */
internal fun jwt(payload: String) =
    "e30." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + ".sig"

/** A signed-in client whose SSO and store both live on [server]. */
internal fun storeClient(server: FakeServer, sessions: SessionStore): StoreClient {
    val session = Session("global", server.url, server.url, "r0")
    sessions.save(session)
    return StoreClient("${server.url}/app/rest/v3", AccessTokens(sessions, session))
}

/** Token endpoint that hands out a1/r1, a2/r2, ... */
internal fun FakeServer.issuesTokens() {
    var n = 0
    on("POST", TOKEN_PATH) { n++; FakeServer.json(200, """{"access_token":"a$n","refresh_token":"r$n"}""") }
}
