package hu.orszembejelento.service.reports

import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.reports.data.ReportFilter
import hu.orszembejelento.service.reports.data.ReportQueuePageResponse
import hu.orszembejelento.service.reports.ui.ReportQueueViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** brief §42-43: filters are server-side, and changing them resets paging. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportFilterTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `activeFacetCount counts only the explicit server-side facets, not free text or the assignee CTA`() {
        assertEquals(0, ReportFilter(query = "vonat", assigneeServiceId = "SZ-1").activeFacetCount)
        assertEquals(1, ReportFilter(areaId = "a").activeFacetCount)
        assertEquals(3, ReportFilter(categoryCode = "c", eventTypeCode = "e", settlementId = "s").activeFacetCount)
    }

    @Test
    fun `updateFilter always re-requests from page 0 and passes every facet to the backend`() = runTest {
        val pages = mutableListOf<Triple<Int, Int, ReportFilter>>()
        val vm = ReportQueueViewModel(
            fetchPage = { page, size, filter ->
                pages += Triple(page, size, filter)
                ApiResult.Success(ReportQueuePageResponse(emptyList(), page = page, size = size, totalElements = 0, totalPages = 3))
            },
            onSessionEnded = {},
        )

        vm.loadMore() // now on page 1
        vm.updateFilter(ReportFilter(categoryCode = "VIOLENCE_DANGER", areaId = "north"))

        // last call after updateFilter must be page 0 and carry the new facets
        val last = pages.last()
        assertEquals(0, last.first)
        assertEquals("VIOLENCE_DANGER", last.third.categoryCode)
        assertEquals("north", last.third.areaId)
    }

    @Test
    fun `a seeded initial filter is used for the very first load`() = runTest {
        var firstFilter: ReportFilter? = null
        ReportQueueViewModel(
            fetchPage = { _, _, filter ->
                if (firstFilter == null) firstFilter = filter
                ApiResult.Success(ReportQueuePageResponse(emptyList(), 0, 50, 0, 0))
            },
            onSessionEnded = {},
            initialFilter = ReportFilter(areaId = "seeded-area"),
        )
        assertEquals("seeded-area", firstFilter?.areaId)
    }
}
