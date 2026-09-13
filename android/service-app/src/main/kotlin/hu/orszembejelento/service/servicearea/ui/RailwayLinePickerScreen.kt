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
import hu.orszembejelento.service.common.ui.apiErrorMessage
import hu.orszembejelento.service.servicearea.data.RailwayLineAdminListItemResponse
import hu.orszembejelento.service.servicearea.data.RailwayLineAssignmentFilter
import kotlinx.coroutines.delay

/**
 * `Vasútvonal kiválasztása` (brief §50): a plain, unassigned line is assigned directly; a
 * line already assigned to a *different* area always shows the explicit move confirmation
 * first (brief §8/§50 - "never silently steal/reassign it"); a line assigned to *this* area
 * or an inactive reference line is shown but not selectable, so the four states brief §50
 * asks for stay visually distinct at a glance rather than only discoverable by tapping.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RailwayLinePickerScreen(
    viewModel: RailwayLinePickerViewModel,
    targetAreaId: String,
    targetAreaName: String,
    onBack: () -> Unit,
    onAssigned: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var searchText by remember { mutableStateOf(state.filter.query.orEmpty()) }
    var pendingLine by remember { mutableStateOf<RailwayLineAdminListItemResponse?>(null) }

    LaunchedEffect(searchText) {
        delay(400)
        if (searchText != state.filter.query.orEmpty()) {
            viewModel.updateFilter(state.filter.copy(query = searchText.ifBlank { null }))
        }
    }
    LaunchedEffect(state.assigned) {
        if (state.assigned) onAssigned()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_description_back))
            }
            Text(stringResource(R.string.railway_line_picker_title), style = MaterialTheme.typography.titleLarge)
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                placeholder = { Text(stringResource(R.string.railway_line_search_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            // FlowRow, not Row: "Hozzárendelés nélküli" is long enough that a plain Row would
            // squeeze the last chip into whatever width the others left over, wrapping its
            // own label narrowly (the same class of bug the Phase 9 correction pass fixed for
            // metadata chips) - wrapping the whole row to a second line instead keeps every
            // chip's own label on one line.
            FlowRow(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.filter.assignment == RailwayLineAssignmentFilter.ALL,
                    onClick = { viewModel.updateFilter(state.filter.copy(assignment = RailwayLineAssignmentFilter.ALL)) },
                    label = { Text(stringResource(R.string.filter_assignment_all)) },
                )
                FilterChip(
                    selected = state.filter.assignment == RailwayLineAssignmentFilter.UNASSIGNED,
                    onClick = { viewModel.updateFilter(state.filter.copy(assignment = RailwayLineAssignmentFilter.UNASSIGNED)) },
                    label = { Text(stringResource(R.string.filter_assignment_unassigned)) },
                )
                FilterChip(
                    selected = state.filter.assignment == RailwayLineAssignmentFilter.ASSIGNED,
                    onClick = { viewModel.updateFilter(state.filter.copy(assignment = RailwayLineAssignmentFilter.ASSIGNED)) },
                    label = { Text(stringResource(R.string.filter_assignment_assigned)) },
                )
            }
            if (state.assignError != null) {
                InlineErrorBanner(apiErrorMessage(state.assignError!!))
            }
        }

        when {
            state.loading -> FullScreenLoading()
            state.loadError != null -> ErrorState(message = apiErrorMessage(state.loadError!!), onRetry = viewModel::refresh)
            state.items.isEmpty() -> EmptyState(stringResource(R.string.railway_line_empty))
            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.items, key = { it.id }) { line ->
                    RailwayLinePickerRow(
                        line = line,
                        targetAreaId = targetAreaId,
                        enabled = !state.assigning,
                        onSelect = { pendingLine = line },
                    )
                }
                if (state.canLoadMore) {
                    item { LoadMoreButton(loading = state.loadingMore, onClick = viewModel::loadMore) }
                }
            }
        }
    }

    pendingLine?.let { line ->
        val isMove = line.currentServiceAreaId != null
        AlertDialog(
            onDismissRequest = { pendingLine = null },
            title = { Text(stringResource(if (isMove) R.string.move_line_dialog_title else R.string.assign_line_dialog_title)) },
            text = {
                Column {
                    Text(
                        if (isMove) {
                            stringResource(R.string.move_line_dialog_text, line.currentServiceAreaName.orEmpty(), targetAreaName)
                        } else {
                            stringResource(R.string.assign_line_dialog_text, targetAreaName)
                        },
                    )
                    Text(stringResource(R.string.line_future_only_notice), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.confirmAssign(line); pendingLine = null }) {
                    Text(stringResource(if (isMove) R.string.action_move else R.string.action_apply))
                }
            },
            dismissButton = { TextButton(onClick = { pendingLine = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun RailwayLinePickerRow(
    line: RailwayLineAdminListItemResponse,
    targetAreaId: String,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    val alreadyHere = line.currentServiceAreaId == targetAreaId
    // Assigning/moving an inactive RailwayLine would create a NEW mapping for it, which the
    // backend never allows (brief §24) - only an existing legacy mapping may be cleared, and
    // that happens from the area detail's own unassign action, never from this picker.
    val selectable = enabled && line.active && !alreadyHere

    Card(
        modifier = Modifier.fillMaxWidth().let { if (selectable) it.clickable(onClick = onSelect) else it },
    ) {
        // Stacked, not a side-by-side Row: an unweighted status label ("Jelenleg itt: <a real,
        // potentially long area name>") would otherwise claim its own full width first and
        // squeeze the weighted name column into whatever is left over - the same class of
        // layout squeeze the Phase 9 correction pass fixed for long metadata.
        Column(modifier = Modifier.padding(16.dp)) {
            Text(line.displayName, style = MaterialTheme.typography.bodyMedium)
            Text(line.lineCode, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = when {
                    !line.active -> stringResource(R.string.railway_line_inactive_reference)
                    alreadyHere -> stringResource(R.string.railway_line_currently_in_this_area)
                    line.currentServiceAreaId != null -> stringResource(R.string.railway_line_currently_in_area, line.currentServiceAreaName.orEmpty())
                    else -> stringResource(R.string.railway_line_unassigned)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
