package hu.orszembejelento.service.reports.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.reports.data.ReportDetailResponse
import hu.orszembejelento.service.reports.data.ReportWorkflowRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class WorkflowMutationKind { CLAIM, RETURN, CLOSE, REASSIGN }

/**
 * One report's full workflow detail and the four mutations (brief §28-34).
 *
 * Every mutation follows the same shape: send [ReportDetailResponse.workflowVersion] as
 * `expectedVersion`, and on **any** rejection - stale version, already-claimed,
 * already-archived, forbidden, whatever the stable code - silently re-fetch the current
 * server state instead of guessing or blindly retrying the mutation (brief §24/§77). The
 * committed response from a successful mutation replaces local state directly; nothing here
 * ever synthesizes a guessed result (brief §22).
 */
class ReportDetailViewModel(
    private val publicReportId: String,
    private val repository: ReportWorkflowRepository,
    private val onSessionEnded: () -> Unit,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val detail: ReportDetailResponse? = null,
        val error: ApiResult<Nothing>? = null,
        val mutationInFlight: Boolean = false,
        val mutationError: ApiResult<Nothing>? = null,
        val lastMutation: WorkflowMutationKind? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) _state.update { it.copy(loading = true, error = null) }
            when (val result = repository.detail(publicReportId)) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, detail = result.value, error = null) }
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

    fun claim() = mutate(WorkflowMutationKind.CLAIM) { version -> repository.claim(publicReportId, version) }
    fun returnToNew() = mutate(WorkflowMutationKind.RETURN) { version -> repository.returnToNew(publicReportId, version) }
    fun close() = mutate(WorkflowMutationKind.CLOSE) { version -> repository.close(publicReportId, version) }
    fun reassign(targetServiceId: String) =
        mutate(WorkflowMutationKind.REASSIGN) { version -> repository.reassign(publicReportId, version, targetServiceId) }

    fun consumeToast() {
        _state.update { it.copy(lastMutation = null) }
    }

    fun consumeMutationError() {
        _state.update { it.copy(mutationError = null) }
    }

    private fun mutate(kind: WorkflowMutationKind, call: suspend (Long) -> ApiResult<ReportDetailResponse>) {
        val version = _state.value.detail?.workflowVersion ?: return
        if (_state.value.mutationInFlight) return // single-flight per screen (brief §76)

        viewModelScope.launch {
            _state.update { it.copy(mutationInFlight = true, mutationError = null) }
            when (val result = call(version)) {
                is ApiResult.Success -> _state.update {
                    it.copy(mutationInFlight = false, detail = result.value, lastMutation = kind)
                }
                ApiResult.SessionEnded -> {
                    onSessionEnded()
                    _state.update { it.copy(mutationInFlight = false) }
                }
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    _state.update { it.copy(mutationInFlight = false, mutationError = result as ApiResult<Nothing>) }
                    // Never blindly retry the mutation (brief §24/§77) - always refresh instead.
                    load(silent = true)
                }
            }
        }
    }
}
