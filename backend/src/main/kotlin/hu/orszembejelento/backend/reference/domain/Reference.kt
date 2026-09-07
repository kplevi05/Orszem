package hu.orszembejelento.backend.reference.domain

import java.time.Instant
import java.util.UUID

/**
 * A Hungarian settlement, identified externally by its official KSH code.
 *
 * The KSH code is the stable external key: imports match on it so that a settlement keeps
 * its internal [id] across dataset versions. The name is deliberately not identity — names
 * are renamed, and two settlements can share one.
 */
data class Settlement(
    val id: UUID,
    val kshCode: KshCode,
    val name: String,
    val countyCode: String?,
    val countyName: String?,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * An official KSH settlement identifier: exactly five decimal digits.
 *
 * A value class over `String`, never an integer. `01234` and `1234` are different
 * identifiers, and an integer round-trip silently merges them.
 */
@JvmInline
value class KshCode private constructor(val value: String) {

    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("^\\d{5}$")

        fun parseOrNull(raw: String?): KshCode? {
            val trimmed = raw?.trim() ?: return null
            return if (PATTERN.matches(trimmed)) KshCode(trimmed) else null
        }

        /** Rehydrates a value already known to be canonical, such as one read from the database. */
        fun ofTrusted(value: String): KshCode = KshCode(value)
    }
}

/**
 * A national railway line.
 *
 * [lineCode] is text, not a number: some national line identifiers carry letter suffixes,
 * and the code is an identifier rather than a quantity.
 *
 * The infrastructure manager is intentionally not part of this concept. Which organisation
 * manages or operates a line can change — as several Hungarian lines did in 2025 — without
 * the line becoming a different line. Modelling an operator here would have forced a
 * reference-data change for an event that did not alter the railway.
 */
data class RailwayLine(
    val id: UUID,
    val lineCode: String,
    val displayName: String,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * An operational responsibility area.
 *
 * Customer configuration, not reference data: it describes how this organisation currently
 * divides work, and means nothing outside Őrszem. That is precisely why its identity never
 * appears in [Settlement] or [RailwayLine].
 */
data class ServiceArea(
    val id: UUID,
    val name: String,
    val status: ServiceAreaStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val isActive: Boolean get() = status == ServiceAreaStatus.ACTIVE
}

enum class ServiceAreaStatus { ACTIVE, INACTIVE }
