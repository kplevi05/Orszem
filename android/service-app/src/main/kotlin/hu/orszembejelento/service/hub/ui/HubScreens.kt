package hu.orszembejelento.service.hub.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.orszembejelento.service.R

/**
 * `Moderáció` (brief §36): the live `Felhasználók` entry, the now-live `Törölt bejelentések`
 * moderation feature (Phase 9), plus own-account actions. ServiceArea administration is
 * SUPER_ADMIN-only (enforced by the backend), so a Moderator sees an explanatory note here
 * instead of a button.
 */
@Composable
fun ModerationHubScreen(onOpenUsers: () -> Unit, onOpenDeletedReports: () -> Unit, onOpenAccount: () -> Unit) {
    Hub(
        title = stringResource(R.string.moderation_title),
        subtitle = stringResource(R.string.moderation_subtitle),
        onOpenUsers = onOpenUsers,
        onOpenAccount = onOpenAccount,
    ) {
        Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenDeletedReports)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.hub_deleted_reports_entry), style = MaterialTheme.typography.titleMedium)
            }
        }
        Text(
            stringResource(R.string.moderation_service_areas_require_admin),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * `Adminisztráció` (brief §37, Phase 10 §45, Phase 12 §45): live `Felhasználók`, `Törölt
 * bejelentések` (SUPER_ADMIN gets restore controls on the same deleted-report UI), `Szolgálati
 * területek`, and now `Változási előzmények` - the last remaining hub entry, previously an
 * honest "not yet available" placeholder, is live. Every entry in this hub is now real.
 */
@Composable
fun AdminHubScreen(
    onOpenUsers: () -> Unit,
    onOpenDeletedReports: () -> Unit,
    onOpenServiceAreas: () -> Unit,
    onOpenAudit: () -> Unit,
    onOpenAccount: () -> Unit,
) {
    Hub(
        title = stringResource(R.string.admin_title),
        subtitle = stringResource(R.string.admin_subtitle),
        onOpenUsers = onOpenUsers,
        onOpenAccount = onOpenAccount,
    ) {
        Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenDeletedReports)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.hub_deleted_reports_entry), style = MaterialTheme.typography.titleMedium)
            }
        }
        Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenServiceAreas)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.areas_admin_title), style = MaterialTheme.typography.titleMedium)
            }
        }
        Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenAudit)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.audit_admin_title), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun Hub(
    title: String,
    subtitle: String,
    onOpenUsers: () -> Unit,
    onOpenAccount: () -> Unit,
    extraContent: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenUsers)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.hub_users_entry), style = MaterialTheme.typography.titleMedium)
            }
        }

        extraContent()

        Text(stringResource(R.string.hub_own_account), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        Button(onClick = onOpenAccount, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.hub_own_account))
        }
    }
}
