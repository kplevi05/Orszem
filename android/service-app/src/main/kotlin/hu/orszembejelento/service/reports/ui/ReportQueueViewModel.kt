package hu.orszembejelento.service.reports.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.reports.data.ReportFilter
import hu.orszembejelento.service.reports.data.ReportListItemResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 50

/**
 * Backs any one of the three report queues (NEW/IN_PROGRESS/Archive, brief §16/§26/§39) -
 * they share identical pagination, filter and refresh mechanics and differ only in which
 * backend endpoint [fetchPage] calls. The backend remains authoritative for scope, ordering
 * and (for NEW) `ageBucket`; this holder never reorders or reclassifies anything it receives
 * (brief §16/§17).
 */
class ReportQueueViewModel(
    private val fetchPage: suspend (page: Int, size: Int, filter: ReportFilter) -> ApiResult<hu.orszembejelento.service.reports.data.ReportQueuePageResponse>,
    private val onSessionEnded: () -> Unit,
) : ViewModel() {

    data class UiState(
        val items: List<ReportListItemResponse> = emptyList(),
        val page: Int = 0,
        val totalPages: Int = 1,
        val loading: Boolean = true,
        val loadingMore: Boolean = false,
        val error: ApiResult<Nothing>? = null,
        val filter: ReportFilter = ReportFilter(),
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
            _state.update { it.copy(loading = true, error = null) }
            load(page = 0, replace = true)
        }
    }

    fun loadMore() {
        val current = _state.value
        if (current.loadingMore || !current.canLoadMore) return
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true, error = null) }
            load(page = current.page + 1, replace = false)
        }
    }

    fun updateFilter(filter: ReportFilter) {
        _state.update { it.copy(filter = filter) }
        refresh()
    }

    private suspend fun load(page: Int, replace: Boolean) {
        when (val result = fetchPage(page, PAGE_SIZE, _state.value.filter)) {
            is ApiResult.Success -> _state.update {
                it.copy(
                    items = if (replace) result.value.items else it.items + result.value.items,
                    page = result.value.page,
                    totalPages = result.value.totalPages,
                    loading = false,
                    loadingMore = false,
                    error = null,
                )
            }
            ApiResult.SessionEnded -> {
                onSessionEnded()
                _state.update { it.copy(loading = false, loadingMore = false) }
            }
            else -> _state.update { it.copy(loading = false, loadingMore = false, error = result as ApiResult<Nothing>) }
        }
    }
}
