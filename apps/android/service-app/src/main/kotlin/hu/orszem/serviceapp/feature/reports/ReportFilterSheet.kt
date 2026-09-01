package hu.orszem.serviceapp.feature.reports

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.orszem.serviceapp.R

/**
 * Material 3 filter + sort sheet for the active Reports screen (Demo v1.1 §C, §D).
 *
 * Choices come from [FilterOptions], which is derived from the loaded dataset and
 * from the reports' own catalog values — there is no second taxonomy here.
 * Selections combine with logical AND; the sheet edits a working copy and reports
 * every change immediately so the match count behind it stays live.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportFilterSheet(
    filters: ReportFilters,
    sort: ReportSort,
    options: FilterOptions,
    matchCount: Int,
    onFiltersChanged: (ReportFilters) -> Unit,
    onSortChanged: (ReportSort) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.filter_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.reports_match_count, matchCount),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // --- Sorting -------------------------------------------------------
            SectionTitle(stringResource(R.string.sort_title))
            ChipRow {
                ReportSort.entries.forEach { option ->
                    FilterChip(
                        selected = sort == option,
                        onClick = { onSortChanged(option) },
                        label = { Text(stringResource(option.labelRes())) },
                    )
                }
            }

            // --- Date ----------------------------------------------------------
            SectionTitle(stringResource(R.string.filter_date))
            ChipRow {
                DateFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filters.date == option,
                        onClick = { onFiltersChanged(filters.copy(date = option)) },
                        label = { Text(stringResource(option.labelRes())) },
                    )
                }
            }

            // --- Category ------------------------------------------------------
            if (options.categories.isNotEmpty()) {
                SectionTitle(stringResource(R.string.filter_category))
                ChipRow {
                    AnyChip(selected = filters.categoryCode == null) {
                        // Clearing the category also clears an incident type that
                        // only made sense inside it.
                        onFiltersChanged(filters.copy(categoryCode = null, eventTypeCode = null))
                    }
                    options.categories.forEach { (code, label) ->
                        FilterChip(
                            selected = filters.categoryCode == code,
                            onClick = {
                                val selecting = filters.categoryCode != code
                                onFiltersChanged(
                                    filters.copy(
                                        categoryCode = if (selecting) code else null,
                                        // Drop an incident type from another category.
                                        eventTypeCode = filters.eventTypeCode?.takeIf { chosen ->
                                            !selecting || options.eventTypes
                                                .any { it.code == chosen && it.categoryCode == code }
                                        },
                                    ),
                                )
                            },
                            label = { Text(label) },
                        )
                    }
                }
            }

            // --- Incident type (narrowed to the chosen category) ----------------
            val eventTypes = options.eventTypes
                .filter { filters.categoryCode == null || it.categoryCode == filters.categoryCode }
            if (eventTypes.isNotEmpty()) {
                SectionTitle(stringResource(R.string.filter_event_type))
                ChipRow {
                    AnyChip(selected = filters.eventTypeCode == null) {
                        onFiltersChanged(filters.copy(eventTypeCode = null))
                    }
                    eventTypes.forEach { option ->
                        FilterChip(
                            selected = filters.eventTypeCode == option.code,
                            onClick = {
                                onFiltersChanged(
                                    filters.copy(
                                        eventTypeCode = option.code.takeIf { filters.eventTypeCode != option.code },
                                    ),
                                )
                            },
                            label = { Text(option.label) },
                        )
                    }
                }
            }

            // --- Settlement ------------------------------------------------------
            if (options.settlements.isNotEmpty()) {
                SectionTitle(stringResource(R.string.filter_settlement))
                ChipRow {
                    AnyChip(selected = filters.settlement == null) {
                        onFiltersChanged(filters.copy(settlement = null))
                    }
                    options.settlements.forEach { value ->
                        FilterChip(
                            selected = filters.settlement == value,
                            onClick = {
                                onFiltersChanged(filters.copy(settlement = value.takeIf { filters.settlement != value }))
                            },
                            label = { Text(value) },
                        )
                    }
                }
            }

            // --- Train -----------------------------------------------------------
            if (options.trains.isNotEmpty()) {
                SectionTitle(stringResource(R.string.filter_train))
                ChipRow {
                    AnyChip(selected = filters.trainIdentifier == null) {
                        onFiltersChanged(filters.copy(trainIdentifier = null))
                    }
                    options.trains.forEach { value ->
                        FilterChip(
                            selected = filters.trainIdentifier == value,
                            onClick = {
                                onFiltersChanged(
                                    filters.copy(
                                        trainIdentifier = value.takeIf { filters.trainIdentifier != value },
                                    ),
                                )
                            },
                            label = { Text(value) },
                        )
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Clears filters only — the selected sort order is intentionally kept.
                TextButton(onClick = onClear, enabled = filters.isActive) {
                    Text(stringResource(R.string.filter_clear))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.filter_apply)) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnyChip(selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(stringResource(R.string.filter_any)) })
}

internal fun ReportSort.labelRes(): Int = when (this) {
    ReportSort.NEWEST_FIRST -> R.string.sort_newest
    ReportSort.OLDEST_FIRST -> R.string.sort_oldest
    ReportSort.CATEGORY_ASC -> R.string.sort_category
    ReportSort.EVENT_TYPE_ASC -> R.string.sort_event_type
    ReportSort.SETTLEMENT_ASC -> R.string.sort_settlement
    ReportSort.TRAIN_ASC -> R.string.sort_train
}

internal fun DateFilter.labelRes(): Int = when (this) {
    DateFilter.ALL -> R.string.filter_date_all
    DateFilter.TODAY -> R.string.filter_date_today
    DateFilter.LAST_7_DAYS -> R.string.filter_date_last7
}
