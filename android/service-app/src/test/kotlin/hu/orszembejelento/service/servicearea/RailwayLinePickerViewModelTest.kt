package hu.orszembejelento.service.servicearea

import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.servicearea.data.RailwayLineAdminListFilter
import hu.orszembejelento.service.servicearea.data.RailwayLineAdminListItemResponse
import hu.orszembejelento.service.servicearea.data.RailwayLineAdminListPageResponse
import hu.orszembejelento.service.servicearea.data.RailwayLineAssignmentFilter
import hu.orszembejelento.service.servicearea.ui.RailwayLinePickerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Phase 10 brief §19/§20/§50/§64/§65/§72 - the picker's own assign/move confirmation call. */
@OptIn(ExperimentalCoroutinesApi::class)
class RailwayLinePickerViewModelTest {

    @Before
    fun setUpMainDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }

    @After
    fun tearDownMainDispatcher() { Dispatchers.resetMain() }

    private fun unassignedLine(id: String) = RailwayLineAdminListItemResponse(id, "L1", "Vonal", true, null, null)
    private fun assignedLine(id: String, areaId: String, areaName: String) = RailwayLineAdminListItemResponse(id, "L1", "Vonal", true, areaId, areaName)

    @Test
    fun `selecting an unassigned line calls assign with a null expectedCurrentServiceAreaId`() = runTest {
        val fake = FakeAreaAdminRepository()
        val vm = RailwayLinePickerViewModel("target-area", fake, onSessionEnded = {})

        vm.confirmAssign(unassignedLine("line-1"))

        assertEquals(Triple("line-1", "target-area", null), fake.lastAssignArgs)
        assertTrue(vm.state.value.assigned)
    }

    @Test
    fun `selecting a line already assigned elsewhere calls assign as a move - carries its current area as expectedCurrentServiceAreaId`() = runTest {
        val fake = FakeAreaAdminRepository()
        val vm = RailwayLinePickerViewModel("target-area", fake, onSessionEnded = {})

        vm.confirmAssign(assignedLine("line-1", "other-area", "Masik terulet"))

        assertEquals(Triple("line-1", "target-area", "other-area"), fake.lastAssignArgs)
    }

    @Test
    fun `changing the assignment filter resets to page 0 and is carried in the next request`() = runTest {
        val fake = FakeAreaAdminRepository()
        val vm = RailwayLinePickerViewModel("target-area", fake, onSessionEnded = {})
        vm.updateFilter(RailwayLineAdminListFilter(assignment = RailwayLineAssignmentFilter.UNASSIGNED))
        assertEquals(RailwayLineAssignmentFilter.UNASSIGNED, fake.seenLineFilters.last().assignment)
        assertEquals(0, vm.state.value.page)
    }

    @Test
    fun `refresh replaces items, load more appends`() = runTest {
        val fake = FakeAreaAdminRepository()
        val vm = RailwayLinePickerViewModel("target-area", fake, onSessionEnded = {})
        fake.listRailwayLinesResult = ApiResult.Success(RailwayLineAdminListPageResponse(listOf(unassignedLine("a"), unassignedLine("b")), 0, 2, 4, 2))
        vm.refresh()
        assertEquals(2, vm.state.value.items.size)
        assertTrue(vm.state.value.canLoadMore)

        fake.listRailwayLinesResult = ApiResult.Success(RailwayLineAdminListPageResponse(listOf(unassignedLine("c"), unassignedLine("d")), 1, 2, 4, 2))
        vm.loadMore()
        assertEquals(4, vm.state.value.items.size)
    }

    @Test
    fun `a rejected assignment surfaces assignError and refreshes - never silently retried`() = runTest {
        val fake = FakeAreaAdminRepository(assignRailwayLineResult = ApiResult.Failure(code = "RAILWAY_LINE_ASSIGNMENT_CHANGED", httpStatus = 409))
        val vm = RailwayLinePickerViewModel("target-area", fake, onSessionEnded = {})

        vm.confirmAssign(unassignedLine("line-1"))

        assertEquals(1, fake.assignCalls)
        assertTrue(vm.state.value.assignError is ApiResult.Failure)
        assertFalse(vm.state.value.assigned)
    }

    @Test
    fun `a second confirmAssign while one is in flight is a no-op - single-flight`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val fake = FakeAreaAdminRepository()
        val vm = RailwayLinePickerViewModel("target-area", fake, onSessionEnded = {})
        runCurrent()

        val line = unassignedLine("line-1")
        vm.confirmAssign(line)
        vm.confirmAssign(line)
        runCurrent()

        assertEquals(1, fake.assignCalls)
    }

    @Test
    fun `SessionEnded invokes the callback and never surfaces as assignError`() = runTest {
        var sessionEndedCalls = 0
        val fake = FakeAreaAdminRepository(assignRailwayLineResult = ApiResult.SessionEnded)
        val vm = RailwayLinePickerViewModel("target-area", fake, onSessionEnded = { sessionEndedCalls++ })

        vm.confirmAssign(unassignedLine("line-1"))

        assertEquals(1, sessionEndedCalls)
        assertNull(vm.state.value.assignError)
    }
}
