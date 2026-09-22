package hu.orszembejelento.service.audit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import hu.orszembejelento.service.audit.data.AuditListItemResponse
import hu.orszembejelento.service.common.ui.EmptyState
import hu.orszembejelento.service.common.ui.ErrorState
import hu.orszembejelento.service.common.ui.FullScreenLoading
import hu.orszembejelento.service.common.ui.LoadMoreButton
import hu.orszembejelento.service.common.ui.apiErrorMessage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

private val LIST_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy. MM. dd. HH:mm").withZone(ZoneId.of("Europe/Budapest"))
private const val MIN_QUERY_LENGTH = 2

/**
 * `Változási előzmények` (Phase 12 brief §47-53) - SUPER_ADMIN only, reached from the
 * Adminisztráció hub. Read-only: search, filter, paginate, open a detail - never an edit,
 * delete, restore or "undo" control anywhere on this screen (brief §43).
 *
 * Field-test fix (§5): an explicit in-app back arrow in its own [TopAppBar], on top of the
 * system Back this already honoured - calls the same [onBack] the caller wires to
 * `popBackStack()`, never a second, parallel way back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditListScreen(viewModel: AuditListViewModel, onOpenEvent: (String) -> Unit, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var searchText by remember { mutableStateOf(state.filter.query.orEmpty()) }
    var showFilters by remember { mutableStateOf(false) }

    LaunchedEffect(searchText) {
        delay(400)
        val normalized = searchText.trim()
        // Client-side mirror of the backend's own minimum-query-length rule (brief §17/§48) -
        // never fires a request the backend would reject as VALIDATION_ERROR; a single
        // character simply does not search yet, exactly like leaving the field blank.
        if (normalized.length in 1 until MIN_QUERY_LENGTH) return@LaunchedEffect
        val newQuery = normalized.ifBlank { null }
        if (newQuery != state.filter.query) viewModel.updateFilter(state.filter.copy(query = newQuery))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.audit_admin_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_description_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(
                    stringResource(R.string.audit_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    placeholder = { Text(stringResource(R.string.audit_search_placeholder)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.content_description_search)) },
                    trailingIcon = {
                        Row {
                            IconButton(onClick = { showFilters = true }) {
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
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }

            when {
                state.loading && state.items.isEmpty() && state.error == null -> FullScreenLoading()
                state.error != null && state.items.isEmpty() ->
                    ErrorState(
                        message = stringResource(R.string.audit_load_error),
                        onRetry = viewModel::refresh,
                        retryLabel = stringResource(R.string.analytics_retry),
                    )
                state.items.isEmpty() -> EmptyState(stringResource(R.string.audit_empty))
                else -> Column(modifier = Modifier.fillMaxSize()) {
                    if (state.error != null) {
                        AuditErrorBanner(
                            message = apiErrorMessage(state.error!!),
                            onRetry = viewModel::refresh,
                            retryEnabled = !state.loading,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                        items(state.items, key = { it.auditEventId }) { item ->
                            AuditListItemCard(item, onClick = { onOpenEvent(item.auditEventId) })
                        }
                        item {
                            if (state.canLoadMore) {
                                LoadMoreButton(loading = state.loadingMore, onClick = viewModel::loadMore)
                            }
                        }
                    }
                }
            }
        }

        if (showFilters) {
            AuditFilterSheet(
                current = state.filter,
                options = state.options,
                onApply = { filter -> showFilters = false; viewModel.updateFilter(filter) },
                onDismiss = { showFilters = false },
            )
        }
    }
}

/** Mirrors [hu.orszembejelento.service.analytics.ui.AnalyticsScreen]'s own `AnalyticsErrorBanner` (Phase 11 correction pass) - the stale-data-retained error surface with its own explicit "Újrapróbálás" action. */
@Composable
private fun AuditErrorBanner(message: String, onRetry: () -> Unit, retryEnabled: Boolean, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onRetry, enabled = retryEnabled, modifier = Modifier.align(Alignment.End).padding(top = 4.dp)) {
                Text(stringResource(R.string.analytics_retry))
            }
        }
    }
}

/**
 * One history card (brief §51): event title, then target, then actor, then timestamp - never
 * crammed onto one line (the Phase 9 layout lesson this app keeps re-applying). A null
 * [AuditListItemResponse.eventType] (a code this build doesn't recognise, brief §6) still
 * renders safely as [R.string.audit_generic_event_title].
 */
@Composable
private fun AuditListItemCard(item: AuditListItemResponse, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), onClick = onClick) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                item.eventType?.let { auditEventTypeLabelRes(it) }?.let { stringResource(it) } ?: stringResource(R.string.audit_generic_event_title),
                style = MaterialTheme.typography.titleSmall,
            )
            AuditTargetLine(targetType = item.targetType, displayLabel = item.targetDisplayLabel)
            if (item.summary.isNotEmpty()) {
                // `joinToString`'s `transform` parameter is not composable-context-safe even
                // though the function itself is inline; `buildString`+`forEachIndexed` is.
                val summaryText = buildString {
                    item.summary.forEachIndexed { index, detail ->
                        if (index > 0) append(" → ")
                        append(auditDetailValueLabel(detail.code, detail.value))
                    }
                }
                Text(summaryText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                stringResource(R.string.audit_executed_by_prefix, item.actorServiceId ?: stringResource(R.string.audit_actor_system)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatAuditTimestamp(item.occurredAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Target presentation (brief §53) - a fixed "type: label" line, entirely safe values, never a UUID. */
@Composable
internal fun AuditTargetLine(targetType: String?, displayLabel: String?) {
    val text = when (targetType) {
        "USER" -> displayLabel?.let { stringResource(R.string.audit_target_prefix_user, it) }
        "REPORT" -> displayLabel?.let { stringResource(R.string.audit_target_prefix_report, it) }
        "SERVICE_AREA" -> displayLabel?.let { stringResource(R.string.audit_target_prefix_service_area, it) }
        "RAILWAY_LINE" -> displayLabel?.let { stringResource(R.string.audit_target_prefix_railway_line, it) }
        "SESSION" -> stringResource(R.string.audit_target_generic_session)
        "REFERENCE_DATASET" -> stringResource(R.string.audit_target_generic_reference_dataset)
        else -> null
    } ?: targetType?.let { auditTargetTypeLabelRes(it) }?.let { stringResource(it) }
    if (text != null) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Europe/Budapest, date + hour + minute (brief §13's list precision), never a raw ISO string. */
internal fun formatAuditTimestamp(iso: String): String = runCatching { LIST_TIME_FORMATTER.format(Instant.parse(iso)) }.getOrDefault(iso)
