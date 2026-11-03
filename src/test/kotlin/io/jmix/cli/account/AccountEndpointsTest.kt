package io.jmix.cli.account

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AccountEndpointsTest {

    @Test
    fun `global endpoints use the Keycloak realm and store paths`() {
        val endpoints = AccountEndpoints.of(AccountRegion.GLOBAL) { null }
        assertEquals("https://sso.jmix.io/auth/realms/jmix-cuba/protocol/openid-connect/auth", endpoints.authorizeUrl)
        assertEquals("https://sso.jmix.io/auth/realms/jmix-cuba/protocol/openid-connect/token", endpoints.tokenUrl)
        assertEquals("https://sso.jmix.io/auth/realms/jmix-cuba/protocol/openid-connect/delegated", endpoints.signedInPageUrl)
        assertEquals("https://store-admin.jmix.io/app/rest/v3", endpoints.storeApiUrl)
    }

    @Test
    fun `russian region uses the jmix ru hosts`() {
        val endpoints = AccountEndpoints.of(AccountRegion.of("ru")) { null }
        assertEquals("https://sso.jmix.ru", endpoints.ssoUrl)
        assertEquals("https://store-admin.jmix.ru", endpoints.storeUrl)
        assertEquals("https://store.jmix.ru/account", AccountRegion.RUSSIA.accountPageUrl)
        assertEquals("https://www.jmix.ru/contacts/", AccountRegion.RUSSIA.contactsUrl)
    }

    @Test
    fun `environment overrides replace the hosts`() {
        val env = mapOf("JMIX_CLI_SSO_URL" to "http://127.0.0.1:9/", "JMIX_CLI_STORE_URL" to "http://127.0.0.1:8")
        val endpoints = AccountEndpoints.of(AccountRegion.GLOBAL) { env[it] }
        assertEquals(AccountEndpoints("http://127.0.0.1:9", "http://127.0.0.1:8"), endpoints)
    }

    @Test
    fun `account overrides require a secure endpoint without credentials in the URL`() {
        for (url in listOf("http://sso.example/PRIVATE", "https://user:PRIVATE@sso.example", "https://sso.example/?PRIVATE")) {
            val error = assertThrows<IllegalArgumentException> {
                AccountEndpoints.of(AccountRegion.GLOBAL) { name -> url.takeIf { name == "JMIX_CLI_SSO_URL" } }
            }
            assertEquals("Jmix account endpoints must use HTTPS, or HTTP on loopback for testing.", error.message)
        }
    }

    @Test
    fun `global region links the jmix io pages`() {
        assertEquals(AccountRegion.GLOBAL, AccountRegion.of("global"))
        assertEquals("https://store.jmix.io/account", AccountRegion.GLOBAL.accountPageUrl)
        assertEquals("https://www.jmix.io/contacts/", AccountRegion.GLOBAL.contactsUrl)
    }

    @Test
    fun `unknown region id is rejected`() {
        assertThrows<IllegalArgumentException> { AccountRegion.of("cn") }
    }
}
