package hu.orszembejelento.service.moderation

import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.moderation.data.DeletedReportFilter
import hu.orszembejelento.service.moderation.data.DeletedReportListItemResponse
import hu.orszembejelento.service.moderation.data.DeletedReportPageResponse
import hu.orszembejelento.service.moderation.ui.DeletedReportsListViewModel
import hu.orszembejelento.service.reports.data.ReportCategorySummary
import hu.orszembejelento.service.reports.data.ReportEventTypeSummary
import hu.orszembejelento.service.reports.data.ReportSettlementSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** brief §18/§44-45/§59 - server-side pagination and filtering, mirrors ReportQueueViewModelTest's shape. */
@OptIn(ExperimentalCoroutinesApi::class)
class DeletedReportsListViewModelTest {

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun item(id: String) = DeletedReportListItemResponse(
        publicReportId = id,
        occurredAt = "2026-01-01T00:00:00Z",
        submittedAt = "2026-01-01T00:00:00Z",
        settlement = ReportSettlementSummary("s", "Település"),
        category = ReportCategorySummary("C", "Kategória"),
        eventType = ReportEventTypeSummary("E", "Esemény"),
        serviceArea = null,
        reason = "SPAM",
        deletedAt = "2026-01-02T00:00:00Z",
        deletedByServiceId = "SZ-1042",
        statusBeforeDelete = "NEW",
        restoreTargetStatus = "NEW",
        workflowVersion = 1,
    )

    @Test
    fun `refresh replaces items, load more appends without duplicating`() = runTest {
        val viewModel = DeletedReportsListViewModel(
            fetchPage = { page, _, _ ->
                ApiResult.Success(
                    DeletedReportPageResponse(
                        items = listOf(item("page$page-a"), item("page$page-b")),
                        page = page, size = 2, totalElements = 6, totalPages = 3,
                    ),
                )
            },
            onSessionEnded = {},
        )

        assertEquals(2, viewModel.state.value.items.size)
        assertTrue(viewModel.state.value.canLoadMore)

        viewModel.loadMore()
        assertEquals(4, viewModel.state.value.items.size)
        assertEquals(listOf("page0-a", "page0-b", "page1-a", "page1-b"), viewModel.state.value.items.map { it.publicReportId })

        viewModel.refresh()
        assertEquals("refresh must replace, not append", 2, viewModel.state.value.items.size)
    }

    @Test
    fun `updating the filter resets to page 0 and refetches`() = runTest {
        val seenFilters = mutableListOf<DeletedReportFilter>()
        val viewModel = DeletedReportsListViewModel(
            fetchPage = { page, _, filter ->
                seenFilters += filter
                ApiResult.Success(DeletedReportPageResponse(items = emptyList(), page = page, size = 50, totalElements = 0, totalPages = 1))
            },
            onSessionEnded = {},
        )
        viewModel.updateFilter(DeletedReportFilter(reason = "SPAM"))
        assertEquals("SPAM", seenFilters.last().reason)
        assertEquals(0, viewModel.state.value.page)
    }

    @Test
    fun `a failure surfaces as an error and never crashes or silently empties the list`() = runTest {
        val viewModel = DeletedReportsListViewModel(
            fetchPage = { _, _, _ -> ApiResult.Failure(code = "REPORT_NOT_FOUND", httpStatus = 404) },
            onSessionEnded = {},
        )
        assertTrue(viewModel.state.value.error is ApiResult.Failure)
        assertFalse(viewModel.state.value.loading)
    }

    @Test
    fun `SessionEnded invokes the callback and does not treat it as an ordinary error`() = runTest {
        var sessionEndedCalls = 0
        val viewModel = DeletedReportsListViewModel(
            fetchPage = { _, _, _ -> ApiResult.SessionEnded },
            onSessionEnded = { sessionEndedCalls++ },
        )
        assertEquals(1, sessionEndedCalls)
        assertEquals(null, viewModel.state.value.error)
    }
}
