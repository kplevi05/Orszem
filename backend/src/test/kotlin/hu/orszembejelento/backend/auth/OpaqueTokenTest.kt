package hu.orszembejelento.backend.auth

import hu.orszembejelento.backend.auth.domain.OpaqueToken
import hu.orszembejelento.backend.auth.domain.OpaqueToken.TokenType
import java.util.UUID
import org.junit.jupiter.api.Test

class OpaqueTokenTest {

    @Test
    fun `round-trips through serialization`() {
        val id = UUID.randomUUID()
        val token = OpaqueToken.issue(TokenType.ACCESS, id)

        val parsed = OpaqueToken.parse(token.serialize(), TokenType.ACCESS)
        check(parsed != null) { "a freshly issued token must parse" }
        check(parsed.id == id)
        check(parsed.secret == token.secret)
    }

    @Test
    fun `access and refresh tokens are not interchangeable`() {
        val access = OpaqueToken.issue(TokenType.ACCESS, UUID.randomUUID())
        val refresh = OpaqueToken.issue(TokenType.REFRESH, UUID.randomUUID())

        // Type confusion is the bug this prefix exists to prevent: an access token must
        // never be usable as a refresh token, nor the reverse.
        check(OpaqueToken.parse(access.serialize(), TokenType.REFRESH) == null) {
            "an access token must not parse as a refresh token"
        }
        check(OpaqueToken.parse(refresh.serialize(), TokenType.ACCESS) == null) {
            "a refresh token must not parse as an access token"
        }
    }

    @Test
    fun `rejects malformed input without throwing`() {
        listOf(
            null, "", "   ", "at_", "at_.", "rt_.secret", "Bearer at_x.y",
            "at_not-a-uuid.secret", "at_${UUID.randomUUID()}", "at_${UUID.randomUUID()}.",
            "${UUID.randomUUID()}.secret", "at${UUID.randomUUID()}.secret",
        ).forEach { candidate ->
            check(OpaqueToken.parse(candidate, TokenType.ACCESS) == null) {
                "should have rejected: $candidate"
            }
        }
    }

    @Test
    fun `secrets are high entropy and distinct`() {
        val secrets = (1..1000).map { OpaqueToken.issue(TokenType.ACCESS, UUID.randomUUID()).secret }
        check(secrets.toSet().size == 1000) { "token secrets must never repeat" }
        // 32 random bytes, Base64url without padding.
        check(secrets.all { it.length >= 42 }) { "secret is shorter than 256 bits of entropy" }
    }

    @Test
    fun `hashing is stable and matching is exact`() {
        val token = OpaqueToken.issue(TokenType.REFRESH, UUID.randomUUID())
        val stored = token.secretHash()

        check(OpaqueToken.secretMatches(token.secret, stored))
        check(!OpaqueToken.secretMatches(token.secret + "x", stored))
        check(!OpaqueToken.secretMatches(token.secret.dropLast(1), stored))
        // SHA-256 digest, so 32 bytes stored as BYTEA.
        check(stored.size == 32) { "expected a 32-byte SHA-256 digest, got ${stored.size}" }
    }

    @Test
    fun `the serialized form never contains the stored hash`() {
        val token = OpaqueToken.issue(TokenType.ACCESS, UUID.randomUUID())
        val serialized = token.serialize()
        val hashHex = token.secretHash().joinToString("") { "%02x".format(it) }

        check(!serialized.contains(hashHex)) { "the wire format must not expose the stored digest" }
        check(serialized.startsWith("at_")) { "access tokens must be identifiable by prefix" }
    }
}
