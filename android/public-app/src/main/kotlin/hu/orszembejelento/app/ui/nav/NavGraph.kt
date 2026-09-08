package hu.orszembejelento.app.ui.nav

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import hu.orszembejelento.app.R
import hu.orszembejelento.app.report.data.AppContainer
import hu.orszembejelento.app.ui.PublicPalette
import hu.orszembejelento.app.ui.history.HistoryScreen
import hu.orszembejelento.app.ui.history.HistoryViewModel
import hu.orszembejelento.app.ui.home.HomeScreen
import hu.orszembejelento.app.ui.newreport.NewReportScreen
import hu.orszembejelento.app.ui.newreport.NewReportViewModel

private sealed class Destination(val route: String, val labelRes: Int, val icon: ImageVector) {
    data object Home : Destination("home", R.string.nav_home, Icons.Filled.Home)
    data object NewReport : Destination("new_report", R.string.nav_new_report, Icons.Filled.Add)
    data object History : Destination("history", R.string.nav_history, Icons.AutoMirrored.Filled.List)
}

private val bottomDestinations = listOf(Destination.Home, Destination.NewReport, Destination.History)

/** The exact three primary destinations the Phase 5 brief requires (§2) - no more, no fewer. */
@Composable
fun PublicNavGraph(container: AppContainer) {
    val navController = rememberNavController()
    val factory = ViewModelFactory(container)
    val historyViewModel: HistoryViewModel = viewModel(factory = factory)

    Scaffold(
        containerColor = PublicPalette.Background,
        bottomBar = {
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = backStackEntry?.destination
            FloatingBottomNav(
                destinations = bottomDestinations,
                isSelected = { destination -> currentDestination?.hierarchy?.any { it.route == destination.route } == true },
                onSelect = { destination ->
                    navController.navigate(destination.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Home.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Destination.Home.route) {
                HomeScreen(
                    historyViewModel = historyViewModel,
                    onNewReport = { navController.navigate(Destination.NewReport.route) },
                    onViewHistory = { navController.navigate(Destination.History.route) },
                )
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

/**
 * A floating rounded "pill" bottom bar - deliberately aligned to the approved mockup's own
 * bottom navigation instead of Material3's default edge-to-edge [androidx.compose.material3.NavigationBar]
 * (§3 of the Phase 5 visual-alignment brief: same three items/order/labels as Web, modern
 * look, active item clearly highlighted). No navigation behaviour changes - only presentation.
 */
@Composable
private fun FloatingBottomNav(
    destinations: List<Destination>,
    isSelected: (Destination) -> Boolean,
    onSelect: (Destination) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        shape = RoundedCornerShape(22.dp),
        color = PublicPalette.Surface.copy(alpha = 0.96f),
        shadowElevation = 10.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, PublicPalette.Border),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            destinations.forEach { destination ->
                BottomNavItem(
                    label = stringResource(destination.labelRes),
                    icon = destination.icon,
                    selected = isSelected(destination),
                    onClick = { onSelect(destination) },
                )
            }
        }
    }
}

@Composable
private fun BottomNavItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val background by animateColorAsState(if (selected) PublicPalette.PrimarySoft else PublicPalette.Surface, label = "navItemBg")
    val content = if (selected) PublicPalette.Primary else PublicPalette.TextMuted
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(background, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.padding(0.dp))
        Text(label, color = content, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
    }
}
