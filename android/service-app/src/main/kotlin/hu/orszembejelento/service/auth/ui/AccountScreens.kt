package hu.orszembejelento.service.auth.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import hu.orszembejelento.service.reports.ui.AreaChoice

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    // Active work view — only the SERVICE_USER Profil passes these; the MOD/SUPER own-account
    // route leaves them at their defaults so the section is hidden there.
    activeWorkAreaChoices: List<AreaChoice> = emptyList(),
    activeWorkAreaId: String? = null,
    onActiveWorkAreaChanged: (String?) -> Unit = {},
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
            stringResource(R.string.account_role, stringResource(hu.orszembejelento.service.common.ui.roleLabelRes(role))),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (activeWorkAreaChoices.isNotEmpty()) {
            HorizontalDivider()
            Text(stringResource(R.string.active_work_view_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.active_work_view_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = activeWorkAreaId == null,
                    onClick = { onActiveWorkAreaChanged(null) },
                    label = { Text(stringResource(R.string.filter_all_areas)) },
                )
                activeWorkAreaChoices.forEach { choice ->
                    FilterChip(
                        selected = activeWorkAreaId == choice.id,
                        onClick = { onActiveWorkAreaChanged(if (activeWorkAreaId == choice.id) null else choice.id) },
                        label = { Text(choice.name) },
                    )
                }
            }
        }

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
