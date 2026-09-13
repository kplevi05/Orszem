package hu.orszembejelento.backend.areaadmin.domain

import hu.orszembejelento.backend.identity.domain.UserRole
import java.util.UUID

/**
 * The actor administering ServiceAreas. Deliberately minimal compared to `ManagementActor`
 * (Phase 6) or `AreaActor` (routing scope) - Phase 10 authorisation has no territorial
 * nuance at all (brief §4: SUPER_ADMIN only, full stop), so this carries nothing but the
 * identity needed for audit attribution.
 */
data class AreaAdminActor(val userId: UUID, val role: UserRole) {
    val isSuperAdmin: Boolean get() = role == UserRole.SUPER_ADMIN
}

/** Optional server-side filters for the ServiceArea admin list (brief §36). */
data class ServiceAreaAdminListFilter(val query: String? = null, val active: Boolean? = null)

/** One row of the ServiceArea admin list (brief §37) - name, status and the two blocker counts, nothing more. */
data class ServiceAreaAdminListRow(
    val id: UUID,
    val name: String,
    val active: Boolean,
    val adminVersion: Long,
    val mappedRailwayLineCount: Int,
    val openOperationalReportCount: Int,
)

data class ServiceAreaAdminListPage(val items: List<ServiceAreaAdminListRow>, val totalElements: Int)

/** One mapped RailwayLine as shown inside a ServiceArea's detail (brief §38/§49). */
data class ServiceAreaMappedRailwayLine(val id: UUID, val lineCode: String, val displayName: String, val active: Boolean)

/** The full ServiceArea admin detail (brief §38). */
data class ServiceAreaAdminDetail(
    val id: UUID,
    val name: String,
    val active: Boolean,
    val adminVersion: Long,
    val mappedRailwayLines: List<ServiceAreaMappedRailwayLine>,
    val mappedRailwayLineCount: Int,
    val openOperationalReportCount: Int,
)

/** Which assignment state to filter the RailwayLine admin list to (brief §39). */
enum class RailwayLineAssignmentFilter { ALL, ASSIGNED, UNASSIGNED }

data class RailwayLineAdminListFilter(
    val query: String? = null,
    val active: Boolean? = null,
    val serviceAreaId: UUID? = null,
    val assignment: RailwayLineAssignmentFilter = RailwayLineAssignmentFilter.ALL,
)

/** One row of the RailwayLine admin list/picker (brief §39/§50). */
data class RailwayLineAdminListRow(
    val id: UUID,
    val lineCode: String,
    val displayName: String,
    val active: Boolean,
    val currentServiceAreaId: UUID?,
    val currentServiceAreaName: String?,
)

data class RailwayLineAdminListPage(val items: List<RailwayLineAdminListRow>, val totalElements: Int)
