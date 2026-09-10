package hu.orszembejelento.service.common.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.orszembejelento.service.R

/** A small rounded pill naming a report's current status (brief §83) - never a raw enum name. */
@Composable
fun StatusBadge(@androidx.annotation.StringRes textRes: Int, color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .background(color = color, shape = RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.background,
        )
    }
}

/** Full-screen centered spinner, for the initial load of any screen (brief §75). */
@Composable
fun FullScreenLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** A recoverable error state with an explicit retry action (brief §45/§75). */
@Composable
fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
            Text(stringResource(R.string.action_retry))
        }
    }
}

/** An empty-but-successful list state - distinct from [ErrorState] (brief §75). */
@Composable
fun EmptyState(message: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A small inline banner used for a non-fatal, dismissible mutation error surfaced above a detail screen's actions. */
@Composable
fun InlineErrorBanner(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

/**
 * A destructive/final-action confirmation dialog (brief §32/§59), always with an explicit
 * cancel - the app never performs a workflow-final or account-final action from a single tap.
 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dangerous: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            if (dangerous) {
                Button(
                    onClick = onConfirm,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(confirmLabel) }
            } else {
                Button(onClick = onConfirm) { Text(confirmLabel) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
fun LoadMoreButton(loading: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = !loading, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
        }
        Text(stringResource(R.string.action_load_more))
    }
}
