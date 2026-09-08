package hu.orszembejelento.app.location

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import hu.orszembejelento.app.R

/**
 * Exact product copy from the Phase 5 brief §31 - do not change without owner approval.
 * The Hungarian text itself lives in strings.xml (§52); this composable only wires it up.
 */
@Composable
fun LocationOffDialog(onDismiss: () -> Unit, onOpenSettings: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.location_off_title)) },
        text = { Text(stringResource(R.string.location_off_message)) },
        confirmButton = {
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.location_off_settings)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.location_off_cancel)) }
        },
    )
}

/** Launches the location-source settings screen, but only if something can actually resolve it (§31). */
fun openLocationSourceSettingsIfResolvable(context: Context): Boolean {
    val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
    if (intent.resolveActivity(context.packageManager) == null) return false
    context.startActivity(intent)
    return true
}
