package hu.orszembejelento.service.servicearea.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.servicearea.data.AreaAdminRepository
import hu.orszembejelento.service.servicearea.data.RailwayLineAdminListFilter
import hu.orszembejelento.service.servicearea.data.RailwayLineAdminListItemResponse
import hu.orszembejelento.service.servicearea.data.RailwayLineAssignmentFilter
import hu.orszembejelento.service.servicearea.data.RailwayLineSettlementMappingsResponse
import hu.orszembejelento.service.servicearea.domain.PairLevelSupport
import hu.orszembejelento.service.servicearea.domain.canOfferWholeLineAssign
import hu.orszembejelento.service.servicearea.domain.detectPairLevelSupport
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
        /** Line ids whose settlement detail is currently open (several may be open at once). */
        val expandedLineIds: Set<String> = emptySet(),
        /** Per line id: the on-demand detail load - never fetched until the user opens that line. */
        val details: Map<String, LineDetailState> = emptyMap(),
        /** Learned from the list rows (see [PairLevelSupport]); starts UNKNOWN, i.e. new features off. */
        val pairLevelSupport: PairLevelSupport = PairLevelSupport.UNKNOWN,
    ) {
        val canLoadMore: Boolean get() = page + 1 < totalPages

        /** The pair-level filter and the settlement detail exist only against a backend that sent `assignmentMode`. */
        val supportsPairLevel: Boolean get() = pairLevelSupport == PairLevelSupport.SUPPORTED
    }

    /** The on-demand pair-level detail of one line. Opening it never selects or assigns the line. */
    sealed interface LineDetailState {
        data object Loading : LineDetailState
        data class Loaded(val mappings: RailwayLineSettlementMappingsResponse) : LineDetailState
        data class Failed(val error: ApiResult<Nothing>) : LineDetailState
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
        // A legacy backend has no PER_SETTLEMENT filter (it would silently answer "all"): never send it.
        if (filter.assignment == RailwayLineAssignmentFilter.PER_SETTLEMENT && !_state.value.supportsPairLevel) return
        _state.update { it.copy(filter = filter) }
        refresh()
    }

    fun clearAssignError() = _state.update { it.copy(assignError = null) }

    /**
     * Opens or closes one line's settlement detail. Opening fetches it once (a single request
     * for that one line - the list itself already carries the mode and the count, so no row
     * needs a request just to be drawn) and NEVER selects or assigns the line: expanding is a
     * read-only action, separate from the explicit, confirmed assign action.
     */
    fun toggleDetails(lineId: String) {
        // The detail endpoint does not exist on a legacy backend: never call it (it would only fail).
        if (!_state.value.supportsPairLevel) return
        val current = _state.value
        if (lineId in current.expandedLineIds) {
            _state.update { it.copy(expandedLineIds = it.expandedLineIds - lineId) }
            return
        }
        _state.update { it.copy(expandedLineIds = it.expandedLineIds + lineId) }
        if (current.details[lineId] is LineDetailState.Loaded) return
        loadDetails(lineId)
    }

    fun retryDetails(lineId: String) = loadDetails(lineId)

    private fun loadDetails(lineId: String) {
        // Claimed synchronously (same reasoning as confirmAssign): two rapid taps must not start two loads.
        var alreadyLoading = false
        _state.update {
            if (it.details[lineId] is LineDetailState.Loading) {
                alreadyLoading = true
                it
            } else {
                it.copy(details = it.details + (lineId to LineDetailState.Loading))
            }
        }
        if (alreadyLoading) return

        viewModelScope.launch {
            when (val result = repository.railwayLineSettlementMappings(lineId)) {
                is ApiResult.Success -> _state.update { it.copy(details = it.details + (lineId to LineDetailState.Loaded(result.value))) }
                ApiResult.SessionEnded -> {
                    onSessionEnded()
                    _state.update { it.copy(details = it.details - lineId) }
                }
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    _state.update { it.copy(details = it.details + (lineId to LineDetailState.Failed(result as ApiResult<Nothing>))) }
                }
            }
        }
    }

    /**
     * Confirms assigning/moving [line] into [targetAreaId] - the caller (the confirmation
     * dialog) has already made the distinction between a plain assign
     * (`line.currentServiceAreaId == null`) and a move explicit to the user; this method just
     * carries out whichever one it turns out to be, single-flight, never blindly retried.
     */
    fun confirmAssign(line: RailwayLineAdminListItemResponse) {
        // Defence in depth: a pair-configured (or inactive, or already-here) line is never
        // assigned as a whole line from this client - the UI does not offer it, and even a
        // stale caller never reaches the network. The backend still rejects it independently
        // (SETTLEMENT_LINE_MIXED_ROUTING_MODES) and remains the authority.
        if (!canOfferWholeLineAssign(line, targetAreaId)) return

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
                    pairLevelSupport = detectPairLevelSupport(it.pairLevelSupport, result.value.items),
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
