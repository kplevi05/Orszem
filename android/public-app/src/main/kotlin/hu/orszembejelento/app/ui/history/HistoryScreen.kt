package hu.orszembejelento.app.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
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
import hu.orszembejelento.app.report.domain.SubmissionState
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

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.history_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.history_storage_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (history.isEmpty()) {
            Text(stringResource(R.string.history_empty), modifier = Modifier.padding(top = 24.dp))
        } else {
            LazyColumn {
                items(history, key = { it.clientSubmissionId }) { item ->
                    HistoryItemCard(
                        item = item,
                        onRetry = { viewModel.retry(item.clientSubmissionId) },
                        onRefresh = { viewModel.refreshStatus(item.clientSubmissionId) },
                    )
                }
            }
        }
    }
}

@Composable
fun HistoryItemCard(item: ReportHistoryEntity, onRetry: () -> Unit, onRefresh: () -> Unit) {
    val formatter = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()) }

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.eventTypeDisplaySnapshot, style = MaterialTheme.typography.titleMedium)
            Text(item.settlementNameSnapshot, style = MaterialTheme.typography.bodyMedium)
            item.trainIdentifier?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(formatter.format(item.occurredAt), style = MaterialTheme.typography.bodySmall)

            when (item.submissionState) {
                SubmissionState.SUBMITTED -> {
                    item.publicStatus?.let { Text(stringResource(publicStatusLabel(it)), style = MaterialTheme.typography.labelLarge) }
                    item.lastStatusCheckedAt?.let {
                        Text(
                            stringResource(R.string.history_last_checked, formatter.format(it)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row {
                        TextButton(onClick = onRefresh) { Text(stringResource(R.string.history_refresh)) }
                    }
                }
                SubmissionState.PENDING -> {
                    Text(
                        stringResource(R.string.history_unconfirmed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                }
                SubmissionState.ACCESS_LOST -> Text(
                    stringResource(R.string.history_access_lost),
                    color = MaterialTheme.colorScheme.error,
                )
                SubmissionState.CONFLICT -> Text(
                    stringResource(R.string.history_conflict),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
