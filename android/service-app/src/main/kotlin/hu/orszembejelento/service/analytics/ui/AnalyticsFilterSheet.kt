package hu.orszembejelento.service.analytics.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.orszembejelento.service.R
import hu.orszembejelento.service.analytics.data.AnalyticsAreaOptionsResponse
import hu.orszembejelento.service.analytics.data.AnalyticsFilter
import hu.orszembejelento.service.analytics.data.AnalyticsPeriod
import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.reports.data.CatalogRepository
import hu.orszembejelento.service.reports.data.ReportCatalogResponse

/**
 * `Statisztika` filter sheet (brief §37-39). Every option comes from backend truth
 * ([areaOptions] and the shared Public catalogue) - nothing is hard-coded except the four
 * frozen period labels themselves (brief §36: "Android labels may be locally mapped because
 * this is frozen product vocabulary"). `areaId`/`unclassifiedOnly` are kept mutually
 * exclusive here directly at the source, mirroring the backend's own validation (brief §15) -
 * selecting one always clears the other, so `onApply` can never even construct the invalid
 * combination.
 *
 * `navigationBarsPadding()` clears 3-button navigation under edge-to-edge exactly like
 * [hu.orszembejelento.service.reports.ui.ReportFilterSheet]/[hu.orszembejelento.service.moderation.ui.DeletedReportFilterSheet].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AnalyticsFilterSheet(
    current: AnalyticsFilter,
    areaOptions: AnalyticsAreaOptionsResponse?,
    catalogRepository: CatalogRepository,
    onApply: (AnalyticsFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    var period by remember { mutableStateOf(current.period) }
    var areaId by remember { mutableStateOf(current.areaId) }
    var unclassifiedOnly by remember { mutableStateOf(current.unclassifiedOnly) }
    var categoryCode by remember { mutableStateOf(current.categoryCode) }

    var catalog by remember { mutableStateOf<ReportCatalogResponse?>(null) }
    LaunchedEffect(Unit) {
        when (val result = catalogRepository.catalog()) {
            is ApiResult.Success -> catalog = result.value
            else -> Unit
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.filters_title), style = MaterialTheme.typography.titleLarge)

            FilterGroup(stringResource(R.string.analytics_filter_period_group)) {
                periodChoices().forEach { (value, labelRes) ->
                    FilterChip(selected = period == value, onClick = { period = value }, label = { Text(stringResource(labelRes)) })
                }
            }

            FilterGroup(stringResource(R.string.filter_area)) {
                FilterChip(
                    selected = areaId == null && !unclassifiedOnly,
                    onClick = { areaId = null; unclassifiedOnly = false },
                    label = { Text(stringResource(R.string.analytics_area_all)) },
                )
                areaOptions?.areas?.forEach { area ->
                    val label = if (area.active) area.name else "${area.name} (${stringResource(R.string.analytics_area_inactive_suffix)})"
                    FilterChip(
                        selected = areaId == area.id,
                        onClick = { areaId = area.id; unclassifiedOnly = false },
                        label = { Text(label) },
                    )
                }
                if (areaOptions?.canViewUnclassified == true) {
                    FilterChip(
                        selected = unclassifiedOnly,
                        onClick = { unclassifiedOnly = true; areaId = null },
                        label = { Text(stringResource(R.string.analytics_area_unclassified)) },
                    )
                }
            }

            if (catalog == null) {
                Text(stringResource(R.string.filter_catalog_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            FilterGroup(stringResource(R.string.filter_category)) {
                FilterChip(selected = categoryCode == null, onClick = { categoryCode = null }, label = { Text(stringResource(R.string.analytics_category_all)) })
                catalog?.categories?.forEach { category ->
                    FilterChip(
                        selected = categoryCode == category.code,
                        onClick = { categoryCode = category.code },
                        label = { Text(category.displayName) },
                    )
                }
            }

            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onApply(current.copy(period = period, areaId = areaId, unclassifiedOnly = unclassifiedOnly, categoryCode = categoryCode)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.action_apply)) }
                TextButton(
                    onClick = { period = AnalyticsPeriod.LAST_30_DAYS; areaId = null; unclassifiedOnly = false; categoryCode = null },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.action_clear_filters)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterGroup(title: String, content: @Composable FlowRowScope.() -> Unit) {
    Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}
