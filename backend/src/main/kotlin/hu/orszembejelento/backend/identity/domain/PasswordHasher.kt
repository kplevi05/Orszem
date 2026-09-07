package hu.orszembejelento.backend.identity.domain

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder

/**
 * The single place where password hashing parameters are decided.
 *
 * Everything else in the system depends on this abstraction rather than on Spring
 * Security directly, so the cost parameters — or the algorithm itself — can be upgraded
 * deliberately later without touching use cases.
 *
 * Algorithm: **Argon2id**, at or above the current OWASP baseline
 * (>= 19 MiB memory, >= 2 iterations, >= 1 parallelism). The parameters are not raised
 * beyond that without benchmark evidence: memory cost is paid on every single login, and
 * an unmeasured increase buys latency rather than security.
 */
class PasswordHasher(private val encoder: PasswordEncoder) {

    /** Hashes a password. The caller must pass an already-normalized password. */
    fun hash(password: String): String =
        requireNotNull(encoder.encode(password)) { "password encoder returned no hash" }

    /**
     * Verifies a password against a stored hash.
     *
     * A stored value that cannot be parsed returns `false` rather than throwing: a corrupt
     * row must fail authentication, not turn into a 500 that distinguishes it from a
     * wrong password.
     */
    fun matches(password: String, storedHash: String): Boolean =
        runCatching { encoder.matches(password, storedHash) }.getOrDefault(false)

    companion object {
        /** OWASP baseline. Keep these explicit: silent defaults are how cost regressions happen. */
        const val SALT_LENGTH_BYTES = 16
        const val HASH_LENGTH_BYTES = 32
        const val PARALLELISM = 1
        const val MEMORY_KIB = 19 * 1024
        const val ITERATIONS = 2

        fun default(): PasswordHasher = PasswordHasher(
            Argon2PasswordEncoder(
                SALT_LENGTH_BYTES,
                HASH_LENGTH_BYTES,
                PARALLELISM,
                MEMORY_KIB,
                ITERATIONS,
            ),
        )
    }
}
