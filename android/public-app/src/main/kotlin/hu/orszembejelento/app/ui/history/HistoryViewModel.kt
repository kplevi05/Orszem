package hu.orszembejelento.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hu.orszembejelento.app.report.data.ReportRepository
import hu.orszembejelento.app.report.domain.SubmissionState
import java.util.UUID
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Local-only history (Phase 5 brief §15-19). No server-side "my reports" listing exists;
 * everything here is read from the local database, and a status refresh is always an
 * explicit action, never a background poll.
 */
class HistoryViewModel(private val reportRepository: ReportRepository) : ViewModel() {

    val history: StateFlow<List<hu.orszembejelento.app.report.data.local.ReportHistoryEntity>> =
        reportRepository.observeHistory().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun refreshStatus(clientSubmissionId: UUID) {
        viewModelScope.launch { reportRepository.refreshStatus(clientSubmissionId) }
    }

    /** Bounded-concurrency refresh of every currently-submitted record (§17). */
    fun refreshAllSubmitted() {
        viewModelScope.launch {
            val ids = history.value.filter { it.submissionState == SubmissionState.SUBMITTED }.map { it.clientSubmissionId }
            reportRepository.refreshAll(ids)
        }
    }

    fun retry(clientSubmissionId: UUID) {
        viewModelScope.launch { reportRepository.retry(clientSubmissionId) }
    }
}
