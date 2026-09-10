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
        if (response.isSuccessful) return@call adopt(response.body()!!)

        // Read once and reuse: the error body is a one-shot stream, so reading it a second
        // time yields nothing and every failure would collapse to "unexpected".
        val code = errorCodeOf(response)
        if (code == ApiErrorCode.PASSWORD_CHANGE_REQUIRED) {
            AuthOutcome.PasswordChangeRequired(serviceId.trim())
        } else {
            AuthOutcome.Failure(failureKind(code))
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
        val response = withFreshToken { bearer ->
            api.changePassword(bearer, ChangePasswordRequest(currentPassword, newPassword))
        } ?: return@call AuthOutcome.SessionEnded

        if (response.isSuccessful) adopt(response.body()!!) else AuthOutcome.Failure(failureKind(response))
    }

    /**
     * The single entry point every other feature repository (report workflow, user
     * management) uses to call a Service-authenticated endpoint.
     *
     * Reuses exactly the same bearer-attach / 401-refresh-once-retry-once / single-flight
     * machinery [changePassword] already relies on, so no other repository re-implements
     * token handling. Returns `null` only when there is no session to authenticate with at
     * all (no access token, and refresh could not produce one) - the caller should treat
     * that identically to [AuthOutcome.SessionEnded].
     */
    suspend fun <T> authorizedCall(call: suspend (bearer: String) -> Response<T>): Response<T>? =
        withFreshToken(call)

    /**
     * Runs a protected call, and on a 401 refreshes **once** and retries **once**.
     *
     * Bounded deliberately: a retry loop against an expired session would hammer the server
     * and, because refresh tokens rotate, could turn one stale access token into a cascade of
     * replays. One refresh, one retry, then give up.
     *
     * Retrying is safe even for a state-changing call because the backend authenticates in a
     * servlet filter, before any controller or use case runs — a request rejected with 401
     * cannot have changed anything, so it cannot be applied twice.
     *
     * The refresh itself goes through [SingleFlight], so several protected calls failing at
     * once still produce exactly one refresh.
     */
    private suspend fun <T> withFreshToken(call: suspend (String) -> Response<T>): Response<T>? {
        val bearer = bearerOrNull() ?: return null
        val response = call(bearer)
        if (response.code() != HTTP_UNAUTHORIZED) return response

        return when (refresh()) {
            is AuthOutcome.Success -> bearerOrNull()?.let { call(it) }
            else -> null
        }
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

    /**
     * Clears local session state only - no network call.
     *
     * For the case where a *different* repository ([authorizedCall] returning null to a
     * report-workflow or user-management screen) has already learned, from the server's own
     * 401/refresh rejection, that the session is gone. Calling [logout] there would attempt
     * a pointless network request with a credential the server has already rejected;
     * clearing local state is all that is left to do.
     */
    fun clearSessionLocally() = clearLocalSession()

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

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
    }

    private inline fun call(block: () -> AuthOutcome): AuthOutcome =
        try {
            block()
        } catch (_: Exception) {
            AuthOutcome.Failure(AuthErrorKind.NETWORK)
        }

    /**
     * Reads the stable error code from the body.
     *
     * The body is a one-shot stream, so this must be called at most once per response and
     * its result reused — never called again to re-derive the same value.
     */
    private fun errorCodeOf(response: Response<*>): String? = runCatching {
        response.errorBody()?.string()?.let { json.decodeFromString<ApiErrorBody>(it).code }
    }.getOrNull()

    private fun failureKind(response: Response<*>): AuthErrorKind = failureKind(errorCodeOf(response))

    private fun failureKind(code: String?): AuthErrorKind = when (code) {
        ApiErrorCode.INVALID_CREDENTIALS -> AuthErrorKind.INVALID_CREDENTIALS
        ApiErrorCode.PASSWORD_POLICY_VIOLATION -> AuthErrorKind.PASSWORD_POLICY
        ApiErrorCode.RATE_LIMITED -> AuthErrorKind.RATE_LIMITED
        ApiErrorCode.SESSION_INVALID -> AuthErrorKind.INVALID_CREDENTIALS
        else -> AuthErrorKind.UNEXPECTED
    }
}
