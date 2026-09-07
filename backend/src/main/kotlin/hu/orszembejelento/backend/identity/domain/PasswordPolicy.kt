package hu.orszembejelento.backend.identity.domain

import java.text.Normalizer
import java.util.Locale

/**
 * Canonical password representation.
 *
 * Passwords are normalized to Unicode NFC and to nothing else. The same normalization is
 * applied when a password is set and when it is verified, which is the entire point: if
 * creation and login normalized differently, a user could set a password they could never
 * type again. "á" entered as one code point and as "a" + combining accent are the same
 * password after NFC; without it they are not.
 *
 * Passwords are deliberately **not** trimmed. A leading or trailing space is a legitimate
 * character the user chose, and silently removing it changes their password.
 */
object PasswordNormalizer {
    fun normalize(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFC)
}

/** Why a password was rejected. Callers map this to a stable API error code. */
enum class PasswordPolicyViolation {
    TOO_SHORT,
    TOO_LONG,
    COMMON_PASSWORD,
    SAME_AS_CURRENT,
}

class PasswordPolicyException(val violation: PasswordPolicyViolation) :
    RuntimeException("password policy violation: $violation")

/**
 * Password rules for user-chosen passwords.
 *
 * Length is the only structural requirement. Composition rules — "must contain an
 * uppercase letter and a digit" — are not used: they measurably push people toward
 * predictable patterns like `Password1!` while excluding strong passphrases, and modern
 * guidance (NIST SP 800-63B) recommends length plus a blocklist instead.
 *
 * There is no periodic expiry, for the same reason: forced rotation produces incremental,
 * guessable variations of the previous password.
 *
 * Length is measured in **Unicode code points**, not UTF-16 units, so an emoji or an
 * astral-plane character counts once rather than twice.
 */
class PasswordPolicy(private val blocklist: CommonPasswordBlocklist) {

    fun validate(normalizedNewPassword: String, currentPasswordForComparison: String? = null) {
        val length = normalizedNewPassword.codePointCount(0, normalizedNewPassword.length)

        if (length < MIN_LENGTH_CODE_POINTS) throw PasswordPolicyException(PasswordPolicyViolation.TOO_SHORT)
        if (length > MAX_LENGTH_CODE_POINTS) throw PasswordPolicyException(PasswordPolicyViolation.TOO_LONG)

        if (currentPasswordForComparison != null && normalizedNewPassword == currentPasswordForComparison) {
            throw PasswordPolicyException(PasswordPolicyViolation.SAME_AS_CURRENT)
        }

        if (blocklist.isBlocked(normalizedNewPassword)) {
            throw PasswordPolicyException(PasswordPolicyViolation.COMMON_PASSWORD)
        }
    }

    companion object {
        /**
         * A long minimum is what makes the absence of composition rules safe. It is also
         * why the blocklist below targets *long* weak passwords rather than the usual
         * short ones, which this length already excludes.
         */
        const val MIN_LENGTH_CODE_POINTS = 15

        /**
         * An upper bound exists only to stop a megabyte of input being fed into Argon2,
         * which would be a cheap denial-of-service. It is far above any real password.
         */
        const val MAX_LENGTH_CODE_POINTS = 128
    }
}

/**
 * Offline blocklist of unacceptably common passwords.
 *
 * Entirely local: no HTTP call, no Have I Been Pwned lookup, no runtime network
 * dependency. Authentication must not depend on a third party being reachable, and
 * sending anything derived from a user's password to an external service is its own risk.
 *
 * Comparison is done on a folded representation — NFC, lower-cased with [Locale.ROOT],
 * surrounding whitespace stripped — so that `  PasswordPassword ` is caught as readily as
 * `passwordpassword`. That folding is used **only** for this comparison. The password that
 * gets hashed and stored is always the user's NFC-normalized original, untrimmed and with
 * its original case.
 */
class CommonPasswordBlocklist(private val entries: Set<String>) {

    fun isBlocked(normalizedPassword: String): Boolean = fold(normalizedPassword) in entries

    val size: Int get() = entries.size

    companion object {
        private const val RESOURCE = "/security/common-passwords.txt"

        private fun fold(value: String): String =
            Normalizer.normalize(value, Normalizer.Form.NFC).trim().lowercase(Locale.ROOT)

        fun loadDefault(): CommonPasswordBlocklist {
            val stream = CommonPasswordBlocklist::class.java.getResourceAsStream(RESOURCE)
                ?: error("common password blocklist resource $RESOURCE is missing")

            val entries = stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .map { fold(it) }
                    .toSet()
            }
            return CommonPasswordBlocklist(entries)
        }
    }
}
