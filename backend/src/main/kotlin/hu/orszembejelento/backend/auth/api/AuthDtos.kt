package hu.orszembejelento.backend.auth.api

import hu.orszembejelento.backend.auth.application.IssuedCredentials
import hu.orszembejelento.backend.identity.domain.PasswordPolicy
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

/**
 * Request and response shapes for the Phase 2 auth API.
 *
 * Password fields are bounded by [PasswordPolicy.MAX_LENGTH_CODE_POINTS] at the edge, so a
 * multi-megabyte body is rejected before it ever reaches Argon2 — feeding unbounded input
 * to a memory-hard hash would be a cheap denial of service. The lower bound is deliberately
 * *not* enforced here: a too-short password must produce a password-policy error from the
 * domain, not a generic validation error, so the client can react correctly.
 */
data class LoginRequest(
    @field:NotBlank val serviceId: String? = null,
    @field:NotBlank @field:Size(max = MAX_PASSWORD_INPUT) val password: String = "",
)

data class CompletePasswordChangeRequest(
    @field:NotBlank val serviceId: String? = null,
    @field:NotBlank @field:Size(max = MAX_PASSWORD_INPUT) val temporaryPassword: String = "",
    @field:NotBlank @field:Size(max = MAX_PASSWORD_INPUT) val newPassword: String = "",
)

data class RefreshRequest(
    @field:NotBlank @field:Size(max = 512) val refreshToken: String = "",
)

data class ChangePasswordRequest(
    @field:NotBlank @field:Size(max = MAX_PASSWORD_INPUT) val currentPassword: String = "",
    @field:NotBlank @field:Size(max = MAX_PASSWORD_INPUT) val newPassword: String = "",
)

/** Credentials returned to the client. Sent with `Cache-Control: no-store`. */
data class TokenResponse(
    val tokenType: String,
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    val refreshToken: String,
    val sessionExpiresAt: Instant,
) {
    companion object {
        fun from(credentials: IssuedCredentials) = TokenResponse(
            tokenType = "Bearer",
            accessToken = credentials.accessToken,
            accessTokenExpiresAt = credentials.accessTokenExpiresAt,
            refreshToken = credentials.refreshToken,
            sessionExpiresAt = credentials.sessionExpiresAt,
        )
    }
}

/**
 * The current user's own account.
 *
 * `serviceId` + `role` since Phase 2. Phase 8 added `globalAreaAccess` and `areas` — the
 * caller's **own** service-area scope, needed to drive the Service app's local "active work
 * view" selector without giving a SERVICE_USER any user-management endpoint. This is strictly
 * self-account information: no other user, no `canManage`, no management permission, no audit
 * data. Fields are still added only when a feature needs them, not in anticipation.
 */
data class MeResponse(
    val serviceId: String,
    val role: String,
    val globalAreaAccess: Boolean,
    val areas: List<MeServiceAreaResponse>,
)

/** One of the caller's own assigned service areas, with its current activation status. */
data class MeServiceAreaResponse(val id: String, val name: String, val status: String)

/**
 * Generous enough for any real passphrase while still bounding the work handed to Argon2.
 * Larger than the policy maximum in code points, because one code point can be several
 * UTF-16 units.
 */
const val MAX_PASSWORD_INPUT = 512
