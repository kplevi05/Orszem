package hu.orszembejelento.backend.identity.domain

import java.security.SecureRandom
import java.util.Locale

/**
 * Generates one-time temporary credentials for account creation and administrative reset.
 *
 * The alphabet excludes characters that are read back wrongly when a credential is
 * dictated over a phone or copied off a screen: `0`/`O`, `1`/`I`/`l`. What remains is 32
 * unambiguous symbols, which also makes each character exactly 5 bits.
 *
 * 16 characters × 5 bits = **80 bits** of entropy — far beyond guessing, while staying
 * short enough that a person can actually transcribe it.
 *
 * Presented in four hyphen-separated groups (`XXXX-XXXX-XXXX-XXXX`) purely so a human can
 * keep their place. The hyphens are formatting, and [normalize] accepts the credential
 * with or without them, in any case, so nobody is locked out for typing it in lower case
 * or omitting the dashes.
 */
class TemporaryCredentialGenerator(private val random: SecureRandom = SecureRandom()) {

    fun generate(): String {
        val chars = CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }
        return String(chars).chunked(GROUP_SIZE).joinToString("-")
    }

    companion object {
        /** Crockford-style unambiguous alphabet: no 0/O, no 1/I/L. */
        const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        const val LENGTH = 16
        const val GROUP_SIZE = 4

        /**
         * Canonical form of a typed temporary credential.
         *
         * Hyphens and surrounding whitespace are formatting, so they are removed, and the
         * alphabet is upper-case only. This normalization applies **only** to temporary
         * credentials, never to user-chosen passwords, whose exact characters are
         * significant.
         */
        fun normalize(raw: String): String =
            raw.trim().replace("-", "").uppercase(Locale.ROOT)
    }
}
