package hu.orszembejelento.app.ui.newreport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.orszembejelento.app.R
import hu.orszembejelento.app.ui.PublicPalette
import hu.orszembejelento.app.ui.components.PillTone
import hu.orszembejelento.app.ui.components.SoftCard
import hu.orszembejelento.app.ui.components.StatusPill

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Step2Content(state: NewReportUiState, viewModel: NewReportViewModel) {
    SoftCard {
        when {
            state.catalogLoading -> CircularProgressIndicator()
            state.catalogFailed -> Column {
                StatusPill(
                    text = stringResource(R.string.catalog_load_failed),
                    tone = PillTone.ERROR,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                TextButton(onClick = viewModel::onRetryCatalog) { Text(stringResource(R.string.action_retry)) }
            }
            else -> {
                Text(stringResource(R.string.category_choose_title), style = MaterialTheme.typography.titleMedium)
                FlowRow(
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.catalog.forEach { category ->
                        FilterChip(
                            selected = state.selectedCategoryCode == category.code,
                            onClick = { viewModel.onCategorySelected(category.code) },
                            label = { Text(category.displayName) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = PublicPalette.Primary,
                                selectedLabelColor = PublicPalette.OnPrimary,
                            ),
                        )
                    }
                }

                val eventTypes = state.catalog.firstOrNull { it.code == state.selectedCategoryCode }?.eventTypes.orEmpty()
                if (eventTypes.isNotEmpty()) {
                    Text(
                        stringResource(R.string.event_type_choose_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Column(modifier = Modifier.padding(top = 4.dp)) {
                        eventTypes.forEach { eventType ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                colors = CardDefaults.cardColors(containerColor = PublicPalette.SurfaceTint),
                            ) {
                                TextButton(onClick = { viewModel.onEventTypeSelected(eventType.code) }) {
                                    RadioButton(
                                        selected = state.selectedEventTypeCode == eventType.code,
                                        onClick = { viewModel.onEventTypeSelected(eventType.code) },
                                    )
                                    Text(eventType.displayName)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
