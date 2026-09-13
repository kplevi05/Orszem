package hu.orszembejelento.service.analytics.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.orszembejelento.service.R
import hu.orszembejelento.service.analytics.data.AnalyticsAreaOptionsResponse
import hu.orszembejelento.service.analytics.data.AnalyticsCategoryCountResponse
import hu.orszembejelento.service.analytics.data.AnalyticsEventTypeCountResponse
import hu.orszembejelento.service.analytics.data.AnalyticsFilter
import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.common.ui.EmptyState
import hu.orszembejelento.service.common.ui.ErrorState
import hu.orszembejelento.service.common.ui.FullScreenLoading
import hu.orszembejelento.service.common.ui.apiErrorMessage
import hu.orszembejelento.service.reports.data.CatalogRepository
import hu.orszembejelento.service.reports.data.ReportCatalogResponse
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * `Statisztika` (Phase 11 brief §33-51): the live analytics dashboard that replaces the former
 * `StatsPlaceholderScreen`. KPI cards, the daily trend, category breakdown and top event types
 * all describe reports RECEIVED in the selected period and their CURRENT workflow state (brief
 * §40/§41) - never a historical status-at-period-end reconstruction. No report-list drilldown,
 * no export, no employee/staff analytics anywhere on this screen (brief §10/§51/§52).
 *
 * Correction pass (owner review): the currently-applied period/area/category scope is now
 * always visible on the dashboard itself, not just as a numeric badge on the filter icon - see
 * [ScopeSummaryRow]. The category name shown there is resolved from the same Public catalogue
 * the filter sheet already uses, fetched here too, because a selected category with zero
 * matching reports would otherwise have no display name available from the summary response
 * itself (its own category breakdown only ever lists categories with count > 0).
 */
@Composable
fun AnalyticsScreen(viewModel: AnalyticsViewModel, catalogRepository: CatalogRepository) {
    val state by viewModel.state.collectAsState()
    var showFilterSheet by remember { mutableStateOf(false) }

    var catalog by remember { mutableStateOf<ReportCatalogResponse?>(null) }
    LaunchedEffect(Unit) {
        when (val result = catalogRepository.catalog()) {
            is ApiResult.Success -> catalog = result.value
            else -> Unit
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.stats_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.analytics_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row {
                IconButton(onClick = { showFilterSheet = true }) {
                    BadgedBox(badge = {
                        if (state.filter.activeFacetCount > 0) Badge { Text(state.filter.activeFacetCount.toString()) }
                    }) {
                        Icon(Icons.Filled.FilterList, contentDescription = stringResource(R.string.action_filters))
                    }
                }
                IconButton(onClick = viewModel::refresh, enabled = !state.loading) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
                }
            }
        }

        // Always visible, in every state (loading/error/content) - the currently-applied
        // period, plus any active non-default area/Besorolatlan/category filter, entirely in
        // Hungarian display names, never a raw code, id or enum value (brief correction §1).
        ScopeSummaryRow(filter = state.filter, areaOptions = state.areaOptions, catalog = catalog)

        when {
            state.loading && state.summary == null -> FullScreenLoading()
            state.error != null && state.summary == null ->
                ErrorState(
                    message = stringResource(R.string.analytics_load_error),
                    onRetry = viewModel::refresh,
                    retryLabel = stringResource(R.string.analytics_retry),
                )
            state.summary == null -> Unit
            else -> {
                val summary = state.summary!!
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (state.error != null) {
                        AnalyticsErrorBanner(
                            message = apiErrorMessage(state.error!!),
                            onRetry = viewModel::refresh,
                            retryEnabled = !state.loading,
                        )
                    }

                    KpiGrid(
                        total = summary.totalReports,
                        newCount = summary.statusCounts.new,
                        inProgress = summary.statusCounts.inProgress,
                        archived = summary.statusCounts.archived,
                    )
                    Text(
                        stringResource(R.string.analytics_status_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (summary.totalReports == 0) {
                        EmptyState(stringResource(R.string.analytics_empty))
                    } else {
                        Section(stringResource(R.string.analytics_trend_title)) {
                            TrendChart(summary.trend, modifier = Modifier.padding(top = 8.dp))
                        }
                        if (summary.categories.isNotEmpty()) {
                            Section(stringResource(R.string.analytics_categories_title)) {
                                val maxCount = summary.categories.maxOf { it.count }
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                    summary.categories.forEach { CategoryRow(it, maxCount) }
                                }
                            }
                        }
                        if (summary.topEventTypes.isNotEmpty()) {
                            Section(stringResource(R.string.analytics_top_events_title)) {
                                Column(modifier = Modifier.padding(top = 8.dp)) {
                                    summary.topEventTypes.forEach { EventTypeRow(it) }
                                }
                            }
                        }
                    }

                    val freshness = runCatching {
                        Instant.parse(summary.generatedAt).atZone(ZoneId.systemDefault()).format(TIME_FORMATTER)
                    }.getOrNull()
                    freshness?.let {
                        Text(
                            stringResource(R.string.analytics_freshness, it),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.padding(bottom = 24.dp))
                }
            }
        }
    }

    if (showFilterSheet) {
        AnalyticsFilterSheet(
            current = state.filter,
            areaOptions = state.areaOptions,
            catalogRepository = catalogRepository,
            onApply = { filter -> showFilterSheet = false; viewModel.updateFilter(filter) },
            onDismiss = { showFilterSheet = false },
        )
    }
}

/**
 * The always-visible current-scope summary (owner correction §1): the period chip is shown
 * unconditionally; an area/`Besorolatlan`/category chip is added only when that filter is
 * active (non-default), never a chip claiming "Minden jogosult terület"/"Minden kategória" -
 * those are the *absence* of a filter, not a fact worth naming in a compact summary. `FlowRow`
 * wraps a chip that does not fit onto its own line rather than ever shrinking its text (brief
 * correction: "Do not reduce text size to make it fit"), verified live at font scale 1.3 and
 * at a narrow phone width.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScopeSummaryRow(
    filter: AnalyticsFilter,
    areaOptions: AnalyticsAreaOptionsResponse?,
    catalog: ReportCatalogResponse?,
) {
    val areaLabel = when {
        filter.unclassifiedOnly -> stringResource(R.string.analytics_area_unclassified)
        filter.areaId != null -> areaOptions?.areas?.firstOrNull { it.id == filter.areaId }?.name
        else -> null
    }
    val categoryLabel = filter.categoryCode?.let { code -> catalog?.categories?.firstOrNull { it.code == code }?.displayName }

    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScopeChip(stringResource(periodLabelRes(filter.period)))
        areaLabel?.let { ScopeChip(it) }
        categoryLabel?.let { ScopeChip(it) }
    }
}

@Composable
private fun ScopeChip(text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * The retained-stale-data error banner (owner correction §2): unlike the plain
 * [hu.orszembejelento.service.common.ui.InlineErrorBanner] every other screen uses, this one
 * carries its own explicit "Újrapróbálás" action - the frozen brief's own exact retry copy
 * (brief §49) - calling the identical [hu.orszembejelento.service.analytics.ui.AnalyticsViewModel.refresh]
 * path the top-right refresh icon already uses. Stacked, not a squeezed side-by-side Row: a
 * long error message must never be forced to share a line with the action (the same layout
 * reasoning the Phase 9/10 correction passes already applied elsewhere). Disabled while a
 * refresh is already in flight so a rapid double-tap can never fire two overlapping requests.
 */
@Composable
private fun AnalyticsErrorBanner(message: String, onRetry: () -> Unit, retryEnabled: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(
                onClick = onRetry,
                enabled = retryEnabled,
                modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
            ) {
                Text(stringResource(R.string.analytics_retry))
            }
        }
    }
}

@Composable
private fun KpiGrid(total: Int, newCount: Int, inProgress: Int, archived: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KpiCard(stringResource(R.string.analytics_kpi_total), total, Modifier.weight(1f))
            KpiCard(stringResource(R.string.analytics_kpi_new), newCount, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KpiCard(stringResource(R.string.analytics_kpi_in_progress), inProgress, Modifier.weight(1f))
            KpiCard(stringResource(R.string.analytics_kpi_archived), archived, Modifier.weight(1f))
        }
    }
}

@Composable
private fun KpiCard(label: String, value: Int, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(value.toString(), style = MaterialTheme.typography.headlineMedium)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

/**
 * Name/count stacked, not a squeezed side-by-side pair claiming fixed count width against a
 * `weight(1f)` name - the same reasoning the Phase 9/10 correction passes already applied to
 * every other long-Hungarian-label row in this app. Both `name` and `count` are short enough
 * in practice that a `Row` is safe here (a real category display name and a bare digit count),
 * unlike the RailwayLine/status-label rows that motivated the stacked fix.
 */
@Composable
private fun CategoryRow(item: AnalyticsCategoryCountResponse, maxCount: Int) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(item.displayName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 8.dp))
            Text(item.count.toString(), style = MaterialTheme.typography.bodyMedium)
        }
        LinearProgressIndicator(
            progress = { item.count.toFloat() / maxCount },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

@Composable
private fun EventTypeRow(item: AnalyticsEventTypeCountResponse) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(item.displayName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Text(item.count.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
