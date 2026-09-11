package hu.orszembejelento.service.reports.data

import hu.orszembejelento.service.common.data.ApiResult
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

// The Public reference/catalogue surface — the ONLY authoritative source of category, event
// type, settlement and railway-line values (ADR 0008 / brief §85-86). Unauthenticated, so
// these calls carry no bearer.

@Serializable
data class CatalogEventTypeResponse(val code: String, val displayName: String)

@Serializable
data class CatalogCategoryResponse(
    val code: String,
    val displayName: String,
    val eventTypes: List<CatalogEventTypeResponse> = emptyList(),
)

@Serializable
data class ReportCatalogResponse(val categories: List<CatalogCategoryResponse> = emptyList())

@Serializable
data class SettlementSearchResultResponse(val id: String, val name: String, val countyName: String? = null)

interface CatalogApi {

    @GET("api/v1/public/report-catalog")
    suspend fun catalog(): Response<ReportCatalogResponse>

    @GET("api/v1/public/reference/settlements")
    suspend fun searchSettlements(@Query("query") query: String): Response<List<SettlementSearchResultResponse>>

    @GET("api/v1/public/reference/settlements/{settlementId}/railway-lines")
    suspend fun railwayLines(@Path("settlementId") settlementId: String): Response<RailwayLinesForSettlementResponse>
}

@Serializable
data class RailwayLineItemResponse(val id: String, val displayName: String)

@Serializable
data class RailwayLinesForSettlementResponse(val coverage: String, val items: List<RailwayLineItemResponse> = emptyList())

/**
 * Read-only reference lookups for the report filter sheet. An interface so a test can supply
 * a plain fake; the production implementation is wired in
 * [hu.orszembejelento.service.auth.data.NetworkModule].
 */
interface CatalogRepository {
    suspend fun catalog(): ApiResult<ReportCatalogResponse>
    suspend fun searchSettlements(query: String): ApiResult<List<SettlementSearchResultResponse>>
}

class DefaultCatalogRepository(private val api: CatalogApi) : CatalogRepository {

    override suspend fun catalog(): ApiResult<ReportCatalogResponse> = safeCall { api.catalog() }

    override suspend fun searchSettlements(query: String): ApiResult<List<SettlementSearchResultResponse>> =
        safeCall { api.searchSettlements(query) }

    private inline fun <T> safeCall(call: () -> Response<T>): ApiResult<T> = try {
        val response = call()
        val body = response.body()
        if (response.isSuccessful && body != null) ApiResult.Success(body)
        else ApiResult.Failure(code = null, httpStatus = response.code())
    } catch (_: Exception) {
        ApiResult.NetworkError
    }
}
