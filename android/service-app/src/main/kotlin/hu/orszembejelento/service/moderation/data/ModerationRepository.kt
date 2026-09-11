package hu.orszembejelento.service.moderation.data

import hu.orszembejelento.service.auth.data.AuthRepository
import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.common.data.apiCall

/** Server-side filters for the deleted-report list (brief §45), mirrors `ReportFilter`'s shape. */
data class DeletedReportFilter(
    val query: String? = null,
    val reason: String? = null,
    val areaId: String? = null,
) {
    val activeFacetCount: Int get() = listOf(reason, areaId).count { it != null }
}

/**
 * The report-moderation surface every screen/ViewModel depends on.
 *
 * An interface, not a class - mirrors [hu.orszembejelento.service.reports.data.ReportWorkflowRepository]'s
 * own reasoning: a test supplies a plain anonymous `object`, production code only ever
 * constructs [DefaultModerationRepository] via [hu.orszembejelento.service.auth.data.NetworkModule].
 */
interface ModerationRepository {
    suspend fun delete(publicReportId: String, expectedVersion: Long, reason: String): ApiResult<Unit>
    suspend fun restore(publicReportId: String, expectedVersion: Long): ApiResult<Unit>
    suspend fun deletedList(page: Int, size: Int, filter: DeletedReportFilter): ApiResult<DeletedReportPageResponse>
    suspend fun deletedDetail(publicReportId: String): ApiResult<DeletedReportDetailResponse>
}

/**
 * Thin wrapper over [ModerationApi]: every method is [apiCall] plus the actual endpoint call,
 * nothing else - no business logic, the backend is authoritative for moderation scope,
 * reason vocabulary and every state transition (brief §56).
 */
class DefaultModerationRepository(
    private val api: ModerationApi,
    private val auth: AuthRepository,
) : ModerationRepository {

    override suspend fun delete(publicReportId: String, expectedVersion: Long, reason: String): ApiResult<Unit> =
        apiCall(auth) { bearer -> api.delete(bearer, publicReportId, ModerationDeleteRequest(expectedVersion, reason)) }

    override suspend fun restore(publicReportId: String, expectedVersion: Long): ApiResult<Unit> =
        apiCall(auth) { bearer -> api.restore(bearer, publicReportId, ModerationRestoreRequest(expectedVersion)) }

    override suspend fun deletedList(page: Int, size: Int, filter: DeletedReportFilter): ApiResult<DeletedReportPageResponse> =
        apiCall(auth) { bearer -> api.deletedList(bearer, page, size, filter.query, filter.reason, filter.areaId) }

    override suspend fun deletedDetail(publicReportId: String): ApiResult<DeletedReportDetailResponse> =
        apiCall(auth) { bearer -> api.deletedDetail(bearer, publicReportId) }
}
