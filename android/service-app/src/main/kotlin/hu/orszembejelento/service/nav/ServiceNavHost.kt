package hu.orszembejelento.service.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import hu.orszembejelento.service.R
import hu.orszembejelento.service.auth.ui.AccountScreen
import hu.orszembejelento.service.auth.ui.AuthViewModel
import hu.orszembejelento.service.hub.ui.AdminHubScreen
import hu.orszembejelento.service.hub.ui.ModerationHubScreen
import hu.orszembejelento.service.hub.ui.StatsPlaceholderScreen
import hu.orszembejelento.service.reports.data.ReportFilter
import hu.orszembejelento.service.reports.data.ReportWorkflowRepository
import hu.orszembejelento.service.reports.ui.ArchiveScreen
import hu.orszembejelento.service.reports.ui.ReportDetailScreen
import hu.orszembejelento.service.reports.ui.ReportDetailViewModel
import hu.orszembejelento.service.reports.ui.ReportQueueViewModel
import hu.orszembejelento.service.reports.ui.ReportsScreen
import hu.orszembejelento.service.usermanagement.data.UserManagementRepository
import hu.orszembejelento.service.usermanagement.ui.CreateUserScreen
import hu.orszembejelento.service.usermanagement.ui.CreateUserViewModel
import hu.orszembejelento.service.usermanagement.ui.UserDetailScreen
import hu.orszembejelento.service.usermanagement.ui.UserDetailViewModel
import hu.orszembejelento.service.usermanagement.ui.UsersListViewModel
import hu.orszembejelento.service.usermanagement.ui.UsersScreen

internal object Routes {
    const val REPORTS = "reports"
    const val REPORT_DETAIL = "reports/{publicReportId}"
    const val ARCHIVE = "archive"
    const val STATS = "stats"
    const val PROFILE = "profile"
    const val MODERATION = "moderation"
    const val ADMIN = "admin"
    const val USERS = "users"
    const val USER_DETAIL = "users/{serviceId}"
    const val CREATE_USER = "users/create"
    const val ACCOUNT = "account"

    fun reportDetail(publicReportId: String) = "reports/$publicReportId"
    fun userDetail(serviceId: String) = "users/$serviceId"
}

private data class BottomDestination(val route: String, val labelRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector)

/**
 * Exactly the four routes for [role], in display order (brief §5) - kept as a pure,
 * icon-free function so the navigation matrix itself is unit-testable without touching
 * Compose.
 */
internal fun bottomRoutesFor(role: String): List<String> = buildList {
    add(Routes.REPORTS)
    add(Routes.ARCHIVE)
    add(Routes.STATS)
    add(
        when (role) {
            "MODERATOR" -> Routes.MODERATION
            "SUPER_ADMIN" -> Routes.ADMIN
            else -> Routes.PROFILE
        },
    )
}

private fun bottomDestinationsFor(role: String): List<BottomDestination> = bottomRoutesFor(role).map { route ->
    when (route) {
        Routes.REPORTS -> BottomDestination(route, R.string.nav_reports, Icons.Filled.Description)
        Routes.ARCHIVE -> BottomDestination(route, R.string.nav_archive, Icons.Filled.Archive)
        Routes.STATS -> BottomDestination(route, R.string.nav_stats, Icons.Filled.BarChart)
        Routes.MODERATION -> BottomDestination(route, R.string.nav_moderation, Icons.Filled.Shield)
        Routes.ADMIN -> BottomDestination(route, R.string.nav_admin, Icons.Filled.AdminPanelSettings)
        else -> BottomDestination(route, R.string.nav_profile, Icons.Filled.AccountCircle)
    }
}

/**
 * The authenticated area of the app: exactly four bottom-navigation destinations, chosen
 * entirely by [role] (brief §5) - never a client-side switch, always what the server's own
 * `/account/me` response said. Everything below this composable requires [AuthState.Authenticated][
 * hu.orszembejelento.service.auth.domain.AuthState.Authenticated] to even be reachable, since
 * [hu.orszembejelento.service.auth.ui.ServiceAuthHost] only calls this from that state.
 */
@Composable
fun ServiceNavHost(
    serviceId: String,
    role: String,
    authViewModel: AuthViewModel,
    reportRepository: ReportWorkflowRepository,
    userManagementRepository: UserManagementRepository,
) {
    val navController = rememberNavController()
    val onSessionEnded: () -> Unit = { authViewModel.forceSignedOut() }

    // A ViewModel store scoped to this signed-in session, not to the Activity. The queue
    // ViewModels below hold the current user's reports; without a session-bound owner they
    // would be Activity-scoped and survive a logout, so the next user to sign in on the same
    // device would briefly see the previous user's queue before a refresh (brief §72 -
    // "clear role-dependent UI state" on logout). This composable only exists while
    // authenticated, so onDispose here is exactly the logout boundary.
    val sessionOwner = remember {
        object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    }
    DisposableEffect(Unit) { onDispose { sessionOwner.viewModelStore.clear() } }

    // Hoisted here (not inside ReportsScreen) so the "USER_HAS_ACTIVE_REPORT_ASSIGNMENTS"
    // CTA (brief §61) can pre-apply an assigneeServiceId filter and switch to the Folyamatban
    // tab from a completely different screen (User detail) without inventing a second way to
    // reach the same queue.
    val newQueueViewModel: ReportQueueViewModel = viewModel(
        viewModelStoreOwner = sessionOwner,
        key = "new-queue",
        factory = viewModelFactory { ReportQueueViewModel(reportRepository::newQueue, onSessionEnded) },
    )
    val inProgressQueueViewModel: ReportQueueViewModel = viewModel(
        viewModelStoreOwner = sessionOwner,
        key = "in-progress-queue",
        factory = viewModelFactory { ReportQueueViewModel(reportRepository::inProgressQueue, onSessionEnded) },
    )
    val archiveQueueViewModel: ReportQueueViewModel = viewModel(
        viewModelStoreOwner = sessionOwner,
        key = "archive-queue",
        factory = viewModelFactory { ReportQueueViewModel(reportRepository::archiveQueue, onSessionEnded) },
    )
    var reportsTabIndex by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = backStackEntry?.destination
            NavigationBar {
                bottomDestinationsFor(role).forEach { destination ->
                    val selected = currentRoute?.hierarchy?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(androidx.compose.ui.res.stringResource(destination.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.REPORTS,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.REPORTS) {
                ReportsScreen(
                    role = role,
                    newViewModel = newQueueViewModel,
                    inProgressViewModel = inProgressQueueViewModel,
                    onOpenReport = { navController.navigate(Routes.reportDetail(it)) },
                    tabIndex = reportsTabIndex,
                    onTabChange = { reportsTabIndex = it },
                )
            }
            composable(
                Routes.REPORT_DETAIL,
                arguments = listOf(navArgument("publicReportId") {}),
            ) { entry ->
                val publicReportId = entry.arguments?.getString("publicReportId") ?: return@composable
                val detailViewModel: ReportDetailViewModel = viewModel(
                    key = "report-detail-$publicReportId",
                    factory = viewModelFactory { ReportDetailViewModel(publicReportId, reportRepository, onSessionEnded) },
                )
                ReportDetailScreen(
                    currentServiceId = serviceId,
                    role = role,
                    viewModel = detailViewModel,
                    userManagementRepository = if (role == "SERVICE_USER") null else userManagementRepository,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.ARCHIVE) {
                ArchiveScreen(viewModel = archiveQueueViewModel, onOpenReport = { navController.navigate(Routes.reportDetail(it)) })
            }
            composable(Routes.STATS) { StatsPlaceholderScreen() }
            composable(Routes.PROFILE) {
                AccountScreen(
                    serviceId = serviceId,
                    role = role,
                    busy = authViewModel.busy.collectAsState().value,
                    error = authViewModel.lastError,
                    onChangePassword = authViewModel::changeOwnPassword,
                    onLogout = authViewModel::logout,
                    onLogoutAll = authViewModel::logoutAll,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.MODERATION) {
                ModerationHubScreen(
                    onOpenUsers = { navController.navigate(Routes.USERS) },
                    onOpenAccount = { navController.navigate(Routes.ACCOUNT) },
                )
            }
            composable(Routes.ADMIN) {
                AdminHubScreen(
                    onOpenUsers = { navController.navigate(Routes.USERS) },
                    onOpenAccount = { navController.navigate(Routes.ACCOUNT) },
                )
            }
            composable(Routes.ACCOUNT) {
                AccountScreen(
                    serviceId = serviceId,
                    role = role,
                    busy = authViewModel.busy.collectAsState().value,
                    error = authViewModel.lastError,
                    onChangePassword = authViewModel::changeOwnPassword,
                    onLogout = authViewModel::logout,
                    onLogoutAll = authViewModel::logoutAll,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.USERS) {
                val usersViewModel: UsersListViewModel = viewModel(
                    key = "users-list",
                    factory = viewModelFactory { UsersListViewModel(userManagementRepository, onSessionEnded) },
                )
                UsersScreen(
                    viewModel = usersViewModel,
                    onOpenUser = { navController.navigate(Routes.userDetail(it)) },
                    onCreateUser = { navController.navigate(Routes.CREATE_USER) },
                )
            }
            composable(
                Routes.USER_DETAIL,
                arguments = listOf(navArgument("serviceId") {}),
            ) { entry ->
                val targetServiceId = entry.arguments?.getString("serviceId") ?: return@composable
                val userDetailViewModel: UserDetailViewModel = viewModel(
                    key = "user-detail-$targetServiceId",
                    factory = viewModelFactory { UserDetailViewModel(targetServiceId, userManagementRepository, onSessionEnded) },
                )
                UserDetailScreen(
                    currentRole = role,
                    viewModel = userDetailViewModel,
                    userManagementRepository = userManagementRepository,
                    onBack = { navController.popBackStack() },
                    onViewInProgressFor = { targetId ->
                        inProgressQueueViewModel.updateFilter(ReportFilter(assigneeServiceId = targetId))
                        reportsTabIndex = 1
                        navController.navigate(Routes.REPORTS) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable(Routes.CREATE_USER) {
                val createViewModel: CreateUserViewModel = viewModel(
                    key = "create-user",
                    factory = viewModelFactory { CreateUserViewModel(role == "SUPER_ADMIN", userManagementRepository, onSessionEnded) },
                )
                CreateUserScreen(
                    viewModel = createViewModel,
                    onBack = { navController.popBackStack() },
                    onCreated = { navController.popBackStack() },
                )
            }
        }
    }
}

/** Small helper mirroring [hu.orszembejelento.service.MainActivity]'s existing manual-factory pattern - no DI framework. */
private fun <T : ViewModel> viewModelFactory(create: () -> T): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = create() as VM
    }
