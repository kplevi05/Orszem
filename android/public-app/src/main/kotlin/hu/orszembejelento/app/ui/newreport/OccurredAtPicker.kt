package hu.orszembejelento.app.ui.newreport

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.orszembejelento.app.R
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * A date + time editor for `occurredAt` (§46). Defaults to now; the user may move it into
 * the past freely - no maximum age is enforced here, matching the backend's own stance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OccurredAtPicker(value: Instant, onValueChange: (Instant) -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val zoned = remember(value) { value.atZone(zone) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }

    val dateFormatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    val timeFormatter = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }

    Column {
        Text(stringResource(R.string.field_occurred_at), style = MaterialTheme.typography.labelLarge)
        Row {
            OutlinedButton(onClick = { showDatePicker = true }) {
                Text(zoned.toLocalDate().format(dateFormatter))
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { showTimePicker = true }) {
                Text(zoned.toLocalTime().format(timeFormatter))
            }
        }
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = zoned.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val millis = state.selectedDateMillis
                    if (millis != null) {
                        val newDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        onValueChange(newDate.atTime(zoned.toLocalTime()).atZone(zone).toInstant())
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Mégse") } },
        ) {
            DatePicker(state = state)
        }
    }

    if (showTimePicker) {
        val state = rememberTimePickerState(initialHour = zoned.hour, initialMinute = zoned.minute, is24Hour = true)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val newTime = LocalTime.of(state.hour, state.minute)
                    onValueChange(zoned.toLocalDate().atTime(newTime).atZone(zone).toInstant())
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Mégse") } },
            text = { TimePicker(state = state) },
        )
    }
}
