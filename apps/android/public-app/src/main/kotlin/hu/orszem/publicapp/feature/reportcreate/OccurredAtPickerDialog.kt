package hu.orszem.publicapp.feature.reportcreate

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Two-step date then time picker. Defaults to the value already in the form (initially "now"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OccurredAtPickerDialog(
    initial: Instant,
    onConfirm: (Instant) -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val initialLocal = LocalDateTime.ofInstant(initial, zone)
    var step by remember { mutableStateOf(0) }
    var pickedDate by remember { mutableStateOf(initialLocal.toLocalDate()) }

    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = initialLocal.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    val timeState = rememberTimePickerState(
        initialHour = initialLocal.hour,
        initialMinute = initialLocal.minute,
        is24Hour = true,
    )

    if (step == 0) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let {
                        pickedDate = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    step = 1
                }) { Text("Tovább") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Mégse") } },
        ) { DatePicker(state = dateState) }
    } else {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    val local = LocalDateTime.of(
                        pickedDate ?: LocalDate.now(),
                        java.time.LocalTime.of(timeState.hour, timeState.minute),
                    )
                    onConfirm(local.atZone(zone).toInstant())
                }) { Text("Kész") }
            },
            dismissButton = { TextButton(onClick = { step = 0 }) { Text("Vissza") } },
        ) { TimePicker(state = timeState) }
    }
}
