package hu.orszembejelento.service.moderation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.orszembejelento.service.R
import hu.orszembejelento.service.common.ui.MODERATION_REASONS
import hu.orszembejelento.service.common.ui.moderationReasonLabelRes
import hu.orszembejelento.service.common.ui.restoreDialogTextRes

/**
 * The moderation-delete confirmation/reason dialog (brief §39-41). Exactly one of the six
 * frozen reasons is required — no free text, no optional explanation — and confirm stays
 * disabled until one is chosen. [isInProgress] switches the consequence copy so an
 * IN_PROGRESS deletion's assignment-ending effect is never mistaken for a temporary hide.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DeleteReasonDialog(isInProgress: Boolean, onConfirm: (reason: String) -> Unit, onDismiss: () -> Unit) {
    var selectedReason by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.moderation_delete_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(
                        if (isInProgress) R.string.moderation_delete_dialog_text_in_progress else R.string.moderation_delete_dialog_text_general,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(stringResource(R.string.moderation_delete_dialog_reason_label), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MODERATION_REASONS.forEach { reason ->
                        FilterChip(
                            selected = selectedReason == reason,
                            onClick = { selectedReason = reason },
                            label = { Text(stringResource(moderationReasonLabelRes(reason))) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { selectedReason?.let(onConfirm) },
                enabled = selectedReason != null,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.action_delete)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** The SUPER_ADMIN restore confirmation dialog (brief §48-49) — explicit confirm, never a one-tap restore. */
@Composable
fun RestoreConfirmDialog(statusBeforeDelete: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val text = stringResource(restoreDialogTextRes(statusBeforeDelete))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restore_dialog_title)) },
        text = { Text(text) },
        confirmButton = { Button(onClick = onConfirm) { Text(stringResource(R.string.action_restore)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
