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
 *    verified relation to [settlementId], or the result is
 *    [UnclassifiedReason.REFERENCE_MISMATCH]. This check runs regardless of whether
 *    relation coverage is COMPLETE or PARTIAL - "always validated", per ADR 0007.
 * 3. No explicit line: every verified relation of the settlement is read
 *    ([JdbcReferenceRepository.findVerifiedLinesOfSettlement], unfiltered by line-active
 *    status - see that method's KDoc), and the *current* `settlementRailwayLines`
 *    coverage component decides what absence, or ambiguity, means:
 *    - **PARTIAL**: always [UnclassifiedReason.RAILWAY_LINE_NOT_SELECTED], regardless of
 *      how many relations are currently known - zero, one, or many. Under PARTIAL
 *      coverage, absence of another relation is not evidence there is only one (ADR 0006),
 *      so a single known relation is never auto-inferred.
 *    - **COMPLETE**: zero relations → [UnclassifiedReason.NO_VERIFIED_RAILWAY_LINE_REFERENCE];
 *      exactly one → inferred and resolved as if it had been selected explicitly; more
 *      than one → [UnclassifiedReason.RAILWAY_LINE_NOT_SELECTED].
 * 4. Once a line is resolved (by either path above), it is checked against operational
 *    state, never against reference data again:
 *    - inactive line → [UnclassifiedReason.RAILWAY_LINE_INACTIVE]
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
        // PARTIAL: absence of another relation is not evidence there is only one, whatever
        // the current count. Never infer from incomplete coverage - ADR 0006/0007.
        if (relationCoverage == CoverageComponentStatus.PARTIAL) {
            return InferenceResult.Reason(UnclassifiedReason.RAILWAY_LINE_NOT_SELECTED)
        }

        val relations = referenceRepository.findVerifiedLinesOfSettlement(settlementId)
        return when (relations.size) {
            0 -> InferenceResult.Reason(UnclassifiedReason.NO_VERIFIED_RAILWAY_LINE_REFERENCE)
            1 -> InferenceResult.Line(relations.single())
            else -> InferenceResult.Reason(UnclassifiedReason.RAILWAY_LINE_NOT_SELECTED)
        }
    }

    private fun resolveLineToArea(line: RailwayLine, datasetVersion: String): RoutingOutcome {
        if (!line.active) return RoutingOutcome.Unclassified(UnclassifiedReason.RAILWAY_LINE_INACTIVE, datasetVersion)

        val area = serviceAreaRepository.findAreaOfRailwayLine(line.id)
            ?: return RoutingOutcome.Unclassified(UnclassifiedReason.RAILWAY_LINE_UNASSIGNED, datasetVersion)

        if (!area.isActive) {
            return RoutingOutcome.Unclassified(UnclassifiedReason.SERVICE_AREA_INACTIVE, datasetVersion)
        }

        return RoutingOutcome.Routed(area.id, line.id, datasetVersion)
    }
}
