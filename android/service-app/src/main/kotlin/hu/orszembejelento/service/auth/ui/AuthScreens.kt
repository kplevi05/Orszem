package hu.orszembejelento.service.auth.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import hu.orszembejelento.service.R
import hu.orszembejelento.service.auth.domain.AuthErrorKind

/**
 * Password entry.
 *
 * Paste is allowed and the value is never trimmed: leading or trailing spaces are characters
 * the user chose, and silently removing them would change their password. A reveal toggle is
 * offered because a hidden field with a long passphrase is a common source of lockouts.
 */
@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Done,
) {
    var revealed by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = imeAction,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = label },
        )
        TextButton(onClick = { revealed = !revealed }) {
            Text(stringResource(if (revealed) R.string.password_hide else R.string.password_show))
        }
    }
}

@Composable
private fun ErrorText(kind: AuthErrorKind?) {
    if (kind == null) return
    val message = when (kind) {
        AuthErrorKind.INVALID_CREDENTIALS -> R.string.error_invalid_credentials
        AuthErrorKind.PASSWORD_POLICY -> R.string.error_password_policy
        AuthErrorKind.RATE_LIMITED -> R.string.error_rate_limited
        AuthErrorKind.NETWORK -> R.string.error_network
        AuthErrorKind.UNEXPECTED -> R.string.error_unexpected
    }
    Text(
        text = stringResource(message),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
fun LoginScreen(
    busy: Boolean,
    error: AuthErrorKind?,
    onLogin: (String, String) -> Unit,
) {
    var serviceId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.login_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.login_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = serviceId,
            onValueChange = { serviceId = it },
            label = { Text(stringResource(R.string.field_service_id)) },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.field_service_id_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )

        PasswordField(
            value = password,
            onValueChange = { password = it },
            label = stringResource(R.string.field_password),
        )

        ErrorText(error)

        Button(
            onClick = { onLogin(serviceId, password) },
            enabled = !busy && serviceId.isNotBlank() && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
            Text(stringResource(R.string.action_login))
        }
    }
}

/**
 * Forced initial password change.
 *
 * Reached only after the temporary credential has been accepted. The guidance shown matches
 * the backend's actual rule — a length minimum — and deliberately does not invent
 * composition requirements the server does not enforce.
 */
@Composable
fun PasswordChangeScreen(
    serviceId: String,
    busy: Boolean,
    error: AuthErrorKind?,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var newPassword by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }

    val mismatch = confirmation.isNotEmpty() && newPassword != confirmation
    val tooShort = newPassword.isNotEmpty() &&
        newPassword.codePointCount(0, newPassword.length) < MIN_PASSWORD_CODE_POINTS

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.password_change_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.password_change_subtitle, serviceId),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.password_rule_length),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PasswordField(
            value = newPassword,
            onValueChange = { newPassword = it },
            label = stringResource(R.string.field_new_password),
            imeAction = ImeAction.Next,
        )
        PasswordField(
            value = confirmation,
            onValueChange = { confirmation = it },
            label = stringResource(R.string.field_confirm_password),
        )

        if (tooShort) {
            Text(
                stringResource(R.string.error_password_policy),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (mismatch) {
            Text(
                stringResource(R.string.error_password_mismatch),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        ErrorText(error)

        Button(
            onClick = { onSubmit(newPassword) },
            enabled = !busy && !mismatch && !tooShort && newPassword.isNotEmpty() && confirmation.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_set_password))
        }
        TextButton(onClick = onCancel, enabled = !busy) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}

/** Client-side hint only. The backend remains the authority on password policy. */
private const val MIN_PASSWORD_CODE_POINTS = 15
