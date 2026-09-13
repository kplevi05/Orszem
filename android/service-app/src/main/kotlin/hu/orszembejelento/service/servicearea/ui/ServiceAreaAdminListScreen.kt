package hu.orszembejelento.service.servicearea.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import hu.orszembejelento.service.common.ui.EmptyState
import hu.orszembejelento.service.common.ui.ErrorState
import hu.orszembejelento.service.common.ui.FullScreenLoading
import hu.orszembejelento.service.common.ui.LoadMoreButton
import hu.orszembejelento.service.common.ui.apiErrorMessage
import hu.orszembejelento.service.servicearea.data.ServiceAreaAdminListFilter
import hu.orszembejelento.service.servicearea.data.ServiceAreaAdminListItemResponse
import kotlinx.coroutines.delay

/**
 * `Szolgálati területek` (brief §46) - the live Adminisztráció entry that replaces the former
 * "Még nem elérhető" placeholder. Search, an Aktív/Inaktív/Összes filter, area cards (name,
 * status, mapped-line count, open-report blocker count when >0), and the create action - never
 * a raw `adminVersion` shown anywhere (brief §46/§67).
 */
@Composable
fun ServiceAreaAdminListScreen(
    viewModel: ServiceAreaAdminListViewModel,
    onOpenArea: (String) -> Unit,
    onCreateArea: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var searchText by remember { mutableStateOf(state.filter.query.orEmpty()) }

    LaunchedEffect(searchText) {
        delay(400)
        if (searchText != state.filter.query.orEmpty()) {
            viewModel.updateFilter(state.filter.copy(query = searchText.ifBlank { null }))
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateArea) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_create_area))
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(stringResource(R.string.areas_admin_title), style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    placeholder = { Text(stringResource(R.string.service_area_search_hint)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.content_description_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(modifier = Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.filter.active == null,
                        onClick = { viewModel.updateFilter(state.filter.copy(active = null)) },
                        label = { Text(stringResource(R.string.filter_status_all)) },
                    )
                    FilterChip(
                        selected = state.filter.active == true,
                        onClick = { viewModel.updateFilter(state.filter.copy(active = true)) },
                        label = { Text(stringResource(R.string.filter_status_active)) },
                    )
                    FilterChip(
                        selected = state.filter.active == false,
                        onClick = { viewModel.updateFilter(state.filter.copy(active = false)) },
                        label = { Text(stringResource(R.string.filter_status_inactive)) },
                    )
                }
            }

            when {
                state.loading -> FullScreenLoading()
                state.error != null -> ErrorState(message = apiErrorMessage(state.error!!), onRetry = viewModel::refresh)
                state.items.isEmpty() -> EmptyState(stringResource(R.string.service_area_empty))
                else -> ServiceAreaAdminList(
                    items = state.items,
                    canLoadMore = state.canLoadMore,
                    loadingMore = state.loadingMore,
                    onItemClick = onOpenArea,
                    onLoadMore = viewModel::loadMore,
                )
            }
        }
    }
}

@Composable
private fun ServiceAreaAdminList(
    items: List<ServiceAreaAdminListItemResponse>,
    canLoadMore: Boolean,
    loadingMore: Boolean,
    onItemClick: (String) -> Unit,
    onLoadMore: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items, key = { it.id }) { area ->
            ServiceAreaAdminCard(area = area, onClick = { onItemClick(area.id) })
        }
        if (canLoadMore) {
            item { LoadMoreButton(loading = loadingMore, onClick = onLoadMore) }
        }
    }
}

@Composable
private fun ServiceAreaAdminCard(area: ServiceAreaAdminListItemResponse, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(area.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    stringResource(if (area.active) R.string.service_area_status_active else R.string.service_area_status_inactive),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (area.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.area_card_line_count, area.mappedRailwayLineCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (area.openOperationalReportCount > 0) {
                Text(
                    stringResource(R.string.area_card_open_report_count, area.openOperationalReportCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
