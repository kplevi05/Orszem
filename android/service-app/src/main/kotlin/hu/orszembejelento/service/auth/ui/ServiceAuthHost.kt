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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.orszembejelento.service.R
import hu.orszembejelento.service.auth.domain.AuthState

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
fun ServiceAuthHost(viewModel: AuthViewModel) {
    val state by viewModel.state.collectAsState()
    val busy by viewModel.busy.collectAsState()
    var showAccount by remember { mutableStateOf(false) }

    Scaffold(
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
            when (val current = state) {
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

                is AuthState.Authenticated ->
                    if (showAccount) {
                        AccountScreen(
                            serviceId = current.serviceId,
                            role = current.role,
                            busy = busy,
                            error = viewModel.lastError,
                            onChangePassword = viewModel::changeOwnPassword,
                            onLogout = viewModel::logout,
                            onLogoutAll = viewModel::logoutAll,
                            onBack = { showAccount = false },
                        )
                    } else {
                        ServiceHomeScreen(
                            serviceId = current.serviceId,
                            role = current.role,
                            onOpenAccount = { showAccount = true },
                        )
                    }

                is AuthState.AuthError -> LoginScreen(
                    busy = busy,
                    error = current.kind,
                    onLogin = { serviceId, password ->
                        viewModel.clearError()
                        viewModel.login(serviceId, password)
                    },
                )
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
