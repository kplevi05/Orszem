package hu.orszembejelento.service.moderation.ui

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
import hu.orszembejelento.service.common.ui.InlineErrorBanner
import hu.orszembejelento.service.common.ui.StatusBadge
import hu.orszembejelento.service.common.ui.apiErrorMessage
import hu.orszembejelento.service.common.ui.moderationReasonLabelRes
import hu.orszembejelento.service.moderation.data.DeletedReportDetailResponse
import hu.orszembejelento.service.reports.domain.buildAssignmentHistoryEntries
import hu.orszembejelento.service.reports.domain.formatInstant
import hu.orszembejelento.service.reports.domain.shortReportId
import hu.orszembejelento.service.reports.domain.statusLabelRes

/**
 * Deleted-report detail (brief §46-50): the same normal-report layout language, plus a
 * `Moderáció` section. MODERATOR gets read-only detail with an explanatory hint; SUPER_ADMIN
 * additionally gets the restore action, behind its own explicit confirmation.
 */
@Composable
fun DeletedReportDetailScreen(
    role: String,
    viewModel: DeletedReportDetailViewModel,
    onBack: () -> Unit,
    onRestored: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    var showRestoreDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.restoreCompleted) {
        if (state.restoreCompleted) onRestored()
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
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(detail.eventType.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "${detail.settlement.name}${detail.resolvedRailwayLine?.let { " · ${it.displayName}" } ?: ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        StatusBadge(R.string.deleted_status_badge, MaterialTheme.colorScheme.error)
                    }

                    if (state.restoreError != null) {
                        InlineErrorBanner(apiErrorMessage(state.restoreError!!))
                    }

                    ReportFieldsCard(detail)

                    ModerationSection(detail)

                    if (detail.assignmentHistory.isNotEmpty()) {
                        AssignmentHistorySection(detail)
                    }

                    if (role == "SUPER_ADMIN") {
                        Button(
                            onClick = { showRestoreDialog = true },
                            enabled = !state.restoreInFlight,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.action_restore)) }
                    } else {
                        Text(
                            stringResource(R.string.deleted_moderator_readonly_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(bottom = 24.dp))
                }
            }
        }
    }

    if (showRestoreDialog) {
        RestoreConfirmDialog(
            statusBeforeDelete = state.detail?.statusBeforeDelete ?: "NEW",
            onConfirm = { showRestoreDialog = false; viewModel.restore() },
            onDismiss = { showRestoreDialog = false },
        )
    }
}

@Composable
private fun ReportFieldsCard(detail: DeletedReportDetailResponse) {
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
            DetailRow(stringResource(R.string.field_report_id), shortReportId(detail.publicReportId))
        }
    }
}

@Composable
private fun ModerationSection(detail: DeletedReportDetailResponse) {
    Column {
        Text(stringResource(R.string.moderation_section_title), style = MaterialTheme.typography.titleSmall)
        Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(modifier = Modifier.padding(4.dp)) {
                DetailRow(stringResource(R.string.deleted_reason_label), stringResource(moderationReasonLabelRes(detail.reason)))
                DetailRow(stringResource(R.string.deleted_by_label), detail.deletedByServiceId)
                DetailRow(stringResource(R.string.deleted_at_label), formatInstant(detail.deletedAt))
                DetailRow(stringResource(R.string.deleted_status_before_label), stringResource(statusLabelRes(detail.statusBeforeDelete)))
                DetailRow(stringResource(R.string.deleted_restore_target_label), stringResource(statusLabelRes(detail.restoreTargetStatus)))
            }
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
private fun AssignmentHistorySection(detail: DeletedReportDetailResponse) {
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
