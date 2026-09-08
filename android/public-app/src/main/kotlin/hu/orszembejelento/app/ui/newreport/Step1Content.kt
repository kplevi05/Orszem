package hu.orszembejelento.app.ui.newreport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import hu.orszembejelento.app.R
import hu.orszembejelento.app.report.data.SettlementOption
import hu.orszembejelento.app.report.domain.LineAnswer
import hu.orszembejelento.app.report.domain.RailwayLineStep

@Composable
fun Step1Content(state: NewReportUiState, viewModel: NewReportViewModel, onLocateMe: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.step1_title), style = MaterialTheme.typography.titleLarge)

        OccurredAtPicker(value = state.occurredAt, onValueChange = viewModel::onOccurredAtChanged)

        OutlinedTextField(
            value = state.trainIdentifierInput,
            onValueChange = viewModel::onTrainIdentifierChanged,
            label = { Text(stringResource(R.string.field_train_identifier)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        SettlementField(state, viewModel)

        Button(onClick = onLocateMe) {
            Icon(Icons.Filled.LocationOn, contentDescription = null)
            Text(" " + stringResource(R.string.action_locate_me))
        }
        LocateStatusMessage(state.locateStatus)

        RailwayLineSection(state, viewModel)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettlementField(state: NewReportUiState, viewModel: NewReportViewModel) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && state.settlementResults.isNotEmpty(), onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = state.settlementQuery,
            onValueChange = {
                viewModel.onSettlementQueryChanged(it)
                expanded = true
            },
            label = { Text(stringResource(R.string.field_settlement)) },
            placeholder = { Text(stringResource(R.string.field_settlement_hint)) },
            trailingIcon = { if (state.settlementSearching) CircularProgressIndicator(modifier = Modifier.size(20.dp)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
        )
        DropdownMenu(expanded = expanded && state.settlementResults.isNotEmpty(), onDismissRequest = { expanded = false }) {
            state.settlementResults.forEach { settlement: SettlementOption ->
                DropdownMenuItem(
                    text = { Text(settlement.name + (settlement.countyName?.let { " ($it)" } ?: "")) },
                    onClick = {
                        viewModel.onSettlementSelected(settlement)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun LocateStatusMessage(status: LocateStatus) {
    val textRes = when (status) {
        LocateStatus.LOCATING -> R.string.locate_me_searching
        LocateStatus.FAILED -> R.string.locate_me_failed
        LocateStatus.PERMISSION_DENIED -> R.string.locate_me_permission_denied
        LocateStatus.PERMISSION_PERMANENTLY_DENIED -> R.string.locate_me_permission_permanently_denied
        LocateStatus.GEOCODER_UNAVAILABLE -> R.string.locate_me_geocoder_unavailable
        LocateStatus.IDLE, LocateStatus.SERVICES_DISABLED -> null
    }
    if (textRes != null) {
        Text(stringResource(textRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RailwayLineSection(state: NewReportUiState, viewModel: NewReportViewModel) {
    when (val step = state.lineStep) {
        RailwayLineStep.NotApplicable, RailwayLineStep.Loading -> {
            if (step == RailwayLineStep.Loading) CircularProgressIndicator(modifier = Modifier.size(20.dp))
        }
        RailwayLineStep.LoadFailed -> Text(
            stringResource(R.string.catalog_load_failed),
            color = MaterialTheme.colorScheme.error,
        )
        is RailwayLineStep.NoVerifiedCandidate -> Text(
            stringResource(R.string.line_none_verified),
            style = MaterialTheme.typography.bodyMedium,
        )
        is RailwayLineStep.SingleInferred -> Text(
            stringResource(R.string.line_auto_identified, step.option.displayName),
            style = MaterialTheme.typography.bodyMedium,
        )
        is RailwayLineStep.RequiresChoice -> Column {
            Text(stringResource(R.string.line_choose_title), style = MaterialTheme.typography.titleMedium)
            step.options.forEach { option ->
                LineChoiceRow(
                    label = option.displayName,
                    selected = (state.lineAnswer as? LineAnswer.Chosen)?.option?.id == option.id,
                    onClick = { viewModel.onLineOptionChosen(option) },
                )
            }
            val unsureLabel = if (step.coverage.name == "PARTIAL") {
                stringResource(R.string.line_choose_unsure_partial)
            } else {
                stringResource(R.string.line_choose_unsure)
            }
            LineChoiceRow(
                label = unsureLabel,
                selected = state.lineAnswer == LineAnswer.Unsure,
                onClick = { viewModel.onLineUnsure() },
            )
        }
    }
}

@Composable
private fun LineChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}
