package hu.orszembejelento.service.servicearea

import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.servicearea.data.AreaAdminRepository
import hu.orszembejelento.service.servicearea.data.RailwayLineAdminListFilter
import hu.orszembejelento.service.servicearea.data.RailwayLineAdminListPageResponse
import hu.orszembejelento.service.servicearea.data.ServiceAreaAdminDetailResponse
import hu.orszembejelento.service.servicearea.data.ServiceAreaAdminListFilter
import hu.orszembejelento.service.servicearea.data.ServiceAreaAdminListPageResponse
import hu.orszembejelento.service.servicearea.data.ServiceAreaAdminResponse

/** A fully configurable test double, mirroring the fakes `ReportDetailViewModelTest`/`DeletedReportDetailViewModelTest` use for their own repositories. */
class FakeAreaAdminRepository(
    var listAreasResult: ApiResult<ServiceAreaAdminListPageResponse> = ApiResult.Success(
        ServiceAreaAdminListPageResponse(emptyList(), 0, 50, 0, 0),
    ),
    var areaDetailResult: ApiResult<ServiceAreaAdminDetailResponse>? = null,
    var createAreaResult: ApiResult<ServiceAreaAdminResponse>? = null,
    var renameAreaResult: ApiResult<ServiceAreaAdminResponse>? = null,
    var activateAreaResult: ApiResult<ServiceAreaAdminResponse>? = null,
    var deactivateAreaResult: ApiResult<ServiceAreaAdminResponse>? = null,
    var listRailwayLinesResult: ApiResult<RailwayLineAdminListPageResponse> = ApiResult.Success(
        RailwayLineAdminListPageResponse(emptyList(), 0, 50, 0, 0),
    ),
    var assignRailwayLineResult: ApiResult<Unit> = ApiResult.Success(Unit),
    var unassignRailwayLineResult: ApiResult<Unit> = ApiResult.Success(Unit),
) : AreaAdminRepository {

    var listAreasCalls = 0
    var areaDetailCalls = 0
    var createAreaCalls = 0
    var lastCreateName: String? = null
    var renameCalls = 0
    var activateCalls = 0
    var deactivateCalls = 0
    var assignCalls = 0
    var unassignCalls = 0
    val seenFilters = mutableListOf<ServiceAreaAdminListFilter>()
    val seenLineFilters = mutableListOf<RailwayLineAdminListFilter>()
    var lastAssignArgs: Triple<String, String, String?>? = null
    var lastUnassignArgs: Pair<String, String>? = null
    var lastRenameArgs: Pair<Long, String>? = null
    var lastActivateVersion: Long? = null
    var lastDeactivateVersion: Long? = null

    override suspend fun listAreas(page: Int, size: Int, filter: ServiceAreaAdminListFilter): ApiResult<ServiceAreaAdminListPageResponse> {
        listAreasCalls++
        seenFilters += filter
        return listAreasResult
    }

    override suspend fun areaDetail(areaId: String): ApiResult<ServiceAreaAdminDetailResponse> {
        areaDetailCalls++
        return areaDetailResult ?: error("areaDetailResult not configured")
    }

    override suspend fun createArea(name: String): ApiResult<ServiceAreaAdminResponse> {
        createAreaCalls++
        lastCreateName = name
        return createAreaResult ?: error("createAreaResult not configured")
    }

    override suspend fun renameArea(areaId: String, expectedVersion: Long, name: String): ApiResult<ServiceAreaAdminResponse> {
        renameCalls++
        lastRenameArgs = expectedVersion to name
        return renameAreaResult ?: error("renameAreaResult not configured")
    }

    override suspend fun activateArea(areaId: String, expectedVersion: Long): ApiResult<ServiceAreaAdminResponse> {
        activateCalls++
        lastActivateVersion = expectedVersion
        return activateAreaResult ?: error("activateAreaResult not configured")
    }

    override suspend fun deactivateArea(areaId: String, expectedVersion: Long): ApiResult<ServiceAreaAdminResponse> {
        deactivateCalls++
        lastDeactivateVersion = expectedVersion
        return deactivateAreaResult ?: error("deactivateAreaResult not configured")
    }

    override suspend fun listRailwayLines(page: Int, size: Int, filter: RailwayLineAdminListFilter): ApiResult<RailwayLineAdminListPageResponse> {
        seenLineFilters += filter
        return listRailwayLinesResult
    }

    override suspend fun assignRailwayLine(railwayLineId: String, targetServiceAreaId: String, expectedCurrentServiceAreaId: String?): ApiResult<Unit> {
        assignCalls++
        lastAssignArgs = Triple(railwayLineId, targetServiceAreaId, expectedCurrentServiceAreaId)
        return assignRailwayLineResult
    }

    override suspend fun unassignRailwayLine(railwayLineId: String, expectedCurrentServiceAreaId: String): ApiResult<Unit> {
        unassignCalls++
        lastUnassignArgs = railwayLineId to expectedCurrentServiceAreaId
        return unassignRailwayLineResult
    }
}
