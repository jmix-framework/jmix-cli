package io.jmix.cli.account

import io.jmix.cli.util.isSecureOrLoopback
import java.net.URI

/** Jmix account deployments; each has its own sign-in and store hosts and public pages. */
internal enum class AccountRegion(
    val id: String,
    val ssoUrl: String,
    val storeUrl: String,
    val accountPageUrl: String,
    val contactsUrl: String,
) {
    GLOBAL(
        id = "global",
        ssoUrl = "https://sso.jmix.io",
        storeUrl = "https://store-admin.jmix.io",
        accountPageUrl = "https://store.jmix.io/account",
        contactsUrl = "https://www.jmix.io/contacts/",
    ),
    RUSSIA(
        id = "ru",
        ssoUrl = "https://sso.jmix.ru",
        storeUrl = "https://store-admin.jmix.ru",
        accountPageUrl = "https://store.jmix.ru/account",
        contactsUrl = "https://www.jmix.ru/contacts/",
    );

    companion object {
        /** Clikt's `choice` keeps unknown ids from reaching here from user input. */
        fun of(id: String): AccountRegion =
            requireNotNull(entries.firstOrNull { it.id == id }) { "Unknown account region: $id" }
    }
}

/** Identity provider and store hosts of one region, with every URL the CLI calls derived from them. */
internal data class AccountEndpoints(val ssoUrl: String, val storeUrl: String) {

    init {
        require(isEndpoint(ssoUrl) && isEndpoint(storeUrl)) {
            "Jmix account endpoints must use HTTPS, or HTTP on loopback for testing."
        }
    }

    private val openIdConnectUrl: String get() = "$ssoUrl/auth/realms/jmix-cuba/protocol/openid-connect"

    val authorizeUrl: String get() = "$openIdConnectUrl/auth"
    val tokenUrl: String get() = "$openIdConnectUrl/token"

    /** Browser landing page after the sign-in callback; `?error=true` marks a failure. */
    val signedInPageUrl: String get() = "$openIdConnectUrl/delegated"

    val storeApiUrl: String get() = "$storeUrl/app/rest/v3"

    companion object {
        /** Public OAuth client, the same one Jmix Studio uses; it has no secret. */
        const val CLIENT_ID = "site-console"

        private fun isEndpoint(url: String): Boolean = runCatching { URI(url) }.getOrNull()?.let {
            it.isSecureOrLoopback() && it.rawQuery == null && it.rawFragment == null
        } ?: false

        /** [env] overrides the region's hosts, which lets staging and tests point elsewhere. */
        fun of(region: AccountRegion, env: (String) -> String? = System::getenv): AccountEndpoints =
            AccountEndpoints(
                ssoUrl = (env("JMIX_CLI_SSO_URL") ?: region.ssoUrl).trimEnd('/'),
                storeUrl = (env("JMIX_CLI_STORE_URL") ?: region.storeUrl).trimEnd('/'),
            )
    }
}
