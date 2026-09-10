package hu.orszembejelento.service.usermanagement.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.orszembejelento.service.R
import hu.orszembejelento.service.common.ui.ConfirmDialog
import hu.orszembejelento.service.common.ui.ErrorState
import hu.orszembejelento.service.common.ui.FullScreenLoading
import hu.orszembejelento.service.common.ui.InlineErrorBanner
import hu.orszembejelento.service.common.ui.apiErrorMessage
import hu.orszembejelento.service.usermanagement.data.AssignableAreaResponse
import hu.orszembejelento.service.usermanagement.data.ManagedUserResponse
import hu.orszembejelento.service.usermanagement.data.UserManagementRepository

/**
 * One managed user's detail (brief §53, §68). Mutation controls are a UI convenience derived
 * from [ManagedUserResponse.canManage] and [currentRole] - the backend independently
 * re-authorises every one of them (brief §52).
 */
@Composable
fun UserDetailScreen(
    currentRole: String,
    viewModel: UserDetailViewModel,
    userManagementRepository: UserManagementRepository,
    onBack: () -> Unit,
    onViewInProgressFor: (serviceId: String) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var confirmDeactivate by remember { mutableStateOf(false) }
    var confirmRoleChange by remember { mutableStateOf<String?>(null) }
    var showAreaPicker by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_description_back))
            }
            Text(state.user?.serviceId ?: "", style = MaterialTheme.typography.titleLarge)
        }

        when {
            state.loading -> FullScreenLoading()
            state.error != null -> ErrorState(message = apiErrorMessage(state.error!!), onRetry = { viewModel.load() })
            state.user == null -> Unit
            else -> {
                val user = state.user!!
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = "${user.role} · ${user.status}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (user.mustChangePassword) {
                        Text(
                            stringResource(R.string.field_must_change_password),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!user.canManage) {
                        Text(
                            stringResource(R.string.label_read_only),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    state.mutationError?.let { error ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            InlineErrorBanner(apiErrorMessage(error))
                            // brief §61: the specific, actionable CTA for the cross-phase guard.
                            if ((error as? hu.orszembejelento.service.common.data.ApiResult.Failure)?.code == "USER_HAS_ACTIVE_REPORT_ASSIGNMENTS") {
                                OutlinedButton(onClick = { onViewInProgressFor(user.serviceId) }, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.view_in_progress_for_user))
                                }
                            }
                        }
                    }

                    Text(stringResource(R.string.section_permissions), style = MaterialTheme.typography.titleSmall)
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(4.dp)) {
                            if (user.globalAreaAccess) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                ) {
                                    Text(stringResource(R.string.active_work_view_all), style = MaterialTheme.typography.bodyMedium)
                                }
                                HorizontalDivider()
                            }
                            user.areas.forEach { area ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                ) {
                                    Text(area.name, style = MaterialTheme.typography.bodyMedium)
                                    if (user.canManage) {
                                        TextButton(onClick = { viewModel.revokeArea(area.id) }, enabled = !state.mutationInFlight) {
                                            Text(stringResource(R.string.action_revoke))
                                        }
                                    }
                                }
                                HorizontalDivider()
                            }
                            if (user.canManage) {
                                Button(
                                    onClick = { showAreaPicker = true },
                                    enabled = !state.mutationInFlight,
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                ) {
                                    Text(stringResource(R.string.action_add_area))
                                }
                            }
                        }
                    }

                    if (user.canManage) {
                        Text(stringResource(R.string.section_account), style = MaterialTheme.typography.titleSmall)
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                OutlinedButton(onClick = viewModel::resetPassword, enabled = !state.mutationInFlight, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.action_password_reset))
                                }
                                if (user.status == "ACTIVE") {
                                    Button(
                                        onClick = { confirmDeactivate = true },
                                        enabled = !state.mutationInFlight,
                                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(stringResource(R.string.action_deactivate))
                                    }
                                } else {
                                    Button(onClick = viewModel::reactivate, enabled = !state.mutationInFlight, modifier = Modifier.fillMaxWidth()) {
                                        Text(stringResource(R.string.action_reactivate))
                                    }
                                }
                                if (currentRole == "SUPER_ADMIN") {
                                    if (user.role == "SERVICE_USER") {
                                        OutlinedButton(onClick = { confirmRoleChange = "MODERATOR" }, enabled = !state.mutationInFlight, modifier = Modifier.fillMaxWidth()) {
                                            Text(stringResource(R.string.action_change_role_to_moderator))
                                        }
                                    } else if (user.role == "MODERATOR") {
                                        OutlinedButton(onClick = { confirmRoleChange = "SERVICE_USER" }, enabled = !state.mutationInFlight, modifier = Modifier.fillMaxWidth()) {
                                            Text(stringResource(R.string.action_change_role_to_service_user))
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = { if (user.globalAreaAccess) viewModel.revokeGlobalAccess() else viewModel.grantGlobalAccess() },
                                        enabled = !state.mutationInFlight,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(stringResource(if (user.globalAreaAccess) R.string.action_revoke_global_access else R.string.action_grant_global_access))
                                    }
                                }
                            }
                        }
                    }

                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(bottom = 24.dp))
                }
            }
        }
    }

    if (confirmDeactivate) {
        ConfirmDialog(
            title = stringResource(R.string.confirm_deactivate_title),
            text = stringResource(R.string.confirm_deactivate_text),
            confirmLabel = stringResource(R.string.action_deactivate),
            dangerous = true,
            onConfirm = { confirmDeactivate = false; viewModel.deactivate() },
            onDismiss = { confirmDeactivate = false },
        )
    }
    confirmRoleChange?.let { targetRole ->
        val confirmLabel = if (targetRole == "MODERATOR") {
            stringResource(R.string.action_change_role_to_moderator)
        } else {
            stringResource(R.string.action_change_role_to_service_user)
        }
        ConfirmDialog(
            title = stringResource(R.string.confirm_role_change_title),
            text = stringResource(R.string.confirm_role_change_text),
            confirmLabel = confirmLabel,
            onConfirm = { confirmRoleChange = null; viewModel.changeRole(targetRole) },
            onDismiss = { confirmRoleChange = null },
        )
    }
    if (showAreaPicker) {
        AreaPickerDialog(
            repository = userManagementRepository,
            onDismiss = { showAreaPicker = false },
            onSelect = { areaId -> showAreaPicker = false; viewModel.grantArea(areaId) },
        )
    }
    state.newCredential?.let { credential ->
        CredentialDisplayDialog(
            titleRes = R.string.credential_reset_title,
            serviceId = credential.serviceId,
            temporaryCredential = credential.temporaryCredential,
            onDismiss = viewModel::consumeCredential,
        )
    }
}

@Composable
private fun AreaPickerDialog(
    repository: UserManagementRepository,
    onDismiss: () -> Unit,
    onSelect: (areaId: String) -> Unit,
) {
    var areas by remember { mutableStateOf<List<AssignableAreaResponse>?>(null) }

    LaunchedEffect(Unit) {
        areas = when (val result = repository.assignableAreas()) {
            is hu.orszembejelento.service.common.data.ApiResult.Success -> result.value
            else -> emptyList()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_add_area)) },
        text = {
            when (val current = areas) {
                null -> CircularProgressIndicator()
                else -> Column {
                    current.forEach { area ->
                        Text(
                            text = area.name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = { onSelect(area.id) })
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
