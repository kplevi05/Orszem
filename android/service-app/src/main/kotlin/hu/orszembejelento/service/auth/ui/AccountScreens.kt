package hu.orszembejelento.service.auth.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import hu.orszembejelento.service.R
import hu.orszembejelento.service.auth.domain.AuthErrorKind

/**
 * Authenticated landing screen.
 *
 * Deliberately a placeholder. Reports, archive, statistics, moderation and administration
 * are later phases; adding empty shells for them now would imply features that do not exist.
 */
@Composable
fun ServiceHomeScreen(
    serviceId: String,
    role: String,
    onOpenAccount: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.home_signed_in_as, serviceId, role),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.home_placeholder),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Button(onClick = onOpenAccount, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_account))
        }
    }
}

@Composable
fun AccountScreen(
    serviceId: String,
    role: String,
    busy: Boolean,
    error: AuthErrorKind?,
    onChangePassword: (String, String, (Boolean) -> Unit) -> Unit,
    onLogout: () -> Unit,
    onLogoutAll: () -> Unit,
    onBack: () -> Unit,
) {
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var changed by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.account_title), style = MaterialTheme.typography.headlineSmall)

        Text(stringResource(R.string.account_service_id, serviceId), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.account_role, role),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()

        Text(stringResource(R.string.account_change_password), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.password_rule_length),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.account_change_password_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AccountPasswordField(
            value = currentPassword,
            onValueChange = { currentPassword = it },
            label = stringResource(R.string.field_current_password),
        )
        AccountPasswordField(
            value = newPassword,
            onValueChange = { newPassword = it },
            label = stringResource(R.string.field_new_password),
        )

        if (changed) {
            Text(
                stringResource(R.string.account_password_changed),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        AccountErrorText(error)

        Button(
            onClick = {
                onChangePassword(currentPassword, newPassword) { success ->
                    changed = success
                    if (success) {
                        currentPassword = ""
                        newPassword = ""
                    }
                }
            },
            enabled = !busy && currentPassword.isNotEmpty() && newPassword.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_change_password))
        }

        HorizontalDivider()

        OutlinedButton(onClick = onLogout, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_logout))
        }
        OutlinedButton(onClick = onLogoutAll, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_logout_all))
        }
        Text(
            stringResource(R.string.account_logout_all_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        TextButton(onClick = onBack, enabled = !busy) {
            Text(stringResource(R.string.action_back))
        }
    }
}

@Composable
private fun AccountPasswordField(value: String, onValueChange: (String) -> Unit, label: String) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AccountErrorText(kind: AuthErrorKind?) {
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
