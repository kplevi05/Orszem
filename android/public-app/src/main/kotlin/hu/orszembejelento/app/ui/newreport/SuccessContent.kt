package hu.orszembejelento.app.ui.newreport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import hu.orszembejelento.app.R
import hu.orszembejelento.app.report.domain.PublicReportStatus
import hu.orszembejelento.app.ui.PublicPalette
import hu.orszembejelento.app.ui.components.SoftCard
import hu.orszembejelento.app.ui.components.SuccessCheckBadge

/**
 * Deliberately aligned to the mockup's success screen (§2.D): a green circular checkmark,
 * a bold heading, a nested details card and a three-tier button stack (primary / secondary
 * / soft) - not four flat, equally-weighted buttons stacked in a column, which is what this
 * screen looked like before this pass. No credential is shown here, unchanged from before.
 */
@Composable
fun SuccessContent(
    info: SuccessInfo,
    onNewReport: () -> Unit,
    onViewHistory: () -> Unit,
    onHome: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SuccessCheckBadge()
        Text(
            stringResource(R.string.success_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )

        SoftCard(containerColor = PublicPalette.SurfaceTint, modifier = Modifier.padding(top = 8.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SuccessDetailLine(stringResource(R.string.success_report_id, info.publicReportId))
                SuccessDetailLine(stringResource(R.string.success_event_type, info.eventTypeDisplay))
                info.trainIdentifier?.let { SuccessDetailLine(stringResource(R.string.success_train, it)) }
                SuccessDetailLine(stringResource(R.string.success_settlement, info.settlementName))
                SuccessDetailLine(stringResource(R.string.success_status, stringResource(R.string.status_received)))
            }
        }

        Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onNewReport,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = PublicPalette.Primary),
            ) { Text(stringResource(R.string.action_new_report)) }
            OutlinedButton(onClick = onViewHistory, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_view_history))
            }
            TextButton(
                onClick = onHome,
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = PublicPalette.Primary),
            ) { Text(stringResource(R.string.action_home)) }
        }
    }
}

@Composable
private fun SuccessDetailLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = PublicPalette.TextMuted)
}

fun publicStatusLabel(status: PublicReportStatus): Int = when (status) {
    PublicReportStatus.RECEIVED -> R.string.status_received
    PublicReportStatus.PROCESSING -> R.string.status_processing
    PublicReportStatus.CLOSED -> R.string.status_closed
}
