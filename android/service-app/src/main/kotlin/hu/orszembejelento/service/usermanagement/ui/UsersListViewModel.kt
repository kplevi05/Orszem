package hu.orszembejelento.service.usermanagement.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.usermanagement.data.AssignableAreaResponse
import hu.orszembejelento.service.usermanagement.data.ManagedUserPageResponse
import hu.orszembejelento.service.usermanagement.data.ManagedUserResponse
import hu.orszembejelento.service.usermanagement.data.UserManagementRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 30

/**
 * `Felhasználók` (brief §51): server-side paginated, service-ID-ascending, backend-enforced
 * visibility/manageability. Supports filtering by status (default: ACTIVE) and service area.
 *
 * Takes [fetchPage] and [fetchAreas] rather than a whole [UserManagementRepository] - mirrors
 * [hu.orszembejelento.service.reports.ui.ReportQueueViewModel]'s identical shape, which
 * makes both unit-testable with plain lambdas instead of a mocked/subclassed repository.
 */
class UsersListViewModel(
    private val fetchPage: suspend (page: Int, size: Int, role: String?, status: String?, query: String?, areaId: String?) -> ApiResult<ManagedUserPageResponse>,
    private val fetchAreas: suspend () -> ApiResult<List<AssignableAreaResponse>> = { ApiResult.Success(emptyList()) },
    private val onSessionEnded: () -> Unit,
) : ViewModel() {

    constructor(repository: UserManagementRepository, onSessionEnded: () -> Unit) : this(
        fetchPage = { page, size, role, status, query, areaId ->
            repository.list(page = page, size = size, role = role, status = status, query = query, areaId = areaId)
        },
        fetchAreas = { repository.assignableAreas() },
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
        val statusFilter: String? = "ACTIVE",
        val areaFilter: String? = null,
        val availableAreas: List<AssignableAreaResponse> = emptyList(),
    ) {
        val canLoadMore: Boolean get() = items.size < totalCount
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    // Field-test fix (§4): also refreshed on navigating back to this screen now
    // (hu.orszembejelento.service.common.ui.RefreshOnResume) - cancel any refresh already in
    // flight before starting a new one, so a slower, stale response can never overwrite a
    // fresher one.
    private var refreshJob: Job? = null

    init {
        loadAreas()
        refresh()
    }

    private fun loadAreas() {
        viewModelScope.launch {
            when (val result = fetchAreas()) {
                is ApiResult.Success -> _state.update { it.copy(availableAreas = result.value) }
                ApiResult.SessionEnded -> onSessionEnded()
                else -> Unit
            }
        }
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
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

    fun setStatusFilter(status: String?) {
        if (_state.value.statusFilter == status) return
        _state.update { it.copy(statusFilter = status) }
        refresh()
    }

    fun setAreaFilter(areaId: String?) {
        if (_state.value.areaFilter == areaId) return
        _state.update { it.copy(areaFilter = areaId) }
        refresh()
    }

    private suspend fun load(page: Int, replace: Boolean) {
        val current = _state.value
        val query = current.query
        when (val result = fetchPage(page, PAGE_SIZE, null, current.statusFilter, query.ifBlank { null }, current.areaFilter)) {
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
