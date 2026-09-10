package hu.orszembejelento.service.auth.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hu.orszembejelento.service.auth.data.AuthOutcome
import hu.orszembejelento.service.auth.data.AuthRepository
import hu.orszembejelento.service.auth.domain.AuthErrorKind
import hu.orszembejelento.service.auth.domain.AuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The single owner of authentication state.
 *
 * Screens observe [state] and never decide for themselves whether the user is signed in.
 *
 * The temporary password lives here, in memory, for the few seconds between the login
 * response and the completion call — never on disk. If the process dies in between, the user
 * simply signs in again with the credential they were given; that is expected, and far
 * better than persisting a credential that grants a password change.
 */
class AuthViewModel(private val repository: AuthRepository) : ViewModel() {

    private val _state = MutableStateFlow<AuthState>(AuthState.RestoringSession)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Held in memory only, and cleared as soon as the change succeeds or is abandoned. */
    private var pendingTemporaryPassword: String? = null

    init {
        restoreSession()
    }

    /**
     * Startup path: if an encrypted refresh token exists, exchange it for a session so the
     * user is not asked to sign in again after every ordinary app restart.
     */
    fun restoreSession() {
        viewModelScope.launch {
            if (!repository.hasStoredSession()) {
                _state.value = AuthState.Unauthenticated
                return@launch
            }
            _state.value = AuthState.RestoringSession
            apply(repository.refresh(), onEnded = AuthState.Unauthenticated)
        }
    }

    fun login(serviceId: String, password: String) {
        withBusy {
            when (val outcome = repository.login(serviceId, password)) {
                is AuthOutcome.PasswordChangeRequired -> {
                    // Kept only in memory, so the completion screen need not ask for it again.
                    pendingTemporaryPassword = password
                    _state.value = AuthState.PasswordChangeRequired(outcome.serviceId)
                }
                else -> apply(outcome, onEnded = AuthState.Unauthenticated)
            }
        }
    }

    fun completePasswordChange(newPassword: String) {
        val current = _state.value
        if (current !is AuthState.PasswordChangeRequired) return
        val temporary = pendingTemporaryPassword ?: run {
            // The process was restarted; the credential is gone, as intended.
            _state.value = AuthState.Unauthenticated
            return
        }

        withBusy {
            val outcome = repository.completePasswordChange(current.serviceId, temporary, newPassword)
            if (outcome is AuthOutcome.Success) pendingTemporaryPassword = null
            // A policy failure must keep the user on this screen with their session intact.
            apply(outcome, onEnded = AuthState.Unauthenticated, keepStateOnFailure = current)
        }
    }

    fun changeOwnPassword(currentPassword: String, newPassword: String, onDone: (Boolean) -> Unit) {
        withBusy {
            val outcome = repository.changePassword(currentPassword, newPassword)
            apply(outcome, onEnded = AuthState.Unauthenticated, keepStateOnFailure = _state.value)
            onDone(outcome is AuthOutcome.Success)
        }
    }

    fun logout() = withBusy {
        repository.logout()
        pendingTemporaryPassword = null
        _state.value = AuthState.Unauthenticated
    }

    fun logoutAll() = withBusy {
        repository.logoutAll()
        pendingTemporaryPassword = null
        _state.value = AuthState.Unauthenticated
    }

    /** Abandons a half-finished forced change, discarding the temporary credential. */
    fun cancelPasswordChange() {
        pendingTemporaryPassword = null
        _state.value = AuthState.Unauthenticated
    }

    /**
     * A report-workflow/user-management screen learned, from its own 401/refresh rejection,
     * that the session is gone. No network call - the server has already rejected it.
     */
    fun forceSignedOut() {
        repository.clearSessionLocally()
        pendingTemporaryPassword = null
        _state.value = AuthState.Unauthenticated
    }

    private fun apply(
        outcome: AuthOutcome,
        onEnded: AuthState,
        keepStateOnFailure: AuthState? = null,
    ) {
        _state.value = when (outcome) {
            is AuthOutcome.Success -> AuthState.Authenticated(outcome.serviceId, outcome.role)
            is AuthOutcome.PasswordChangeRequired -> AuthState.PasswordChangeRequired(outcome.serviceId)
            AuthOutcome.SessionEnded -> onEnded
            is AuthOutcome.Failure ->
                if (keepStateOnFailure != null && outcome.kind == AuthErrorKind.PASSWORD_POLICY) {
                    lastError = outcome.kind
                    keepStateOnFailure
                } else {
                    AuthState.AuthError(outcome.kind)
                }
        }
    }

    /** The most recent recoverable error, for screens that stay put and show a message. */
    var lastError: AuthErrorKind? = null
        private set

    fun clearError() {
        lastError = null
        if (_state.value is AuthState.AuthError) _state.value = AuthState.Unauthenticated
    }

    private fun withBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }
}
