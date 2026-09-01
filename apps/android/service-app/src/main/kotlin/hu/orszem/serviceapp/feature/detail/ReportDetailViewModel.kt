package hu.orszem.serviceapp.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hu.orszem.core.common.ApiErrorCode
import hu.orszem.core.common.Outcome
import hu.orszem.core.model.ReportStatus
import hu.orszem.core.model.ServiceCapability
import hu.orszem.core.model.ServiceReportDetail
import hu.orszem.serviceapp.data.AuthRepository
import hu.orszem.serviceapp.data.ServiceReportRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** User-facing failure reasons for detail actions (Demo v1.1 §F). */
enum class DetailErrorReason { NETWORK, TIMEOUT, SERVER, GENERIC }

data class DetailState(
    val loading: Boolean = true,
    val report: ServiceReportDetail? = null,
    val loadError: Boolean = false,
    val actionInProgress: Boolean = false,
    /** Set when a 409 tells us the state changed under us; the report is reloaded. */
    val staleConflict: Boolean = false,
    /** True once the backend confirms this environment grants REPORT_DELETE. */
    val canDelete: Boolean = false,
    val confirmingDelete: Boolean = false,
    val deleting: Boolean = false,
    val deleted: Boolean = false,
    val deleteError: DetailErrorReason? = null,
) {
    val canAccept: Boolean get() = !actionInProgress && !deleting && report?.status == ReportStatus.NEW
    val canArchive: Boolean get() = !actionInProgress && !deleting && report?.status == ReportStatus.IN_PROGRESS
}

@HiltViewModel
class ReportDetailViewModel @Inject constructor(
    private val repository: ServiceReportRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _state.asStateFlow()

    private var reportId: String = ""

    fun load(id: String) {
        reportId = id
        _state.update { it.copy(loading = true, loadError = false) }
        viewModelScope.launch {
            fetch()
            loadCapabilities()
        }
    }

    fun accept() = transition { repository.accept(reportId) }

    fun archive() = transition { repository.archive(reportId) }

    fun dismissConflict() = _state.update { it.copy(staleConflict = false) }

    // --- Demo v1.1 §E: permanent deletion ---------------------------------

    fun requestDelete() {
        if (_state.value.canDelete) _state.update { it.copy(confirmingDelete = true) }
    }

    /** MÉGSE: nothing changes at all. */
    fun cancelDelete() = _state.update { it.copy(confirmingDelete = false) }

    fun dismissDeleteError() = _state.update { it.copy(deleteError = null) }

    /**
     * TÖRLÉS. Local state is only treated as deleted once the backend confirms it;
     * on failure the report stays exactly as it was and the error is retryable.
     */
    fun confirmDelete() {
        if (_state.value.deleting) return
        _state.update { it.copy(confirmingDelete = false, deleting = true, deleteError = null) }
        viewModelScope.launch {
            when (val outcome = repository.delete(reportId)) {
                is Outcome.Success -> _state.update { it.copy(deleting = false, deleted = true) }
                is Outcome.Failure -> _state.update {
                    it.copy(deleting = false, deleteError = outcome.error.code.toDetailReason())
                }
            }
        }
    }

    /**
     * The delete affordance follows the server-advertised capability, never the
     * role name: in a deployment without demo deletion the capability is absent
     * and the button never appears (and the route would refuse it anyway).
     */
    private suspend fun loadCapabilities() {
        when (val profile = authRepository.profile()) {
            is Outcome.Success ->
                _state.update { it.copy(canDelete = ServiceCapability.REPORT_DELETE in profile.value.capabilities) }
            is Outcome.Failure -> _state.update { it.copy(canDelete = false) }
        }
    }

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

    private fun ApiErrorCode.toDetailReason(): DetailErrorReason = when (this) {
        ApiErrorCode.NETWORK -> DetailErrorReason.NETWORK
        ApiErrorCode.TIMEOUT -> DetailErrorReason.TIMEOUT
        ApiErrorCode.INTERNAL_ERROR -> DetailErrorReason.SERVER
        else -> DetailErrorReason.GENERIC
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
