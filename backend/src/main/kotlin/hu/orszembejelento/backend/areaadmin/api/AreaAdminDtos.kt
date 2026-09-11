package hu.orszembejelento.backend.areaadmin.api

import hu.orszembejelento.backend.areaadmin.domain.RailwayLineAdminListPage
import hu.orszembejelento.backend.areaadmin.domain.RailwayLineAdminListRow
import hu.orszembejelento.backend.areaadmin.domain.ServiceAreaAdminDetail
import hu.orszembejelento.backend.areaadmin.domain.ServiceAreaAdminListPage
import hu.orszembejelento.backend.areaadmin.domain.ServiceAreaAdminListRow
import hu.orszembejelento.backend.areaadmin.domain.ServiceAreaMappedRailwayLine
import hu.orszembejelento.backend.reference.domain.ServiceArea
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.util.UUID

/** Every mutation body carries the optimistic-concurrency version it read - mirrors `ModerationDeleteRequest`. */
data class CreateServiceAreaRequest(@field:NotBlank val name: String = "")

data class RenameServiceAreaRequest(@field:NotNull val expectedVersion: Long? = null, @field:NotBlank val name: String = "")

data class ServiceAreaVersionedRequest(@field:NotNull val expectedVersion: Long? = null)

/**
 * The assign/move endpoint's one body shape (brief §19/§20) - `expectedCurrentServiceAreaId`
 * null means "assign an unassigned line", non-null means "move from that specific area".
 */
data class AssignRailwayLineRequest(
    @field:NotNull val targetServiceAreaId: UUID? = null,
    val expectedCurrentServiceAreaId: UUID? = null,
)

data class UnassignRailwayLineRequest(@field:NotNull val expectedCurrentServiceAreaId: UUID? = null)

/** A mutated/created ServiceArea, returned by create/rename/activate/deactivate - never the raw `adminVersion` name leaking internal DB metadata beyond what brief §8 itself requires clients to carry back as `expectedVersion` next time. */
data class ServiceAreaAdminResponse(val id: String, val name: String, val active: Boolean, val adminVersion: Long) {
    companion object {
        fun from(area: ServiceArea) = ServiceAreaAdminResponse(area.id.toString(), area.name, area.isActive, area.adminVersion)
    }
}

data class ServiceAreaAdminListItemResponse(
    val id: String,
    val name: String,
    val active: Boolean,
    val adminVersion: Long,
    val mappedRailwayLineCount: Int,
    val openOperationalReportCount: Int,
) {
    companion object {
        fun from(row: ServiceAreaAdminListRow) = ServiceAreaAdminListItemResponse(
            id = row.id.toString(),
            name = row.name,
            active = row.active,
            adminVersion = row.adminVersion,
            mappedRailwayLineCount = row.mappedRailwayLineCount,
            openOperationalReportCount = row.openOperationalReportCount,
        )
    }
}

data class ServiceAreaAdminListPageResponse(
    val items: List<ServiceAreaAdminListItemResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
) {
    companion object {
        fun from(result: ServiceAreaAdminListPage, page: Int, size: Int): ServiceAreaAdminListPageResponse {
            val totalPages = if (size <= 0) 0 else (result.totalElements + size - 1) / size
            return ServiceAreaAdminListPageResponse(result.items.map(ServiceAreaAdminListItemResponse::from), page, size, result.totalElements, totalPages)
        }
    }
}

data class MappedRailwayLineResponse(val id: String, val lineCode: String, val displayName: String, val active: Boolean) {
    companion object {
        fun from(line: ServiceAreaMappedRailwayLine) = MappedRailwayLineResponse(line.id.toString(), line.lineCode, line.displayName, line.active)
    }
}

data class ServiceAreaAdminDetailResponse(
    val id: String,
    val name: String,
    val active: Boolean,
    val adminVersion: Long,
    val mappedRailwayLines: List<MappedRailwayLineResponse>,
    val mappedRailwayLineCount: Int,
    val openOperationalReportCount: Int,
) {
    companion object {
        fun from(detail: ServiceAreaAdminDetail) = ServiceAreaAdminDetailResponse(
            id = detail.id.toString(),
            name = detail.name,
            active = detail.active,
            adminVersion = detail.adminVersion,
            mappedRailwayLines = detail.mappedRailwayLines.map(MappedRailwayLineResponse::from),
            mappedRailwayLineCount = detail.mappedRailwayLineCount,
            openOperationalReportCount = detail.openOperationalReportCount,
        )
    }
}

data class RailwayLineAdminListItemResponse(
    val id: String,
    val lineCode: String,
    val displayName: String,
    val active: Boolean,
    val currentServiceAreaId: String?,
    val currentServiceAreaName: String?,
) {
    companion object {
        fun from(row: RailwayLineAdminListRow) = RailwayLineAdminListItemResponse(
            id = row.id.toString(),
            lineCode = row.lineCode,
            displayName = row.displayName,
            active = row.active,
            currentServiceAreaId = row.currentServiceAreaId?.toString(),
            currentServiceAreaName = row.currentServiceAreaName,
        )
    }
}

data class RailwayLineAdminListPageResponse(
    val items: List<RailwayLineAdminListItemResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
) {
    companion object {
        fun from(result: RailwayLineAdminListPage, page: Int, size: Int): RailwayLineAdminListPageResponse {
            val totalPages = if (size <= 0) 0 else (result.totalElements + size - 1) / size
            return RailwayLineAdminListPageResponse(result.items.map(RailwayLineAdminListItemResponse::from), page, size, result.totalElements, totalPages)
        }
    }
}
