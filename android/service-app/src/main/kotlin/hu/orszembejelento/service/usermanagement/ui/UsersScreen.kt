package hu.orszembejelento.service.usermanagement.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.orszembejelento.service.R
import hu.orszembejelento.service.common.ui.EmptyState
import hu.orszembejelento.service.common.ui.ErrorState
import hu.orszembejelento.service.common.ui.FullScreenLoading
import hu.orszembejelento.service.common.ui.LoadMoreButton
import hu.orszembejelento.service.common.ui.apiErrorMessage
import hu.orszembejelento.service.usermanagement.data.ManagedUserResponse
import kotlinx.coroutines.delay

/** `Felhasználók` (brief §51-52) - entry lives inside Moderáció/Adminisztráció, never reachable by SERVICE_USER. */
@Composable
fun UsersScreen(
    viewModel: UsersListViewModel,
    onOpenUser: (String) -> Unit,
    onCreateUser: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var searchText by remember { mutableStateOf(state.query) }

    LaunchedEffect(searchText) {
        delay(400)
        if (searchText != state.query) viewModel.updateQuery(searchText)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.users_title), style = MaterialTheme.typography.headlineSmall)
            IconButton(onClick = onCreateUser) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_create_user))
            }
        }

        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it },
            placeholder = { Text(stringResource(R.string.search_hint_users)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.content_description_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )

        when {
            state.loading -> FullScreenLoading()
            state.error != null -> ErrorState(message = apiErrorMessage(state.error!!), onRetry = viewModel::refresh)
            state.items.isEmpty() -> EmptyState(stringResource(R.string.error_user_not_found))
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.items, key = { it.serviceId }) { user ->
                    ManagedUserRow(user = user, onClick = { onOpenUser(user.serviceId) })
                }
                if (state.canLoadMore) {
                    item { LoadMoreButton(loading = state.loadingMore, onClick = viewModel::loadMore) }
                }
            }
        }
    }
}

@Composable
private fun ManagedUserRow(user: ManagedUserResponse, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column {
                Text(user.serviceId, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = buildString {
                        append(stringResource(hu.orszembejelento.service.common.ui.roleLabelRes(user.role)))
                        append(" · ")
                        append(stringResource(hu.orszembejelento.service.common.ui.userStatusLabelRes(user.status)))
                        if (user.globalAreaAccess) {
                            append(" · ").append(stringResource(R.string.active_work_view_all))
                        } else if (user.areas.isNotEmpty()) {
                            append(" · ").append(user.areas.joinToString { it.name })
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(if (user.canManage) R.string.label_manageable else R.string.label_read_only),
                style = MaterialTheme.typography.labelSmall,
                color = if (user.canManage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
