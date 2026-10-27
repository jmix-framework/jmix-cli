package io.jmix.cli.account

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * PKCE (RFC 7636, method S256) pair for one sign-in. Never print or log the
 * [verifier]; only the [challenge] goes into the authorization URL.
 */
internal class Pkce(val verifier: String) {

    val challenge: String = challengeOf(verifier)

    companion object {
        // 32 random bytes encode to a 43-character verifier, the RFC's minimum length.
        private const val VERIFIER_BYTES = 32

        fun create(random: SecureRandom = SecureRandom()): Pkce {
            val bytes = ByteArray(VERIFIER_BYTES).also { random.nextBytes(it) }
            return Pkce(base64Url(bytes))
        }

        fun challengeOf(verifier: String): String =
            base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        /** Opaque value the callback must echo back, tying it to this sign-in attempt. */
        fun newState(): String = UUID.randomUUID().toString()

        private fun base64Url(bytes: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
