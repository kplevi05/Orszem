package hu.orszembejelento.app.ui.newreport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.orszembejelento.app.R
import hu.orszembejelento.app.report.domain.PublicReportStatus

@Composable
fun SuccessContent(
    info: SuccessInfo,
    onNewReport: () -> Unit,
    onViewHistory: () -> Unit,
    onHome: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(stringResource(R.string.success_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.success_report_id, info.publicReportId))
        Text(stringResource(R.string.success_event_type, info.eventTypeDisplay))
        info.trainIdentifier?.let { Text(stringResource(R.string.success_train, it)) }
        Text(stringResource(R.string.success_settlement, info.settlementName))
        Text(stringResource(R.string.success_status, stringResource(R.string.status_received)))

        Button(onClick = onNewReport, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_new_report))
        }
        OutlinedButton(onClick = onViewHistory, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_view_history))
        }
        OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_home))
        }
    }
}

fun publicStatusLabel(status: PublicReportStatus): Int = when (status) {
    PublicReportStatus.RECEIVED -> R.string.status_received
    PublicReportStatus.PROCESSING -> R.string.status_processing
    PublicReportStatus.CLOSED -> R.string.status_closed
}
