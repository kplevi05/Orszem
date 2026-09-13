package hu.orszembejelento.service.servicearea.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import hu.orszembejelento.service.common.ui.ConfirmDialog
import hu.orszembejelento.service.common.ui.ErrorState
import hu.orszembejelento.service.common.ui.FullScreenLoading
import hu.orszembejelento.service.common.ui.InlineErrorBanner
import hu.orszembejelento.service.common.ui.apiErrorMessage
import hu.orszembejelento.service.servicearea.data.MappedRailwayLineResponse
import hu.orszembejelento.service.servicearea.data.ServiceAreaAdminDetailResponse

/**
 * `Szolgálati terület` detail (brief §48-49/§53-56/§60).
 *
 * Never shows `adminVersion`, internal DB metadata, or a routing/lock detail anywhere (brief
 * §48/§67/§6). Deactivation's own blockers are explained in plain Hungarian both as a
 * disabled-button hint (brief §53) and, since the backend stays authoritative and displayed
 * counts can go stale, as the exact same copy if the backend rejects anyway (brief §53/§58).
 * There is no report-reroute action anywhere on this screen (brief §63), and user-area
 * assignments are never edited here (brief §61) - only a read-only mapped-line list and the
 * area's own lifecycle actions.
 */
@Composable
fun ServiceAreaDetailScreen(viewModel: ServiceAreaDetailViewModel, onBack: () -> Unit, onAddRailwayLine: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var showRenameDialog by remember { mutableStateOf(false) }
    var showActivateDialog by remember { mutableStateOf(false) }
    var showDeactivateDialog by remember { mutableStateOf(false) }
    var confirmUnassignLine by remember { mutableStateOf<MappedRailwayLineResponse?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_description_back))
            }
            Text(stringResource(R.string.service_area_detail_title), style = MaterialTheme.typography.titleLarge)
        }

        when {
            state.loading && state.detail == null -> FullScreenLoading()
            state.loadError != null && state.detail == null -> ErrorState(message = apiErrorMessage(state.loadError!!), onRetry = viewModel::refresh)
            state.detail == null -> Unit
            else -> {
                val detail = state.detail!!
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(detail.name, style = MaterialTheme.typography.titleLarge)
                            Text(
                                stringResource(if (detail.active) R.string.service_area_status_active else R.string.service_area_status_inactive),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (detail.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { showRenameDialog = true }) { Text(stringResource(R.string.action_rename_area)) }
                    }

                    if (state.mutationError != null) {
                        InlineErrorBanner(apiErrorMessage(state.mutationError!!))
                    }

                    RailwayLinesSection(
                        lines = detail.mappedRailwayLines,
                        mutationInFlight = state.mutationInFlight,
                        onAdd = onAddRailwayLine,
                        onUnassign = { confirmUnassignLine = it },
                    )

                    LifecycleSection(
                        detail = detail,
                        mutationInFlight = state.mutationInFlight,
                        onActivate = { showActivateDialog = true },
                        onDeactivate = { showDeactivateDialog = true },
                    )

                    Spacer(modifier = Modifier.padding(bottom = 24.dp))
                }
            }
        }
    }

    if (showRenameDialog) {
        RenameAreaDialog(
            currentName = state.detail?.name.orEmpty(),
            onConfirm = { name -> showRenameDialog = false; viewModel.rename(name) },
            onDismiss = { showRenameDialog = false },
        )
    }
    if (showActivateDialog) {
        ConfirmDialog(
            title = stringResource(R.string.action_activate_area),
            text = stringResource(R.string.activate_area_dialog_text),
            confirmLabel = stringResource(R.string.action_activate),
            onConfirm = { showActivateDialog = false; viewModel.activate() },
            onDismiss = { showActivateDialog = false },
        )
    }
    if (showDeactivateDialog) {
        ConfirmDialog(
            title = stringResource(R.string.deactivate_area_dialog_title),
            text = stringResource(R.string.deactivate_area_dialog_text),
            confirmLabel = stringResource(R.string.action_deactivate),
            dangerous = true,
            onConfirm = { showDeactivateDialog = false; viewModel.deactivate() },
            onDismiss = { showDeactivateDialog = false },
        )
    }
    confirmUnassignLine?.let { line ->
        ConfirmDialog(
            title = stringResource(R.string.unassign_line_dialog_title),
            text = stringResource(R.string.unassign_line_warning) + "\n\n" + stringResource(R.string.line_future_only_notice),
            confirmLabel = stringResource(R.string.action_unassign_line),
            dangerous = true,
            onConfirm = { confirmUnassignLine = null; viewModel.unassignLine(line.id) },
            onDismiss = { confirmUnassignLine = null },
        )
    }
}

@Composable
private fun RailwayLinesSection(
    lines: List<MappedRailwayLineResponse>,
    mutationInFlight: Boolean,
    onAdd: () -> Unit,
    onUnassign: (MappedRailwayLineResponse) -> Unit,
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.railway_lines_section_title), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = onAdd) { Text(stringResource(R.string.action_add_railway_line)) }
        }
        if (lines.isEmpty()) {
            Text(
                stringResource(R.string.railway_lines_section_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(modifier = Modifier.padding(4.dp)) {
                    lines.forEach { line ->
                        // A stacked layout, not a single squeezed Row: a long display name
                        // (realistic Hungarian line names routinely are) must never be forced
                        // into whatever narrow width is left over once "Hozzárendelés
                        // megszüntetése" claims its own space - the same class of layout
                        // squeeze the Phase 9 correction pass fixed for long metadata.
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(line.displayName, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                line.lineCode + if (!line.active) " · " + stringResource(R.string.railway_line_inactive_reference) else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(
                                onClick = { onUnassign(line) },
                                enabled = !mutationInFlight,
                                modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
                            ) {
                                Text(stringResource(R.string.action_unassign_line))
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun LifecycleSection(
    detail: ServiceAreaAdminDetailResponse,
    mutationInFlight: Boolean,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit,
) {
    Column {
        if (detail.active) {
            val blockedByLines = detail.mappedRailwayLineCount > 0
            val blockedByReports = detail.openOperationalReportCount > 0
            val eligible = !blockedByLines && !blockedByReports
            if (blockedByLines) {
                Text(stringResource(R.string.deactivate_blocked_lines), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (blockedByReports) {
                Text(
                    stringResource(R.string.deactivate_blocked_reports),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = if (blockedByLines) 4.dp else 0.dp),
                )
            }
            OutlinedButton(
                onClick = onDeactivate,
                // Backend remains authoritative regardless (brief §53) - this only spares the
                // user an avoidable round trip when the displayed counts already show why.
                enabled = eligible && !mutationInFlight,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            ) { Text(stringResource(R.string.action_deactivate_area)) }
        } else {
            Button(
                onClick = onActivate,
                enabled = !mutationInFlight,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            ) { Text(stringResource(R.string.action_activate_area)) }
        }
    }
}
