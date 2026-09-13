package hu.orszembejelento.service.servicearea.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.servicearea.data.AreaAdminRepository
import hu.orszembejelento.service.servicearea.data.ServiceAreaAdminResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * `Új szolgálati terület` (brief §10/§47). Deliberately creates the area only - no RailwayLine
 * assignment happens in the same flow (brief §47: "create first, then configure lines").
 */
class CreateServiceAreaViewModel(
    private val repository: AreaAdminRepository,
    private val onSessionEnded: () -> Unit,
) : ViewModel() {

    data class UiState(
        val name: String = "",
        val submitting: Boolean = false,
        val error: ApiResult<Nothing>? = null,
        val created: ServiceAreaAdminResponse? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun setName(name: String) = _state.update { it.copy(name = name) }

    fun submit() {
        val current = _state.value
        if (current.submitting || current.name.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }
            when (val result = repository.createArea(current.name.trim())) {
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
}
