package hu.orszembejelento.service.reports.data

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

// ------------------------------------------------------------------------------- response DTOs
//
// Mirror the Phase 7 backend response shapes (ReportWorkflowDtos.kt) field-for-field.
// Timestamps stay `String` (ISO-8601, exactly as the server sends them) - formatting for
// display is a UI concern, not a networking one, matching how AuthApi already treats
// `accessTokenExpiresAt`/`sessionExpiresAt`.

@Serializable
data class ReportSettlementSummary(val id: String, val name: String, val countyName: String? = null)

@Serializable
data class ReportCategorySummary(val code: String, val displayName: String)

@Serializable
data class ReportEventTypeSummary(val code: String, val displayName: String)

@Serializable
data class ReportAreaSummary(val id: String, val name: String)

@Serializable
data class ReportAssigneeSummary(val serviceId: String)

@Serializable
data class ReportRailwayLineSummary(val id: String, val displayName: String)

@Serializable
data class ReportListItemResponse(
    val publicReportId: String,
    val occurredAt: String,
    val submittedAt: String,
    val trainIdentifier: String? = null,
    val settlement: ReportSettlementSummary,
    val category: ReportCategorySummary,
    val eventType: ReportEventTypeSummary,
    val routingClassification: String,
    val serviceArea: ReportAreaSummary? = null,
    val assignee: ReportAssigneeSummary? = null,
    val workflowVersion: Long,
    val ageBucket: String? = null,
    val archivedAt: String? = null,
)

@Serializable
data class ReportQueuePageResponse(
    val items: List<ReportListItemResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
)

@Serializable
data class AssignmentHistoryItemResponse(
    val assigneeServiceId: String,
    val assignedByServiceId: String,
    val assignedAt: String,
    val endedAt: String? = null,
    val endedByServiceId: String? = null,
    val endReason: String? = null,
)

@Serializable
data class ReportDetailResponse(
    val publicReportId: String,
    val occurredAt: String,
    val submittedAt: String,
    val trainIdentifier: String? = null,
    val settlement: ReportSettlementSummary,
    val category: ReportCategorySummary,
    val eventType: ReportEventTypeSummary,
    val status: String,
    val workflowVersion: Long,
    val routingClassification: String,
    val routingReason: String? = null,
    val resolvedRailwayLine: ReportRailwayLineSummary? = null,
    val serviceArea: ReportAreaSummary? = null,
    val assignee: ReportAssigneeSummary? = null,
    val archivedAt: String? = null,
    val assignmentHistory: List<AssignmentHistoryItemResponse> = emptyList(),
)

// ---------------------------------------------------------------------------------- request DTOs

@Serializable
data class WorkflowMutationRequest(val expectedVersion: Long)

@Serializable
data class ReassignRequest(val expectedVersion: Long, val targetServiceId: String)

/**
 * The Phase 7 service report-workflow API.
 *
 * Every method takes the bearer explicitly, exactly like [hu.orszembejelento.service.auth.data.AuthApi] -
 * see that interface's own KDoc for why. Every call is routed through
 * [hu.orszembejelento.service.auth.data.AuthRepository.authorizedCall], never called directly.
 */
interface ReportWorkflowApi {

    @GET("api/v1/service/reports/new")
    suspend fun newQueue(
        @Header("Authorization") bearer: String,
        @Query("page") page: Int,
        @Query("size") size: Int,
        @Query("query") query: String? = null,
        @Query("categoryCode") categoryCode: String? = null,
        @Query("eventTypeCode") eventTypeCode: String? = null,
        @Query("settlementId") settlementId: String? = null,
        @Query("areaId") areaId: String? = null,
    ): Response<ReportQueuePageResponse>

    @GET("api/v1/service/reports/in-progress")
    suspend fun inProgressQueue(
        @Header("Authorization") bearer: String,
        @Query("page") page: Int,
        @Query("size") size: Int,
        @Query("query") query: String? = null,
        @Query("categoryCode") categoryCode: String? = null,
        @Query("eventTypeCode") eventTypeCode: String? = null,
        @Query("settlementId") settlementId: String? = null,
        @Query("areaId") areaId: String? = null,
        @Query("assigneeServiceId") assigneeServiceId: String? = null,
    ): Response<ReportQueuePageResponse>

    @GET("api/v1/service/reports/archive")
    suspend fun archiveQueue(
        @Header("Authorization") bearer: String,
        @Query("page") page: Int,
        @Query("size") size: Int,
        @Query("query") query: String? = null,
        @Query("categoryCode") categoryCode: String? = null,
        @Query("eventTypeCode") eventTypeCode: String? = null,
        @Query("settlementId") settlementId: String? = null,
        @Query("areaId") areaId: String? = null,
    ): Response<ReportQueuePageResponse>

    @GET("api/v1/service/reports/{publicReportId}")
    suspend fun detail(
        @Header("Authorization") bearer: String,
        @Path("publicReportId") publicReportId: String,
    ): Response<ReportDetailResponse>

    @POST("api/v1/service/reports/{publicReportId}/claim")
    suspend fun claim(
        @Header("Authorization") bearer: String,
        @Path("publicReportId") publicReportId: String,
        @Body request: WorkflowMutationRequest,
    ): Response<ReportDetailResponse>

    @POST("api/v1/service/reports/{publicReportId}/return")
    suspend fun returnToNew(
        @Header("Authorization") bearer: String,
        @Path("publicReportId") publicReportId: String,
        @Body request: WorkflowMutationRequest,
    ): Response<ReportDetailResponse>

    @POST("api/v1/service/reports/{publicReportId}/close")
    suspend fun close(
        @Header("Authorization") bearer: String,
        @Path("publicReportId") publicReportId: String,
        @Body request: WorkflowMutationRequest,
    ): Response<ReportDetailResponse>

    @POST("api/v1/service/reports/{publicReportId}/reassign")
    suspend fun reassign(
        @Header("Authorization") bearer: String,
        @Path("publicReportId") publicReportId: String,
        @Body request: ReassignRequest,
    ): Response<ReportDetailResponse>
}
