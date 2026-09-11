package hu.orszembejelento.service.reports.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.reports.data.CatalogRepository
import hu.orszembejelento.service.reports.data.ReportCatalogResponse
import hu.orszembejelento.service.reports.data.ReportFilter
import kotlinx.coroutines.delay

/** One selectable service area, already narrowed to what the current actor may legitimately pick. */
data class AreaChoice(val id: String, val name: String)

/**
 * The report filter sheet (brief §42). Every option comes from backend catalogue / reference
 * truth — nothing is hard-coded. Applying calls back with a new [ReportFilter]; the queue
 * ViewModel resets paging on `updateFilter`. Free-text search and the assignee CTA filter are
 * preserved, never cleared here.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReportFilterSheet(
    current: ReportFilter,
    areaChoices: List<AreaChoice>,
    catalogRepository: CatalogRepository,
    onApply: (ReportFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    var catalog by remember { mutableStateOf<ReportCatalogResponse?>(null) }
    var catalogFailed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        when (val result = catalogRepository.catalog()) {
            is ApiResult.Success -> catalog = result.value
            else -> catalogFailed = true
        }
    }

    var areaId by remember { mutableStateOf(current.areaId) }
    var categoryCode by remember { mutableStateOf(current.categoryCode) }
    var eventTypeCode by remember { mutableStateOf(current.eventTypeCode) }
    var settlementId by remember { mutableStateOf(current.settlementId) }
    var settlementName by remember { mutableStateOf(current.settlementName) }
    var settlementQuery by remember { mutableStateOf("") }
    var settlementResults by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    LaunchedEffect(settlementQuery) {
        if (settlementQuery.length < 2) {
            settlementResults = emptyList()
            return@LaunchedEffect
        }
        delay(350)
        when (val result = catalogRepository.searchSettlements(settlementQuery)) {
            is ApiResult.Success -> settlementResults = result.value.map { it.id to it.name }
            else -> settlementResults = emptyList()
        }
    }

    val selectedCategory = catalog?.categories?.firstOrNull { it.code == categoryCode }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.filters_title), style = MaterialTheme.typography.titleLarge)

            if (areaChoices.isNotEmpty()) {
                FilterGroup(stringResource(R.string.filter_area)) {
                    FilterChip(selected = areaId == null, onClick = { areaId = null }, label = { Text(stringResource(R.string.filter_all_areas)) })
                    areaChoices.forEach { choice ->
                        FilterChip(
                            selected = areaId == choice.id,
                            onClick = { areaId = if (areaId == choice.id) null else choice.id },
                            label = { Text(choice.name) },
                        )
                    }
                }
            }

            if (catalogFailed && catalog == null) {
                Text(stringResource(R.string.filter_catalog_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            catalog?.let { cat ->
                FilterGroup(stringResource(R.string.filter_category)) {
                    cat.categories.forEach { category ->
                        FilterChip(
                            selected = categoryCode == category.code,
                            onClick = {
                                if (categoryCode == category.code) {
                                    categoryCode = null
                                } else {
                                    categoryCode = category.code
                                }
                                eventTypeCode = null // event types belong to a category
                            },
                            label = { Text(category.displayName) },
                        )
                    }
                }
                selectedCategory?.let { category ->
                    if (category.eventTypes.isNotEmpty()) {
                        FilterGroup(stringResource(R.string.filter_event_type)) {
                            category.eventTypes.forEach { event ->
                                FilterChip(
                                    selected = eventTypeCode == event.code,
                                    onClick = { eventTypeCode = if (eventTypeCode == event.code) null else event.code },
                                    label = { Text(event.displayName) },
                                )
                            }
                        }
                    }
                }
            }

            FilterGroup(stringResource(R.string.filter_settlement)) {}
            settlementName?.let { name ->
                FilterChip(selected = true, onClick = { settlementId = null; settlementName = null }, label = { Text(name) })
            }
            OutlinedTextField(
                value = settlementQuery,
                onValueChange = { settlementQuery = it },
                placeholder = { Text(stringResource(R.string.filter_settlement_search_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            settlementResults.forEach { (id, name) ->
                TextButton(onClick = {
                    settlementId = id
                    settlementName = name
                    settlementQuery = ""
                    settlementResults = emptyList()
                }) { Text(name) }
            }

            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onApply(
                            current.copy(
                                areaId = areaId,
                                categoryCode = categoryCode,
                                eventTypeCode = eventTypeCode,
                                settlementId = settlementId,
                                settlementName = settlementName,
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.action_apply)) }
                TextButton(
                    onClick = {
                        areaId = null; categoryCode = null; eventTypeCode = null; settlementId = null; settlementName = null
                    },
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
