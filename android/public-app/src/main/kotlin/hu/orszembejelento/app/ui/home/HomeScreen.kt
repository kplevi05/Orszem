package hu.orszembejelento.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.orszembejelento.app.R
import hu.orszembejelento.app.ui.PublicPalette
import hu.orszembejelento.app.ui.components.BadgeChip
import hu.orszembejelento.app.ui.components.QuickGrid
import hu.orszembejelento.app.ui.components.SoftCard
import hu.orszembejelento.app.ui.history.HistoryItemCard
import hu.orszembejelento.app.ui.history.HistoryViewModel

/**
 * Deliberately aligned to the approved mockup's Home screen (§2.A of the Phase 5
 * visual-alignment brief): a badge + headline hero card, a two-up "quick actions" grid
 * (new report / history) and a recent-history preview - not a plain stacked column of a
 * headline, a button and a list, which is what this screen looked like before this pass.
 */
@Composable
fun HomeScreen(historyViewModel: HistoryViewModel, onNewReport: () -> Unit, onViewHistory: () -> Unit) {
    val history by historyViewModel.history.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        SoftCard(containerColor = PublicPalette.SurfaceTint) {
            BadgeChip(stringResource(R.string.home_badge))
            Column(modifier = Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.home_headline), style = MaterialTheme.typography.headlineMedium)
                Text(
                    stringResource(R.string.home_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PublicPalette.TextMuted,
                )
            }

            QuickGrid(modifier = Modifier.padding(top = 14.dp)) {
                QuickActionCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.home_quick_new_report_title),
                    body = stringResource(R.string.home_quick_new_report_body),
                    ctaLabel = stringResource(R.string.home_quick_new_report_cta),
                    primary = true,
                    onClick = onNewReport,
                )
                QuickActionCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.home_quick_history_title),
                    body = stringResource(R.string.home_quick_history_body),
                    ctaLabel = stringResource(R.string.home_quick_history_cta),
                    primary = false,
                    onClick = onViewHistory,
                )
            }
        }

        Text(
            stringResource(R.string.home_recent_history_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 4.dp),
        )
        if (history.isEmpty()) {
            Text(
                stringResource(R.string.home_no_history),
                style = MaterialTheme.typography.bodySmall,
                color = PublicPalette.TextMuted,
                modifier = Modifier.padding(start = 4.dp),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                history.take(3).forEach { item -> HistoryItemCard(item, onRetry = {}, onRefresh = {}) }
            }
        }
    }
}

@Composable
private fun QuickActionCard(
    title: String,
    body: String,
    ctaLabel: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SoftCard(modifier = modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = PublicPalette.TextMuted,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        val ctaPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
        if (primary) {
            Button(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = ctaPadding,
                colors = ButtonDefaults.buttonColors(containerColor = PublicPalette.Primary),
            ) { Text(ctaLabel, maxLines = 1, style = MaterialTheme.typography.labelMedium) }
        } else {
            OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), contentPadding = ctaPadding) {
                Text(ctaLabel, maxLines = 1, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
