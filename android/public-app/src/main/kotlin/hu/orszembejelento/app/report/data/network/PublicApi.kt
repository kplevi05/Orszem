package hu.orszembejelento.app.report.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** The exact request body `PublicReportController.SubmitReportRequest` accepts - no `categoryCode`, no free text, no GPS. */
@Serializable
data class SubmitReportRequestBody(
    val clientSubmissionId: String,
    /** ISO-8601 instant text, e.g. `2026-01-01T12:00:00Z` - parsed/formatted via `java.time.Instant`. */
    val occurredAt: String,
    val trainIdentifier: String?,
    val settlementId: String,
    val railwayLineId: String?,
    val eventTypeCode: String,
)

@Serializable
data class SubmitReportResponseBody(
    val reportId: String,
    val submittedAt: String,
    val initialStatus: String,
)

@Serializable
data class SettlementSummaryBody(val id: String, val name: String)

@Serializable
data class CategorySummaryBody(val code: String, val displayName: String)

@Serializable
data class EventTypeSummaryBody(val code: String, val displayName: String)

@Serializable
data class PublicReportResponseBody(
    val reportId: String,
    val occurredAt: String,
    val submittedAt: String,
    val trainIdentifier: String?,
    val settlement: SettlementSummaryBody,
    val category: CategorySummaryBody,
    val eventType: EventTypeSummaryBody,
    val status: String,
)

@Serializable
data class ReportCatalogResponseBody(val categories: List<ReportCatalogCategoryBody>)

@Serializable
data class ReportCatalogCategoryBody(
    val code: String,
    val displayName: String,
    val eventTypes: List<EventTypeSummaryBody>,
)

@Serializable
data class SettlementSearchResultBody(
    val id: String,
    val kshCode: String,
    val name: String,
    val countyName: String?,
)

@Serializable
data class RailwayLineItemBody(val id: String, val code: String, val displayName: String)

@Serializable
data class RailwayLinesForSettlementResponseBody(val coverage: String, val items: List<RailwayLineItemBody>)

/** The server's error shape. Clients switch on [code]; [message] is human-facing and never parsed. */
@Serializable
data class ApiErrorBody(
    val code: String = "",
    val message: String = "",
    @SerialName("correlationId") val correlationId: String = "",
)

/** Stable error codes this client actually branches on. */
object ApiErrorCode {
    const val INVALID_REPORT_ACCESS_CREDENTIAL = "INVALID_REPORT_ACCESS_CREDENTIAL"
    const val INVALID_EVENT_TYPE = "INVALID_EVENT_TYPE"
    const val INVALID_SETTLEMENT = "INVALID_SETTLEMENT"
    const val INVALID_RAILWAY_LINE = "INVALID_RAILWAY_LINE"
    const val VALIDATION_ERROR = "VALIDATION_ERROR"
    const val IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED"
    const val REPORT_NOT_FOUND = "REPORT_NOT_FOUND"
    const val REFERENCE_DATASET_UNAVAILABLE = "REFERENCE_DATASET_UNAVAILABLE"
}

private const val REPORT_ACCESS_HEADER = "X-Orszem-Report-Access"

/**
 * The Phase 4 Public API surface this client uses.
 *
 * Every method returns [Response] rather than the body directly, so the caller can branch
 * on the status and the stable error code instead of on exceptions - same convention as the
 * Service app's `AuthApi`. The report-access header is attached only on the two calls that
 * need it (§25 - never a global interceptor).
 */
interface PublicApi {

    @GET("api/v1/public/report-catalog")
    suspend fun reportCatalog(): Response<ReportCatalogResponseBody>

    @GET("api/v1/public/reference/settlements")
    suspend fun searchSettlements(@Query("query") query: String): Response<List<SettlementSearchResultBody>>

    @GET("api/v1/public/reference/settlements/{settlementId}/railway-lines")
    suspend fun railwayLinesOfSettlement(@Path("settlementId") settlementId: String): Response<RailwayLinesForSettlementResponseBody>

    @POST("api/v1/public/reports")
    suspend fun submitReport(
        @Body body: SubmitReportRequestBody,
        @Header(REPORT_ACCESS_HEADER) accessCredential: String,
    ): Response<SubmitReportResponseBody>

    @GET("api/v1/public/reports/{publicReportId}")
    suspend fun getReport(
        @Path("publicReportId") publicReportId: String,
        @Header(REPORT_ACCESS_HEADER) accessCredential: String,
    ): Response<PublicReportResponseBody>
}
