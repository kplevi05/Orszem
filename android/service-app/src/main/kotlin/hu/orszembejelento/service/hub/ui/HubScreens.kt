package hu.orszembejelento.service.hub.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.orszembejelento.service.R

/**
 * `Moderáció` (brief §36): the live `Felhasználók` entry, the now-live `Törölt bejelentések`
 * moderation feature (Phase 9), plus own-account actions. Only ServiceArea administration
 * (Phase 10) remains an honest "not yet available" placeholder here - never a fake button.
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
            stringResource(R.string.moderation_functions_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * `Adminisztráció` (brief §37, Phase 10 §45): live `Felhasználók`, `Törölt bejelentések`
 * (SUPER_ADMIN gets restore controls on the same deleted-report UI) and now `Szolgálati
 * területek`. Only Phase 12 (audit/`Változási előzmények`) remains a visibly, honestly
 * unavailable placeholder - never built, never faked.
 */
@Composable
fun AdminHubScreen(
    onOpenUsers: () -> Unit,
    onOpenDeletedReports: () -> Unit,
    onOpenServiceAreas: () -> Unit,
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
        UnavailableRow(stringResource(R.string.audit_admin_title))
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

@Composable
private fun UnavailableRow(label: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.not_yet_available), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** `Statisztika` (brief §50): the Phase 11 backend does not exist yet - no fake numbers, no fake charts. */
@Composable
fun StatsPlaceholderScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.stats_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.stats_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}
