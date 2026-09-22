package hu.orszembejelento.service.audit.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hu.orszembejelento.service.audit.data.AuditFilter
import hu.orszembejelento.service.audit.data.AuditListItemResponse
import hu.orszembejelento.service.audit.data.AuditOptionsResponse
import hu.orszembejelento.service.audit.data.AuditRepository
import hu.orszembejelento.service.common.data.ApiResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 50

/**
 * `Változási előzmények` (Phase 12 brief) - server-side paginated/filtered/searched, mirroring
 * [hu.orszembejelento.service.moderation.ui.DeletedReportsListViewModel]'s own shape exactly:
 * changing the filter always resets to page 0, refresh replaces the page, loadMore appends.
 *
 * Session-scoped, not Activity-scoped, exactly like every other Phase 8-11 list ViewModel - a
 * logout clears it (brief §60), so the next signed-in user never briefly sees a cached list,
 * search term or filter from a previous session.
 */
class AuditListViewModel(
    private val repository: AuditRepository,
    private val onSessionEnded: () -> Unit,
) : ViewModel() {

    data class UiState(
        val items: List<AuditListItemResponse> = emptyList(),
        val page: Int = 0,
        val totalPages: Int = 1,
        val loading: Boolean = true,
        val loadingMore: Boolean = false,
        val error: ApiResult<Nothing>? = null,
        val filter: AuditFilter = AuditFilter(),
        val options: AuditOptionsResponse? = null,
    ) {
        val canLoadMore: Boolean get() = page + 1 < totalPages
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    // Field-test fix (§4): also refreshed on navigating back to this screen now
    // (hu.orszembejelento.service.common.ui.RefreshOnResume) - cancel any refresh already in
    // flight before starting a new one, so a slower, stale response can never overwrite a
    // fresher one.
    private var refreshJob: Job? = null

    init {
        refresh()
    }

    /** The screen's only reload path (brief §59) - opening it fresh, an explicit pull/retry, or an explicit filter change. Also (re)loads the filter-sheet options. */
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }

            val optionsResult = repository.options()
            if (optionsResult is ApiResult.SessionEnded) {
                onSessionEnded()
                _state.update { it.copy(loading = false) }
                return@launch
            }

            load(page = 0, replace = true, freshOptions = (optionsResult as? ApiResult.Success)?.value)
        }
    }

    fun loadMore() {
        val current = _state.value
        if (current.loadingMore || !current.canLoadMore) return
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true, error = null) }
            load(page = current.page + 1, replace = false, freshOptions = null)
        }
    }

    fun updateFilter(filter: AuditFilter) {
        _state.update { it.copy(filter = filter) }
        refresh()
    }

    private suspend fun load(page: Int, replace: Boolean, freshOptions: AuditOptionsResponse?) {
        when (val result = repository.events(_state.value.filter, page, PAGE_SIZE)) {
            is ApiResult.Success -> _state.update { current ->
                current.copy(
                    items = if (replace) result.value.items else current.items + result.value.items,
                    page = result.value.page,
                    totalPages = result.value.totalPages,
                    loading = false,
                    loadingMore = false,
                    error = null,
                    options = freshOptions ?: current.options,
                )
            }
            ApiResult.SessionEnded -> {
                onSessionEnded()
                _state.update { it.copy(loading = false, loadingMore = false) }
            }
            else -> _state.update { current ->
                @Suppress("UNCHECKED_CAST")
                current.copy(loading = false, loadingMore = false, error = result as ApiResult<Nothing>, options = freshOptions ?: current.options)
            }
        }
    }
}
