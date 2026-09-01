package hu.orszem.serviceapp.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.lifecycle.HiltViewModel
import hu.orszem.core.designsystem.LoadingState
import hu.orszem.serviceapp.data.AuthState
import hu.orszem.serviceapp.data.SessionManager
import hu.orszem.serviceapp.feature.auth.LoginScreen
import hu.orszem.serviceapp.feature.detail.ReportDetailScreen
import hu.orszem.serviceapp.feature.main.MainScaffold
import kotlinx.coroutines.launch
import javax.inject.Inject

object ServiceRoutes {
    const val LOGIN = "login"
    const val MAIN = "main"
    const val DETAIL = "detail/{reportId}?readOnly={readOnly}"
    fun detail(reportId: String, readOnly: Boolean) = "detail/$reportId?readOnly=$readOnly"
}

@HiltViewModel
class SessionViewModel @Inject constructor(
    val sessionManager: SessionManager,
) : ViewModel() {
    val state = sessionManager.state
    init {
        viewModelScope.launch { sessionManager.bootstrap() }
    }
}

@Composable
fun ServiceNavHost(
    navController: NavHostController = rememberNavController(),
    sessionViewModel: SessionViewModel = hiltViewModel(),
) {
    val authState by sessionViewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(authState) {
        when (authState) {
            AuthState.AUTHENTICATED -> navController.navigate(ServiceRoutes.MAIN) {
                popUpTo(0) { inclusive = true }
            }
            AuthState.UNAUTHENTICATED -> navController.navigate(ServiceRoutes.LOGIN) {
                popUpTo(0) { inclusive = true }
            }
            AuthState.UNKNOWN -> Unit
        }
    }

    when (authState) {
        AuthState.UNKNOWN -> LoadingState()
        else -> NavHost(navController = navController, startDestination = ServiceRoutes.LOGIN) {
            composable(ServiceRoutes.LOGIN) { LoginScreen() }
            composable(ServiceRoutes.MAIN) {
                MainScaffold(
                    onOpenReport = { id, readOnly ->
                        navController.navigate(ServiceRoutes.detail(id, readOnly))
                    },
                )
            }
            composable(ServiceRoutes.DETAIL) { backStackEntry ->
                val reportId = backStackEntry.arguments?.getString("reportId").orEmpty()
                val readOnly = backStackEntry.arguments?.getString("readOnly")?.toBoolean() ?: false
                ReportDetailScreen(
                    reportId = reportId,
                    readOnly = readOnly,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
