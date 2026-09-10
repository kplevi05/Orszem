package hu.orszembejelento.service.reports.data

import hu.orszembejelento.service.auth.data.AuthRepository
import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.common.data.apiCall

/** One page of report-workflow filters (brief §26/§42), shared by all three queues. */
data class ReportFilter(
    val query: String? = null,
    val categoryCode: String? = null,
    val eventTypeCode: String? = null,
    val settlementId: String? = null,
    val areaId: String? = null,
    val assigneeServiceId: String? = null,
)

/**
 * The report-workflow surface every screen/ViewModel depends on.
 *
 * An interface, not a class - so a test can implement it directly (a plain anonymous
 * `object`, no constructor arguments, no Retrofit/AuthRepository double needed at all) rather
 * than mocking or subclassing [DefaultReportWorkflowRepository]. Production code only ever
 * constructs the latter, via [hu.orszembejelento.service.auth.data.NetworkModule].
 */
interface ReportWorkflowRepository {
    suspend fun newQueue(page: Int, size: Int, filter: ReportFilter): ApiResult<ReportQueuePageResponse>
    suspend fun inProgressQueue(page: Int, size: Int, filter: ReportFilter): ApiResult<ReportQueuePageResponse>
    suspend fun archiveQueue(page: Int, size: Int, filter: ReportFilter): ApiResult<ReportQueuePageResponse>
    suspend fun detail(publicReportId: String): ApiResult<ReportDetailResponse>
    suspend fun claim(publicReportId: String, expectedVersion: Long): ApiResult<ReportDetailResponse>
    suspend fun returnToNew(publicReportId: String, expectedVersion: Long): ApiResult<ReportDetailResponse>
    suspend fun close(publicReportId: String, expectedVersion: Long): ApiResult<ReportDetailResponse>
    suspend fun reassign(publicReportId: String, expectedVersion: Long, targetServiceId: String): ApiResult<ReportDetailResponse>
}

/**
 * Thin wrapper over [ReportWorkflowApi]: every method is [apiCall] plus the actual endpoint
 * call, nothing else. No business logic - the backend is authoritative for scope, ordering,
 * ageBucket, and every workflow transition (brief §16/§74).
 */
class DefaultReportWorkflowRepository(
    private val api: ReportWorkflowApi,
    private val auth: AuthRepository,
) : ReportWorkflowRepository {

    override suspend fun newQueue(page: Int, size: Int, filter: ReportFilter): ApiResult<ReportQueuePageResponse> =
        apiCall(auth) { bearer ->
            api.newQueue(bearer, page, size, filter.query, filter.categoryCode, filter.eventTypeCode, filter.settlementId, filter.areaId)
        }

    override suspend fun inProgressQueue(page: Int, size: Int, filter: ReportFilter): ApiResult<ReportQueuePageResponse> =
        apiCall(auth) { bearer ->
            api.inProgressQueue(
                bearer, page, size, filter.query, filter.categoryCode, filter.eventTypeCode,
                filter.settlementId, filter.areaId, filter.assigneeServiceId,
            )
        }

    override suspend fun archiveQueue(page: Int, size: Int, filter: ReportFilter): ApiResult<ReportQueuePageResponse> =
        apiCall(auth) { bearer ->
            api.archiveQueue(bearer, page, size, filter.query, filter.categoryCode, filter.eventTypeCode, filter.settlementId, filter.areaId)
        }

    override suspend fun detail(publicReportId: String): ApiResult<ReportDetailResponse> =
        apiCall(auth) { bearer -> api.detail(bearer, publicReportId) }

    override suspend fun claim(publicReportId: String, expectedVersion: Long): ApiResult<ReportDetailResponse> =
        apiCall(auth) { bearer -> api.claim(bearer, publicReportId, WorkflowMutationRequest(expectedVersion)) }

    override suspend fun returnToNew(publicReportId: String, expectedVersion: Long): ApiResult<ReportDetailResponse> =
        apiCall(auth) { bearer -> api.returnToNew(bearer, publicReportId, WorkflowMutationRequest(expectedVersion)) }

    override suspend fun close(publicReportId: String, expectedVersion: Long): ApiResult<ReportDetailResponse> =
        apiCall(auth) { bearer -> api.close(bearer, publicReportId, WorkflowMutationRequest(expectedVersion)) }

    override suspend fun reassign(publicReportId: String, expectedVersion: Long, targetServiceId: String): ApiResult<ReportDetailResponse> =
        apiCall(auth) { bearer -> api.reassign(bearer, publicReportId, ReassignRequest(expectedVersion, targetServiceId)) }
}
