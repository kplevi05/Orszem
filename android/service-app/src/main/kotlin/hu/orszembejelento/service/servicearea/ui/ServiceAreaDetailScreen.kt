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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import hu.orszembejelento.service.common.ui.ConfirmDialog
import hu.orszembejelento.service.common.ui.ErrorState
import hu.orszembejelento.service.common.ui.FullScreenLoading
import hu.orszembejelento.service.common.ui.InlineErrorBanner
import hu.orszembejelento.service.common.ui.apiErrorMessage
import hu.orszembejelento.service.servicearea.data.MappedSettlementResponse
import hu.orszembejelento.service.servicearea.data.ServiceAreaAdminDetailResponse

/**
 * `Szolgálati terület` detail: rename, activate/deactivate, delete, and settlement assignments.
 */
@Composable
fun ServiceAreaDetailScreen(
    viewModel: ServiceAreaDetailViewModel,
    onBack: () -> Unit,
    onAddSettlement: () -> Unit = {},
    onDeleted: () -> Unit = onBack,
    @Suppress("UNUSED_PARAMETER") onAddRailwayLine: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    var showRenameDialog by remember { mutableStateOf(false) }
    var showActivateDialog by remember { mutableStateOf(false) }
    var showDeactivateDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var confirmUnassignSettlement by remember { mutableStateOf<MappedSettlementResponse?>(null) }

    LaunchedEffect(state.deleted) {
        if (state.deleted) onDeleted()
    }

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

                    SettlementsSection(
                        settlements = detail.mappedSettlements,
                        mutationInFlight = state.mutationInFlight,
                        onAdd = onAddSettlement,
                        onUnassign = { confirmUnassignSettlement = it },
                    )

                    LifecycleSection(
                        detail = detail,
                        mutationInFlight = state.mutationInFlight,
                        onActivate = { showActivateDialog = true },
                        onDeactivate = { showDeactivateDialog = true },
                        onDelete = { showDeleteDialog = true },
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
    if (showDeleteDialog) {
        ConfirmDialog(
            title = stringResource(R.string.delete_area_dialog_title),
            text = stringResource(R.string.delete_area_dialog_text),
            confirmLabel = stringResource(R.string.action_delete_area),
            dangerous = true,
            onConfirm = { showDeleteDialog = false; viewModel.deleteArea() },
            onDismiss = { showDeleteDialog = false },
        )
    }
    confirmUnassignSettlement?.let { settlement ->
        ConfirmDialog(
            title = stringResource(R.string.unassign_settlement_dialog_title),
            text = stringResource(R.string.unassign_settlement_warning, settlement.name),
            confirmLabel = stringResource(R.string.action_unassign_line),
            dangerous = true,
            onConfirm = { confirmUnassignSettlement = null; viewModel.unassignSettlement(settlement.id) },
            onDismiss = { confirmUnassignSettlement = null },
        )
    }
}

@Composable
private fun SettlementsSection(
    settlements: List<MappedSettlementResponse>,
    mutationInFlight: Boolean,
    onAdd: () -> Unit,
    onUnassign: (MappedSettlementResponse) -> Unit,
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.settlements_section_title, settlements.size),
                style = MaterialTheme.typography.titleSmall,
            )
            TextButton(onClick = onAdd) { Text(stringResource(R.string.action_add_settlement)) }
        }
        if (settlements.isEmpty()) {
            Text(
                stringResource(R.string.settlements_section_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(modifier = Modifier.padding(4.dp)) {
                    settlements.forEach { settlement ->
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                            val title = buildString {
                                append(settlement.name)
                                settlement.countyName?.takeIf { it.isNotBlank() }?.let { append(" ($it)") }
                            }
                            Text(title, style = MaterialTheme.typography.bodyMedium)
                            if (!settlement.active) {
                                Text(
                                    stringResource(R.string.line_settlement_inactive_suffix),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                onClick = { onUnassign(settlement) },
                                enabled = !mutationInFlight,
                                modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
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
    onDelete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (detail.active) {
            OutlinedButton(
                onClick = onDeactivate,
                enabled = !mutationInFlight,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            ) { Text(stringResource(R.string.action_deactivate_area)) }
        } else {
            Button(
                onClick = onActivate,
                enabled = !mutationInFlight,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            ) { Text(stringResource(R.string.action_activate_area)) }
        }
        OutlinedButton(
            onClick = onDelete,
            enabled = !mutationInFlight,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_delete_area))
        }
    }
}
