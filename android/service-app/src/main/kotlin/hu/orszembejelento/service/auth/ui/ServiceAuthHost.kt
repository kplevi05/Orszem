package hu.orszembejelento.service.auth.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.orszembejelento.service.R
import hu.orszembejelento.service.auth.domain.AuthState
import hu.orszembejelento.service.moderation.data.ModerationRepository
import hu.orszembejelento.service.nav.ServiceNavHost
import hu.orszembejelento.service.reports.data.ActiveWorkAreaStore
import hu.orszembejelento.service.reports.data.CatalogRepository
import hu.orszembejelento.service.reports.data.ReportWorkflowRepository
import hu.orszembejelento.service.usermanagement.data.UserManagementRepository

/**
 * Routes on the single [AuthState] rather than on a navigation graph.
 *
 * Which screen is correct is entirely a function of the authentication state, so deriving it
 * from that state is both simpler and safer than a back stack: there is no way to navigate
 * to the authenticated area without being authenticated, and a session ending anywhere in
 * the app returns the user to sign-in with no stale screen left behind.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceAuthHost(
    viewModel: AuthViewModel,
    reportRepository: ReportWorkflowRepository,
    userManagementRepository: UserManagementRepository,
    catalogRepository: CatalogRepository,
    activeWorkAreaStore: ActiveWorkAreaStore,
    moderationRepository: ModerationRepository? = null,
) {
    val state by viewModel.state.collectAsState()
    val busy by viewModel.busy.collectAsState()

    when (val current = state) {
        // The authenticated area owns its own Scaffold (bottom navigation, per-screen top
        // bars) - only the pre-authentication states below share this simple top-bar shell.
        is AuthState.Authenticated -> ServiceNavHost(
            serviceId = current.serviceId,
            role = current.role,
            globalAreaAccess = current.globalAreaAccess,
            ownAreas = current.areas,
            authViewModel = viewModel,
            reportRepository = reportRepository,
            userManagementRepository = userManagementRepository,
            catalogRepository = catalogRepository,
            activeWorkAreaStore = activeWorkAreaStore,
            moderationRepository = moderationRepository,
        )

        else -> Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (current) {
                    AuthState.RestoringSession -> Restoring()

                    AuthState.Unauthenticated -> LoginScreen(
                        busy = busy,
                        error = viewModel.lastError,
                        onLogin = viewModel::login,
                    )

                    is AuthState.PasswordChangeRequired -> PasswordChangeScreen(
                        serviceId = current.serviceId,
                        busy = busy,
                        error = viewModel.lastError,
                        onSubmit = viewModel::completePasswordChange,
                        onCancel = viewModel::cancelPasswordChange,
                    )

                    is AuthState.AuthError -> LoginScreen(
                        busy = busy,
                        error = current.kind,
                        onLogin = { serviceId, password ->
                            viewModel.clearError()
                            viewModel.login(serviceId, password)
                        },
                    )

                    is AuthState.Authenticated -> Unit // handled above
                }
            }
        }
    }
}

@Composable
private fun Restoring() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(R.string.restoring_session),
            modifier = Modifier.padding(top = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
