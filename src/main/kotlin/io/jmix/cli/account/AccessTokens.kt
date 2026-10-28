package io.jmix.cli.account

/**
 * The access token of one signed-in [session]. Only the refresh token is saved, so the first call trades it for
 * an access token; that token is then reused for the rest of the process until the store rejects it.
 * Refresh tokens rotate, so every refresh saves the new one.
 */
internal class AccessTokens(
    private val sessions: SessionStore,
    session: Session,
    private val tokenClient: TokenClient = TokenClient(AccountEndpoints(session.ssoUrl, session.storeUrl)),
) {

    private var session = session
    private var accessToken: String? = null

    /** The access token to send, requested only when this process has none yet. */
    fun current(): String = accessToken ?: refresh()

    /**
     * Trades the refresh token for new tokens, saves the rotated refresh token and returns the new access token.
     * Throws [SessionExpiredException], after deleting the saved session, when the provider has ended it.
     */
    fun refresh(): String {
        val tokens = try {
            tokenClient.refresh(session.refreshToken)
        } catch (e: SessionExpiredException) {
            sessions.delete()
            throw e
        }
        // Refresh tokens rotate, so the old one may be spent: keep the new one in memory even if saving it fails.
        val identity = Identity.fromAccessToken(tokens.accessToken)
        session = session.copy(
            refreshToken = tokens.refreshToken,
            identity = identity.takeIf { it.displayName != null } ?: session.identity,
        )
        sessions.save(session)
        accessToken = tokens.accessToken
        return tokens.accessToken
    }
}
