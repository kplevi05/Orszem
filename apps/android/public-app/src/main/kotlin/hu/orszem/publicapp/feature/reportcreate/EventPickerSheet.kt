package hu.orszem.publicapp.feature.reportcreate

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hu.orszem.core.model.EventType
import hu.orszem.publicapp.R

/**
 * Grouped, scrollable, searchable event picker (DEMO_V1_SCREENS §2 P02).
 * Search matches the Hungarian label, case-insensitively. Machine codes are never shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventPickerSheet(
    categories: List<CatalogCategory>,
    onSelected: (EventType) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val normalized = query.trim().lowercase()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.form_search_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        LazyColumn(Modifier.fillMaxWidth().padding(16.dp)) {
            categories.forEach { category ->
                val matches = if (normalized.isEmpty()) {
                    category.eventTypes
                } else {
                    category.eventTypes.filter { it.label.lowercase().contains(normalized) }
                }
                if (matches.isNotEmpty()) {
                    item(key = "cat-${category.code}") {
                        Text(
                            text = category.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(matches, key = { it.code }) { eventType ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelected(eventType) }
                                .padding(vertical = 12.dp),
                        ) {
                            Text(eventType.label, style = MaterialTheme.typography.bodyLarge)
                            eventType.description?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
