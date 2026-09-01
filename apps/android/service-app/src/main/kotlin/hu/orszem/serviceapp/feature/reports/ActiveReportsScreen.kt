package hu.orszem.serviceapp.feature.reports

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.orszem.core.designsystem.LoadingState
import hu.orszem.core.designsystem.MessageState
import hu.orszem.serviceapp.R
import hu.orszem.serviceapp.feature.common.ListUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveReportsScreen(
    modifier: Modifier = Modifier,
    onOpenReport: (String) -> Unit,
    viewModel: ActiveReportsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                val count = (state as? ListUiState.Content)?.items?.size ?: 0
                Text(
                    text = stringResource(R.string.tab_reports) +
                        if (state is ListUiState.Content) "  " + stringResource(R.string.reports_active_count, count) else "",
                )
            },
        )
        PullToRefreshBox(
            isRefreshing = state is ListUiState.Loading,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            when (val s = state) {
                ListUiState.Loading -> LoadingState()
                ListUiState.Empty -> MessageState(title = stringResource(R.string.reports_empty))
                is ListUiState.Error -> MessageState(
                    title = stringResource(R.string.reports_error),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = viewModel::refresh,
                )
                is ListUiState.Content -> LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                ) {
                    items(s.items, key = { it.id }) { report ->
                        ReportCard(report = report, onClick = { onOpenReport(report.id) })
                    }
                    if (s.hasMore) {
                        item { LoadMoreRow(onAppear = viewModel::loadMore) }
                    }
                }
            }
        }
    }
}
