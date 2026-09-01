package hu.orszem.serviceapp.feature.reports

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.orszem.core.designsystem.StatusChip
import hu.orszem.core.model.ReportStatus
import hu.orszem.core.model.ServiceReportSummary
import hu.orszem.serviceapp.R
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun statusLabel(status: ReportStatus): String = when (status) {
    ReportStatus.NEW -> stringResource(R.string.status_new)
    ReportStatus.IN_PROGRESS -> stringResource(R.string.status_in_progress)
    ReportStatus.ARCHIVED -> stringResource(R.string.status_archived)
}

private val dateFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)

@Composable
fun ReportCard(report: ServiceReportSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(report.eventType.label, style = MaterialTheme.typography.titleMedium)
                StatusChip(statusLabel(report.status))
            }
            Text(report.trainIdentifier, style = MaterialTheme.typography.bodyMedium)
            Text(report.settlement, style = MaterialTheme.typography.bodyMedium)
            Text(
                dateFormatter.format(report.occurredAt.atZone(ZoneId.systemDefault())),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
