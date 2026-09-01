package hu.orszem.auth.api

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

/** Matches OpenAPI `ServiceLoginRequest`. */
data class ServiceLoginRequest(
    @field:NotBlank @field:Size(max = 100) val username: String?,
    @field:NotBlank @field:Size(max = 256) val password: String?,
)

/** Matches OpenAPI `ServiceLoginResponse`. */
data class ServiceLoginResponse(
    val accessToken: String,
    val tokenType: String,
    val expiresAt: Instant,
)

/** Matches OpenAPI `ServiceUserProfile`. */
data class ServiceUserProfileResponse(
    val id: String,
    val username: String,
    val displayName: String,
    val role: String,
    val capabilities: List<String>,
)
