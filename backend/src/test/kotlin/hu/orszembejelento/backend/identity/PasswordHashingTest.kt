package hu.orszembejelento.backend.identity

import hu.orszembejelento.backend.identity.domain.PasswordHasher
import org.junit.jupiter.api.Test

/**
 * Guards the one property that must never silently regress: passwords are hashed with
 * **Argon2id**, not Argon2i, Argon2d, bcrypt or PBKDF2, and with parameters at or above
 * the OWASP baseline.
 *
 * The encoded form carries the algorithm and parameters, so asserting on it is a direct
 * check of what would actually be written to the database.
 */
class PasswordHashingTest {

    private val hasher = PasswordHasher.default()

    @Test
    fun `produces argon2id hashes with the configured parameters`() {
        val encoded = hasher.hash("a sufficiently long passphrase")

        check(encoded.startsWith("\$argon2id\$")) {
            "password hash must be Argon2id, got: ${encoded.take(24)}"
        }
        // Encoded form: $argon2id$v=19$m=<memory>,t=<iterations>,p=<parallelism>$salt$hash
        check(encoded.contains("\$v=19\$")) { "expected Argon2 version 19, got: $encoded" }

        val params = encoded.split("$")[3]
        val memory = Regex("m=(\\d+)").find(params)!!.groupValues[1].toInt()
        val iterations = Regex("t=(\\d+)").find(params)!!.groupValues[1].toInt()
        val parallelism = Regex("p=(\\d+)").find(params)!!.groupValues[1].toInt()

        check(memory >= 19 * 1024) { "OWASP baseline is >= 19 MiB, got ${memory}KiB" }
        check(iterations >= 2) { "OWASP baseline is >= 2 iterations, got $iterations" }
        check(parallelism >= 1) { "parallelism must be >= 1, got $parallelism" }
    }

    @Test
    fun `verifies a correct password and rejects a wrong one`() {
        val encoded = hasher.hash("correct horse battery staple")

        check(hasher.matches("correct horse battery staple", encoded)) { "correct password must verify" }
        check(!hasher.matches("Correct horse battery staple", encoded)) { "wrong password must not verify" }
    }

    @Test
    fun `salts each hash independently`() {
        val a = hasher.hash("correct horse battery staple")
        val b = hasher.hash("correct horse battery staple")

        check(a != b) { "identical passwords must not produce identical hashes" }
        check(hasher.matches("correct horse battery staple", a))
        check(hasher.matches("correct horse battery staple", b))
    }

    @Test
    fun `treats an unparseable stored hash as a non-match rather than throwing`() {
        check(!hasher.matches("anything", "not-a-hash")) { "corrupt hash must not verify" }
    }
}
