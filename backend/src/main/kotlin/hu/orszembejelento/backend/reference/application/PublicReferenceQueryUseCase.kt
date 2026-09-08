package hu.orszembejelento.backend.reference.application

import hu.orszembejelento.backend.reference.domain.CoverageComponentStatus
import hu.orszembejelento.backend.reference.domain.RailwayLine
import hu.orszembejelento.backend.reference.domain.ReferenceDatasetUnavailableException
import hu.orszembejelento.backend.reference.domain.Settlement
import hu.orszembejelento.backend.reference.domain.SettlementQueryTooShortException
import hu.orszembejelento.backend.reference.infrastructure.JdbcReferenceRepository
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** The railway lines with a verified relation to a settlement, and whether that roster is complete. */
data class RailwayLinesForSettlement(
    val coverage: CoverageComponentStatus,
    val lines: List<RailwayLine>,
)

/**
 * Read-only queries behind the two Public reference endpoints (ADR 0007).
 *
 * No authentication, no mutation, and deliberately nothing about service areas, users, or
 * moderators anywhere in these results - the Public client gets reference facts and
 * coverage completeness, never routing destinations or internal configuration. See
 * `PublicReferenceController`.
 *
 * Both methods refuse to answer with a misleadingly empty result when no reference dataset
 * has ever been imported: [ReferenceDatasetUnavailableException] surfaces that as a
 * distinct condition, mapped to HTTP 503 by `ApiExceptionHandler` rather than an
 * indistinguishable 200 with an empty list.
 */
@Service
class PublicReferenceQueryUseCase(private val repository: JdbcReferenceRepository) {

    @Transactional(readOnly = true)
    fun searchSettlements(query: String): List<Settlement> {
        requireCurrentReferenceState()

        val codePoints = query.codePointCount(0, query.length)
        if (codePoints < MINIMUM_QUERY_CODE_POINTS) {
            throw SettlementQueryTooShortException(MINIMUM_QUERY_CODE_POINTS)
        }

        return repository.searchActiveSettlements(query, MAX_SEARCH_RESULTS)
    }

    @Transactional(readOnly = true)
    fun railwayLinesOfSettlement(settlementId: UUID): RailwayLinesForSettlement {
        val current = requireCurrentReferenceState()

        // Active lines only: an inactive line is not a meaningful option to offer a
        // citizen selecting where their report belongs, even though it stays a verified
        // fact `RoutingService` still consults (see that class's KDoc). A settlement that
        // does not exist, or has no active verified relation, yields an empty list here,
        // not an error - consistent with how routing treats absence.
        val lines = repository.findActiveLinesOfSettlement(settlementId)
        return RailwayLinesForSettlement(current.settlementRailwayLinesCoverage, lines)
    }

    private fun requireCurrentReferenceState() =
        repository.findCurrentReferenceState() ?: throw ReferenceDatasetUnavailableException()

    private companion object {
        const val MINIMUM_QUERY_CODE_POINTS = 2

        // Bounded so a broad query cannot pull the whole settlement table in one request.
        // No client-supplied limit: keeping the contract fixed avoids a second thing to
        // validate, and 20 is generous for an autocomplete-style search.
        const val MAX_SEARCH_RESULTS = 20
    }
}
