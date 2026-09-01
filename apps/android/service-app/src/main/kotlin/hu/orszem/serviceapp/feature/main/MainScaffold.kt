package hu.orszem.serviceapp.feature.main

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import hu.orszem.serviceapp.R
import hu.orszem.serviceapp.feature.archive.ArchiveScreen
import hu.orszem.serviceapp.feature.reports.ActiveReportsScreen
import hu.orszem.serviceapp.feature.statistics.StatisticsScreen

private enum class MainTab(val labelRes: Int, val icon: ImageVector) {
    REPORTS(R.string.tab_reports, Icons.AutoMirrored.Filled.List),
    ARCHIVE(R.string.tab_archive, Icons.Filled.Archive),
    STATISTICS(R.string.tab_statistics, Icons.Filled.BarChart),
}

@Composable
fun MainScaffold(onOpenReport: (reportId: String, readOnly: Boolean) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(MainTab.REPORTS) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selected == tab,
                        onClick = { selected = tab },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        when (selected) {
            MainTab.REPORTS -> ActiveReportsScreen(
                modifier = Modifier.padding(padding),
                onOpenReport = { onOpenReport(it, false) },
            )
            MainTab.ARCHIVE -> ArchiveScreen(
                modifier = Modifier.padding(padding),
                onOpenReport = { onOpenReport(it, true) },
            )
            MainTab.STATISTICS -> StatisticsScreen(modifier = Modifier.padding(padding))
        }
    }
}
