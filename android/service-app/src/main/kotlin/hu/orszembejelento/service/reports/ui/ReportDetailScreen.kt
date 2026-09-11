package hu.orszembejelento.service.reports.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import hu.orszembejelento.service.common.ui.ConfirmDialog
import hu.orszembejelento.service.common.ui.ErrorState
import hu.orszembejelento.service.common.ui.FullScreenLoading
import hu.orszembejelento.service.common.ui.InlineErrorBanner
import hu.orszembejelento.service.common.ui.StatusBadge
import hu.orszembejelento.service.common.ui.apiErrorMessage
import hu.orszembejelento.service.moderation.ui.DeleteReasonDialog
import hu.orszembejelento.service.reports.data.ReportDetailResponse
import hu.orszembejelento.service.reports.domain.buildAssignmentHistoryEntries
import hu.orszembejelento.service.reports.domain.canModerationDelete
import hu.orszembejelento.service.reports.domain.formatInstant
import hu.orszembejelento.service.reports.domain.isUnclassified
import hu.orszembejelento.service.reports.domain.shortReportId
import hu.orszembejelento.service.reports.domain.statusColor
import hu.orszembejelento.service.reports.domain.statusLabelRes
import hu.orszembejelento.service.usermanagement.data.UserManagementRepository

/**
 * Full report-workflow detail (brief §28): every status, every role's available actions, and
 * the stale/conflict UX (§23-25). Controls are shown as a UI convenience only - the backend
 * re-authorises every mutation independently, and a rejection is always handled the same way
 * regardless of whether this screen "should" have shown the control (brief §33/§77).
 */
@Composable
fun ReportDetailScreen(
    currentServiceId: String,
    role: String,
    viewModel: ReportDetailViewModel,
    userManagementRepository: UserManagementRepository?,
    onBack: () -> Unit,
    // Called once after a moderation delete resolves - either this call's own success, or
    // the REPORT_ALREADY_DELETED conflict (brief §42/§43, correction pass §3): the caller
    // navigates away either way, since the report is no longer reachable through ordinary
    // detail, but the two carry different snackbar copy - see [ModerationDeleteOutcome].
    onDeleted: (ModerationDeleteOutcome) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    var confirmReturn by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    var showReassign by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.moderationDeleteOutcome) {
        state.moderationDeleteOutcome?.let(onDeleted)
    }

    // A successful mutation's own committed response already replaces `state.detail` (brief
    // §22: "prefer the committed API response") - the refreshed status badge, assignee and
    // action set on screen ARE the success feedback, so no separate toast/snackbar duplicates
    // it. `lastMutation` still exists on the state for a caller (e.g. a future snackbar host)
    // that wants to react to *which* mutation just completed; consumed here so it fires once.
    LaunchedEffect(state.lastMutation) {
        if (state.lastMutation != null) viewModel.consumeToast()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_description_back))
            }
            Text(stringResource(R.string.report_detail_title), style = MaterialTheme.typography.titleLarge)
        }

        when {
            state.loading -> FullScreenLoading()
            state.error != null -> ErrorState(message = apiErrorMessage(state.error!!), onRetry = { viewModel.load() })
            state.detail == null -> Unit
            else -> {
                val detail = state.detail!!
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    ReportSummaryHeader(detail)

                    if (state.mutationError != null) {
                        InlineErrorBanner(apiErrorMessage(state.mutationError!!))
                    }
                    if (state.moderationDeleteError != null) {
                        InlineErrorBanner(apiErrorMessage(state.moderationDeleteError!!))
                    }

                    ReportFieldsCard(detail)

                    if (detail.assignmentHistory.isNotEmpty()) {
                        AssignmentHistorySection(detail)
                    }

                    WorkflowActionsSection(
                        detail = detail,
                        currentServiceId = currentServiceId,
                        role = role,
                        busy = state.mutationInFlight,
                        onClaim = viewModel::claim,
                        onReturnRequested = { confirmReturn = true },
                        onCloseRequested = { confirmClose = true },
                        onReassignRequested = { showReassign = true },
                    )

                    // Deliberately separated from the workflow-action row above (brief §38):
                    // moderation deletion is a different kind of action from Lezárás /
                    // Visszaadás / Átrendelés, and must never be visually confused with them.
                    // SERVICE_USER never sees this at all.
                    if (canModerationDelete(role)) {
                        HorizontalDivider()
                        OutlinedButton(
                            onClick = { showDeleteDialog = true },
                            enabled = !state.mutationInFlight && !state.moderationDeleteInFlight,
                            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.action_moderation_delete))
                        }
                    }

                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(bottom = 24.dp))
                }
            }
        }
    }

    if (confirmReturn) {
        ConfirmDialog(
            title = stringResource(R.string.confirm_return_title),
            text = stringResource(R.string.confirm_return_text),
            confirmLabel = stringResource(R.string.action_return),
            onConfirm = { confirmReturn = false; viewModel.returnToNew() },
            onDismiss = { confirmReturn = false },
        )
    }
    if (confirmClose) {
        ConfirmDialog(
            title = stringResource(R.string.confirm_close_title),
            text = stringResource(R.string.confirm_close_text),
            confirmLabel = stringResource(R.string.action_close),
            dangerous = true,
            onConfirm = { confirmClose = false; viewModel.close() },
            onDismiss = { confirmClose = false },
        )
    }
    if (showReassign && userManagementRepository != null) {
        ReassignDialog(
            userManagementRepository = userManagementRepository,
            onDismiss = { showReassign = false },
            onConfirm = { target ->
                showReassign = false
                viewModel.reassign(target)
            },
        )
    }
    if (showDeleteDialog) {
        DeleteReasonDialog(
            isInProgress = state.detail?.status == "IN_PROGRESS",
            onConfirm = { reason ->
                showDeleteDialog = false
                viewModel.deleteReport(reason)
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

@Composable
private fun ReportSummaryHeader(detail: ReportDetailResponse) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(detail.eventType.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                text = "${detail.settlement.name}${detail.resolvedRailwayLine?.let { " · ${it.displayName}" } ?: ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (detail.isUnclassified()) {
            StatusBadge(R.string.status_unclassified, statusColor("NEW"))
        } else {
            StatusBadge(statusLabelRes(detail.status), statusColor(detail.status))
        }
    }
}

@Composable
private fun ReportFieldsCard(detail: ReportDetailResponse) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(4.dp)) {
            DetailRow(stringResource(R.string.field_occurred_at), formatInstant(detail.occurredAt))
            DetailRow(stringResource(R.string.field_submitted_at), formatInstant(detail.submittedAt))
            detail.trainIdentifier?.let { DetailRow(stringResource(R.string.field_train_identifier), it) }
            DetailRow(stringResource(R.string.field_category), detail.category.displayName)
            DetailRow(stringResource(R.string.field_event_type), detail.eventType.displayName)
            DetailRow(stringResource(R.string.field_settlement), detail.settlement.name)
            detail.resolvedRailwayLine?.let { DetailRow(stringResource(R.string.field_railway_line), it.displayName) }
            DetailRow(
                stringResource(R.string.field_service_area),
                detail.serviceArea?.name ?: stringResource(R.string.value_unclassified_area),
            )
            detail.assignee?.let { DetailRow(stringResource(R.string.field_current_assignee), it.serviceId) }
            detail.archivedAt?.let { DetailRow(stringResource(R.string.field_closed_at), formatInstant(it)) }
            DetailRow(stringResource(R.string.field_report_id), shortReportId(detail.publicReportId))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
    HorizontalDivider()
}

@Composable
private fun AssignmentHistorySection(detail: ReportDetailResponse) {
    Column {
        Text(stringResource(R.string.assignment_history_title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.assignment_history_subtitle),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                buildAssignmentHistoryEntries(detail.assignmentHistory).forEach { entry ->
                    Column {
                        Text(entry.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = formatInstant(entry.timestampRaw) + if (entry.active) " · " + stringResource(R.string.assignment_currently_active) else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkflowActionsSection(
    detail: ReportDetailResponse,
    currentServiceId: String,
    role: String,
    busy: Boolean,
    onClaim: () -> Unit,
    onReturnRequested: () -> Unit,
    onCloseRequested: () -> Unit,
    onReassignRequested: () -> Unit,
) {
    val isOwnAssignment = detail.assignee?.serviceId == currentServiceId
    val availability = hu.orszembejelento.service.reports.domain.availableWorkflowActions(detail.status, role, isOwnAssignment)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (availability.claim) {
            Button(onClick = onClaim, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_claim))
            }
        }
        if (availability.reassign) {
            Button(onClick = onReassignRequested, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_reassign))
            }
        }
        if (availability.returnToNew || availability.close) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (availability.returnToNew) {
                    OutlinedButton(onClick = onReturnRequested, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.action_return))
                    }
                }
                if (availability.close) {
                    if (availability.reassign) {
                        // Supervisor view: close is secondary once reassign/return are shown.
                        OutlinedButton(onClick = onCloseRequested, enabled = !busy, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.action_close))
                        }
                    } else {
                        Button(onClick = onCloseRequested, enabled = !busy, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.action_close))
                        }
                    }
                }
            }
        }
    }
}
