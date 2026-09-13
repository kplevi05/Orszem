package hu.orszembejelento.service.servicearea.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.servicearea.data.AreaAdminRepository
import hu.orszembejelento.service.servicearea.data.ServiceAreaAdminDetailResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * `Szolgálati terület` detail (brief §48): rename, activate/deactivate, and the mapped-lines
 * section's unassign action. Every mutation is single-flight (brief §64 - `mutationInFlight`
 * guards every entry point) and never blindly retried: whatever the outcome, this always
 * re-fetches the real server state afterward rather than reconstructing it locally (brief
 * §65) - a line unassign or an area deactivate may already have committed even if the HTTP
 * response was ambiguous, so the only safe move is to ask the server what is actually true now.
 */
class ServiceAreaDetailViewModel(
    private val areaId: String,
    private val repository: AreaAdminRepository,
    private val onSessionEnded: () -> Unit,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val detail: ServiceAreaAdminDetailResponse? = null,
        val loadError: ApiResult<Nothing>? = null,
        val mutationInFlight: Boolean = false,
        val mutationError: ApiResult<Nothing>? = null,
        /** Set exactly when the last mutation attempt failed - purely so the UI can dismiss the inline banner independently of a fresh load error. */
        val lastMutationFailed: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, loadError = null) }
            when (val result = repository.areaDetail(areaId)) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, detail = result.value, loadError = null) }
                ApiResult.SessionEnded -> {
                    onSessionEnded()
                    _state.update { it.copy(loading = false) }
                }
                else -> _state.update {
                    @Suppress("UNCHECKED_CAST")
                    it.copy(loading = false, loadError = result as ApiResult<Nothing>)
                }
            }
        }
    }

    fun clearMutationError() = _state.update { it.copy(mutationError = null, lastMutationFailed = false) }

    fun rename(name: String) = mutate { repository.renameArea(areaId, requireVersion(), name.trim()) }

    fun activate() = mutate { repository.activateArea(areaId, requireVersion()) }

    fun deactivate() = mutate { repository.deactivateArea(areaId, requireVersion()) }

    /** Removes one mapped line from this area (brief §21/§52) - `expectedCurrentServiceAreaId` is this area's own id, since the line is listed as currently mapped here. */
    fun unassignLine(railwayLineId: String) = mutate { repository.unassignRailwayLine(railwayLineId, areaId) }

    private fun requireVersion(): Long = _state.value.detail?.adminVersion ?: error("mutate() called before detail loaded")

    /**
     * Single-flight wrapper for every mutation (brief §64): a second tap while one is already
     * in flight is a no-op, never a second concurrent request. Whatever the outcome, the
     * detail is always re-fetched afterward (brief §65) - never reconstructed from the
     * request that was just sent.
     */
    private fun mutate(call: suspend () -> ApiResult<*>) {
        // The flag is claimed synchronously, in the same call that checks it - not inside the
        // launched coroutine, which would leave a window where two rapid taps (both arriving
        // before either coroutine body has actually run) could each see `mutationInFlight ==
        // false` and both launch. `MutableStateFlow.update` is atomic for exactly this reason.
        var alreadyInFlight = false
        _state.update {
            if (it.mutationInFlight) {
                alreadyInFlight = true
                it
            } else {
                it.copy(mutationInFlight = true, mutationError = null)
            }
        }
        if (alreadyInFlight) return

        viewModelScope.launch {
            when (val result = call()) {
                is ApiResult.Success -> {
                    _state.update { it.copy(mutationInFlight = false, lastMutationFailed = false) }
                    refresh()
                }
                ApiResult.SessionEnded -> {
                    onSessionEnded()
                    _state.update { it.copy(mutationInFlight = false) }
                }
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    _state.update { it.copy(mutationInFlight = false, mutationError = result as ApiResult<Nothing>, lastMutationFailed = true) }
                    // Never replay the mutation - only refresh the read-only state so the UI
                    // reflects what is actually true now (brief §56/§57/§65), whether or not
                    // the rejected call itself changed anything server-side.
                    refresh()
                }
            }
        }
    }
}
