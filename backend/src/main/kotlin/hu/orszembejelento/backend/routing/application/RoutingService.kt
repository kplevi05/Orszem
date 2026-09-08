package hu.orszembejelento.backend.routing.application

import hu.orszembejelento.backend.reference.domain.CoverageComponentStatus
import hu.orszembejelento.backend.reference.domain.RailwayLine
import hu.orszembejelento.backend.reference.infrastructure.JdbcReferenceRepository
import hu.orszembejelento.backend.routing.domain.RoutingOutcome
import hu.orszembejelento.backend.routing.domain.UnclassifiedReason
import hu.orszembejelento.backend.scope.infrastructure.JdbcServiceAreaRepository
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Decides which service area, if any, a settlement (and optional railway line) belongs to.
 *
 * Application/domain service only - there is no HTTP routing endpoint (ADR 0007) and this
 * class is never called from a controller. It is read-only: nothing here writes to
 * `service_areas` or its mapping table. A future Phase 4 report-submission flow is the
 * intended caller.
 *
 * **Precondition, deliberately not enforced here:** [settlementId] is expected to already
 * name a settlement the caller has validated exists - reference/input validation is the
 * caller's responsibility, not this service's. An arbitrary or unknown UUID degrades
 * gracefully today (zero candidates, same as a real settlement with none), purely as a side
 * effect of how the query is written, not as a designed business meaning. **Phase 4 must
 * not treat that graceful degradation as a legitimate `UNCLASSIFIED` business outcome for
 * an unknown settlement** - it must validate the settlement itself before ever calling
 * `route`. That validation is intentionally not built here.
 *
 * The whole decision is read from the *current* reference state
 * ([JdbcReferenceRepository.findCurrentReferenceState]) and current service-area
 * configuration - never from a client-supplied guess. An explicitly supplied
 * [railwayLineId] is validated against a verified relation before it is trusted for
 * anything; it can narrow the decision, but it can never *substitute for* verification.
 *
 * ## The decision, in order
 *
 * 1. No current reference state at all → [RoutingOutcome.ReferenceDatasetUnavailable].
 *    Infrastructure, not a business result - see that type's KDoc.
 * 2. An explicit [railwayLineId] was supplied → it must resolve to an existing line with a
 *    verified relation to [settlementId] - **active or not** - or the result is
 *    [UnclassifiedReason.REFERENCE_MISMATCH]. This check runs regardless of whether
 *    relation coverage is COMPLETE or PARTIAL - "always validated", per ADR 0007. Whether
 *    the resolved line is currently active is decided afterwards, in step 4, exactly like
 *    an inferred line - explicit selection of a verified line that happens to be inactive
 *    is [UnclassifiedReason.RAILWAY_LINE_INACTIVE], never [UnclassifiedReason.REFERENCE_MISMATCH].
 * 3. No explicit line: the **candidate set for inference is active verified relations
 *    only** ([JdbcReferenceRepository.findActiveLinesOfSettlement] - the identical query
 *    the public reference API uses for line availability, so the two can never disagree
 *    about what is selectable). An inactive relation is a verified fact (see step 2), but
 *    it is not a candidate for automatic inference: offering to auto-resolve a line the
 *    public API would never have shown as an option is exactly the inconsistency this
 *    restriction exists to prevent. The active-candidate count, and the *current*
 *    `settlementRailwayLines` coverage component, together decide the result:
 *    - **zero active candidates** → [UnclassifiedReason.NO_VERIFIED_RAILWAY_LINE_REFERENCE],
 *      **regardless of coverage** - COMPLETE or PARTIAL alike. This reason means only that
 *      the current verified reference state has no active line relation on record for the
 *      settlement; see that reason's KDoc for why it must never be read as proof no
 *      railway physically exists there, whichever coverage produced it.
 *    - **exactly one active candidate, COMPLETE coverage** → inferred and resolved as if
 *      it had been selected explicitly.
 *    - **exactly one active candidate, PARTIAL coverage** → [UnclassifiedReason.RAILWAY_LINE_NOT_SELECTED].
 *      Under PARTIAL coverage, absence of another relation is not evidence there is only
 *      one (ADR 0006), so a single known candidate is never auto-inferred.
 *    - **two or more active candidates, any coverage** → [UnclassifiedReason.RAILWAY_LINE_NOT_SELECTED].
 * 4. Once a line is resolved (by either path above), it is checked against operational
 *    state, never against reference data again:
 *    - inactive line → [UnclassifiedReason.RAILWAY_LINE_INACTIVE] (reachable only via the
 *      explicit-selection path, step 2 - an inferred line is always active by construction)
 *    - active line, no service-area mapping → [UnclassifiedReason.RAILWAY_LINE_UNASSIGNED]
 *    - active line, inactive service area → [UnclassifiedReason.SERVICE_AREA_INACTIVE]
 *    - active line, active service area → [RoutingOutcome.Routed]
 */
@Service
class RoutingService(
    private val referenceRepository: JdbcReferenceRepository,
    private val serviceAreaRepository: JdbcServiceAreaRepository,
) {

    @Transactional(readOnly = true)
    fun route(settlementId: UUID, railwayLineId: UUID? = null): RoutingOutcome {
        val current = referenceRepository.findCurrentReferenceState()
            ?: return RoutingOutcome.ReferenceDatasetUnavailable

        val resolvedLine = if (railwayLineId != null) {
            resolveExplicitLine(settlementId, railwayLineId)
                ?: return RoutingOutcome.Unclassified(UnclassifiedReason.REFERENCE_MISMATCH, current.datasetVersion)
        } else {
            when (val inferred = inferLine(settlementId, current.settlementRailwayLinesCoverage)) {
                is InferenceResult.Line -> inferred.line
                is InferenceResult.Reason ->
                    return RoutingOutcome.Unclassified(inferred.reason, current.datasetVersion)
            }
        }

        return resolveLineToArea(resolvedLine, current.datasetVersion)
    }

    /** Null when the line does not exist, or exists but has no verified relation to the settlement. */
    private fun resolveExplicitLine(settlementId: UUID, railwayLineId: UUID): RailwayLine? {
        val line = referenceRepository.findRailwayLineById(railwayLineId) ?: return null
        if (!referenceRepository.relationExists(settlementId, railwayLineId)) return null
        return line
    }

    private sealed class InferenceResult {
        data class Line(val line: RailwayLine) : InferenceResult()
        data class Reason(val reason: UnclassifiedReason) : InferenceResult()
    }

    private fun inferLine(settlementId: UUID, relationCoverage: CoverageComponentStatus): InferenceResult {
        // Active candidates only, deliberately the same query the public reference API
        // uses for line availability (findActiveLinesOfSettlement): an inactive relation
        // is a verified fact, but never a candidate for automatic inference, so that
        // routing can never silently resolve a line the public API would never have
        // offered as a selectable option.
        val activeCandidates = referenceRepository.findActiveLinesOfSettlement(settlementId)

        return when {
            // Zero candidates is a fact about the *current verified reference state*, not
            // a claim about physical reality - it holds regardless of coverage, because
            // "we have no active verified relation right now" is true either way. See
            // NO_VERIFIED_RAILWAY_LINE_REFERENCE's KDoc: it must never be read as proof no
            // railway exists, only as "none is currently on record".
            activeCandidates.isEmpty() ->
                InferenceResult.Reason(UnclassifiedReason.NO_VERIFIED_RAILWAY_LINE_REFERENCE)

            // Exactly one candidate is only ever auto-inferred under COMPLETE coverage.
            // Under PARTIAL, absence of another relation is not evidence there is only
            // one (ADR 0006) - the single candidate falls through to the `else` branch.
            activeCandidates.size == 1 && relationCoverage == CoverageComponentStatus.COMPLETE ->
                InferenceResult.Line(activeCandidates.single())

            // Two or more candidates (any coverage), or exactly one under PARTIAL: the
            // reference state cannot safely narrow this to one line on its own.
            else -> InferenceResult.Reason(UnclassifiedReason.RAILWAY_LINE_NOT_SELECTED)
        }
    }

    private fun resolveLineToArea(line: RailwayLine, datasetVersion: String): RoutingOutcome {
        if (!line.active) {
            return RoutingOutcome.Unclassified(UnclassifiedReason.RAILWAY_LINE_INACTIVE, datasetVersion, line.id)
        }

        val area = serviceAreaRepository.findAreaOfRailwayLine(line.id)
            ?: return RoutingOutcome.Unclassified(UnclassifiedReason.RAILWAY_LINE_UNASSIGNED, datasetVersion, line.id)

        if (!area.isActive) {
            return RoutingOutcome.Unclassified(UnclassifiedReason.SERVICE_AREA_INACTIVE, datasetVersion, line.id)
        }

        return RoutingOutcome.Routed(area.id, line.id, datasetVersion)
    }
}
