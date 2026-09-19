package hu.orszembejelento.app.ui.newreport

import android.content.res.Configuration
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.orszembejelento.app.R
import hu.orszembejelento.app.ui.components.HungarianDateTime
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

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

    Column {
        Text(stringResource(R.string.field_occurred_at), style = MaterialTheme.typography.labelLarge)
        Row {
            OutlinedButton(onClick = { showDatePicker = true }) {
                Text(zoned.toLocalDate().format(HungarianDateTime.DATE))
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { showTimePicker = true }) {
                Text(zoned.toLocalTime().format(HungarianDateTime.TIME))
            }
        }
    }

    if (showDatePicker) {
        // Outer wrapper: the calendar state builds its month/weekday names from the locale in
        // effect when it is created. Inner wrapper (inside the dialog): see [HungarianPickerLocale].
        HungarianPickerLocale {
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
                    }) { Text(stringResource(R.string.action_confirm)) }
                },
                dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.action_cancel)) } },
            ) {
                HungarianPickerLocale { DatePicker(state = state) }
            }
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
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text(stringResource(R.string.action_cancel)) } },
            text = { HungarianPickerLocale { TimePicker(state = state) } },
        )
    }
}

private val PICKER_LOCALE: Locale = Locale.forLanguageTag("hu-HU")

/**
 * Material's pickers draw all of their own text (title, headline, month and weekday names,
 * every accessibility label) from the *device* locale, so on an en-US phone they are English
 * inside this Hungarian app. Material already ships complete Hungarian translations; it just
 * is not asked for them. This gives ONE picker subtree a hu-HU configuration and resources.
 *
 * It must be applied *inside* the dialog: every `Dialog` window hosts its own compose view,
 * which re-provides `LocalContext`/`LocalConfiguration`/`LocalResources` from the window, so
 * an override placed outside the dialog never reaches the picker. Deliberately local: no
 * app-wide locale, no manifest or AppCompat locale configuration, and nothing else is affected.
 */
@Composable
private fun HungarianPickerLocale(content: @Composable () -> Unit) {
    val base = LocalContext.current
    val current = LocalConfiguration.current
    val configuration = remember(current) { Configuration(current).apply { setLocale(PICKER_LOCALE) } }
    val localizedContext = remember(base, configuration) { base.createConfigurationContext(configuration) }
    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides configuration,
        LocalResources provides localizedContext.resources,
        content = content,
    )
}
