package hu.orszembejelento.service.moderation.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.moderation.data.DeletedReportFilter
import hu.orszembejelento.service.moderation.data.DeletedReportListItemResponse
import hu.orszembejelento.service.moderation.data.DeletedReportPageResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 50

/**
 * `Törölt bejelentések` (brief §18/§44/§59) - server-side paginated and filtered, exactly
 * mirroring [hu.orszembejelento.service.reports.ui.ReportQueueViewModel]'s shape: changing a
 * filter always resets to page 0, refresh replaces the page, loadMore appends.
 */
class DeletedReportsListViewModel(
    private val fetchPage: suspend (page: Int, size: Int, filter: DeletedReportFilter) -> ApiResult<DeletedReportPageResponse>,
    private val onSessionEnded: () -> Unit,
) : ViewModel() {

    constructor(repository: hu.orszembejelento.service.moderation.data.ModerationRepository, onSessionEnded: () -> Unit) : this(
        fetchPage = { page, size, filter -> repository.deletedList(page, size, filter) },
        onSessionEnded = onSessionEnded,
    )

    data class UiState(
        val items: List<DeletedReportListItemResponse> = emptyList(),
        val page: Int = 0,
        val totalPages: Int = 1,
        val loading: Boolean = true,
        val loadingMore: Boolean = false,
        val error: ApiResult<Nothing>? = null,
        val filter: DeletedReportFilter = DeletedReportFilter(),
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

    fun updateFilter(filter: DeletedReportFilter) {
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
            else -> _state.update {
                @Suppress("UNCHECKED_CAST")
                it.copy(loading = false, loadingMore = false, error = result as ApiResult<Nothing>)
            }
        }
    }
}
