package hu.orszembejelento.service.usermanagement.data

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

// ------------------------------------------------------------------------------- response DTOs
// Mirror the Phase 6 backend response shapes (UserManagementDtos.kt) field-for-field.

@Serializable
data class ManagedUserAreaResponse(val id: String, val name: String, val status: String)

@Serializable
data class ManagedUserResponse(
    val serviceId: String,
    val role: String,
    val status: String,
    val mustChangePassword: Boolean,
    val globalAreaAccess: Boolean,
    val areas: List<ManagedUserAreaResponse> = emptyList(),
    val canManage: Boolean,
)

@Serializable
data class ManagedUserPageResponse(
    val items: List<ManagedUserResponse>,
    val page: Int,
    val size: Int,
    val totalCount: Int,
)

@Serializable
data class AssignableAreaResponse(val id: String, val name: String)

@Serializable
data class CreateUserRequest(val role: String, val areaIds: List<String> = emptyList(), val globalAreaAccess: Boolean = false)

@Serializable
data class CreateUserResponse(val serviceId: String, val role: String, val temporaryCredential: String, val mustChangePassword: Boolean)

@Serializable
data class PasswordResetResponse(val serviceId: String, val temporaryCredential: String)

@Serializable
data class ChangeRoleRequest(val role: String)

/**
 * The Phase 6 service user-management API. MODERATOR/SUPER_ADMIN only - the backend rejects
 * a SERVICE_USER caller before any use case runs, so this interface is never used from a
 * SERVICE_USER screen (brief §47).
 */
interface UserManagementApi {

    @GET("api/v1/service/user-management/users")
    suspend fun list(
        @Header("Authorization") bearer: String,
        @Query("page") page: Int,
        @Query("size") size: Int,
        @Query("role") role: String? = null,
        @Query("status") status: String? = null,
        @Query("query") query: String? = null,
        @Query("areaId") areaId: String? = null,
    ): Response<ManagedUserPageResponse>

    @GET("api/v1/service/user-management/users/{serviceId}")
    suspend fun detail(
        @Header("Authorization") bearer: String,
        @Path("serviceId") serviceId: String,
    ): Response<ManagedUserResponse>

    @GET("api/v1/service/user-management/areas")
    suspend fun assignableAreas(@Header("Authorization") bearer: String): Response<List<AssignableAreaResponse>>

    @POST("api/v1/service/user-management/users")
    suspend fun create(
        @Header("Authorization") bearer: String,
        @Body request: CreateUserRequest,
    ): Response<CreateUserResponse>

    @POST("api/v1/service/user-management/users/{serviceId}/password-reset")
    suspend fun resetPassword(
        @Header("Authorization") bearer: String,
        @Path("serviceId") serviceId: String,
    ): Response<PasswordResetResponse>

    @POST("api/v1/service/user-management/users/{serviceId}/deactivate")
    suspend fun deactivate(
        @Header("Authorization") bearer: String,
        @Path("serviceId") serviceId: String,
    ): Response<ManagedUserResponse>

    @POST("api/v1/service/user-management/users/{serviceId}/reactivate")
    suspend fun reactivate(
        @Header("Authorization") bearer: String,
        @Path("serviceId") serviceId: String,
    ): Response<ManagedUserResponse>

    @POST("api/v1/service/user-management/users/{serviceId}/role")
    suspend fun changeRole(
        @Header("Authorization") bearer: String,
        @Path("serviceId") serviceId: String,
        @Body request: ChangeRoleRequest,
    ): Response<ManagedUserResponse>

    @POST("api/v1/service/user-management/users/{serviceId}/global-access/grant")
    suspend fun grantGlobalAccess(
        @Header("Authorization") bearer: String,
        @Path("serviceId") serviceId: String,
    ): Response<ManagedUserResponse>

    @POST("api/v1/service/user-management/users/{serviceId}/global-access/revoke")
    suspend fun revokeGlobalAccess(
        @Header("Authorization") bearer: String,
        @Path("serviceId") serviceId: String,
    ): Response<ManagedUserResponse>

    @POST("api/v1/service/user-management/users/{serviceId}/areas/{areaId}/grant")
    suspend fun grantArea(
        @Header("Authorization") bearer: String,
        @Path("serviceId") serviceId: String,
        @Path("areaId") areaId: String,
    ): Response<ManagedUserResponse>

    @POST("api/v1/service/user-management/users/{serviceId}/areas/{areaId}/revoke")
    suspend fun revokeArea(
        @Header("Authorization") bearer: String,
        @Path("serviceId") serviceId: String,
        @Path("areaId") areaId: String,
    ): Response<ManagedUserResponse>
}
