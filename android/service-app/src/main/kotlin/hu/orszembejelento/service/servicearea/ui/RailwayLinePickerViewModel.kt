package hu.orszembejelento.service.servicearea.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.servicearea.data.AreaAdminRepository
import hu.orszembejelento.service.servicearea.data.RailwayLineAdminListFilter
import hu.orszembejelento.service.servicearea.data.RailwayLineAdminListItemResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 50

/**
 * `Vasútvonal kiválasztása` (brief §50) - the RailwayLine picker used from a ServiceArea's
 * `Vasútvonal hozzáadása` action. Search/paginate over every line the way
 * [hu.orszembejelento.service.moderation.ui.DeletedReportsListViewModel] does; the assign/move
 * mutation itself is single-flight and, on success, closes the picker rather than trying to
 * merge the new mapping into the already-loaded list (brief §64/§65 - the caller reloads the
 * area detail fresh).
 */
class RailwayLinePickerViewModel(
    private val targetAreaId: String,
    private val repository: AreaAdminRepository,
    private val onSessionEnded: () -> Unit,
) : ViewModel() {

    data class UiState(
        val items: List<RailwayLineAdminListItemResponse> = emptyList(),
        val page: Int = 0,
        val totalPages: Int = 1,
        val loading: Boolean = true,
        val loadingMore: Boolean = false,
        val loadError: ApiResult<Nothing>? = null,
        val filter: RailwayLineAdminListFilter = RailwayLineAdminListFilter(),
        val assigning: Boolean = false,
        val assignError: ApiResult<Nothing>? = null,
        val assigned: Boolean = false,
    ) {
        val canLoadMore: Boolean get() = page + 1 < totalPages
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, loadError = null) }
            load(page = 0, replace = true)
        }
    }

    fun loadMore() {
        val current = _state.value
        if (current.loadingMore || !current.canLoadMore) return
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true, loadError = null) }
            load(page = current.page + 1, replace = false)
        }
    }

    fun updateFilter(filter: RailwayLineAdminListFilter) {
        _state.update { it.copy(filter = filter) }
        refresh()
    }

    fun clearAssignError() = _state.update { it.copy(assignError = null) }

    /**
     * Confirms assigning/moving [line] into [targetAreaId] - the caller (the confirmation
     * dialog) has already made the distinction between a plain assign
     * (`line.currentServiceAreaId == null`) and a move explicit to the user; this method just
     * carries out whichever one it turns out to be, single-flight, never blindly retried.
     */
    fun confirmAssign(line: RailwayLineAdminListItemResponse) {
        // Claimed synchronously, not inside the launched coroutine - see
        // ServiceAreaDetailViewModel.mutate's identical reasoning for why a check-then-launch
        // guard would leave a window for two rapid taps to both slip through.
        var alreadyAssigning = false
        _state.update {
            if (it.assigning) {
                alreadyAssigning = true
                it
            } else {
                it.copy(assigning = true, assignError = null)
            }
        }
        if (alreadyAssigning) return

        viewModelScope.launch {
            when (val result = repository.assignRailwayLine(line.id, targetAreaId, line.currentServiceAreaId)) {
                is ApiResult.Success -> _state.update { it.copy(assigning = false, assigned = true) }
                ApiResult.SessionEnded -> {
                    onSessionEnded()
                    _state.update { it.copy(assigning = false) }
                }
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    _state.update { it.copy(assigning = false, assignError = result as ApiResult<Nothing>) }
                    // Never replay - refresh the list so a stale assignment-changed conflict
                    // is immediately visible (brief §57/§65).
                    refresh()
                }
            }
        }
    }

    private suspend fun load(page: Int, replace: Boolean) {
        when (val result = repository.listRailwayLines(page, PAGE_SIZE, _state.value.filter)) {
            is ApiResult.Success -> _state.update {
                it.copy(
                    items = if (replace) result.value.items else it.items + result.value.items,
                    page = result.value.page,
                    totalPages = result.value.totalPages,
                    loading = false,
                    loadingMore = false,
                    loadError = null,
                )
            }
            ApiResult.SessionEnded -> {
                onSessionEnded()
                _state.update { it.copy(loading = false, loadingMore = false) }
            }
            else -> _state.update {
                @Suppress("UNCHECKED_CAST")
                it.copy(loading = false, loadingMore = false, loadError = result as ApiResult<Nothing>)
            }
        }
    }
}
