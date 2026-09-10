package hu.orszembejelento.service.usermanagement.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.usermanagement.data.ManagedUserResponse
import hu.orszembejelento.service.usermanagement.data.PasswordResetResponse
import hu.orszembejelento.service.usermanagement.data.UserManagementRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One managed user's detail and every Phase 6 mutation (brief §53-67).
 *
 * Every mutation follows [UserManagementRepository]'s uniform [ApiResult] shape; on any
 * rejection the screen refreshes from the server rather than trusting stale local state
 * (brief §69) - this matters especially for `USER_HAS_ACTIVE_REPORT_ASSIGNMENTS` (brief
 * §60-61), where the target's real, current state is exactly what the actor needs to see.
 */
class UserDetailViewModel(
    private val serviceId: String,
    private val repository: UserManagementRepository,
    private val onSessionEnded: () -> Unit,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val user: ManagedUserResponse? = null,
        val error: ApiResult<Nothing>? = null,
        val mutationInFlight: Boolean = false,
        val mutationError: ApiResult<Nothing>? = null,
        /** One-time display only (brief §56-57) - never re-derivable, never persisted. */
        val newCredential: PasswordResetResponse? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) _state.update { it.copy(loading = true, error = null) }
            when (val result = repository.detail(serviceId)) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, user = result.value, error = null) }
                ApiResult.SessionEnded -> {
                    onSessionEnded()
                    _state.update { it.copy(loading = false) }
                }
                else -> _state.update { current ->
                    @Suppress("UNCHECKED_CAST")
                    current.copy(loading = false, error = if (silent) current.error else result as ApiResult<Nothing>)
                }
            }
        }
    }

    fun resetPassword() {
        viewModelScope.launch {
            _state.update { it.copy(mutationInFlight = true, mutationError = null) }
            when (val result = repository.resetPassword(serviceId)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(mutationInFlight = false, newCredential = result.value) }
                    load(silent = true) // mustChangePassword/sessions changed server-side
                }
                ApiResult.SessionEnded -> {
                    onSessionEnded()
                    _state.update { it.copy(mutationInFlight = false) }
                }
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    _state.update { it.copy(mutationInFlight = false, mutationError = result as ApiResult<Nothing>) }
                    load(silent = true)
                }
            }
        }
    }

    fun consumeCredential() {
        _state.update { it.copy(newCredential = null) }
    }

    fun consumeMutationError() {
        _state.update { it.copy(mutationError = null) }
    }

    fun deactivate() = mutateUser { repository.deactivate(serviceId) }
    fun reactivate() = mutateUser { repository.reactivate(serviceId) }
    fun changeRole(role: String) = mutateUser { repository.changeRole(serviceId, role) }
    fun grantGlobalAccess() = mutateUser { repository.grantGlobalAccess(serviceId) }
    fun revokeGlobalAccess() = mutateUser { repository.revokeGlobalAccess(serviceId) }
    fun grantArea(areaId: String) = mutateUser { repository.grantArea(serviceId, areaId) }
    fun revokeArea(areaId: String) = mutateUser { repository.revokeArea(serviceId, areaId) }

    private fun mutateUser(call: suspend () -> ApiResult<ManagedUserResponse>) {
        if (_state.value.mutationInFlight) return
        viewModelScope.launch {
            _state.update { it.copy(mutationInFlight = true, mutationError = null) }
            when (val result = call()) {
                is ApiResult.Success -> _state.update { it.copy(mutationInFlight = false, user = result.value) }
                ApiResult.SessionEnded -> {
                    onSessionEnded()
                    _state.update { it.copy(mutationInFlight = false) }
                }
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    _state.update { it.copy(mutationInFlight = false, mutationError = result as ApiResult<Nothing>) }
                    // Never blindly retry (brief §69/§77) - refresh current authoritative state.
                    load(silent = true)
                }
            }
        }
    }
}
