package hu.orszembejelento.backend.areaadmin.api

import hu.orszembejelento.backend.areaadmin.domain.RailwayLineAdminListPage
import hu.orszembejelento.backend.areaadmin.domain.RailwayLineAdminListRow
import hu.orszembejelento.backend.areaadmin.domain.RailwayLineSettlementAssignment
import hu.orszembejelento.backend.areaadmin.domain.RailwayLineSettlementMappings
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
    val mappedSettlementLineCount: Int,
) {
    companion object {
        fun from(row: ServiceAreaAdminListRow) = ServiceAreaAdminListItemResponse(
            id = row.id.toString(),
            name = row.name,
            active = row.active,
            adminVersion = row.adminVersion,
            mappedRailwayLineCount = row.mappedRailwayLineCount,
            openOperationalReportCount = row.openOperationalReportCount,
            mappedSettlementLineCount = row.mappedSettlementLineCount,
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
    val mappedSettlementLineCount: Int,
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
            mappedSettlementLineCount = detail.mappedSettlementLineCount,
        )
    }
}

data class RailwayLineAdminListItemResponse(
    val id: String,
    val lineCode: String,
    val displayName: String,
    val active: Boolean,
    /** The *whole-line* mapping only; null for a pair-configured line - read [assignmentMode], never infer "unassigned" from this. */
    val currentServiceAreaId: String?,
    val currentServiceAreaName: String?,
    /** `UNASSIGNED` | `WHOLE_LINE` | `PER_SETTLEMENT` (ADR 0011). Additive field: older clients ignore it. */
    val assignmentMode: String,
    /** Number of (settlement, line) pairs configured for this line; 0 unless `PER_SETTLEMENT`. */
    val settlementMappingCount: Int,
) {
    companion object {
        fun from(row: RailwayLineAdminListRow) = RailwayLineAdminListItemResponse(
            id = row.id.toString(),
            lineCode = row.lineCode,
            displayName = row.displayName,
            active = row.active,
            currentServiceAreaId = row.currentServiceAreaId?.toString(),
            currentServiceAreaName = row.currentServiceAreaName,
            assignmentMode = row.assignmentMode.name,
            settlementMappingCount = row.settlementMappingCount,
        )
    }
}

/**
 * One verified settlement of a line and where it routes today. Deliberately carries no
 * routing reason, evidence, reference version, revision or internal state - an administrator
 * sees *what is configured*, nothing about how it was derived.
 */
data class RailwayLineSettlementAssignmentResponse(
    val settlementId: String,
    val kshCode: String,
    val settlementName: String,
    val countyName: String?,
    /** `false` = a retired settlement whose relation still exists: administrative/historical visibility only, NOT "currently selectable in the Public clients". */
    val settlementActive: Boolean,
    val serviceAreaId: String?,
    val serviceAreaName: String?,
    val serviceAreaActive: Boolean?,
) {
    companion object {
        fun from(row: RailwayLineSettlementAssignment) = RailwayLineSettlementAssignmentResponse(
            settlementId = row.settlementId.toString(),
            kshCode = row.kshCode,
            settlementName = row.settlementName,
            countyName = row.countyName,
            settlementActive = row.settlementActive,
            serviceAreaId = row.serviceAreaId?.toString(),
            serviceAreaName = row.serviceAreaName,
            serviceAreaActive = row.serviceAreaActive,
        )
    }
}

data class RailwayLineSettlementMappingsResponse(
    val railwayLineId: String,
    val lineCode: String,
    val displayName: String,
    val active: Boolean,
    val assignmentMode: String,
    /** The exact number of currently verified settlements of this line (not a coverage claim). */
    val settlementCount: Int,
    val items: List<RailwayLineSettlementAssignmentResponse>,
    /** True only if [items] was capped below [settlementCount]. */
    val truncated: Boolean,
) {
    companion object {
        fun from(detail: RailwayLineSettlementMappings) = RailwayLineSettlementMappingsResponse(
            railwayLineId = detail.railwayLineId.toString(),
            lineCode = detail.lineCode,
            displayName = detail.displayName,
            active = detail.active,
            assignmentMode = detail.assignmentMode.name,
            settlementCount = detail.settlementCount,
            items = detail.items.map(RailwayLineSettlementAssignmentResponse::from),
            truncated = detail.truncated,
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
