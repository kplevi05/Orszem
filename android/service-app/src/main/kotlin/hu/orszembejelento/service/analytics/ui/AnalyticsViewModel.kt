package hu.orszembejelento.service.analytics.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hu.orszembejelento.service.analytics.data.AnalyticsAreaOptionsResponse
import hu.orszembejelento.service.analytics.data.AnalyticsFilter
import hu.orszembejelento.service.analytics.data.AnalyticsRepository
import hu.orszembejelento.service.analytics.data.AnalyticsSummaryResponse
import hu.orszembejelento.service.common.data.ApiResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * `Statisztika` (Phase 11 brief §33-51) - a real, role-scoped analytics dashboard. Read-only:
 * a failed load may be retried explicitly (brief §49), and this ViewModel never polls in the
 * background - it reloads on a real user action only: opening the tab, an explicit pull/retry,
 * an explicit filter change, or (field-test fix, §4) navigating back to this tab
 * (hu.orszembejelento.service.common.ui.RefreshOnResume). Filters are local UI preference, not
 * authorization (brief §48) - the backend independently re-validates every one of them on
 * every request regardless of what this ViewModel remembers.
 *
 * Session-scoped, not Activity-scoped, exactly like [hu.orszembejelento.service.servicearea.ui.ServiceAreaAdminListViewModel] -
 * [hu.orszembejelento.service.nav.ServiceNavHost] constructs this against its own
 * session-lifetime `ViewModelStoreOwner`, so a logout clears it and the next signed-in user on
 * the same device starts from a fresh instance (brief §47), never a stale filter or a
 * previous user's aggregates.
 */
class AnalyticsViewModel(
    private val repository: AnalyticsRepository,
    private val onSessionEnded: () -> Unit,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val summary: AnalyticsSummaryResponse? = null,
        val areaOptions: AnalyticsAreaOptionsResponse? = null,
        val filter: AnalyticsFilter = AnalyticsFilter(),
        val error: ApiResult<Nothing>? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    // Cancel any refresh already in flight before starting a new one, so a slower, stale
    // response can never overwrite a fresher one - matters more now that a resumed navigation
    // can trigger a refresh close to the ViewModel's own init-time load (§4).
    private var refreshJob: Job? = null

    init {
        refresh()
    }

    /** The screen's reload path (brief §35/§49, §4) - opening it fresh, an explicit pull, an explicit filter change, or returning to this tab. */
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }

            val areasResult = repository.areaOptions()
            if (areasResult is ApiResult.SessionEnded) {
                onSessionEnded()
                _state.update { it.copy(loading = false) }
                return@launch
            }

            val summaryResult = repository.summary(_state.value.filter)
            if (summaryResult is ApiResult.SessionEnded) {
                onSessionEnded()
                _state.update { it.copy(loading = false) }
                return@launch
            }

            _state.update { current ->
                // The summary call is the one the screen's own KPIs/charts depend on, so it
                // drives the visible error state; a failed area-options call still leaves the
                // filter sheet functional with a stale-but-present area list rather than
                // blocking the whole screen on a secondary, less critical call. A failed
                // summary keeps whatever was previously shown rather than blanking it - the
                // error banner/state itself is what tells the user this refresh didn't land.
                @Suppress("UNCHECKED_CAST")
                current.copy(
                    loading = false,
                    summary = (summaryResult as? ApiResult.Success)?.value ?: current.summary,
                    areaOptions = (areasResult as? ApiResult.Success)?.value ?: current.areaOptions,
                    error = if (summaryResult is ApiResult.Success) null else summaryResult as ApiResult<Nothing>,
                )
            }
        }
    }

    fun updateFilter(filter: AnalyticsFilter) {
        _state.update { it.copy(filter = filter) }
        refresh()
    }
}
