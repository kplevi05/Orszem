package hu.orszem.serviceapp.feature.common

import hu.orszem.core.model.ServiceReportSummary

sealed interface ListUiState {
    data object Loading : ListUiState
    data object Empty : ListUiState
    data class Error(val recoverable: Boolean) : ListUiState
    data class Content(
        val items: List<ServiceReportSummary>,
        val loadingMore: Boolean = false,
        val hasMore: Boolean = false,
    ) : ListUiState
}
