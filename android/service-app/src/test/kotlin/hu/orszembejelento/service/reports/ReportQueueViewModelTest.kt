package hu.orszembejelento.service.reports

import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.reports.data.ReportAreaSummary
import hu.orszembejelento.service.reports.data.ReportCategorySummary
import hu.orszembejelento.service.reports.data.ReportEventTypeSummary
import hu.orszembejelento.service.reports.data.ReportFilter
import hu.orszembejelento.service.reports.data.ReportListItemResponse
import hu.orszembejelento.service.reports.data.ReportQueuePageResponse
import hu.orszembejelento.service.reports.data.ReportSettlementSummary
import hu.orszembejelento.service.reports.ui.ReportQueueViewModel
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

/**
 * `viewModelScope` uses `Dispatchers.Main.immediate`, which does not exist on a plain JVM
 * unit test unless installed - [UnconfinedTestDispatcher] runs every launched coroutine
 * synchronously, so a fake, non-suspending `fetchPage` completes before the ViewModel
 * constructor call even returns and no explicit `advanceUntilIdle()` is needed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportQueueViewModelTest {

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun item(id: String) = ReportListItemResponse(
        publicReportId = id,
        occurredAt = "2026-01-01T00:00:00Z",
        submittedAt = "2026-01-01T00:00:00Z",
        settlement = ReportSettlementSummary("s", "Település"),
        category = ReportCategorySummary("C", "Kategória"),
        eventType = ReportEventTypeSummary("E", "Esemény"),
        routingClassification = "ROUTED",
        serviceArea = ReportAreaSummary("a", "Terület"),
        workflowVersion = 0,
    )

    @Test
    fun `refresh replaces items, load more appends without duplicating`() = runTest {
        var calls = 0
        val viewModel = ReportQueueViewModel(
            fetchPage = { page, _, _ ->
                calls++
                ApiResult.Success(
                    ReportQueuePageResponse(
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

        viewModel.loadMore()
        assertEquals(6, viewModel.state.value.items.size)
        assertFalse(viewModel.state.value.canLoadMore)

        viewModel.refresh()
        assertEquals("refresh must replace, not append", 2, viewModel.state.value.items.size)
        assertEquals(listOf("page0-a", "page0-b"), viewModel.state.value.items.map { it.publicReportId })
    }

    @Test
    fun `a failure surfaces as an error and never crashes or silently empties the list`() = runTest {
        val viewModel = ReportQueueViewModel(
            fetchPage = { _, _, _ -> ApiResult.Failure(code = "REPORT_NOT_FOUND", httpStatus = 404) },
            onSessionEnded = {},
        )
        assertTrue(viewModel.state.value.error is ApiResult.Failure)
        assertEquals("REPORT_NOT_FOUND", (viewModel.state.value.error as ApiResult.Failure).code)
        assertFalse(viewModel.state.value.loading)
    }

    @Test
    fun `SessionEnded invokes the callback and does not treat it as an ordinary error`() = runTest {
        var sessionEndedCalls = 0
        val viewModel = ReportQueueViewModel(
            fetchPage = { _, _, _ -> ApiResult.SessionEnded },
            onSessionEnded = { sessionEndedCalls++ },
        )
        assertEquals(1, sessionEndedCalls)
        assertEquals(null, viewModel.state.value.error)
    }

    @Test
    fun `updating the filter resets to page 0 and refetches`() = runTest {
        val seenFilters = mutableListOf<ReportFilter>()
        val viewModel = ReportQueueViewModel(
            fetchPage = { page, _, filter ->
                seenFilters += filter
                ApiResult.Success(ReportQueuePageResponse(items = emptyList(), page = page, size = 50, totalElements = 0, totalPages = 1))
            },
            onSessionEnded = {},
        )
        viewModel.updateFilter(ReportFilter(query = "IC 924"))
        assertEquals("IC 924", seenFilters.last().query)
        assertEquals(0, viewModel.state.value.page)
    }
}
