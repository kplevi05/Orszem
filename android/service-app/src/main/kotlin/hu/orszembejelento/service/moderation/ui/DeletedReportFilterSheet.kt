package hu.orszembejelento.service.moderation.ui

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
import hu.orszembejelento.service.common.ui.MODERATION_REASONS
import hu.orszembejelento.service.common.ui.moderationReasonLabelRes
import hu.orszembejelento.service.moderation.data.DeletedReportFilter
import hu.orszembejelento.service.reports.ui.AreaChoice

/**
 * The deleted-report filter sheet (brief §18/§45) - compact, consistent with
 * [hu.orszembejelento.service.reports.ui.ReportFilterSheet]'s style. Only what the backend
 * supports: reason and area - the caller's own legitimately-available area choices, never a
 * hardcoded list. Free text stays on the list screen's own search field, untouched here.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DeletedReportFilterSheet(
    current: DeletedReportFilter,
    areaChoices: List<AreaChoice>,
    onApply: (DeletedReportFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    var areaId by remember { mutableStateOf(current.areaId) }
    var reason by remember { mutableStateOf(current.reason) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            // `ModalBottomSheet`'s own default windowInsets do not clear the 3-button
            // navigation bar under edge-to-edge (brief correction §1) - `navigationBarsPadding()`
            // is the standard Compose/Material inset API for that, applied on top of (not
            // instead of) the sheet's own visual bottom margin, so it scales with whatever the
            // real system bar height is rather than a guessed constant.
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.filters_title), style = MaterialTheme.typography.titleLarge)

            if (areaChoices.isNotEmpty()) {
                Text(stringResource(R.string.filter_area), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

            Text(stringResource(R.string.filter_deleted_reason), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MODERATION_REASONS.forEach { r ->
                    FilterChip(
                        selected = reason == r,
                        onClick = { reason = if (reason == r) null else r },
                        label = { Text(stringResource(moderationReasonLabelRes(r))) },
                    )
                }
            }

            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onApply(current.copy(areaId = areaId, reason = reason)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.action_apply)) }
                TextButton(
                    onClick = { areaId = null; reason = null },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.action_clear_filters)) }
            }
        }
    }
}
