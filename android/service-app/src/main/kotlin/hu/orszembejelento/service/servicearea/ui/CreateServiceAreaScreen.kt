package hu.orszembejelento.service.servicearea.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.orszembejelento.service.R
import hu.orszembejelento.service.common.ui.InlineErrorBanner
import hu.orszembejelento.service.common.ui.apiErrorMessage

/**
 * `Új szolgálati terület` (brief §47) - name only, ACTIVE/unmapped by construction. On success
 * navigates straight into the new area's own detail so lines can be configured next, rather
 * than bundling that into the same transaction (brief §10/§47).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateServiceAreaScreen(
    viewModel: CreateServiceAreaViewModel,
    onBack: () -> Unit,
    onCreated: (areaId: String) -> Unit,
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.created) {
        state.created?.let { onCreated(it.id) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.action_create_area)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_description_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = { Text(stringResource(R.string.field_area_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.error != null) {
                InlineErrorBanner(apiErrorMessage(state.error!!))
            }

            Button(
                onClick = viewModel::submit,
                enabled = state.name.isNotBlank() && !state.submitting,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                if (state.submitting) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), color = MaterialTheme.colorScheme.onPrimary)
                }
                Text(stringResource(R.string.action_create))
            }
        }
    }
}
