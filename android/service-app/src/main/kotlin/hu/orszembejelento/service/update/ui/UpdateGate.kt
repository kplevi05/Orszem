package hu.orszembejelento.service.update.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import hu.orszembejelento.service.R
import hu.orszembejelento.service.common.ui.InlineErrorBanner
import hu.orszembejelento.service.update.domain.UpdateDecision
import hu.orszembejelento.service.update.domain.UpdateManifest

/**
 * Overlays the whole app: a blocking full-screen page for a mandatory update, or a dismissible
 * dialog for an optional one. Placed above the auth host, so a mandatory update also blocks
 * the login screen.
 */
@Composable
fun UpdateGate(viewModel: UpdateViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.check()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val state by viewModel.state.collectAsState()
    when (val decision = state.decision) {
        is UpdateDecision.Mandatory -> MandatoryUpdateScreen(decision.manifest, state.phase, viewModel::startUpdate)
        is UpdateDecision.Optional -> if (state.showOptionalDialog) {
            OptionalUpdateDialog(decision.manifest, state.phase, viewModel::startUpdate, viewModel::dismiss)
        }
        UpdateDecision.UpToDate -> Unit
    }
}

@Composable
private fun MandatoryUpdateScreen(manifest: UpdateManifest, phase: UpdatePhase, onUpdate: () -> Unit) {
    // Back must not reveal the blocked app underneath.
    BackHandler(enabled = true) {}
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.update_mandatory_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.update_mandatory_text, manifest.versionName), style = MaterialTheme.typography.bodyLarge)
            UpdateBody(manifest, phase, onUpdate)
        }
    }
}

@Composable
private fun OptionalUpdateDialog(manifest: UpdateManifest, phase: UpdatePhase, onUpdate: () -> Unit, onDismiss: () -> Unit) {
    val busy = phase is UpdatePhase.Downloading || phase == UpdatePhase.Installing
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.update_optional_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.update_optional_text, manifest.versionName))
                UpdateStatus(manifest, phase)
            }
        },
        confirmButton = {
            Button(onClick = onUpdate, enabled = !busy) { Text(stringResource(R.string.update_action_update)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.update_action_later)) }
        },
    )
}

@Composable
private fun UpdateBody(manifest: UpdateManifest, phase: UpdatePhase, onUpdate: () -> Unit) {
    val busy = phase is UpdatePhase.Downloading || phase == UpdatePhase.Installing
    UpdateStatus(manifest, phase)
    Button(onClick = onUpdate, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.update_action_update))
    }
}

@Composable
private fun UpdateStatus(manifest: UpdateManifest, phase: UpdatePhase) {
    manifest.releaseNotes?.takeIf { it.isNotBlank() }?.let { notes ->
        Text(stringResource(R.string.update_release_notes_title), style = MaterialTheme.typography.titleSmall)
        Text(notes, style = MaterialTheme.typography.bodyMedium)
    }
    when (phase) {
        UpdatePhase.Idle -> Unit
        UpdatePhase.NeedsPermission -> Text(
            stringResource(R.string.update_needs_permission),
            style = MaterialTheme.typography.bodyMedium,
        )
        is UpdatePhase.Downloading -> {
            Text(stringResource(R.string.update_downloading), style = MaterialTheme.typography.bodyMedium)
            val progress = phase.progress
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        UpdatePhase.Installing -> Text(stringResource(R.string.update_installing), style = MaterialTheme.typography.bodyMedium)
        is UpdatePhase.Failed -> InlineErrorBanner(stringResource(errorText(phase.error)))
    }
}

private fun errorText(error: UpdateError): Int = when (error) {
    UpdateError.DOWNLOAD_FAILED -> R.string.update_error_download
    UpdateError.TOO_LARGE -> R.string.update_error_too_large
    UpdateError.CHECKSUM_MISMATCH -> R.string.update_error_checksum
    UpdateError.VERIFICATION_FAILED -> R.string.update_error_verification
    UpdateError.INSTALL_FAILED -> R.string.update_error_install
    UpdateError.INSTALL_ABORTED -> R.string.update_error_aborted
}
