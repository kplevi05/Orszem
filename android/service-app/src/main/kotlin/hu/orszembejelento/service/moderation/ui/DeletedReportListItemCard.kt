package hu.orszembejelento.service.moderation.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import hu.orszembejelento.service.common.ui.moderationReasonLabelRes
import hu.orszembejelento.service.moderation.data.DeletedReportListItemResponse
import hu.orszembejelento.service.reports.domain.formatInstant
import hu.orszembejelento.service.reports.domain.shortReportId

/**
 * One deleted-report card - event type first, then a `TÖRÖLVE` status badge (a moderation UI
 * label, never a new backend workflow-status enum, brief §44), then the deletion facts a
 * moderator most needs: reason, when, who, and the area/Besorolatlan indication.
 */
@Composable
fun DeletedReportListItemCard(item: DeletedReportListItemResponse, onClick: () -> Unit) {
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
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusBadge(R.string.deleted_status_badge, MaterialTheme.colorScheme.error)
            }
            Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MetaChip(stringResource(moderationReasonLabelRes(item.reason)))
                MetaChip(formatInstant(item.deletedAt))
                MetaChip(item.deletedByServiceId)
                MetaChip(item.serviceArea?.name ?: stringResource(R.string.value_unclassified_area))
            }
        }
    }
}

@Composable
private fun MetaChip(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The paginated deleted-report list body - mirrors `ReportList`'s exact shape. */
@Composable
fun DeletedReportList(
    items: List<DeletedReportListItemResponse>,
    canLoadMore: Boolean,
    loadingMore: Boolean,
    onItemClick: (String) -> Unit,
    onLoadMore: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items, key = { it.publicReportId }) { report ->
            DeletedReportListItemCard(item = report, onClick = { onItemClick(report.publicReportId) })
        }
        if (canLoadMore) {
            item { hu.orszembejelento.service.common.ui.LoadMoreButton(loading = loadingMore, onClick = onLoadMore) }
        }
    }
}
