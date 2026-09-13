package hu.orszembejelento.service.analytics.data

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

// ------------------------------------------------------------------------------- response DTOs
//
// Mirror the Phase 11 backend response shapes (AnalyticsDtos.kt) field-for-field, exactly like
// AreaAdminApi.kt already does for the Phase 10 surface. `from`/`to`/`generatedAt`/`localDate`
// stay plain wire strings here (ISO-8601, as Jackson serializes an Instant/LocalDate) - parsed
// to `java.time` types only where the UI actually needs to compute with them (brief §50's
// freshness copy, the trend's date labels), never re-derived from device-local assumptions.

@Serializable
data class AnalyticsPeriodResponse(val code: String, val from: String, val to: String, val zoneId: String)

@Serializable
data class AnalyticsStatusCountsResponse(val new: Int, val inProgress: Int, val archived: Int)

@Serializable
data class AnalyticsTrendPointResponse(val localDate: String, val count: Int)

@Serializable
data class AnalyticsCategoryCountResponse(val code: String, val displayName: String, val count: Int)

@Serializable
data class AnalyticsEventTypeCountResponse(val code: String, val displayName: String, val categoryCode: String, val count: Int)

@Serializable
data class AnalyticsSummaryResponse(
    val period: AnalyticsPeriodResponse,
    val generatedAt: String,
    val totalReports: Int,
    val statusCounts: AnalyticsStatusCountsResponse,
    val trend: List<AnalyticsTrendPointResponse> = emptyList(),
    val categories: List<AnalyticsCategoryCountResponse> = emptyList(),
    val topEventTypes: List<AnalyticsEventTypeCountResponse> = emptyList(),
)

@Serializable
data class AnalyticsAreaOptionResponse(val id: String, val name: String, val active: Boolean)

@Serializable
data class AnalyticsAreaOptionsResponse(val areas: List<AnalyticsAreaOptionResponse> = emptyList(), val canViewUnclassified: Boolean = false)

/**
 * The Phase 11 analytics API. Every method takes the bearer explicitly, exactly like
 * [hu.orszembejelento.service.servicearea.data.AreaAdminApi] - every call is routed through
 * [hu.orszembejelento.service.auth.data.AuthRepository.authorizedCall], never called directly.
 */
interface AnalyticsApi {

    @GET("api/v1/service/analytics/summary")
    suspend fun summary(
        @Header("Authorization") bearer: String,
        @Query("period") period: String? = null,
        @Query("areaId") areaId: String? = null,
        @Query("unclassifiedOnly") unclassifiedOnly: Boolean? = null,
        @Query("categoryCode") categoryCode: String? = null,
    ): Response<AnalyticsSummaryResponse>

    @GET("api/v1/service/analytics/areas")
    suspend fun areas(@Header("Authorization") bearer: String): Response<AnalyticsAreaOptionsResponse>
}
