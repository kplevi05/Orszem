package hu.orszembejelento.service.usermanagement.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.usermanagement.data.ManagedUserPageResponse
import hu.orszembejelento.service.usermanagement.data.ManagedUserResponse
import hu.orszembejelento.service.usermanagement.data.UserManagementRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 30

/**
 * `Felhasználók` (brief §51): server-side paginated, service-ID-ascending, backend-enforced
 * visibility/manageability.
 *
 * Takes [fetchPage] rather than a whole [UserManagementRepository] - mirrors
 * [hu.orszembejelento.service.reports.ui.ReportQueueViewModel]'s identical shape, which
 * makes both unit-testable with a plain lambda instead of a mocked/subclassed repository.
 */
class UsersListViewModel(
    private val fetchPage: suspend (page: Int, size: Int, query: String?) -> ApiResult<ManagedUserPageResponse>,
    private val onSessionEnded: () -> Unit,
) : ViewModel() {

    constructor(repository: UserManagementRepository, onSessionEnded: () -> Unit) : this(
        fetchPage = { page, size, query -> repository.list(page = page, size = size, query = query) },
        onSessionEnded = onSessionEnded,
    )

    data class UiState(
        val items: List<ManagedUserResponse> = emptyList(),
        val page: Int = 0,
        val totalCount: Int = 0,
        val loading: Boolean = true,
        val loadingMore: Boolean = false,
        val error: ApiResult<Nothing>? = null,
        val query: String = "",
    ) {
        val canLoadMore: Boolean get() = items.size < totalCount
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
            _state.update { it.copy(loadingMore = true) }
            load(page = current.page + 1, replace = false)
        }
    }

    fun updateQuery(query: String) {
        _state.update { it.copy(query = query) }
        refresh()
    }

    private suspend fun load(page: Int, replace: Boolean) {
        val query = _state.value.query
        when (val result = fetchPage(page, PAGE_SIZE, query.ifBlank { null })) {
            is ApiResult.Success -> _state.update {
                it.copy(
                    items = if (replace) result.value.items else it.items + result.value.items,
                    page = result.value.page,
                    totalCount = result.value.totalCount,
                    loading = false,
                    loadingMore = false,
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
