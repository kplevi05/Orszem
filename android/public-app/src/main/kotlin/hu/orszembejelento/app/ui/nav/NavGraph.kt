package hu.orszembejelento.app.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import hu.orszembejelento.app.R
import hu.orszembejelento.app.report.data.AppContainer
import hu.orszembejelento.app.ui.history.HistoryScreen
import hu.orszembejelento.app.ui.history.HistoryViewModel
import hu.orszembejelento.app.ui.home.HomeScreen
import hu.orszembejelento.app.ui.newreport.NewReportScreen
import hu.orszembejelento.app.ui.newreport.NewReportViewModel

private sealed class Destination(val route: String, val labelRes: Int) {
    data object Home : Destination("home", R.string.nav_home)
    data object NewReport : Destination("new_report", R.string.nav_new_report)
    data object History : Destination("history", R.string.nav_history)
}

private val bottomDestinations = listOf(Destination.Home, Destination.NewReport, Destination.History)

/** The exact three primary destinations the Phase 5 brief requires (§2) - no more, no fewer. */
@Composable
fun PublicNavGraph(container: AppContainer) {
    val navController = rememberNavController()
    val factory = ViewModelFactory(container)
    val historyViewModel: HistoryViewModel = viewModel(factory = factory)

    Scaffold(
        bottomBar = {
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = backStackEntry?.destination
            NavigationBar {
                bottomDestinations.forEach { destination ->
                    val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                when (destination) {
                                    Destination.Home -> Icons.Filled.Home
                                    Destination.NewReport -> Icons.Filled.Add
                                    Destination.History -> Icons.AutoMirrored.Filled.List
                                },
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(destination.labelRes)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Home.route,
            modifier = androidx.compose.ui.Modifier.padding(innerPadding),
        ) {
            composable(Destination.Home.route) {
                HomeScreen(historyViewModel = historyViewModel, onNewReport = { navController.navigate(Destination.NewReport.route) })
            }
            composable(Destination.NewReport.route) {
                val newReportViewModel: NewReportViewModel = viewModel(factory = factory)
                NewReportScreen(
                    viewModel = newReportViewModel,
                    onNavigateHome = { navController.navigate(Destination.Home.route) { popUpTo(Destination.Home.route) } },
                    onNavigateHistory = { navController.navigate(Destination.History.route) { popUpTo(Destination.Home.route) } },
                )
            }
            composable(Destination.History.route) {
                HistoryScreen(viewModel = historyViewModel)
            }
        }
    }
}
