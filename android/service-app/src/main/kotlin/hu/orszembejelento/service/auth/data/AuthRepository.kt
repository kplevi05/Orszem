package hu.orszembejelento.service.auth.data

import hu.orszembejelento.service.auth.domain.AuthErrorKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import retrofit2.Response

/** Outcome of an authentication attempt, in terms the state holder can act on. */
sealed interface AuthOutcome {
    data class Success(val serviceId: String, val role: String) : AuthOutcome
    data class PasswordChangeRequired(val serviceId: String) : AuthOutcome
    data class Failure(val kind: AuthErrorKind) : AuthOutcome
    /** The stored refresh token is gone or rejected: local state must be cleared. */
    data object SessionEnded : AuthOutcome
}

/**
 * Owns the session: the in-memory access token, the encrypted refresh token, and rotation.
 *
 * ## Access token
 * Held in memory only — never in SharedPreferences, DataStore, a database or a file. It
 * expires in fifteen minutes, so persisting it would trade a real disclosure risk for almost
 * no convenience. After process death the app restores the session from the refresh token
 * instead, which is the credential actually designed to be stored.
 */
class AuthRepository(
    private val api: AuthApi,
    private val tokenStore: EncryptedTokenStore,
    scope: CoroutineScope,
) {

    /** In memory only, and deliberately not exposed. */
    @Volatile
    private var accessToken: String? = null

    @Volatile
    private var cachedIdentity: MeResponse? = null

    private val singleFlight = SingleFlight<AuthOutcome>(scope)

    private val json = Json { ignoreUnknownKeys = true }

    val refreshExecutions: Int get() = singleFlight.executions

    fun hasStoredSession(): Boolean = tokenStore.load() != null

    // ------------------------------------------------------------------- sign in

    suspend fun login(serviceId: String, password: String): AuthOutcome = call {
        val response = api.login(LoginRequest(serviceId.trim(), password))
        when {
            response.isSuccessful -> adopt(response.body()!!)
            errorCodeOf(response) == ApiErrorCode.PASSWORD_CHANGE_REQUIRED ->
                AuthOutcome.PasswordChangeRequired(serviceId.trim())
            else -> AuthOutcome.Failure(failureKind(response))
        }
    }

    suspend fun completePasswordChange(
        serviceId: String,
        temporaryPassword: String,
        newPassword: String,
    ): AuthOutcome = call {
        val response = api.completePasswordChange(
            CompletePasswordChangeRequest(serviceId.trim(), temporaryPassword, newPassword),
        )
        if (response.isSuccessful) adopt(response.body()!!) else AuthOutcome.Failure(failureKind(response))
    }

    // ------------------------------------------------------------------- refresh

    /**
     * Restores or renews the session from the stored refresh token.
     *
     * Routed through [SingleFlight] so concurrent callers share one HTTP request. Sending
     * the same rotating token twice would look like theft to the server and end the session.
     */
    suspend fun refresh(): AuthOutcome = singleFlight.run {
        val stored = tokenStore.load() ?: return@run AuthOutcome.SessionEnded

        val response = try {
            api.refresh(RefreshRequest(stored.refreshToken))
        } catch (_: Exception) {
            // Transport failure. The token may or may not have been consumed, so it is
            // never blindly resent: report a recoverable network state instead. Automatic
            // retry of a rotating credential is exactly what must not happen.
            return@run AuthOutcome.Failure(AuthErrorKind.NETWORK)
        }

        if (response.isSuccessful) {
            adopt(response.body()!!)
        } else {
            // Rejected outright: expired, revoked, or already consumed. Nothing local can
            // recover it, so drop it and require a sign-in.
            clearLocalSession()
            AuthOutcome.SessionEnded
        }
    }

    // ------------------------------------------------------------------ account

    suspend fun changePassword(currentPassword: String, newPassword: String): AuthOutcome = call {
        val bearer = bearerOrNull() ?: return@call AuthOutcome.SessionEnded
        val response = api.changePassword(bearer, ChangePasswordRequest(currentPassword, newPassword))
        if (response.isSuccessful) adopt(response.body()!!) else AuthOutcome.Failure(failureKind(response))
    }

    suspend fun logout(): AuthOutcome {
        // Best effort: the local session is cleared even if the call fails, so a user on a
        // shared device is never left signed in because the network was down.
        runCatching { bearerOrNull()?.let { api.logout(it) } }
        clearLocalSession()
        return AuthOutcome.SessionEnded
    }

    suspend fun logoutAll(): AuthOutcome {
        runCatching { bearerOrNull()?.let { api.logoutAll(it) } }
        clearLocalSession()
        return AuthOutcome.SessionEnded
    }

    // ------------------------------------------------------------------ internals

    /** Adopts fresh credentials: access token in memory, rotated refresh token encrypted. */
    private suspend fun adopt(tokens: TokenResponse): AuthOutcome {
        accessToken = tokens.accessToken
        tokenStore.save(
            StoredSession(
                refreshToken = tokens.refreshToken,
                sessionExpiresAt = tokens.sessionExpiresAt,
            ),
        )

        val identity = runCatching {
            api.me("Bearer ${tokens.accessToken}").takeIf { it.isSuccessful }?.body()
        }.getOrNull()

        return if (identity != null) {
            cachedIdentity = identity
            AuthOutcome.Success(identity.serviceId, identity.role)
        } else {
            AuthOutcome.Failure(AuthErrorKind.NETWORK)
        }
    }

    private fun bearerOrNull(): String? = accessToken?.let { "Bearer $it" }

    private fun clearLocalSession() {
        accessToken = null
        cachedIdentity = null
        tokenStore.clear()
    }

    private inline fun call(block: () -> AuthOutcome): AuthOutcome =
        try {
            block()
        } catch (_: Exception) {
            AuthOutcome.Failure(AuthErrorKind.NETWORK)
        }

    private fun errorCodeOf(response: Response<*>): String? = runCatching {
        response.errorBody()?.string()?.let { json.decodeFromString<ApiErrorBody>(it).code }
    }.getOrNull()

    private fun failureKind(response: Response<*>): AuthErrorKind = when (errorCodeOf(response)) {
        ApiErrorCode.INVALID_CREDENTIALS -> AuthErrorKind.INVALID_CREDENTIALS
        ApiErrorCode.PASSWORD_POLICY_VIOLATION -> AuthErrorKind.PASSWORD_POLICY
        ApiErrorCode.RATE_LIMITED -> AuthErrorKind.RATE_LIMITED
        ApiErrorCode.SESSION_INVALID -> AuthErrorKind.INVALID_CREDENTIALS
        else -> AuthErrorKind.UNEXPECTED
    }
}
