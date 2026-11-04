package hu.orszembejelento.service.servicearea.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.servicearea.data.AreaAdminRepository
import hu.orszembejelento.service.servicearea.data.SettlementAdminListFilter
import hu.orszembejelento.service.servicearea.data.SettlementAdminListItemResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 50

class SettlementPickerViewModel(
    private val targetAreaId: String,
    private val repository: AreaAdminRepository,
    private val onSessionEnded: () -> Unit,
) : ViewModel() {

    data class UiState(
        val items: List<SettlementAdminListItemResponse> = emptyList(),
        val page: Int = 0,
        val totalPages: Int = 1,
        val loading: Boolean = true,
        val loadingMore: Boolean = false,
        val loadError: ApiResult<Nothing>? = null,
        val filter: SettlementAdminListFilter = SettlementAdminListFilter(),
        val assigning: Boolean = false,
        val assignError: ApiResult<Nothing>? = null,
        val anyAssigned: Boolean = false,
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

    fun updateFilter(filter: SettlementAdminListFilter) {
        _state.update { it.copy(filter = filter) }
        refresh()
    }

    fun confirmAssign(settlement: SettlementAdminListItemResponse) {
        if (!settlement.active || settlement.currentServiceAreaId == targetAreaId) return

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
            when (val result = repository.assignSettlement(settlement.id, targetAreaId, settlement.currentServiceAreaId)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(assigning = false, anyAssigned = true) }
                    refresh()
                }
                ApiResult.SessionEnded -> {
                    onSessionEnded()
                    _state.update { it.copy(assigning = false) }
                }
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    _state.update { it.copy(assigning = false, assignError = result as ApiResult<Nothing>) }
                    refresh()
                }
            }
        }
    }

    private suspend fun load(page: Int, replace: Boolean) {
        when (val result = repository.listSettlements(page, PAGE_SIZE, _state.value.filter)) {
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
