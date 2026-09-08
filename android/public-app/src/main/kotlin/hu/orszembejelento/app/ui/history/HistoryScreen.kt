package hu.orszembejelento.app.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.orszembejelento.app.R
import hu.orszembejelento.app.report.data.local.ReportHistoryEntity
import hu.orszembejelento.app.report.domain.PublicReportStatus
import hu.orszembejelento.app.report.domain.SubmissionState
import hu.orszembejelento.app.ui.PublicPalette
import hu.orszembejelento.app.ui.components.PillTone
import hu.orszembejelento.app.ui.components.SoftCard
import hu.orszembejelento.app.ui.components.StatusPill
import hu.orszembejelento.app.ui.newreport.publicStatusLabel
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.ZoneId

@Composable
fun HistoryScreen(viewModel: HistoryViewModel) {
    val history by viewModel.history.collectAsState()

    // history.value is seeded emptyList() until Room's Flow actually emits (§21) - a
    // LaunchedEffect(Unit) firing immediately would call refreshAllSubmitted() against that
    // still-empty seed and refresh nothing. This mirrors the Web client's identical fix,
    // found the same way: a real end-to-end run against a live backend, not a unit test
    // against a synchronous fake DAO, which never surfaces this ordering.
    var hasRefreshedOnce by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(history) {
        if (hasRefreshedOnce || history.isEmpty()) return@LaunchedEffect
        hasRefreshedOnce = true
        viewModel.refreshAllSubmitted()
    }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text(stringResource(R.string.history_title), style = MaterialTheme.typography.headlineSmall)

        SoftCard(containerColor = PublicPalette.SurfaceTint, modifier = Modifier.padding(top = 14.dp)) {
            Text(
                stringResource(R.string.history_storage_notice),
                style = MaterialTheme.typography.bodySmall,
                color = PublicPalette.TextMuted,
            )
        }

        if (history.isEmpty()) {
            Text(
                stringResource(R.string.history_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = PublicPalette.TextMuted,
                modifier = Modifier.padding(top = 24.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.padding(top = 14.dp)) {
                items(history, key = { it.clientSubmissionId }) { item ->
                    HistoryItemCard(
                        item = item,
                        onRetry = { viewModel.retry(item.clientSubmissionId) },
                        onRefresh = { viewModel.refreshStatus(item.clientSubmissionId) },
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
            }
        }
    }
}

/**
 * Every submission state gets its own icon + colour + text (§2.E of the Phase 5
 * visual-alignment brief: "not only by colour") - PENDING, CONFLICT and ACCESS_LOST are
 * never distinguishable by a shared red dot alone.
 */
@Composable
fun HistoryItemCard(item: ReportHistoryEntity, onRetry: () -> Unit, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    val formatter = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()) }

    SoftCard(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                item.eventTypeDisplaySnapshot,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(end = 8.dp),
            )
            SubmissionStatePill(item)
        }
        Column(modifier = Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.settlementNameSnapshot, style = MaterialTheme.typography.bodyMedium, color = PublicPalette.TextMuted)
            item.trainIdentifier?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = PublicPalette.TextMuted) }
            Text(formatter.format(item.occurredAt), style = MaterialTheme.typography.bodySmall, color = PublicPalette.TextMuted)
            item.lastStatusCheckedAt?.let {
                Text(
                    stringResource(R.string.history_last_checked, formatter.format(it)),
                    style = MaterialTheme.typography.bodySmall,
                    color = PublicPalette.TextMuted,
                )
            }
        }

        when (item.submissionState) {
            SubmissionState.SUBMITTED -> TextButton(
                onClick = onRefresh,
                modifier = Modifier.padding(top = 4.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = PublicPalette.Primary),
            ) { Text(stringResource(R.string.history_refresh)) }
            SubmissionState.PENDING -> TextButton(
                onClick = onRetry,
                modifier = Modifier.padding(top = 4.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = PublicPalette.Primary),
            ) { Text(stringResource(R.string.action_retry)) }
            SubmissionState.ACCESS_LOST -> Text(
                stringResource(R.string.history_access_lost),
                style = MaterialTheme.typography.bodySmall,
                color = PublicPalette.TextMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
            SubmissionState.CONFLICT -> Text(
                stringResource(R.string.history_conflict),
                style = MaterialTheme.typography.bodySmall,
                color = PublicPalette.TextMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun SubmissionStatePill(item: ReportHistoryEntity) {
    when (item.submissionState) {
        SubmissionState.SUBMITTED -> {
            val status = item.publicStatus
            if (status != null) {
                val (tone, icon) = when (status) {
                    PublicReportStatus.RECEIVED -> PillTone.INFO to Icons.Filled.Info
                    PublicReportStatus.PROCESSING -> PillTone.WARNING to Icons.Filled.Refresh
                    PublicReportStatus.CLOSED -> PillTone.SUCCESS to Icons.Filled.CheckCircle
                }
                StatusPill(text = stringResource(publicStatusLabel(status)), tone = tone, icon = icon)
            }
        }
        SubmissionState.PENDING -> StatusPill(
            text = stringResource(R.string.history_unconfirmed),
            tone = PillTone.WARNING,
            icon = Icons.Filled.Warning,
        )
        SubmissionState.ACCESS_LOST -> StatusPill(
            text = stringResource(R.string.history_access_lost_short),
            tone = PillTone.ERROR,
            icon = Icons.Filled.Lock,
        )
        SubmissionState.CONFLICT -> StatusPill(
            text = stringResource(R.string.history_conflict_short),
            tone = PillTone.ERROR,
            icon = Icons.Filled.Clear,
        )
    }
}
