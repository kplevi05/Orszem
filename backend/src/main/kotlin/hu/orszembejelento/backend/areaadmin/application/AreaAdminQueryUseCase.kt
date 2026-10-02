package hu.orszembejelento.backend.areaadmin.application

import hu.orszembejelento.backend.areaadmin.domain.RailwayLineAdminListFilter
import hu.orszembejelento.backend.areaadmin.domain.RailwayLineAdminListPage
import hu.orszembejelento.backend.areaadmin.domain.RailwayLineAdminNotFoundException
import hu.orszembejelento.backend.areaadmin.domain.RailwayLineAssignmentMode
import hu.orszembejelento.backend.areaadmin.domain.RailwayLineSettlementAssignment
import hu.orszembejelento.backend.areaadmin.domain.RailwayLineSettlementMappings
import hu.orszembejelento.backend.areaadmin.domain.ServiceAreaAdminDetail
import hu.orszembejelento.backend.areaadmin.domain.ServiceAreaAdminListFilter
import hu.orszembejelento.backend.areaadmin.domain.ServiceAreaAdminListPage
import hu.orszembejelento.backend.areaadmin.domain.ServiceAreaMappedRailwayLine
import hu.orszembejelento.backend.areaadmin.domain.ServiceAreaNotFoundException
import hu.orszembejelento.backend.areaadmin.infrastructure.JdbcAreaAdminQueryRepository
import hu.orszembejelento.backend.reference.infrastructure.JdbcReferenceRepository
import hu.orszembejelento.backend.scope.infrastructure.JdbcServiceAreaRepository
import java.text.Collator
import java.util.Locale
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Read-only queries behind the ServiceArea/RailwayLine admin screens (brief §36-§39). */
@Service
class AreaAdminQueryUseCase(
    private val serviceAreas: JdbcServiceAreaRepository,
    private val referenceRepository: JdbcReferenceRepository,
    private val queries: JdbcAreaAdminQueryRepository,
) {

    @Transactional(readOnly = true)
    fun areaList(filter: ServiceAreaAdminListFilter, page: Int, size: Int): ServiceAreaAdminListPage =
        queries.findAreaList(filter, page, size)

    @Transactional(readOnly = true)
    fun areaDetail(areaId: UUID): ServiceAreaAdminDetail {
        val area = serviceAreas.findById(areaId) ?: throw ServiceAreaNotFoundException()
        val mappedLineIds = serviceAreas.mappedRailwayLineIds(area.id)
        val mappedLines = mappedLineIds.mapNotNull { lineId ->
            referenceRepository.findRailwayLineById(lineId)?.let {
                ServiceAreaMappedRailwayLine(it.id, it.lineCode, it.displayName, it.active)
            }
        }.sortedBy { it.lineCode }

        return ServiceAreaAdminDetail(
            id = area.id,
            name = area.name,
            active = area.isActive,
            adminVersion = area.adminVersion,
            mappedRailwayLines = mappedLines,
            mappedRailwayLineCount = mappedLines.size,
            openOperationalReportCount = serviceAreas.countOpenOperationalReports(area.id),
            mappedSettlementLineCount = serviceAreas.countSettlementLineMappings(area.id),
        )
    }

    @Transactional(readOnly = true)
    fun railwayLineList(filter: RailwayLineAdminListFilter, page: Int, size: Int): RailwayLineAdminListPage =
        queries.findRailwayLineList(filter, page, size)

    /**
     * The pair-level detail of one RailwayLine: its routing mode and every currently verified
     * settlement relation with the ServiceArea that relation routes to today.
     *
     * One joined repository query for all rows (no per-settlement lookup), then ordered in
     * Hungarian collation here - never by an assumed database collation. States no coverage
     * claim: the rows are exactly what the *current* verified reference data names, and the
     * client says so (the relation set grows with later reference imports).
     */
    @Transactional(readOnly = true)
    fun railwayLineSettlementMappings(railwayLineId: UUID): RailwayLineSettlementMappings {
        val line = referenceRepository.findRailwayLineById(railwayLineId) ?: throw RailwayLineAdminNotFoundException()
        val (rows, total) = queries.findRailwayLineSettlementAssignments(line.id, DETAIL_LIMIT)
        val mode = when {
            serviceAreas.findAreaOfRailwayLine(line.id) != null -> RailwayLineAssignmentMode.WHOLE_LINE
            queries.hasPairMapping(line.id) -> RailwayLineAssignmentMode.PER_SETTLEMENT
            else -> RailwayLineAssignmentMode.UNASSIGNED
        }
        val collator = Collator.getInstance(Locale.forLanguageTag("hu"))
        val sorted = rows.sortedWith(
            Comparator<RailwayLineSettlementAssignment> { a, b -> collator.compare(a.settlementName, b.settlementName) }
                .thenComparing(Comparator.comparing(RailwayLineSettlementAssignment::kshCode)),
        )
        return RailwayLineSettlementMappings(
            railwayLineId = line.id,
            lineCode = line.lineCode,
            displayName = line.displayName,
            active = line.active,
            assignmentMode = mode,
            settlementCount = total,
            items = sorted,
            truncated = total > rows.size,
        )
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
        const val MAX_PAGE_SIZE = 100

        /** A single line's verified relations are a few dozen today; this only bounds a pathological future one. */
        const val DETAIL_LIMIT = 500
    }
}
