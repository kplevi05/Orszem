package hu.orszembejelento.backend.identity.domain

import java.security.SecureRandom
import java.util.Locale

/**
 * A service user's pseudonymous identity: `SZ-` followed by exactly six decimal digits.
 *
 * It is deliberately not derived from anything about the person, and carries no role
 * prefix — encoding a role in the identifier would leak it and would break as soon as a
 * role changes.
 */
@JvmInline
value class ServiceId private constructor(val value: String) {

    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("^SZ-\\d{6}$")

        /**
         * Parses user input into the canonical form.
         *
         * Surrounding whitespace is trimmed and letters are upper-cased with
         * [Locale.ROOT] — deliberately not the default locale, where Turkish would turn
         * "sz" into "SZ" via a dotted capital I in related cases and produce an identifier
         * that no longer matches the stored one.
         *
         * Returns null for anything that is not a well-formed service ID. Callers treat
         * that as ordinary input validation, not as an authentication failure.
         */
        fun parseOrNull(raw: String?): ServiceId? {
            val canonical = raw?.trim()?.uppercase(Locale.ROOT) ?: return null
            return if (PATTERN.matches(canonical)) ServiceId(canonical) else null
        }

        /** Rehydrates a value already known to be canonical, such as one read from the database. */
        fun ofTrusted(value: String): ServiceId = ServiceId(value)
    }
}

/**
 * Generates service IDs with a CSPRNG.
 *
 * Sequential identifiers are avoided on purpose: they would disclose how many service
 * users exist and roughly when an account was created, and would let anyone enumerate
 * valid identities by counting upwards.
 *
 * Six digits is one million values, so collisions are expected long before the space is
 * exhausted. The database UNIQUE constraint is the authority; this generator only
 * proposes candidates and the caller retries on conflict.
 */
class ServiceIdGenerator(private val random: SecureRandom = SecureRandom()) {

    fun next(): ServiceId =
        ServiceId.ofTrusted("SZ-%06d".format(random.nextInt(1_000_000)))
}
