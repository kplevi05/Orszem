package hu.orszembejelento.service.moderation.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.moderation.data.DeletedReportDetailResponse
import hu.orszembejelento.service.moderation.data.ModerationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One deleted report's moderation detail, and — SUPER_ADMIN only — restore (brief §46-50).
 * Mirrors [hu.orszembejelento.service.reports.ui.ReportDetailViewModel]'s shape: single-flight
 * mutation, never blindly retried, the committed response is trusted directly.
 */
class DeletedReportDetailViewModel(
    private val publicReportId: String,
    private val repository: ModerationRepository,
    private val onSessionEnded: () -> Unit,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val detail: DeletedReportDetailResponse? = null,
        val error: ApiResult<Nothing>? = null,
        val restoreInFlight: Boolean = false,
        val restoreError: ApiResult<Nothing>? = null,
        val restoreCompleted: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) _state.update { it.copy(loading = true, error = null) }
            when (val result = repository.deletedDetail(publicReportId)) {
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

    /** SUPER_ADMIN only — the backend independently re-authorises (brief §2, FROZEN). */
    fun restore() {
        val detail = _state.value.detail ?: return
        if (_state.value.restoreInFlight) return

        viewModelScope.launch {
            _state.update { it.copy(restoreInFlight = true, restoreError = null) }
            when (val result = repository.restore(publicReportId, detail.workflowVersion)) {
                is ApiResult.Success -> _state.update { it.copy(restoreInFlight = false, restoreCompleted = true) }
                ApiResult.SessionEnded -> {
                    onSessionEnded()
                    _state.update { it.copy(restoreInFlight = false) }
                }
                is ApiResult.Failure -> if (result.code == "REPORT_NOT_DELETED") {
                    // Already restored (by someone else, or a stale double-tap) - gone from
                    // the deleted set either way, so navigate away rather than show a dead end.
                    _state.update { it.copy(restoreInFlight = false, restoreCompleted = true) }
                } else {
                    _state.update { it.copy(restoreInFlight = false, restoreError = result) }
                    load(silent = true)
                }
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    _state.update { it.copy(restoreInFlight = false, restoreError = result as ApiResult<Nothing>) }
                }
            }
        }
    }

    fun consumeRestoreError() {
        _state.update { it.copy(restoreError = null) }
    }
}
