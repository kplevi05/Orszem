package hu.orszembejelento.service.auth.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

@Serializable
data class LoginRequest(val serviceId: String, val password: String)

@Serializable
data class CompletePasswordChangeRequest(
    val serviceId: String,
    val temporaryPassword: String,
    val newPassword: String,
)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

@Serializable
data class TokenResponse(
    val tokenType: String,
    val accessToken: String,
    val accessTokenExpiresAt: String,
    val refreshToken: String,
    val sessionExpiresAt: String,
)

@Serializable
data class MeResponse(
    val serviceId: String,
    val role: String,
    // Phase 8: the caller's own service-area scope, for the local "active work view".
    // Defaulted so an older server (or a test double) that omits them still deserializes.
    val globalAreaAccess: Boolean = false,
    val areas: List<MeAreaResponse> = emptyList(),
)

@Serializable
data class MeAreaResponse(val id: String, val name: String, val status: String)

/**
 * The server's error shape. Clients switch on [code]; [message] is human-facing and may be
 * reworded at any time, so it is never parsed.
 */
@Serializable
data class ApiErrorBody(
    val code: String = "",
    val message: String = "",
    @SerialName("correlationId") val correlationId: String = "",
)

/**
 * The Phase 2 Service API.
 *
 * Every method returns [Response] rather than the body directly, so the caller can branch on
 * the status and the stable error code instead of on exceptions.
 *
 * The access token is passed explicitly per call rather than attached by an interceptor.
 * That is deliberate: the token lives only in memory and the refresh flow needs precise
 * control over which token each request carries, which a shared interceptor holding mutable
 * state would obscure.
 */
interface AuthApi {

    @POST("api/v1/service/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<TokenResponse>

    @POST("api/v1/service/auth/complete-password-change")
    suspend fun completePasswordChange(
        @Body request: CompletePasswordChangeRequest,
    ): Response<TokenResponse>

    @POST("api/v1/service/auth/refresh")
    suspend fun refresh(@Body request: RefreshRequest): Response<TokenResponse>

    @POST("api/v1/service/auth/logout")
    suspend fun logout(@Header("Authorization") bearer: String): Response<Unit>

    @POST("api/v1/service/auth/logout-all")
    suspend fun logoutAll(@Header("Authorization") bearer: String): Response<Unit>

    @GET("api/v1/service/account/me")
    suspend fun me(@Header("Authorization") bearer: String): Response<MeResponse>

    @POST("api/v1/service/account/change-password")
    suspend fun changePassword(
        @Header("Authorization") bearer: String,
        @Body request: ChangePasswordRequest,
    ): Response<TokenResponse>
}

/** Stable error codes the client actually branches on. */
object ApiErrorCode {
    const val INVALID_CREDENTIALS = "INVALID_CREDENTIALS"
    const val PASSWORD_CHANGE_REQUIRED = "PASSWORD_CHANGE_REQUIRED"
    const val PASSWORD_POLICY_VIOLATION = "PASSWORD_POLICY_VIOLATION"
    const val SESSION_INVALID = "SESSION_INVALID"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val VALIDATION_ERROR = "VALIDATION_ERROR"
}
