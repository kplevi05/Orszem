package hu.orszembejelento.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.orszembejelento.app.R
import hu.orszembejelento.app.ui.history.HistoryItemCard
import hu.orszembejelento.app.ui.history.HistoryViewModel

@Composable
fun HomeScreen(historyViewModel: HistoryViewModel, onNewReport: () -> Unit) {
    val history by historyViewModel.history.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.home_headline), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.home_body), style = MaterialTheme.typography.bodyMedium)

        Button(onClick = onNewReport) {
            Text(stringResource(R.string.home_new_report_cta))
        }

        Text(stringResource(R.string.home_recent_history_title), style = MaterialTheme.typography.titleMedium)
        if (history.isEmpty()) {
            Text(stringResource(R.string.home_no_history), style = MaterialTheme.typography.bodySmall)
        } else {
            history.take(3).forEach { item -> HistoryItemCard(item, onRetry = {}, onRefresh = {}) }
        }
    }
}
