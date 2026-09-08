package hu.orszembejelento.app.ui.newreport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.orszembejelento.app.R

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Step2Content(state: NewReportUiState, viewModel: NewReportViewModel) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.step2_title), style = MaterialTheme.typography.titleLarge)

        when {
            state.catalogLoading -> CircularProgressIndicator()
            state.catalogFailed -> Column {
                Text(stringResource(R.string.catalog_load_failed), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::onRetryCatalog) { Text(stringResource(R.string.action_retry)) }
            }
            else -> {
                Text(stringResource(R.string.category_choose_title), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.catalog.forEach { category ->
                        FilterChip(
                            selected = state.selectedCategoryCode == category.code,
                            onClick = { viewModel.onCategorySelected(category.code) },
                            label = { Text(category.displayName) },
                        )
                    }
                }

                val eventTypes = state.catalog.firstOrNull { it.code == state.selectedCategoryCode }?.eventTypes.orEmpty()
                if (eventTypes.isNotEmpty()) {
                    Text(stringResource(R.string.event_type_choose_title), style = MaterialTheme.typography.titleMedium)
                    LazyColumn {
                        items(eventTypes) { eventType ->
                            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
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
