package hu.orszembejelento.service.moderation.ui

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
import hu.orszembejelento.service.common.ui.FullScreenLoading
import hu.orszembejelento.service.common.ui.apiErrorMessage
import hu.orszembejelento.service.moderation.data.DeletedReportFilter
import hu.orszembejelento.service.reports.ui.AreaChoice
import kotlinx.coroutines.delay

/** `Törölt bejelentések` (brief §18/§44-45) — the shared deleted-report list, reachable from both Moderáció and Adminisztráció. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeletedReportsListScreen(
    viewModel: DeletedReportsListViewModel,
    onOpenReport: (String) -> Unit,
    areaChoices: List<AreaChoice>,
) {
    val state by viewModel.state.collectAsState()
    var searchText by remember { mutableStateOf(state.filter.query.orEmpty()) }
    var showFilters by remember { mutableStateOf(false) }

    LaunchedEffect(searchText) {
        delay(400)
        if (searchText != state.filter.query.orEmpty()) {
            viewModel.updateFilter(state.filter.copy(query = searchText.ifBlank { null }))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(stringResource(R.string.deleted_reports_title), style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                placeholder = { Text(stringResource(R.string.search_hint_reports)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.content_description_search)) },
                trailingIcon = {
                    androidx.compose.foundation.layout.Row {
                        IconButton(onClick = { showFilters = true }) {
                            BadgedBox(badge = {
                                if (state.filter.activeFacetCount > 0) Badge { Text(state.filter.activeFacetCount.toString()) }
                            }) {
                                Icon(Icons.Filled.FilterList, contentDescription = stringResource(R.string.action_filters))
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

        if (showFilters) {
            DeletedReportFilterSheet(
                current = state.filter,
                areaChoices = areaChoices,
                onApply = { newFilter -> showFilters = false; viewModel.updateFilter(newFilter) },
                onDismiss = { showFilters = false },
            )
        }

        when {
            state.loading -> FullScreenLoading()
            state.error != null -> ErrorState(message = apiErrorMessage(state.error!!), onRetry = viewModel::refresh)
            state.items.isEmpty() -> hu.orszembejelento.service.common.ui.EmptyState(stringResource(R.string.deleted_reports_empty))
            else -> DeletedReportList(
                items = state.items,
                canLoadMore = state.canLoadMore,
                loadingMore = state.loadingMore,
                onItemClick = onOpenReport,
                onLoadMore = viewModel::loadMore,
            )
        }
    }
}
