package hu.orszembejelento.backend.areaadmin.application

import hu.orszembejelento.backend.areaadmin.domain.RailwayLineAdminListFilter
import hu.orszembejelento.backend.areaadmin.domain.RailwayLineAdminListPage
import hu.orszembejelento.backend.areaadmin.domain.ServiceAreaAdminDetail
import hu.orszembejelento.backend.areaadmin.domain.ServiceAreaAdminListFilter
import hu.orszembejelento.backend.areaadmin.domain.ServiceAreaAdminListPage
import hu.orszembejelento.backend.areaadmin.domain.ServiceAreaMappedRailwayLine
import hu.orszembejelento.backend.areaadmin.domain.ServiceAreaNotFoundException
import hu.orszembejelento.backend.areaadmin.infrastructure.JdbcAreaAdminQueryRepository
import hu.orszembejelento.backend.reference.infrastructure.JdbcReferenceRepository
import hu.orszembejelento.backend.scope.infrastructure.JdbcServiceAreaRepository
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
        )
    }

    @Transactional(readOnly = true)
    fun railwayLineList(filter: RailwayLineAdminListFilter, page: Int, size: Int): RailwayLineAdminListPage =
        queries.findRailwayLineList(filter, page, size)

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
        const val MAX_PAGE_SIZE = 100
    }
}
