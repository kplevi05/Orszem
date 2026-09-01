package hu.orszem.serviceapp.feature.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hu.orszem.core.common.ApiErrorCode
import hu.orszem.core.common.Outcome
import hu.orszem.core.model.ReportStatus
import hu.orszem.core.model.ServiceReportSummary
import hu.orszem.serviceapp.data.ServiceReportRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

/** User-facing load failure reasons (Demo v1.1 §F). */
enum class ListErrorReason { NETWORK, TIMEOUT, SERVER, UNAUTHORIZED, GENERIC }

data class ActiveReportsState(
    /** True only for the very first load, when there is nothing to show yet. */
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    /** Transient: set on a failed load, cleared on the next success or dismissal. */
    val error: ListErrorReason? = null,
    /** The COMPLETE active dataset, every cursor page, both statuses. */
    val all: List<ServiceReportSummary> = emptyList(),
    val tab: ReportTab = ReportTab.NEW,
    val filters: ReportFilters = ReportFilters.NONE,
    val sort: ReportSort = ReportSort.DEFAULT,
    val options: FilterOptions = FilterOptions(),
    /** Rows for the selected tab, filtered and sorted. */
    val visible: List<ServiceReportSummary> = emptyList(),
    val newCount: Int = 0,
    val inProgressCount: Int = 0,
    val filteredNewCount: Int = 0,
    val filteredInProgressCount: Int = 0,
    val deleting: Boolean = false,
) {
    val hasContent: Boolean get() = all.isNotEmpty()
    val filtersActive: Boolean get() = filters.isActive
    /** True when filters are hiding everything on this tab but the tab is not empty. */
    val emptyDueToFilters: Boolean
        get() = visible.isEmpty() && filters.isActive &&
            all.any { it.status == tab.status }
}

/**
 * Active Reports screen (Demo v1.1 §B, §C, §D, §F).
 *
 * The whole active dataset is loaded up front through the existing opaque-cursor
 * API — see [ServiceReportRepository.allActiveReports]. Partitioning by tab,
 * filtering and sorting then all run over that complete list, never over a single
 * page, so a sort really is a sort of everything the service has open.
 */
@HiltViewModel
class ActiveReportsViewModel @Inject constructor(
    private val repository: ServiceReportRepository,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(ActiveReportsState())
    val state: StateFlow<ActiveReportsState> = _state.asStateFlow()

    init {
        refresh()
    }

    /**
     * Reloads the complete dataset.
     *
     * On failure any data already on screen is kept — blanking a working list
     * because one refresh failed is worse than showing slightly stale rows (§F).
     */
    fun refresh() {
        val hadContent = _state.value.hasContent
        _state.value = _state.value.copy(
            loading = !hadContent,
            refreshing = hadContent,
            error = null,
        )
        viewModelScope.launch {
            when (val outcome = repository.allActiveReports()) {
                is Outcome.Success -> _state.value = _state.value
                    .copy(
                        loading = false,
                        refreshing = false,
                        error = null,
                        all = outcome.value,
                        options = outcome.value.filterOptions(),
                    )
                    .recomputed()
                is Outcome.Failure -> _state.value = _state.value.copy(
                    loading = false,
                    refreshing = false,
                    error = outcome.error.code.toListReason(),
                )
            }
        }
    }

    /** Switching tabs keeps the current filters and sort (§C, §D). */
    fun onTabSelected(tab: ReportTab) {
        if (_state.value.tab == tab) return
        _state.value = _state.value.copy(tab = tab).recomputed()
    }

    fun onFiltersChanged(filters: ReportFilters) {
        _state.value = _state.value.copy(filters = filters).recomputed()
    }

    /** Clears filters only — the chosen sort order deliberately survives (§C). */
    fun clearFilters() {
        _state.value = _state.value.copy(filters = ReportFilters.NONE).recomputed()
    }

    fun onSortSelected(sort: ReportSort) {
        _state.value = _state.value.copy(sort = sort).recomputed()
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    /**
     * Applies a status change made elsewhere (accept/archive on the detail screen)
     * to the local dataset, so a report moves between tabs — or leaves the active
     * set entirely when archived — without a full round trip.
     */
    fun onStatusChanged(reportId: String, status: ReportStatus) {
        val current = _state.value
        val updated = when (status) {
            ReportStatus.ARCHIVED -> current.all.filterNot { it.id == reportId }
            else -> current.all.map { if (it.id == reportId) it.copy(status = status) else it }
        }
        _state.value = current.copy(all = updated, options = updated.filterOptions()).recomputed()
    }

    /**
     * Demo v1.1 §E: permanently removes a report.
     *
     * The local dataset is only mutated after the backend confirms the deletion —
     * a failure leaves the list exactly as it was.
     */
    fun deleteReport(reportId: String, onResult: (Boolean) -> Unit = {}) {
        if (_state.value.deleting) return
        _state.value = _state.value.copy(deleting = true, error = null)
        viewModelScope.launch {
            when (val outcome = repository.delete(reportId)) {
                is Outcome.Success -> {
                    val remaining = _state.value.all.filterNot { it.id == reportId }
                    _state.value = _state.value
                        .copy(deleting = false, all = remaining, options = remaining.filterOptions())
                        .recomputed()
                    onResult(true)
                }
                is Outcome.Failure -> {
                    _state.value = _state.value.copy(
                        deleting = false,
                        error = outcome.error.code.toListReason(),
                    )
                    onResult(false)
                }
            }
        }
    }

    /** Recomputes every derived field from `all` + tab + filters + sort. */
    private fun ActiveReportsState.recomputed(): ActiveReportsState {
        val filtered = all.applyFilters(filters, clock)
        return copy(
            newCount = all.count { it.status == ReportStatus.NEW },
            inProgressCount = all.count { it.status == ReportStatus.IN_PROGRESS },
            filteredNewCount = filtered.count { it.status == ReportStatus.NEW },
            filteredInProgressCount = filtered.count { it.status == ReportStatus.IN_PROGRESS },
            // Partition first, then sort: the backend's NEW-first ordering is
            // irrelevant inside a single-status tab and must not leak through.
            visible = filtered.filter { it.status == tab.status }.applySort(sort),
        )
    }

    private fun ApiErrorCode.toListReason(): ListErrorReason = when (this) {
        ApiErrorCode.NETWORK -> ListErrorReason.NETWORK
        ApiErrorCode.TIMEOUT -> ListErrorReason.TIMEOUT
        ApiErrorCode.INTERNAL_ERROR -> ListErrorReason.SERVER
        ApiErrorCode.UNAUTHORIZED, ApiErrorCode.INVALID_CREDENTIALS -> ListErrorReason.UNAUTHORIZED
        else -> ListErrorReason.GENERIC
    }
}
