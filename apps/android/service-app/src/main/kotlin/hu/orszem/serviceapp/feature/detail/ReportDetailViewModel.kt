package hu.orszem.serviceapp.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hu.orszem.core.common.ApiErrorCode
import hu.orszem.core.common.Outcome
import hu.orszem.core.model.ReportStatus
import hu.orszem.core.model.ServiceReportDetail
import hu.orszem.serviceapp.data.ServiceReportRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DetailState(
    val loading: Boolean = true,
    val report: ServiceReportDetail? = null,
    val loadError: Boolean = false,
    val actionInProgress: Boolean = false,
    /** Set when a 409 tells us the state changed under us; the report is reloaded. */
    val staleConflict: Boolean = false,
) {
    val canAccept: Boolean get() = !actionInProgress && report?.status == ReportStatus.NEW
    val canArchive: Boolean get() = !actionInProgress && report?.status == ReportStatus.IN_PROGRESS
}

@HiltViewModel
class ReportDetailViewModel @Inject constructor(
    private val repository: ServiceReportRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _state.asStateFlow()

    private var reportId: String = ""

    fun load(id: String) {
        reportId = id
        _state.update { it.copy(loading = true, loadError = false) }
        viewModelScope.launch { fetch() }
    }

    fun accept() = transition { repository.accept(reportId) }

    fun archive() = transition { repository.archive(reportId) }

    fun dismissConflict() = _state.update { it.copy(staleConflict = false) }

    private fun transition(block: suspend () -> Outcome<ServiceReportDetail>) {
        if (_state.value.actionInProgress) return
        _state.update { it.copy(actionInProgress = true, staleConflict = false) }
        viewModelScope.launch {
            when (val outcome = block()) {
                is Outcome.Success -> _state.update {
                    it.copy(actionInProgress = false, report = outcome.value)
                }
                is Outcome.Failure -> {
                    val stale = outcome.error.code == ApiErrorCode.REPORT_NOT_ACCEPTABLE ||
                        outcome.error.code == ApiErrorCode.REPORT_NOT_ARCHIVABLE
                    _state.update { it.copy(actionInProgress = false, staleConflict = stale) }
                    if (stale) fetch()
                }
            }
        }
    }

    private suspend fun fetch() {
        when (val outcome = repository.detail(reportId)) {
            is Outcome.Success -> _state.update {
                it.copy(loading = false, report = outcome.value, loadError = false)
            }
            is Outcome.Failure -> _state.update { it.copy(loading = false, loadError = true) }
        }
    }
}
