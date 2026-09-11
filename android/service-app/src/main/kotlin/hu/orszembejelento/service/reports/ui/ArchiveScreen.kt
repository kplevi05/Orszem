package hu.orszembejelento.service.reports.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.orszembejelento.service.R
import hu.orszembejelento.service.reports.data.CatalogRepository

/** `Archívum` (brief §39-40): read-only, scope-aware, most-recently-archived first - never a filter on "only mine". */
@Composable
fun ArchiveScreen(
    viewModel: ReportQueueViewModel,
    onOpenReport: (String) -> Unit,
    catalogRepository: CatalogRepository,
    areaChoices: List<AreaChoice>,
    onAreaFilterChanged: (String?) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(text = stringResource(R.string.archive_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                text = stringResource(R.string.archive_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ReportQueueBody(
            viewModel = viewModel,
            onOpenReport = onOpenReport,
            emptyMessage = stringResource(R.string.empty_archive),
            bucketed = false,
            catalogRepository = catalogRepository,
            areaChoices = areaChoices,
            onAreaFilterChanged = onAreaFilterChanged,
        )
    }
}
