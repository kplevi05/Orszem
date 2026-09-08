package hu.orszembejelento.backend.routing.domain

import java.util.UUID

/**
 * Why a settlement (and optional railway line) did not resolve to a service area.
 *
 * A closed set, matching exactly the distinctions Phase 3C requires - see ADR 0007. Each
 * reason names a different fact about the current reference/service-area state, never a
 * guess: nothing here is inferred from a similarity score or a client's unverified claim.
 */
enum class UnclassifiedReason {
    /** COMPLETE relation coverage, and the settlement has zero verified relations. */
    NO_VERIFIED_RAILWAY_LINE_REFERENCE,

    /**
     * No railway line was supplied by the caller, and the current reference state cannot
     * safely narrow it down to exactly one: either relation coverage is PARTIAL (absence
     * of another relation is not evidence there is only one - ADR 0006/0007), or coverage
     * is COMPLETE but the settlement has more than one verified relation.
     */
    RAILWAY_LINE_NOT_SELECTED,

    /** The supplied railway line exists but has no verified relation to the settlement. */
    REFERENCE_MISMATCH,

    /** The resolved railway line is active and verified, but no service area claims it. */
    RAILWAY_LINE_UNASSIGNED,

    /** The resolved railway line is not active. */
    RAILWAY_LINE_INACTIVE,

    /** The resolved railway line's service area exists but is not active. */
    SERVICE_AREA_INACTIVE,
}

/**
 * The result of [hu.orszembejelento.backend.routing.application.RoutingService.route].
 *
 * [ReferenceDatasetUnavailable] is deliberately **not** a fourth [Unclassified] reason.
 * `Unclassified` is a business conclusion about a specific settlement and line, reached by
 * consulting a real reference state; `ReferenceDatasetUnavailable` means no such state
 * exists to consult at all. Collapsing the two would let "we checked and found no
 * reference" and "we have never successfully imported anything" look identical to a
 * caller, which is exactly the false-negative this system exists to avoid (see ADR 0006's
 * treatment of PARTIAL coverage, and ADR 0007).
 */
sealed class RoutingOutcome {

    /**
     * [referenceDatasetVersion] names the reference-state revision that was current when
     * this decision was made - not that every fact used in it originated in that specific
     * manifest. A relation preserved from an older PARTIAL import can still be what
     * resolved this decision, under the newest [CurrentReferenceState][hu.orszembejelento.backend.reference.domain.CurrentReferenceState]'s version. See ADR 0007.
     */
    sealed class Decided : RoutingOutcome() {
        abstract val referenceDatasetVersion: String
    }

    data class Routed(
        val serviceAreaId: UUID,
        val railwayLineId: UUID,
        override val referenceDatasetVersion: String,
    ) : Decided()

    data class Unclassified(
        val reason: UnclassifiedReason,
        override val referenceDatasetVersion: String,
    ) : Decided()

    /** Infrastructure state: no reference dataset has ever been successfully imported. */
    data object ReferenceDatasetUnavailable : RoutingOutcome()
}
