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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import hu.orszembejelento.service.servicearea.data.RailwayLineSettlementAssignmentResponse
import hu.orszembejelento.service.servicearea.domain.LineRowStatus
import hu.orszembejelento.service.servicearea.domain.canOfferWholeLineAssign
import hu.orszembejelento.service.servicearea.domain.lineRowStatus
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
                FilterChip(
                    selected = state.filter.assignment == RailwayLineAssignmentFilter.PER_SETTLEMENT,
                    onClick = { viewModel.updateFilter(state.filter.copy(assignment = RailwayLineAssignmentFilter.PER_SETTLEMENT)) },
                    label = { Text(stringResource(R.string.filter_assignment_per_settlement)) },
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
                        expanded = line.id in state.expandedLineIds,
                        detail = state.details[line.id],
                        onSelect = { pendingLine = line },
                        onToggleDetails = { viewModel.toggleDetails(line.id) },
                        onRetryDetails = { viewModel.retryDetails(line.id) },
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
    expanded: Boolean,
    detail: RailwayLinePickerViewModel.LineDetailState?,
    onSelect: () -> Unit,
    onToggleDetails: () -> Unit,
    onRetryDetails: () -> Unit,
) {
    val status = lineRowStatus(line, targetAreaId)
    // The legacy whole-line assign/move is offered ONLY where it can succeed: never for a
    // pair-configured line (ADR 0011), an inactive one, or the line's current area. Nothing
    // about this row is clickable otherwise - the settlement detail has its own control.
    val selectable = enabled && canOfferWholeLineAssign(line, targetAreaId)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Stacked, not a side-by-side Row: an unweighted status label ("Jelenleg itt: <a real,
            // potentially long area name>") would otherwise claim its own full width first and
            // squeeze the weighted name column into whatever is left over.
            Column(
                modifier = Modifier.fillMaxWidth().let { if (selectable) it.clickable(onClick = onSelect) else it },
            ) {
                Text(line.displayName, style = MaterialTheme.typography.bodyMedium)
                Text(line.lineCode, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // The status is always spelled out in words, never conveyed by colour alone.
                Text(
                    text = when (status) {
                        LineRowStatus.INACTIVE -> stringResource(R.string.railway_line_inactive_reference)
                        LineRowStatus.ALREADY_IN_THIS_AREA -> stringResource(R.string.railway_line_currently_in_this_area)
                        LineRowStatus.IN_OTHER_AREA -> stringResource(R.string.railway_line_currently_in_area, line.currentServiceAreaName.orEmpty())
                        LineRowStatus.PER_SETTLEMENT -> stringResource(R.string.railway_line_per_settlement)
                        LineRowStatus.UNASSIGNED -> stringResource(R.string.railway_line_unassigned)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (status == LineRowStatus.PER_SETTLEMENT) {
                    Text(
                        stringResource(R.string.railway_line_per_settlement_count, line.settlementMappingCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.railway_line_per_settlement_hint),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            val stateWord = stringResource(if (expanded) R.string.line_details_state_open else R.string.line_details_state_closed)
            val toggleDescription = if (status == LineRowStatus.PER_SETTLEMENT) {
                stringResource(R.string.cd_line_details_toggle_count, line.displayName, line.settlementMappingCount, stateWord)
            } else {
                stringResource(R.string.cd_line_details_toggle, line.displayName, stateWord)
            }
            TextButton(
                onClick = onToggleDetails,
                modifier = Modifier.semantics {
                    contentDescription = toggleDescription
                    stateDescription = stateWord
                },
            ) {
                Text(stringResource(if (expanded) R.string.line_details_close else R.string.line_details_open))
            }

            if (expanded) {
                LineSettlementDetails(detail = detail, onRetry = onRetryDetails)
            }
        }
    }
}

/**
 * The line's currently verified settlements and where each routes. States no coverage claim:
 * the title and the note say the list is what the *current* verified data names and grows with
 * later imports. Shows only names and ServiceArea names - no routing reason, evidence, revision,
 * KSH or internal id.
 */
@Composable
private fun LineSettlementDetails(detail: RailwayLinePickerViewModel.LineDetailState?, onRetry: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            stringResource(R.string.line_settlements_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.line_settlements_partial_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        when (detail) {
            null, RailwayLinePickerViewModel.LineDetailState.Loading ->
                Text(stringResource(R.string.line_settlements_loading), style = MaterialTheme.typography.bodySmall)
            is RailwayLinePickerViewModel.LineDetailState.Failed -> {
                InlineErrorBanner(apiErrorMessage(detail.error))
                TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
            is RailwayLinePickerViewModel.LineDetailState.Loaded -> {
                val mappings = detail.mappings
                if (mappings.items.isEmpty()) {
                    Text(stringResource(R.string.line_settlements_empty), style = MaterialTheme.typography.bodySmall)
                } else {
                    // A bounded, scrollable window: a long list scrolls inside the card instead of
                    // pushing the rest of the picker off screen. heightIn (not a fixed height) so a
                    // short list stays short and a large system font still gets the room it needs.
                    Column(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                        mappings.items.forEach { item -> LineSettlementRow(item) }
                    }
                }
                if (mappings.truncated) {
                    Text(
                        stringResource(R.string.line_settlements_truncated, mappings.items.size, mappings.settlementCount),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LineSettlementRow(item: RailwayLineSettlementAssignmentResponse) {
    val areaText = when {
        item.serviceAreaName == null -> stringResource(R.string.line_settlement_no_area)
        item.serviceAreaActive == false -> stringResource(R.string.line_settlement_area_inactive, item.serviceAreaName)
        else -> stringResource(R.string.line_settlement_area, item.serviceAreaName)
    }
    val title = buildString {
        append(item.settlementName)
        item.countyName?.takeIf { it.isNotBlank() }?.let { append(" ($it)") }
    }
    val suffix = if (item.settlementActive) "" else " " + stringResource(R.string.line_settlement_inactive_suffix)
    // One merged accessibility node per settlement: TalkBack reads "<name>, <area>" as a unit.
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) {}) {
        Text(title + suffix, style = MaterialTheme.typography.bodyMedium)
        Text(areaText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
