package hu.orszembejelento.service.servicearea.data

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
// Mirror the Phase 10 backend response shapes (AreaAdminDtos.kt) field-for-field, exactly like
// ModerationApi.kt already does for the Phase 9 moderation surface.

@Serializable
data class ServiceAreaAdminResponse(
    val id: String,
    val name: String,
    val active: Boolean,
    val adminVersion: Long,
)

@Serializable
data class ServiceAreaAdminListItemResponse(
    val id: String,
    val name: String,
    val active: Boolean,
    val adminVersion: Long,
    val mappedRailwayLineCount: Int,
    val openOperationalReportCount: Int,
)

@Serializable
data class ServiceAreaAdminListPageResponse(
    val items: List<ServiceAreaAdminListItemResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
)

@Serializable
data class MappedRailwayLineResponse(
    val id: String,
    val lineCode: String,
    val displayName: String,
    val active: Boolean,
)

@Serializable
data class ServiceAreaAdminDetailResponse(
    val id: String,
    val name: String,
    val active: Boolean,
    val adminVersion: Long,
    val mappedRailwayLines: List<MappedRailwayLineResponse> = emptyList(),
    val mappedRailwayLineCount: Int,
    val openOperationalReportCount: Int,
)

@Serializable
data class RailwayLineAdminListItemResponse(
    val id: String,
    val lineCode: String,
    val displayName: String,
    val active: Boolean,
    val currentServiceAreaId: String? = null,
    val currentServiceAreaName: String? = null,
)

@Serializable
data class RailwayLineAdminListPageResponse(
    val items: List<RailwayLineAdminListItemResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
)

// ---------------------------------------------------------------------------------- request DTOs

@Serializable
data class CreateServiceAreaRequest(val name: String)

@Serializable
data class RenameServiceAreaRequest(val expectedVersion: Long, val name: String)

@Serializable
data class ServiceAreaVersionedRequest(val expectedVersion: Long)

@Serializable
data class AssignRailwayLineRequest(val targetServiceAreaId: String, val expectedCurrentServiceAreaId: String? = null)

@Serializable
data class UnassignRailwayLineRequest(val expectedCurrentServiceAreaId: String)

/**
 * The Phase 10 ServiceArea-administration API.
 *
 * Every method takes the bearer explicitly, exactly like [hu.orszembejelento.service.moderation.data.ModerationApi] -
 * every call is routed through [hu.orszembejelento.service.auth.data.AuthRepository.authorizedCall], never called directly.
 */
interface AreaAdminApi {

    @GET("api/v1/service/service-area-admin/areas")
    suspend fun listAreas(
        @Header("Authorization") bearer: String,
        @Query("page") page: Int,
        @Query("size") size: Int,
        @Query("query") query: String? = null,
        @Query("active") active: Boolean? = null,
    ): Response<ServiceAreaAdminListPageResponse>

    @GET("api/v1/service/service-area-admin/areas/{areaId}")
    suspend fun areaDetail(
        @Header("Authorization") bearer: String,
        @Path("areaId") areaId: String,
    ): Response<ServiceAreaAdminDetailResponse>

    @POST("api/v1/service/service-area-admin/areas")
    suspend fun createArea(
        @Header("Authorization") bearer: String,
        @Body request: CreateServiceAreaRequest,
    ): Response<ServiceAreaAdminResponse>

    @POST("api/v1/service/service-area-admin/areas/{areaId}/rename")
    suspend fun renameArea(
        @Header("Authorization") bearer: String,
        @Path("areaId") areaId: String,
        @Body request: RenameServiceAreaRequest,
    ): Response<ServiceAreaAdminResponse>

    @POST("api/v1/service/service-area-admin/areas/{areaId}/activate")
    suspend fun activateArea(
        @Header("Authorization") bearer: String,
        @Path("areaId") areaId: String,
        @Body request: ServiceAreaVersionedRequest,
    ): Response<ServiceAreaAdminResponse>

    @POST("api/v1/service/service-area-admin/areas/{areaId}/deactivate")
    suspend fun deactivateArea(
        @Header("Authorization") bearer: String,
        @Path("areaId") areaId: String,
        @Body request: ServiceAreaVersionedRequest,
    ): Response<ServiceAreaAdminResponse>

    @GET("api/v1/service/service-area-admin/railway-lines")
    suspend fun listRailwayLines(
        @Header("Authorization") bearer: String,
        @Query("page") page: Int,
        @Query("size") size: Int,
        @Query("query") query: String? = null,
        @Query("active") active: Boolean? = null,
        @Query("serviceAreaId") serviceAreaId: String? = null,
        @Query("assignment") assignment: String? = null,
    ): Response<RailwayLineAdminListPageResponse>

    @POST("api/v1/service/service-area-admin/railway-lines/{railwayLineId}/assign")
    suspend fun assignRailwayLine(
        @Header("Authorization") bearer: String,
        @Path("railwayLineId") railwayLineId: String,
        @Body request: AssignRailwayLineRequest,
    ): Response<Unit>

    @POST("api/v1/service/service-area-admin/railway-lines/{railwayLineId}/unassign")
    suspend fun unassignRailwayLine(
        @Header("Authorization") bearer: String,
        @Path("railwayLineId") railwayLineId: String,
        @Body request: UnassignRailwayLineRequest,
    ): Response<Unit>
}
