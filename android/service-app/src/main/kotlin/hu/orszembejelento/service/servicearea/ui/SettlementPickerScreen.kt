package hu.orszembejelento.service.servicearea.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.orszembejelento.service.R
import hu.orszembejelento.service.common.ui.EmptyState
import hu.orszembejelento.service.common.ui.ErrorState
import hu.orszembejelento.service.common.ui.FullScreenLoading
import hu.orszembejelento.service.common.ui.InlineErrorBanner
import hu.orszembejelento.service.common.ui.LoadMoreButton
import hu.orszembejelento.service.common.ui.SettlementDataSourceNote
import hu.orszembejelento.service.common.ui.apiErrorMessage
import hu.orszembejelento.service.servicearea.data.SettlementAdminListItemResponse
import hu.orszembejelento.service.servicearea.data.SettlementAssignmentFilter
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettlementPickerScreen(
    viewModel: SettlementPickerViewModel,
    targetAreaId: String,
    targetAreaName: String,
    onBack: () -> Unit,
    onChanged: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var searchText by remember { mutableStateOf(state.filter.query.orEmpty()) }
    var pendingMoveSettlement by remember { mutableStateOf<SettlementAdminListItemResponse?>(null) }

    LaunchedEffect(searchText) {
        delay(350)
        if (searchText != state.filter.query.orEmpty()) {
            viewModel.updateFilter(state.filter.copy(query = searchText.ifBlank { null }))
        }
    }
    LaunchedEffect(state.anyAssigned) {
        if (state.anyAssigned) onChanged()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_description_back))
            }
            Text(stringResource(R.string.settlement_picker_title), style = MaterialTheme.typography.titleLarge)
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                placeholder = { Text(stringResource(R.string.settlement_picker_search_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SettlementDataSourceNote(color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            FlowRow(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.filter.assignment == SettlementAssignmentFilter.ALL,
                    onClick = { viewModel.updateFilter(state.filter.copy(assignment = SettlementAssignmentFilter.ALL)) },
                    label = { Text(stringResource(R.string.filter_assignment_all)) },
                )
                FilterChip(
                    selected = state.filter.assignment == SettlementAssignmentFilter.UNASSIGNED,
                    onClick = { viewModel.updateFilter(state.filter.copy(assignment = SettlementAssignmentFilter.UNASSIGNED)) },
                    label = { Text(stringResource(R.string.filter_assignment_unassigned)) },
                )
                FilterChip(
                    selected = state.filter.assignment == SettlementAssignmentFilter.ASSIGNED,
                    onClick = { viewModel.updateFilter(state.filter.copy(assignment = SettlementAssignmentFilter.ASSIGNED)) },
                    label = { Text(stringResource(R.string.filter_assignment_assigned_settlement)) },
                )
            }
            if (state.assignError != null) {
                InlineErrorBanner(apiErrorMessage(state.assignError!!))
            }
        }

        when {
            state.loading -> FullScreenLoading()
            state.loadError != null -> ErrorState(message = apiErrorMessage(state.loadError!!), onRetry = viewModel::refresh)
            state.items.isEmpty() -> EmptyState(stringResource(R.string.settlement_picker_empty))
            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.items, key = { it.id }) { settlement ->
                    SettlementPickerRow(
                        settlement = settlement,
                        targetAreaId = targetAreaId,
                        enabled = !state.assigning,
                        onSelect = {
                            if (settlement.currentServiceAreaId != null && settlement.currentServiceAreaId != targetAreaId) {
                                pendingMoveSettlement = settlement
                            } else {
                                viewModel.confirmAssign(settlement)
                            }
                        },
                    )
                }
                if (state.canLoadMore) {
                    item { LoadMoreButton(loading = state.loadingMore, onClick = viewModel::loadMore) }
                }
            }
        }
    }

    pendingMoveSettlement?.let { settlement ->
        AlertDialog(
            onDismissRequest = { pendingMoveSettlement = null },
            title = { Text(stringResource(R.string.move_settlement_dialog_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.move_settlement_dialog_text,
                        settlement.name,
                        settlement.currentServiceAreaName.orEmpty(),
                        targetAreaName,
                    ),
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.confirmAssign(settlement); pendingMoveSettlement = null }) {
                    Text(stringResource(R.string.action_move))
                }
            },
            dismissButton = { TextButton(onClick = { pendingMoveSettlement = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun SettlementPickerRow(
    settlement: SettlementAdminListItemResponse,
    targetAreaId: String,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    val alreadyHere = settlement.currentServiceAreaId == targetAreaId
    val selectable = enabled && settlement.active && !alreadyHere
    val title = buildString {
        append(settlement.name)
        settlement.countyName?.takeIf { it.isNotBlank() }?.let { append(" ($it)") }
    }
    val statusText = when {
        !settlement.active -> stringResource(R.string.line_settlement_inactive_suffix)
        alreadyHere -> stringResource(R.string.railway_line_currently_in_this_area)
        settlement.currentServiceAreaId != null -> stringResource(R.string.railway_line_currently_in_area, settlement.currentServiceAreaName.orEmpty())
        else -> stringResource(R.string.line_settlement_no_area)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .let { if (selectable) it.clickable(onClick = onSelect) else it }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (alreadyHere) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (selectable) {
                TextButton(onClick = onSelect, enabled = enabled) {
                    Text(
                        stringResource(
                            if (settlement.currentServiceAreaId != null) R.string.action_move else R.string.action_add_settlement_short,
                        ),
                    )
                }
            }
        }
    }
}
