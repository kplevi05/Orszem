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
    /**
     * The settlement has zero currently *active* verified railway-line relations, under
     * either COMPLETE or PARTIAL relation coverage.
     *
     * This means only that the current verified reference state has no active line
     * relation on record for this settlement right now. **It must never be read, stated,
     * or documented as proof that no railway line physically exists there** - not even
     * under COMPLETE coverage, and certainly not under PARTIAL, where the dataset does not
     * claim to enumerate every settlement a line crosses in the first place (ADR 0006).
     * "No verified reference" and "no railway" are different claims; this reason makes
     * only the first one, and only about *active* relations - a settlement whose only
     * relation is to a currently-inactive line reaches this reason too, since an inactive
     * relation is never an inference candidate (see `RoutingService`).
     */
    NO_VERIFIED_RAILWAY_LINE_REFERENCE,

    /**
     * No railway line was supplied by the caller, and the current reference state cannot
     * safely narrow it down to exactly one active candidate: either relation coverage is
     * PARTIAL (absence of another relation is not evidence there is only one - ADR
     * 0006/0007, so even a single active candidate is not auto-inferred), or coverage is
     * COMPLETE but the settlement has two or more active verified relations.
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

    /**
     * [resolvedRailwayLineId] is present exactly when a specific line *was* resolved (by
     * inference or explicit selection) and only then found unassigned or inactive -
     * [UnclassifiedReason.RAILWAY_LINE_UNASSIGNED], [UnclassifiedReason.RAILWAY_LINE_INACTIVE]
     * or [UnclassifiedReason.SERVICE_AREA_INACTIVE]. It is null for the reasons reached
     * before any line was resolved at all -
     * [UnclassifiedReason.NO_VERIFIED_RAILWAY_LINE_REFERENCE],
     * [UnclassifiedReason.RAILWAY_LINE_NOT_SELECTED] and [UnclassifiedReason.REFERENCE_MISMATCH].
     * Added for Phase 4's report routing snapshot, which records exactly this distinction;
     * `RoutingService`'s own decision logic (ADR 0007) is unchanged by it.
     */
    data class Unclassified(
        val reason: UnclassifiedReason,
        override val referenceDatasetVersion: String,
        val resolvedRailwayLineId: UUID? = null,
    ) : Decided()

    /** Infrastructure state: no reference dataset has ever been successfully imported. */
    data object ReferenceDatasetUnavailable : RoutingOutcome()
}
