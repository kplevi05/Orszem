package hu.orszembejelento.service.usermanagement.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.usermanagement.data.AssignableAreaResponse
import hu.orszembejelento.service.usermanagement.data.CreateUserResponse
import hu.orszembejelento.service.usermanagement.data.UserManagementRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * New-user creation (brief §54-58). `role`/`areaIds`/`globalAreaAccess` are the only inputs
 * the caller supplies - service ID, the temporary credential, status and
 * `mustChangePassword` are all server-generated, exactly as [UserManagementRepository.create]
 * documents.
 */
class CreateUserViewModel(
    canAssignModerator: Boolean,
    private val repository: UserManagementRepository,
    private val onSessionEnded: () -> Unit,
) : ViewModel() {

    data class UiState(
        val canAssignModerator: Boolean = false,
        val role: String = "SERVICE_USER",
        val selectedAreaIds: Set<String> = emptySet(),
        val globalAreaAccess: Boolean = false,
        val availableAreas: List<AssignableAreaResponse> = emptyList(),
        val loadingAreas: Boolean = true,
        val submitting: Boolean = false,
        val error: ApiResult<Nothing>? = null,
        val created: CreateUserResponse? = null,
    )

    private val _state = MutableStateFlow(UiState(canAssignModerator = canAssignModerator))
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            when (val result = repository.assignableAreas()) {
                is ApiResult.Success -> _state.update { it.copy(availableAreas = result.value, loadingAreas = false) }
                ApiResult.SessionEnded -> {
                    onSessionEnded()
                    _state.update { it.copy(loadingAreas = false) }
                }
                else -> _state.update { it.copy(loadingAreas = false) }
            }
        }
    }

    fun setRole(role: String) = _state.update { it.copy(role = role) }

    fun toggleArea(areaId: String) = _state.update {
        it.copy(selectedAreaIds = if (areaId in it.selectedAreaIds) it.selectedAreaIds - areaId else it.selectedAreaIds + areaId)
    }

    fun setGlobalAccess(value: Boolean) = _state.update { it.copy(globalAreaAccess = value) }

    fun submit() {
        val current = _state.value
        if (current.submitting) return
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }
            when (val result = repository.create(current.role, current.selectedAreaIds.toList(), current.globalAreaAccess)) {
                is ApiResult.Success -> _state.update { it.copy(submitting = false, created = result.value) }
                ApiResult.SessionEnded -> {
                    onSessionEnded()
                    _state.update { it.copy(submitting = false) }
                }
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    _state.update { it.copy(submitting = false, error = result as ApiResult<Nothing>) }
                }
            }
        }
    }

    fun consumeCreated() = _state.update { it.copy(created = null) }
}
