package hu.orszembejelento.service.audit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.orszembejelento.service.R
import hu.orszembejelento.service.audit.data.AuditEventDetailResponse
import hu.orszembejelento.service.common.ui.ErrorState
import hu.orszembejelento.service.common.ui.FullScreenLoading
import hu.orszembejelento.service.common.ui.apiErrorMessage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DETAIL_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy. MM. dd. HH:mm:ss").withZone(ZoneId.of("Europe/Budapest"))

/**
 * `Esemény részletei` (brief §54/§55) - four sections (Esemény/Végrehajtotta/Érintett elem/
 * Részletek), entirely safe values. No mutation control of any kind exists on this screen
 * (brief §43/§62): no restore, no undo, no re-execute, and no link out to any domain-editing
 * screen (user edit, report workflow action, ServiceArea mutation).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditDetailScreen(viewModel: AuditDetailViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.audit_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading && state.detail == null -> FullScreenLoading()
                state.error != null && state.detail == null ->
                    ErrorState(
                        message = stringResource(R.string.audit_load_error),
                        onRetry = viewModel::load,
                        retryLabel = stringResource(R.string.analytics_retry),
                    )
                state.detail != null -> AuditDetailContent(state.detail!!)
            }
        }
    }
}

@Composable
private fun AuditDetailContent(detail: AuditEventDetailResponse) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DetailSection(stringResource(R.string.audit_detail_section_event)) {
            Text(
                detail.eventType?.let { auditEventTypeLabelRes(it) }?.let { stringResource(it) } ?: stringResource(R.string.audit_generic_event_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                runCatching { DETAIL_TIME_FORMATTER.format(Instant.parse(detail.occurredAt)) }.getOrDefault(detail.occurredAt),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        DetailSection(stringResource(R.string.audit_detail_section_actor)) {
            Text(detail.actorServiceId ?: stringResource(R.string.audit_actor_system), style = MaterialTheme.typography.bodyMedium)
        }

        DetailSection(stringResource(R.string.audit_detail_section_target)) {
            AuditTargetLine(targetType = detail.targetType, displayLabel = detail.targetDisplayLabel)
        }

        if (detail.details.isNotEmpty()) {
            DetailSection(stringResource(R.string.audit_detail_section_details)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    detail.details.forEach { DetailRow(auditDetailCodeLabel(it.code), auditDetailValueLabel(it.code, it.value)) }
                }
            }
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.padding(top = 6.dp)) { content() }
    }
}

/**
 * Key on its own line, value on the next (never squeezed side by side) - the same layout
 * reasoning the Phase 9/10/11 correction passes already applied to every other long-Hungarian-
 * label row in this app, needed here even more: a ServiceArea name can be long, a Service ID
 * is short, and this row has to hold both classes of value safely at font scale 1.3.
 */
@Composable
private fun DetailRow(label: String, value: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
