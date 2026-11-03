package io.jmix.cli.account

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PkceTest {

    @Test
    fun `challenge matches RFC 7636 appendix B`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challengeOf("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun `verifier is 43 url-safe characters and new every time`() {
        val first = Pkce.create()
        val second = Pkce.create()
        assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(first.verifier), first.verifier)
        assertNotEquals(first.verifier, second.verifier)
        assertEquals(Pkce.challengeOf(first.verifier), first.challenge)
    }
}
