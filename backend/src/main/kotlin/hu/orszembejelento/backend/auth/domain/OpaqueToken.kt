package hu.orszembejelento.backend.auth.domain

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * Opaque bearer credentials.
 *
 * Deliberately **not** JWT. A JWT is worth its complexity when a resource server must
 * validate a token without asking the issuer. Here there is one backend, one database and
 * one client type, and every request already touches PostgreSQL — so a self-contained
 * token would buy nothing and cost the thing that matters most: immediate revocation.
 * With an opaque token, logout, deactivation and role changes take effect on the very
 * next request, because the server reads current state instead of trusting a signed claim.
 *
 * Wire format:
 * ```
 *   at_<session-uuid>.<secret>      access token
 *   rt_<refresh-token-uuid>.<secret> refresh token
 * ```
 * The UUID is a database lookup key, not a secret. The secret is 256 bits of CSPRNG
 * output, Base64url-encoded without padding.
 *
 * The distinct prefixes make the two token types unambiguous, so a refresh token can
 * never be presented as a bearer access token, or the reverse — a type-confusion class of
 * bug that is easy to introduce when both are just opaque strings.
 *
 * Only SHA-256 of the secret is ever stored. That is sound here precisely because the
 * secret is uniformly random and high-entropy: unlike a password, it has no guessable
 * distribution, so a slow memory-hard hash would add latency to every authenticated
 * request while defending against nothing.
 */
data class OpaqueToken(
    val type: TokenType,
    val id: UUID,
    val secret: String,
) {
    /** The full credential string handed to the client. Never logged, never persisted. */
    fun serialize(): String = "${type.prefix}$id.$secret"

    fun secretHash(): ByteArray = hashSecret(secret)

    enum class TokenType(val prefix: String) {
        ACCESS("at_"),
        REFRESH("rt_"),
    }

    companion object {
        private const val SECRET_BYTES = 32 // 256 bits
        private val random = SecureRandom()
        private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

        fun issue(type: TokenType, id: UUID): OpaqueToken {
            val bytes = ByteArray(SECRET_BYTES).also(random::nextBytes)
            return OpaqueToken(type, id, encoder.encodeToString(bytes))
        }

        /**
         * Parses a presented credential.
         *
         * Returns null for anything malformed — wrong prefix, wrong type, missing
         * separator, unparseable UUID. Callers turn that into the same generic
         * authentication failure as a valid-but-unknown token, so the shape of the failure
         * never tells an attacker how far their guess got.
         */
        fun parse(raw: String?, expected: TokenType): OpaqueToken? {
            if (raw == null || !raw.startsWith(expected.prefix)) return null

            val body = raw.substring(expected.prefix.length)
            val separator = body.indexOf('.')
            if (separator <= 0 || separator == body.length - 1) return null

            val id = runCatching { UUID.fromString(body.substring(0, separator)) }.getOrNull() ?: return null
            val secret = body.substring(separator + 1)
            if (secret.isEmpty()) return null

            return OpaqueToken(expected, id, secret)
        }

        fun hashSecret(secret: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8))

        /**
         * Constant-time comparison of stored and presented digests.
         *
         * `MessageDigest.isEqual` does not short-circuit on the first differing byte, so
         * it does not leak how much of a guess was correct through response timing.
         */
        fun secretMatches(presentedSecret: String, storedHash: ByteArray): Boolean =
            MessageDigest.isEqual(hashSecret(presentedSecret), storedHash)
    }
}
