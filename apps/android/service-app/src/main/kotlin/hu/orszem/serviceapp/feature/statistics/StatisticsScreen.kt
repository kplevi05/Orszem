package hu.orszem.serviceapp.feature.statistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.orszem.core.designsystem.LoadingState
import hu.orszem.core.designsystem.MessageState
import hu.orszem.serviceapp.R
import hu.orszem.serviceapp.data.Analytics

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    modifier: Modifier = Modifier,
    viewModel: StatisticsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier.fillMaxWidth()) {
        TopAppBar(title = { Text(stringResource(R.string.stats_title)) })
        when (val s = state) {
            StatisticsUiState.Loading -> LoadingState()
            StatisticsUiState.Error -> MessageState(
                title = stringResource(R.string.stats_error),
                actionLabel = stringResource(R.string.action_retry),
                onAction = viewModel::refresh,
            )
            is StatisticsUiState.Content -> Content(s.analytics)
        }
    }
}

@Composable
private fun Content(analytics: Analytics) {
    LazyColumn(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text(
                stringResource(R.string.stats_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Kpi(stringResource(R.string.stats_kpi_total), analytics.summary.totalReports, Modifier.weight(1f))
                Kpi(stringResource(R.string.stats_kpi_today), analytics.summary.todayReports, Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Kpi(stringResource(R.string.stats_kpi_active), analytics.summary.activeReports, Modifier.weight(1f))
                Kpi(stringResource(R.string.stats_kpi_archived), analytics.summary.archivedReports, Modifier.weight(1f))
            }
        }
        item { SectionTitle(stringResource(R.string.stats_top_events)) }
        items(analytics.eventTypes.take(5)) { CountRow(it.label, it.count) }

        item { SectionTitle(stringResource(R.string.stats_top_settlements)) }
        items(analytics.settlements.take(5)) { CountRow(it.settlement, it.count) }

        item { SectionTitle(stringResource(R.string.stats_top_trains)) }
        items(analytics.trains.take(5)) { CountRow(it.trainIdentifier, it.count) }

        item { SectionTitle(stringResource(R.string.stats_categories)) }
        val maxCat = (analytics.categories.maxOfOrNull { it.count } ?: 1).coerceAtLeast(1)
        items(analytics.categories) { CategoryBar(it.categoryLabel, it.count, it.percentage, maxCat) }
    }
}

@Composable
private fun Kpi(label: String, value: Int, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(
                value.toString(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun CountRow(label: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(count.toString(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CategoryBar(label: String, count: Int, percentage: Double, maxCount: Int) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text("$count (${"%.0f".format(percentage)}%)", style = MaterialTheme.typography.bodySmall)
        }
        androidx.compose.foundation.layout.Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            androidx.compose.foundation.layout.Box(
                Modifier
                    .fillMaxWidth(fraction = (count.toFloat() / maxCount).coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}
