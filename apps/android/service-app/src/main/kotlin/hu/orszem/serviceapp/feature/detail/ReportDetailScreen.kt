package hu.orszem.serviceapp.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.orszem.core.designsystem.LoadingState
import hu.orszem.core.designsystem.MessageState
import hu.orszem.core.designsystem.PrimaryButton
import hu.orszem.core.designsystem.StatusChip
import hu.orszem.core.model.ServiceReportDetail
import hu.orszem.serviceapp.R
import hu.orszem.serviceapp.feature.reports.statusLabel
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportDetailScreen(
    reportId: String,
    readOnly: Boolean,
    onBack: () -> Unit,
    viewModel: ReportDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(reportId) { viewModel.load(reportId) }

    // A confirmed deletion leaves this screen; the list reloads on return.
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }

    val deleteFailed = stringResource(R.string.detail_delete_failed)
    val retry = stringResource(R.string.action_retry)
    LaunchedEffect(state.deleteError) {
        if (state.deleteError == null) return@LaunchedEffect
        val result = snackbar.showSnackbar(message = deleteFailed, actionLabel = retry)
        if (result == SnackbarResult.ActionPerformed) viewModel.confirmDelete()
        viewModel.dismissDeleteError()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            state.loading -> LoadingState()
            state.loadError || state.report == null -> MessageState(
                title = stringResource(R.string.detail_error),
                actionLabel = stringResource(R.string.action_retry),
                onAction = { viewModel.load(reportId) },
            )
            else -> DetailContent(
                report = state.report!!,
                showActions = !readOnly,
                canAccept = state.canAccept,
                canArchive = state.canArchive,
                canDelete = state.canDelete,
                actionInProgress = state.actionInProgress,
                deleting = state.deleting,
                onAccept = viewModel::accept,
                onArchive = viewModel::archive,
                onDelete = viewModel::requestDelete,
                modifier = Modifier.padding(padding),
            )
        }
    }

    // Deliberately a confirmation dialog, not swipe-to-delete (§E).
    if (state.confirmingDelete) {
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text(stringResource(R.string.detail_delete_confirm_title)) },
            text = { Text(stringResource(R.string.detail_delete_confirm_message)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) {
                    Text(stringResource(R.string.detail_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDelete) {
                    Text(stringResource(R.string.detail_delete_cancel))
                }
            },
        )
    }

    if (state.staleConflict) {
        AlertDialog(
            onDismissRequest = viewModel::dismissConflict,
            confirmButton = {
                TextButton(onClick = viewModel::dismissConflict) { Text(stringResource(R.string.detail_reload)) }
            },
            title = { Text(stringResource(R.string.detail_state_changed)) },
            text = { Text(stringResource(R.string.detail_state_changed)) },
        )
    }
}

private val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)

@Composable
private fun DetailContent(
    report: ServiceReportDetail,
    showActions: Boolean,
    canDelete: Boolean = false,
    deleting: Boolean = false,
    onDelete: () -> Unit = {},
    canAccept: Boolean,
    canArchive: Boolean,
    actionInProgress: Boolean,
    onAccept: () -> Unit,
    onArchive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    fun ts(instant: java.time.Instant?) = instant?.let { formatter.format(it.atZone(ZoneId.systemDefault())) } ?: "—"

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(report.eventType.label, style = MaterialTheme.typography.headlineSmall)
        StatusChip(statusLabel(report.status))
        Field(stringResource(R.string.detail_category), report.eventType.categoryLabel)
        Field(stringResource(R.string.detail_train), report.trainIdentifier)
        Field(stringResource(R.string.detail_settlement), report.settlement)
        Field(stringResource(R.string.detail_occurred), ts(report.occurredAt))
        Field(stringResource(R.string.detail_received), ts(report.receivedAt))
        Field(stringResource(R.string.detail_status), statusLabel(report.status))

        if (report.acceptedAt != null) {
            Field(stringResource(R.string.detail_accepted_at), ts(report.acceptedAt))
            Field(stringResource(R.string.detail_accepted_by), report.acceptedBy?.displayName ?: "—")
        }
        if (report.archivedAt != null) {
            Field(stringResource(R.string.detail_archived_at), ts(report.archivedAt))
            Field(stringResource(R.string.detail_archived_by), report.archivedBy?.displayName ?: "—")
        }

        if (showActions && canAccept) {
            PrimaryButton(stringResource(R.string.detail_accept), onAccept, loading = actionInProgress)
        }
        if (showActions && canArchive) {
            PrimaryButton(stringResource(R.string.detail_archive), onArchive, loading = actionInProgress)
        }
        // Demo v1.1: pilot/test-data cleanup. Shown only when the backend grants
        // REPORT_DELETE, and available from every status — including the archive.
        if (canDelete) {
            OutlinedButton(
                onClick = onDelete,
                enabled = !deleting && !actionInProgress,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.detail_delete))
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
