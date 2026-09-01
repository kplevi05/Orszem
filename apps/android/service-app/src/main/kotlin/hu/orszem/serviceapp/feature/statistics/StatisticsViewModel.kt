package hu.orszem.serviceapp.feature.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hu.orszem.core.common.Outcome
import hu.orszem.serviceapp.data.Analytics
import hu.orszem.serviceapp.data.AnalyticsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface StatisticsUiState {
    data object Loading : StatisticsUiState
    data object Error : StatisticsUiState
    data class Content(val analytics: Analytics) : StatisticsUiState
}

@HiltViewModel
class StatisticsViewModel @Inject constructor(
    private val repository: AnalyticsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<StatisticsUiState>(StatisticsUiState.Loading)
    val state: StateFlow<StatisticsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = StatisticsUiState.Loading
        viewModelScope.launch {
            _state.value = when (val outcome = repository.load()) {
                is Outcome.Success -> StatisticsUiState.Content(outcome.value)
                is Outcome.Failure -> StatisticsUiState.Error
            }
        }
    }
}
