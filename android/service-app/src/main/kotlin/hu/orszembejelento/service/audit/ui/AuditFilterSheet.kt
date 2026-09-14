package hu.orszembejelento.service.audit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.orszembejelento.service.R
import hu.orszembejelento.service.audit.data.AuditFilter
import hu.orszembejelento.service.audit.data.AuditOptionsResponse
import hu.orszembejelento.service.audit.data.AuditPeriod

/**
 * `Változási előzmények` filter sheet (brief §49/§50). Every option comes from the backend's
 * own `/audit/options` response ([options]) - nothing about which event/target types exist is
 * hard-coded here, only their Hungarian labels ([auditEventTypeLabelRes]/[auditTargetTypeLabelRes]).
 * The event-type group is deliberately a vertical, full-width selectable list (brief §50 -
 * "there may now be many event types... do not squeeze 15+ long Hungarian labels into one
 * horizontal row"), unlike the short period/target-type groups, which stay `FlowRow` chips
 * exactly like every earlier Phase 8-11 filter sheet.
 *
 * `navigationBarsPadding()` clears 3-button navigation, mirroring every earlier filter sheet.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AuditFilterSheet(
    current: AuditFilter,
    options: AuditOptionsResponse?,
    onApply: (AuditFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    var period by remember { mutableStateOf(current.period) }
    var eventType by remember { mutableStateOf(current.eventType) }
    var targetType by remember { mutableStateOf(current.targetType) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.filters_title), style = MaterialTheme.typography.titleLarge)

            // Reuses the Phase 11 analytics string verbatim (brief §26: "do not create a second
            // inconsistent localization table when an existing mapper can be reused safely").
            FilterGroup(stringResource(R.string.analytics_filter_period_group)) {
                auditPeriodChoices().forEach { (value, labelRes) ->
                    FilterChip(selected = period == value, onClick = { period = value }, label = { Text(stringResource(labelRes)) })
                }
            }

            FilterGroup(stringResource(R.string.audit_filter_target_type_group)) {
                FilterChip(selected = targetType == null, onClick = { targetType = null }, label = { Text(stringResource(R.string.audit_target_type_all)) })
                options?.targetTypes?.forEach { code ->
                    val labelRes = auditTargetTypeLabelRes(code) ?: return@forEach
                    FilterChip(selected = targetType == code, onClick = { targetType = code }, label = { Text(stringResource(labelRes)) })
                }
            }

            Text(stringResource(R.string.audit_filter_event_type_group), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                EventTypeRow(label = stringResource(R.string.audit_event_type_all), selected = eventType == null, onClick = { eventType = null })
                (options?.eventTypes ?: emptyList())
                    .sortedBy { code -> AUDIT_EVENT_TYPE_ORDER.indexOf(code).let { if (it < 0) Int.MAX_VALUE else it } }
                    .forEach { code ->
                        val labelRes = auditEventTypeLabelRes(code) ?: return@forEach
                        EventTypeRow(label = stringResource(labelRes), selected = eventType == code, onClick = { eventType = code })
                    }
            }

            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onApply(current.copy(period = period, eventType = eventType, targetType = targetType)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.action_apply)) }
                TextButton(
                    onClick = { period = AuditPeriod.LAST_30_DAYS; eventType = null; targetType = null },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.action_clear_filters)) }
            }
        }
    }
}

@Composable
private fun EventTypeRow(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterGroup(title: String, content: @Composable androidx.compose.foundation.layout.FlowRowScope.() -> Unit) {
    Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}
