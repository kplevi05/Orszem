package hu.orszembejelento.service.reports.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.orszembejelento.service.R
import hu.orszembejelento.service.common.ui.StatusBadge
import hu.orszembejelento.service.reports.data.ReportListItemResponse
import hu.orszembejelento.service.reports.domain.formatInstant
import hu.orszembejelento.service.reports.domain.isUnclassified
import hu.orszembejelento.service.reports.domain.shortReportId
import hu.orszembejelento.service.reports.domain.statusColor
import hu.orszembejelento.service.reports.domain.statusLabelRes

/**
 * One report card - event type first, then settlement/line, then a compact meta row (brief
 * §18/§27). Deliberately does not try to show everything; the detail screen has the rest.
 *
 * Field-test fix: the submitted-at/category/area meta line used to be a plain, non-wrapping
 * [Row], which shifted or clipped the row once a [hu.orszembejelento.service.reports.data.ReportAreaSummary]
 * name was long enough (a real production ServiceArea name, e.g. "Debrecen–Miskolc" or a
 * "[FIKTÍV] …" placeholder, next to a narrow ~320dp screen and/or a large font scale) - it
 * never truncates or abbreviates the canonical name, so the layout has to give. Mirrors
 * [hu.orszembejelento.service.moderation.ui.DeletedReportListItemCard]'s existing, already
 * width-safe shape exactly: the submitted-at/category chips wrap in a [FlowRow], and the area
 * name gets its own full-width row underneath so it wraps as ordinary multi-line text instead
 * of being squeezed into whatever the chips above left over.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportListItemCard(item: ReportListItemResponse, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(15.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.eventType.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = buildString {
                            append(shortReportId(item.publicReportId))
                            append(" · ")
                            append(item.settlement.name)
                            item.assignee?.let { append(" · ").append(it.serviceId) }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item.isUnclassified()) {
                    StatusBadge(R.string.status_unclassified, statusColor("NEW"))
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MetaChip(formatInstant(item.submittedAt))
                MetaChip(item.category.displayName)
            }
            item.serviceArea?.let {
                MetaChip(text = it.name, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun MetaChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** The shared, paginated report list body every queue screen (New/In-progress/Archive) renders. */
@Composable
fun ReportList(
    items: List<ReportListItemResponse>,
    canLoadMore: Boolean,
    loadingMore: Boolean,
    onItemClick: (String) -> Unit,
    onLoadMore: () -> Unit,
    header: (@Composable () -> Unit)? = null,
    dividerBeforeIndex: Int? = null,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        header?.let { item { it() } }
        itemsIndexed(items, key = { _, report -> report.publicReportId }) { index, report ->
            if (dividerBeforeIndex != null && index == dividerBeforeIndex) {
                Text(
                    text = stringResource(R.string.older_reports_divider),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            ReportListItemCard(item = report, onClick = { onItemClick(report.publicReportId) })
        }
        if (canLoadMore) {
            item {
                hu.orszembejelento.service.common.ui.LoadMoreButton(loading = loadingMore, onClick = onLoadMore)
            }
        }
    }
}
