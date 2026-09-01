package hu.orszem.serviceapp.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

enum class AuthState { UNKNOWN, AUTHENTICATED, UNAUTHENTICATED }

/**
 * In-memory source of truth for whether the Service App has a valid session.
 * A 401 from any call flips this to UNAUTHENTICATED and the UI returns to login.
 */
@Singleton
class SessionManager @Inject constructor(
    private val sessionStore: SessionStore,
    private val clock: Clock,
) {
    private val _state = MutableStateFlow(AuthState.UNKNOWN)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    @Volatile
    private var token: String? = null

    suspend fun bootstrap() {
        val stored = firstSession()
        if (stored != null && stored.expiresAt.isAfter(clock.instant())) {
            token = stored.accessToken
            _state.value = AuthState.AUTHENTICATED
        } else {
            if (stored != null) sessionStore.clear()
            _state.value = AuthState.UNAUTHENTICATED
        }
    }

    suspend fun onLoggedIn(accessToken: String, expiresAt: Instant) {
        token = accessToken
        sessionStore.save(accessToken, expiresAt)
        _state.value = AuthState.AUTHENTICATED
    }

    suspend fun logout() {
        token = null
        sessionStore.clear()
        _state.value = AuthState.UNAUTHENTICATED
    }

    /** Called by the auth interceptor when the backend rejects the token. */
    fun onUnauthorized() {
        token = null
        _state.value = AuthState.UNAUTHENTICATED
    }

    fun currentToken(): String? = token

    private suspend fun firstSession(): StoredSession? {
        var result: StoredSession? = null
        kotlinx.coroutines.flow.firstOrNull(sessionStore.session)?.let { result = it }
        return result
    }
}
