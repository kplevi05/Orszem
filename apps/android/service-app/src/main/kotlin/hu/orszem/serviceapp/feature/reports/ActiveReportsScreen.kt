package hu.orszem.serviceapp.feature.reports

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.orszem.core.designsystem.LoadingState
import hu.orszem.core.designsystem.MessageState
import hu.orszem.serviceapp.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveReportsScreen(
    modifier: Modifier = Modifier,
    onOpenReport: (String) -> Unit,
    viewModel: ActiveReportsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }

    // Returning from the detail screen (accept / archive / delete) re-enters this
    // composition; reload so tabs and counts reflect what just happened. The very
    // first composition is already covered by the ViewModel's init.
    var firstComposition by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        if (firstComposition) firstComposition = false else viewModel.refresh()
    }

    // A failed load never blanks the screen when there is data (§F): the error is
    // surfaced as a retryable snackbar instead.
    val errorTexts = mapOf(
        ListErrorReason.NETWORK to stringResource(R.string.error_network),
        ListErrorReason.TIMEOUT to stringResource(R.string.error_timeout),
        ListErrorReason.SERVER to stringResource(R.string.error_server),
        ListErrorReason.UNAUTHORIZED to stringResource(R.string.session_expired),
        ListErrorReason.GENERIC to stringResource(R.string.error_generic),
    )
    val retryLabel = stringResource(R.string.action_retry)
    LaunchedEffect(state.error, state.hasContent) {
        val reason = state.error ?: return@LaunchedEffect
        if (!state.hasContent) return@LaunchedEffect
        val result = snackbar.showSnackbar(
            message = errorTexts.getValue(reason),
            actionLabel = retryLabel,
            duration = androidx.compose.material3.SnackbarDuration.Long,
        )
        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) viewModel.refresh()
        viewModel.dismissError()
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    stringResource(R.string.tab_reports) + "  " +
                        stringResource(R.string.reports_active_count, state.newCount + state.inProgressCount),
                )
            },
            actions = {
                BadgedBox(
                    badge = {
                        if (state.filtersActive) {
                            Badge { Text(state.filters.activeCount.toString()) }
                        }
                    },
                ) {
                    IconButton(onClick = { sheetOpen = true }) {
                        Icon(Icons.Filled.FilterList, contentDescription = stringResource(R.string.filter_open))
                    }
                }
            },
        )

        // NEW and IN_PROGRESS are separate tabs; counts respect the active filters.
        TabRow(selectedTabIndex = state.tab.ordinal) {
            Tab(
                selected = state.tab == ReportTab.NEW,
                onClick = { viewModel.onTabSelected(ReportTab.NEW) },
                text = { Text(stringResource(R.string.reports_tab_new, state.filteredNewCount)) },
            )
            Tab(
                selected = state.tab == ReportTab.IN_PROGRESS,
                onClick = { viewModel.onTabSelected(ReportTab.IN_PROGRESS) },
                text = { Text(stringResource(R.string.reports_tab_in_progress, state.filteredInProgressCount)) },
            )
        }

        Box(Modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state.loading -> LoadingState()
                    state.error != null && !state.hasContent -> MessageState(
                        title = errorTexts.getValue(state.error!!),
                        actionLabel = retryLabel,
                        onAction = viewModel::refresh,
                    )
                    state.visible.isEmpty() -> MessageState(
                        title = when {
                            state.emptyDueToFilters -> stringResource(R.string.reports_empty_filtered)
                            state.tab == ReportTab.NEW -> stringResource(R.string.reports_empty_new)
                            else -> stringResource(R.string.reports_empty_in_progress)
                        },
                        actionLabel = stringResource(R.string.filter_clear).takeIf { state.emptyDueToFilters },
                        onAction = viewModel::clearFilters,
                    )
                    else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        if (state.filtersActive) {
                            item {
                                Text(
                                    stringResource(R.string.reports_match_count, state.visible.size),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                            }
                        }
                        items(state.visible, key = { it.id }) { report ->
                            ReportCard(report = report, onClick = { onOpenReport(report.id) })
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }

    if (sheetOpen) {
        ReportFilterSheet(
            filters = state.filters,
            sort = state.sort,
            options = state.options,
            matchCount = state.visible.size,
            onFiltersChanged = viewModel::onFiltersChanged,
            onSortChanged = viewModel::onSortSelected,
            onClear = viewModel::clearFilters,
            onDismiss = { sheetOpen = false },
        )
    }
}
