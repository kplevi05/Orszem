package hu.orszembejelento.service.usermanagement.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import hu.orszembejelento.service.R

/**
 * The one-time temporary-credential display (brief §56-58) - shown exactly once, immediately
 * after a create-user or password-reset response, and never re-derivable afterward.
 *
 * Deliberately holds nothing beyond the two plain [String] parameters passed in from the
 * *current* API response: no ViewModel field this composable itself creates, no
 * SavedStateHandle/navigation-argument round-trip, so the credential cannot leak into any
 * persistence layer through this screen. The caller (`UserDetailScreen`/`CreateUserScreen`)
 * is responsible for the same discipline - see their own `newCredential`/created-credential
 * state, which is cleared the moment this dialog is dismissed and never written anywhere else.
 */
@Composable
fun CredentialDisplayDialog(
    titleRes: Int,
    serviceId: String,
    temporaryCredential: String,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.credential_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    Text(stringResource(R.string.field_new_service_id), style = MaterialTheme.typography.labelSmall)
                    Text(serviceId, style = MaterialTheme.typography.titleMedium)
                }
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    Text(stringResource(R.string.field_temporary_credential), style = MaterialTheme.typography.labelSmall)
                    Text(temporaryCredential, style = MaterialTheme.typography.titleMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_saved_it)) }
        },
        dismissButton = {
            TextButton(onClick = {
                // Explicit, user-initiated only (brief §56) - the app never copies this
                // automatically, and makes no claim about clipboard secrecy afterward.
                clipboard.setText(AnnotatedString(temporaryCredential))
            }) {
                Text(stringResource(R.string.action_copy))
            }
        },
    )
}
