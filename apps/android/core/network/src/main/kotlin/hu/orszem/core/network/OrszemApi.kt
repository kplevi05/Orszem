package hu.orszem.core.network

import hu.orszem.core.network.dto.AnalyticsSummaryDto
import hu.orszem.core.network.dto.CategoryStatisticsResponseDto
import hu.orszem.core.network.dto.EventTypeListResponseDto
import hu.orszem.core.network.dto.EventTypeStatisticsResponseDto
import hu.orszem.core.network.dto.PublicReportCreateRequestDto
import hu.orszem.core.network.dto.PublicReportCreateResponseDto
import hu.orszem.core.network.dto.ReportListResponseDto
import hu.orszem.core.network.dto.ServiceLoginRequestDto
import hu.orszem.core.network.dto.ServiceLoginResponseDto
import hu.orszem.core.network.dto.ServiceReportDetailDto
import hu.orszem.core.network.dto.ServiceUserProfileDto
import hu.orszem.core.network.dto.SettlementStatisticsResponseDto
import hu.orszem.core.network.dto.TrainStatisticsResponseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The canonical Őrszem Demo v1 HTTP contract as a Retrofit interface.
 * Public endpoints are anonymous; every /service endpoint except login needs a
 * Bearer token (added by an OkHttp interceptor in the Service App).
 */
interface OrszemApi {

    @GET("api/v1/public/event-types")
    suspend fun listEventTypes(): Response<EventTypeListResponseDto>

    @POST("api/v1/public/reports")
    suspend fun createReport(@Body body: PublicReportCreateRequestDto): Response<PublicReportCreateResponseDto>

    @POST("api/v1/service/auth/login")
    suspend fun login(@Body body: ServiceLoginRequestDto): Response<ServiceLoginResponseDto>

    @GET("api/v1/service/me")
    suspend fun me(): Response<ServiceUserProfileDto>

    @GET("api/v1/service/reports")
    suspend fun activeReports(
        @Query("status") status: String? = null,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int? = null,
    ): Response<ReportListResponseDto>

    @GET("api/v1/service/archive")
    suspend fun archivedReports(
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int? = null,
    ): Response<ReportListResponseDto>

    @GET("api/v1/service/reports/{reportId}")
    suspend fun reportDetail(@Path("reportId") reportId: String): Response<ServiceReportDetailDto>

    @POST("api/v1/service/reports/{reportId}/accept")
    suspend fun acceptReport(@Path("reportId") reportId: String): Response<ServiceReportDetailDto>

    @POST("api/v1/service/reports/{reportId}/archive")
    suspend fun archiveReport(@Path("reportId") reportId: String): Response<ServiceReportDetailDto>

    @GET("api/v1/service/analytics/summary")
    suspend fun analyticsSummary(): Response<AnalyticsSummaryDto>

    @GET("api/v1/service/analytics/event-types")
    suspend fun analyticsEventTypes(): Response<EventTypeStatisticsResponseDto>

    @GET("api/v1/service/analytics/categories")
    suspend fun analyticsCategories(): Response<CategoryStatisticsResponseDto>

    @GET("api/v1/service/analytics/settlements")
    suspend fun analyticsSettlements(): Response<SettlementStatisticsResponseDto>

    @GET("api/v1/service/analytics/trains")
    suspend fun analyticsTrains(): Response<TrainStatisticsResponseDto>
}
