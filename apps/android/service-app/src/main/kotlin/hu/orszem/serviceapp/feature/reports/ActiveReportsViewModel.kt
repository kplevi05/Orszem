package hu.orszem.serviceapp.feature.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hu.orszem.core.common.Outcome
import hu.orszem.serviceapp.data.ServiceReportRepository
import hu.orszem.serviceapp.feature.common.ListUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ActiveReportsViewModel @Inject constructor(
    private val repository: ServiceReportRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<ListUiState>(ListUiState.Loading)
    val state: StateFlow<ListUiState> = _state.asStateFlow()

    private var nextCursor: String? = null

    init {
        refresh()
    }

    fun refresh() {
        _state.value = ListUiState.Loading
        nextCursor = null
        viewModelScope.launch {
            when (val outcome = repository.activeReports()) {
                is Outcome.Success -> {
                    nextCursor = outcome.value.nextCursor
                    _state.value = if (outcome.value.items.isEmpty()) {
                        ListUiState.Empty
                    } else {
                        ListUiState.Content(outcome.value.items, hasMore = nextCursor != null)
                    }
                }
                is Outcome.Failure -> _state.value = ListUiState.Error(recoverable = outcome.error.isRecoverableNetwork)
            }
        }
    }

    fun loadMore() {
        val current = _state.value as? ListUiState.Content ?: return
        val cursor = nextCursor ?: return
        if (current.loadingMore) return
        _state.value = current.copy(loadingMore = true)
        viewModelScope.launch {
            when (val outcome = repository.activeReports(cursor = cursor)) {
                is Outcome.Success -> {
                    nextCursor = outcome.value.nextCursor
                    _state.value = ListUiState.Content(
                        items = current.items + outcome.value.items,
                        hasMore = nextCursor != null,
                    )
                }
                is Outcome.Failure -> _state.value = current.copy(loadingMore = false)
            }
        }
    }
}
