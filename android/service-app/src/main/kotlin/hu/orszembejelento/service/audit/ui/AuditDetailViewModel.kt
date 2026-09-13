package hu.orszembejelento.service.audit.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hu.orszembejelento.service.audit.data.AuditEventDetailResponse
import hu.orszembejelento.service.audit.data.AuditRepository
import hu.orszembejelento.service.common.data.ApiResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One audit event's safe detail (brief §22/§54/§62) - read-only, no mutation of any kind (brief
 * §43: "no Visszaállítás button, no Visszavonás, no Újra végrehajtás - audit is evidence, not
 * command history"). [load] is the one and only network call this ViewModel ever makes.
 */
class AuditDetailViewModel(
    private val auditEventId: String,
    private val repository: AuditRepository,
    private val onSessionEnded: () -> Unit,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val detail: AuditEventDetailResponse? = null,
        val error: ApiResult<Nothing>? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val result = repository.detail(auditEventId)) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, detail = result.value, error = null) }
                ApiResult.SessionEnded -> {
                    onSessionEnded()
                    _state.update { it.copy(loading = false) }
                }
                else -> _state.update { current ->
                    @Suppress("UNCHECKED_CAST")
                    current.copy(loading = false, error = result as ApiResult<Nothing>)
                }
            }
        }
    }
}
