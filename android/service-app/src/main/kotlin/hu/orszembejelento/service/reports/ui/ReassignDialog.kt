package hu.orszembejelento.service.reports.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.orszembejelento.service.R
import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.usermanagement.data.ManagedUserResponse
import hu.orszembejelento.service.usermanagement.data.UserManagementRepository
import kotlinx.coroutines.delay

/**
 * The reassignment target picker (brief §35): reuses the Phase 6 user-management list/search
 * endpoint filtered to `role=SERVICE_USER&status=ACTIVE`, server-side paginated, never a full
 * user dump. This is a *best-effort* suitability filter only - the actual reassignment
 * request is what the backend authoritatively validates (brief §35's own explicit warning);
 * `canManage` from that endpoint is deliberately never used here, since user-management
 * authority and report-assignment eligibility are different rules.
 */
@Composable
fun ReassignDialog(
    userManagementRepository: UserManagementRepository,
    onDismiss: () -> Unit,
    onConfirm: (targetServiceId: String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ManagedUserResponse>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query) {
        delay(350)
        loading = true
        when (val result = userManagementRepository.list(page = 0, size = 20, role = "SERVICE_USER", status = "ACTIVE", query = query.ifBlank { null })) {
            is ApiResult.Success -> results = result.value.items
            else -> results = emptyList()
        }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reassign_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.reassign_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.search_hint_users)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 12.dp))
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 280.dp).padding(top = 8.dp)) {
                        items(results, key = { it.serviceId }) { user ->
                            androidx.compose.foundation.layout.Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = selected == user.serviceId, onClick = { selected = user.serviceId })
                                Column {
                                    Text(user.serviceId, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = if (user.globalAreaAccess) {
                                            stringResource(R.string.active_work_view_all)
                                        } else {
                                            user.areas.joinToString { it.name }
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { selected?.let(onConfirm) }, enabled = selected != null) {
                Text(stringResource(R.string.action_reassign))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
