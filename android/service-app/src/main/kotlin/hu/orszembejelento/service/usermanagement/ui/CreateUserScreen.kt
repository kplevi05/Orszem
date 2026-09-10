package hu.orszembejelento.service.usermanagement.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.orszembejelento.service.R
import hu.orszembejelento.service.common.ui.InlineErrorBanner
import hu.orszembejelento.service.common.ui.apiErrorMessage

/** `Új felhasználó` (brief §54-58). */
@Composable
fun CreateUserScreen(
    viewModel: CreateUserViewModel,
    onBack: () -> Unit,
    onCreated: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_description_back))
            }
            Text(stringResource(R.string.create_user_title), style = MaterialTheme.typography.titleLarge)
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(R.string.create_user_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(stringResource(R.string.field_role), style = MaterialTheme.typography.labelLarge)
            if (state.canAssignModerator) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf("SERVICE_USER", "MODERATOR").forEachIndexed { index, role ->
                        SegmentedButton(
                            selected = state.role == role,
                            onClick = { viewModel.setRole(role) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                        ) { Text(role) }
                    }
                }
            } else {
                Text("SERVICE_USER", style = MaterialTheme.typography.bodyMedium)
            }

            Text(stringResource(R.string.field_initial_areas), style = MaterialTheme.typography.labelLarge)
            if (state.loadingAreas) {
                CircularProgressIndicator()
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(4.dp)) {
                        state.availableAreas.forEach { area ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = area.id in state.selectedAreaIds,
                                    onCheckedChange = { viewModel.toggleArea(area.id) },
                                )
                                Text(area.name, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            if (state.canAssignModerator) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = state.globalAreaAccess, onCheckedChange = viewModel::setGlobalAccess)
                    Column {
                        Text(stringResource(R.string.check_global_access), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.check_global_access_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            state.error?.let { InlineErrorBanner(apiErrorMessage(it)) }

            Button(
                onClick = viewModel::submit,
                enabled = !state.submitting && (state.globalAreaAccess || state.selectedAreaIds.isNotEmpty() || state.role == "MODERATOR"),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_create))
            }

            Text(
                stringResource(R.string.create_user_rules_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    state.created?.let { created ->
        CredentialDisplayDialog(
            titleRes = R.string.credential_title,
            serviceId = created.serviceId,
            temporaryCredential = created.temporaryCredential,
            onDismiss = { viewModel.consumeCreated(); onCreated() },
        )
    }
}
