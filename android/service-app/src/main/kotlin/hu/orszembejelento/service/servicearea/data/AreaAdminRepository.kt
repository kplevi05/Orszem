package hu.orszembejelento.service.servicearea.data

import hu.orszembejelento.service.auth.data.AuthRepository
import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.common.data.apiCall

/** Server-side filters for the ServiceArea admin list (brief §36). */
data class ServiceAreaAdminListFilter(val query: String? = null, val active: Boolean? = null) {
    val activeFacetCount: Int get() = if (active != null) 1 else 0
}

/** Which assignment state to filter the RailwayLine admin list/picker to (brief §39/§50). */
enum class RailwayLineAssignmentFilter { ALL, ASSIGNED, UNASSIGNED }

data class RailwayLineAdminListFilter(
    val query: String? = null,
    val active: Boolean? = null,
    val serviceAreaId: String? = null,
    val assignment: RailwayLineAssignmentFilter = RailwayLineAssignmentFilter.ALL,
)

/**
 * The ServiceArea-administration surface every Phase 10 screen/ViewModel depends on.
 *
 * An interface, not a class - mirrors [hu.orszembejelento.service.moderation.data.ModerationRepository]'s
 * own reasoning: a test supplies a plain anonymous `object`, production code only ever
 * constructs [DefaultAreaAdminRepository] via [hu.orszembejelento.service.auth.data.NetworkModule].
 *
 * `assign(...)`/`unassign(...)` return `ApiResult<Unit>`, exactly like moderation delete/restore
 * - the backend never returns a body for these, and the caller re-fetches whatever it needs
 * (area detail, line list) rather than trusting a locally-reconstructed state (brief §65).
 */
interface AreaAdminRepository {
    suspend fun listAreas(page: Int, size: Int, filter: ServiceAreaAdminListFilter): ApiResult<ServiceAreaAdminListPageResponse>
    suspend fun areaDetail(areaId: String): ApiResult<ServiceAreaAdminDetailResponse>
    suspend fun createArea(name: String): ApiResult<ServiceAreaAdminResponse>
    suspend fun renameArea(areaId: String, expectedVersion: Long, name: String): ApiResult<ServiceAreaAdminResponse>
    suspend fun activateArea(areaId: String, expectedVersion: Long): ApiResult<ServiceAreaAdminResponse>
    suspend fun deactivateArea(areaId: String, expectedVersion: Long): ApiResult<ServiceAreaAdminResponse>
    suspend fun listRailwayLines(page: Int, size: Int, filter: RailwayLineAdminListFilter): ApiResult<RailwayLineAdminListPageResponse>
    suspend fun assignRailwayLine(railwayLineId: String, targetServiceAreaId: String, expectedCurrentServiceAreaId: String?): ApiResult<Unit>
    suspend fun unassignRailwayLine(railwayLineId: String, expectedCurrentServiceAreaId: String): ApiResult<Unit>
}

/**
 * Thin wrapper over [AreaAdminApi]: every method is [apiCall] plus the actual endpoint call,
 * nothing else - no business logic, the backend is authoritative for every state transition
 * and every blocker decision (brief §64).
 */
class DefaultAreaAdminRepository(
    private val api: AreaAdminApi,
    private val auth: AuthRepository,
) : AreaAdminRepository {

    override suspend fun listAreas(page: Int, size: Int, filter: ServiceAreaAdminListFilter): ApiResult<ServiceAreaAdminListPageResponse> =
        apiCall(auth) { bearer -> api.listAreas(bearer, page, size, filter.query, filter.active) }

    override suspend fun areaDetail(areaId: String): ApiResult<ServiceAreaAdminDetailResponse> =
        apiCall(auth) { bearer -> api.areaDetail(bearer, areaId) }

    override suspend fun createArea(name: String): ApiResult<ServiceAreaAdminResponse> =
        apiCall(auth) { bearer -> api.createArea(bearer, CreateServiceAreaRequest(name)) }

    override suspend fun renameArea(areaId: String, expectedVersion: Long, name: String): ApiResult<ServiceAreaAdminResponse> =
        apiCall(auth) { bearer -> api.renameArea(bearer, areaId, RenameServiceAreaRequest(expectedVersion, name)) }

    override suspend fun activateArea(areaId: String, expectedVersion: Long): ApiResult<ServiceAreaAdminResponse> =
        apiCall(auth) { bearer -> api.activateArea(bearer, areaId, ServiceAreaVersionedRequest(expectedVersion)) }

    override suspend fun deactivateArea(areaId: String, expectedVersion: Long): ApiResult<ServiceAreaAdminResponse> =
        apiCall(auth) { bearer -> api.deactivateArea(bearer, areaId, ServiceAreaVersionedRequest(expectedVersion)) }

    override suspend fun listRailwayLines(page: Int, size: Int, filter: RailwayLineAdminListFilter): ApiResult<RailwayLineAdminListPageResponse> =
        apiCall(auth) { bearer ->
            api.listRailwayLines(
                bearer, page, size, filter.query, filter.active, filter.serviceAreaId,
                filter.assignment.takeIf { it != RailwayLineAssignmentFilter.ALL }?.name,
            )
        }

    override suspend fun assignRailwayLine(railwayLineId: String, targetServiceAreaId: String, expectedCurrentServiceAreaId: String?): ApiResult<Unit> =
        apiCall(auth) { bearer -> api.assignRailwayLine(bearer, railwayLineId, AssignRailwayLineRequest(targetServiceAreaId, expectedCurrentServiceAreaId)) }

    override suspend fun unassignRailwayLine(railwayLineId: String, expectedCurrentServiceAreaId: String): ApiResult<Unit> =
        apiCall(auth) { bearer -> api.unassignRailwayLine(bearer, railwayLineId, UnassignRailwayLineRequest(expectedCurrentServiceAreaId)) }
}
