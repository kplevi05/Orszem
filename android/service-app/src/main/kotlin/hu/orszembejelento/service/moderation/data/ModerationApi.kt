package hu.orszembejelento.service.moderation.data

import hu.orszembejelento.service.reports.data.AssignmentHistoryItemResponse
import hu.orszembejelento.service.reports.data.ReportAreaSummary
import hu.orszembejelento.service.reports.data.ReportCategorySummary
import hu.orszembejelento.service.reports.data.ReportEventTypeSummary
import hu.orszembejelento.service.reports.data.ReportRailwayLineSummary
import hu.orszembejelento.service.reports.data.ReportSettlementSummary
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
// Mirror the Phase 9 backend response shapes (ModerationDtos.kt) field-for-field, exactly
// like ReportWorkflowApi.kt already does for the Phase 7 workflow surface.

@Serializable
data class DeletedReportListItemResponse(
    val publicReportId: String,
    val occurredAt: String,
    val submittedAt: String,
    val trainIdentifier: String? = null,
    val settlement: ReportSettlementSummary,
    val category: ReportCategorySummary,
    val eventType: ReportEventTypeSummary,
    val serviceArea: ReportAreaSummary? = null,
    val reason: String,
    val deletedAt: String,
    val deletedByServiceId: String,
    val statusBeforeDelete: String,
    val restoreTargetStatus: String,
    val workflowVersion: Long,
)

@Serializable
data class DeletedReportPageResponse(
    val items: List<DeletedReportListItemResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
)

@Serializable
data class DeletedReportDetailResponse(
    val publicReportId: String,
    val occurredAt: String,
    val submittedAt: String,
    val trainIdentifier: String? = null,
    val settlement: ReportSettlementSummary,
    val category: ReportCategorySummary,
    val eventType: ReportEventTypeSummary,
    val resolvedRailwayLine: ReportRailwayLineSummary? = null,
    val serviceArea: ReportAreaSummary? = null,
    val workflowVersion: Long,
    val assignmentHistory: List<AssignmentHistoryItemResponse> = emptyList(),
    val reason: String,
    val deletedAt: String,
    val deletedByServiceId: String,
    val statusBeforeDelete: String,
    val restoreTargetStatus: String,
)

// ---------------------------------------------------------------------------------- request DTOs

@Serializable
data class ModerationDeleteRequest(val expectedVersion: Long, val reason: String)

@Serializable
data class ModerationRestoreRequest(val expectedVersion: Long)

/**
 * The Phase 9 report-moderation API.
 *
 * Every method takes the bearer explicitly, exactly like [hu.orszembejelento.service.reports.data.ReportWorkflowApi] -
 * every call is routed through [hu.orszembejelento.service.auth.data.AuthRepository.authorizedCall], never called directly.
 */
interface ModerationApi {

    @POST("api/v1/service/moderation/reports/{publicReportId}/delete")
    suspend fun delete(
        @Header("Authorization") bearer: String,
        @Path("publicReportId") publicReportId: String,
        @Body request: ModerationDeleteRequest,
    ): Response<Unit>

    @POST("api/v1/service/moderation/reports/{publicReportId}/restore")
    suspend fun restore(
        @Header("Authorization") bearer: String,
        @Path("publicReportId") publicReportId: String,
        @Body request: ModerationRestoreRequest,
    ): Response<Unit>

    @GET("api/v1/service/moderation/deleted")
    suspend fun deletedList(
        @Header("Authorization") bearer: String,
        @Query("page") page: Int,
        @Query("size") size: Int,
        @Query("query") query: String? = null,
        @Query("reason") reason: String? = null,
        @Query("areaId") areaId: String? = null,
    ): Response<DeletedReportPageResponse>

    @GET("api/v1/service/moderation/deleted/{publicReportId}")
    suspend fun deletedDetail(
        @Header("Authorization") bearer: String,
        @Path("publicReportId") publicReportId: String,
    ): Response<DeletedReportDetailResponse>
}
