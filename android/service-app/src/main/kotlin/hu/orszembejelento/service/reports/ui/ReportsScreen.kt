package hu.orszembejelento.service.reports.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.orszembejelento.service.R
import hu.orszembejelento.service.common.ui.ErrorState
import hu.orszembejelento.service.common.ui.EmptyState
import hu.orszembejelento.service.common.ui.FullScreenLoading
import hu.orszembejelento.service.common.ui.apiErrorMessage
import hu.orszembejelento.service.reports.data.CatalogRepository
import hu.orszembejelento.service.reports.data.ReportFilter
import kotlinx.coroutines.delay

/**
 * `Bejelentések` (brief §15): exactly two operational tabs, Új and Folyamatban - Archive is
 * its own bottom-nav destination, never a third tab here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    role: String,
    newViewModel: ReportQueueViewModel,
    inProgressViewModel: ReportQueueViewModel,
    onOpenReport: (String) -> Unit,
    tabIndex: Int,
    onTabChange: (Int) -> Unit,
    catalogRepository: CatalogRepository,
    areaChoices: List<AreaChoice>,
    onAreaFilterChanged: (String?) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(text = stringResource(R.string.reports_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                text = stringResource(R.string.reports_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SecondaryTabRow(selectedTabIndex = tabIndex) {
            Tab(selected = tabIndex == 0, onClick = { onTabChange(0) }, text = { Text(stringResource(R.string.tab_new)) })
            Tab(selected = tabIndex == 1, onClick = { onTabChange(1) }, text = { Text(stringResource(R.string.tab_in_progress)) })
        }

        if (tabIndex == 0) {
            ReportQueueBody(
                viewModel = newViewModel,
                onOpenReport = onOpenReport,
                emptyMessage = stringResource(R.string.empty_new_queue),
                bucketed = true,
                catalogRepository = catalogRepository,
                areaChoices = areaChoices,
                onAreaFilterChanged = onAreaFilterChanged,
            )
        } else {
            ReportQueueBody(
                viewModel = inProgressViewModel,
                onOpenReport = onOpenReport,
                emptyMessage = stringResource(R.string.empty_in_progress_queue),
                bucketed = false,
                hint = stringResource(
                    if (role == "SERVICE_USER") R.string.in_progress_hint_service else R.string.in_progress_hint_supervisor,
                ),
                catalogRepository = catalogRepository,
                areaChoices = areaChoices,
                onAreaFilterChanged = onAreaFilterChanged,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReportQueueBody(
    viewModel: ReportQueueViewModel,
    onOpenReport: (String) -> Unit,
    emptyMessage: String,
    bucketed: Boolean,
    hint: String? = null,
    catalogRepository: CatalogRepository? = null,
    areaChoices: List<AreaChoice> = emptyList(),
    onAreaFilterChanged: (String?) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    var searchText by remember { mutableStateOf(state.filter.query.orEmpty()) }
    var showFilters by remember { mutableStateOf(false) }

    // Debounced server-side search (brief §41) - fires the network request only after the
    // user has paused typing, never once per keystroke.
    LaunchedEffect(searchText) {
        delay(400)
        if (searchText != state.filter.query.orEmpty()) {
            viewModel.updateFilter(state.filter.copy(query = searchText.ifBlank { null }))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            hint?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                placeholder = { Text(stringResource(R.string.search_hint_reports)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.content_description_search)) },
                trailingIcon = {
                    androidx.compose.foundation.layout.Row {
                        if (catalogRepository != null) {
                            IconButton(onClick = { showFilters = true }) {
                                BadgedBox(badge = {
                                    if (state.filter.activeFacetCount > 0) Badge { Text(state.filter.activeFacetCount.toString()) }
                                }) {
                                    Icon(Icons.Filled.FilterList, contentDescription = stringResource(R.string.action_filters))
                                }
                            }
                        }
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }

        if (showFilters && catalogRepository != null) {
            ReportFilterSheet(
                current = state.filter,
                areaChoices = areaChoices,
                catalogRepository = catalogRepository,
                onApply = { newFilter ->
                    showFilters = false
                    if (newFilter.areaId != state.filter.areaId) onAreaFilterChanged(newFilter.areaId)
                    viewModel.updateFilter(newFilter)
                },
                onDismiss = { showFilters = false },
            )
        }

        when {
            state.loading -> FullScreenLoading()
            state.error != null -> ErrorState(message = apiErrorMessage(state.error!!), onRetry = viewModel::refresh)
            state.items.isEmpty() -> EmptyState(emptyMessage)
            else -> {
                val dividerIndex = if (bucketed) state.items.indexOfFirst { it.ageBucket == "OLDER" }.takeIf { it >= 0 } else null
                ReportList(
                    items = state.items,
                    canLoadMore = state.canLoadMore,
                    loadingMore = state.loadingMore,
                    onItemClick = onOpenReport,
                    onLoadMore = viewModel::loadMore,
                    dividerBeforeIndex = dividerIndex,
                )
            }
        }
    }
}

/** Reused as-is by the Archive screen too - identical body, different backend endpoint and empty copy. */
