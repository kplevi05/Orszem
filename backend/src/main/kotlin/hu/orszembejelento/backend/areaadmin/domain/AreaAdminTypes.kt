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
    val mappedSettlementLineCount: Int = 0,
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
    val mappedSettlementLineCount: Int = 0,
)

/**
 * Which assignment state to filter the RailwayLine admin list to (brief §39, extended for
 * ADR 0011's pair-level routing).
 *
 * A line is in exactly one of three routing modes (ADR 0011 forbids mixing them):
 * `ASSIGNED` = mapped as a *whole line* to one area (the legacy mode, unchanged);
 * `PER_SETTLEMENT` = configured pair by pair through `service_area_settlement_lines`;
 * `UNASSIGNED` = neither - genuinely without any ServiceArea assignment. A pair-configured
 * line is therefore never "unassigned": `UNASSIGNED` excludes it, which is what keeps the
 * "Hozzárendelés nélküli" filter honest once pair-level mappings exist.
 */
enum class RailwayLineAssignmentFilter { ALL, ASSIGNED, PER_SETTLEMENT, UNASSIGNED }

/** The three mutually exclusive routing modes a RailwayLine can be in (ADR 0011). */
enum class RailwayLineAssignmentMode { UNASSIGNED, WHOLE_LINE, PER_SETTLEMENT }

data class RailwayLineAdminListFilter(
    val query: String? = null,
    val active: Boolean? = null,
    val serviceAreaId: UUID? = null,
    val assignment: RailwayLineAssignmentFilter = RailwayLineAssignmentFilter.ALL,
)

/**
 * One row of the RailwayLine admin list/picker (brief §39/§50).
 *
 * [currentServiceAreaId]/[currentServiceAreaName] describe the *whole-line* mapping only and
 * stay null for a pair-configured line - [assignmentMode] and [settlementMappingCount] say
 * what such a line actually is, so a client never has to infer "unassigned" from a null.
 */
data class RailwayLineAdminListRow(
    val id: UUID,
    val lineCode: String,
    val displayName: String,
    val active: Boolean,
    val currentServiceAreaId: UUID?,
    val currentServiceAreaName: String?,
    val settlementMappingCount: Int = 0,
) {
    val assignmentMode: RailwayLineAssignmentMode
        get() = when {
            currentServiceAreaId != null -> RailwayLineAssignmentMode.WHOLE_LINE
            settlementMappingCount > 0 -> RailwayLineAssignmentMode.PER_SETTLEMENT
            else -> RailwayLineAssignmentMode.UNASSIGNED
        }
}

data class RailwayLineAdminListPage(val items: List<RailwayLineAdminListRow>, val totalElements: Int)

/**
 * One currently verified (settlement, line) relation of a RailwayLine and the ServiceArea it
 * routes to today. [serviceAreaId] is the *effective* area: the pair mapping if one exists,
 * else the line's whole-line mapping, else null (that pair currently resolves UNCLASSIFIED).
 */
data class RailwayLineSettlementAssignment(
    val settlementId: UUID,
    val kshCode: String,
    val settlementName: String,
    val countyName: String?,
    val settlementActive: Boolean,
    val serviceAreaId: UUID?,
    val serviceAreaName: String?,
    val serviceAreaActive: Boolean?,
)

/** The pair-level detail of one RailwayLine: its mode and its currently verified settlements. */
data class RailwayLineSettlementMappings(
    val railwayLineId: UUID,
    val lineCode: String,
    val displayName: String,
    val active: Boolean,
    val assignmentMode: RailwayLineAssignmentMode,
    val settlementCount: Int,
    val items: List<RailwayLineSettlementAssignment>,
    val truncated: Boolean,
)
