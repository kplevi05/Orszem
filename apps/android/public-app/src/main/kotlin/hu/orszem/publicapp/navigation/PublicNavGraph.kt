package hu.orszem.publicapp.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import hu.orszem.publicapp.feature.home.HomeScreen
import hu.orszem.publicapp.feature.reportcreate.ReportFormScreen
import hu.orszem.publicapp.feature.success.SuccessScreen

object PublicRoutes {
    const val HOME = "home"
    const val REPORT_FORM = "report-form"
    const val SUCCESS = "success"
}

@Composable
fun PublicNavGraph(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = PublicRoutes.HOME) {
        composable(PublicRoutes.HOME) {
            HomeScreen(onStartReport = { navController.navigate(PublicRoutes.REPORT_FORM) })
        }
        composable(PublicRoutes.REPORT_FORM) {
            ReportFormScreen(
                onSubmitted = {
                    navController.navigate(PublicRoutes.SUCCESS) {
                        popUpTo(PublicRoutes.HOME)
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(PublicRoutes.SUCCESS) {
            SuccessScreen(
                onNewReport = {
                    navController.navigate(PublicRoutes.REPORT_FORM) {
                        popUpTo(PublicRoutes.HOME)
                    }
                },
                onHome = {
                    navController.navigate(PublicRoutes.HOME) {
                        popUpTo(PublicRoutes.HOME) { inclusive = true }
                    }
                },
            )
        }
    }
}
