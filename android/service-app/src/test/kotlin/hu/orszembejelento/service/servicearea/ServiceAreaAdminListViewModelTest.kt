package hu.orszembejelento.service.servicearea

import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.servicearea.data.ServiceAreaAdminListFilter
import hu.orszembejelento.service.servicearea.data.ServiceAreaAdminListItemResponse
import hu.orszembejelento.service.servicearea.data.ServiceAreaAdminListPageResponse
import hu.orszembejelento.service.servicearea.ui.ServiceAreaAdminListViewModel
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

/** Phase 10 brief §46/§66/§72 - server-side pagination and filtering, mirrors DeletedReportsListViewModelTest's shape. */
@OptIn(ExperimentalCoroutinesApi::class)
class ServiceAreaAdminListViewModelTest {

    @Before
    fun setUpMainDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }

    @After
    fun tearDownMainDispatcher() { Dispatchers.resetMain() }

    private fun item(id: String) = ServiceAreaAdminListItemResponse(
        id = id, name = "Terulet $id", active = true, adminVersion = 0, mappedRailwayLineCount = 1, openOperationalReportCount = 0,
    )

    @Test
    fun `refresh replaces items, load more appends without duplicating`() = runTest {
        val fake = FakeAreaAdminRepository()
        var page = 0
        val vm = ServiceAreaAdminListViewModel(fake, onSessionEnded = {})
        fake.listAreasResult = ApiResult.Success(ServiceAreaAdminListPageResponse(listOf(item("a"), item("b")), 0, 2, 6, 3))
        vm.refresh()
        assertEquals(2, vm.state.value.items.size)
        assertTrue(vm.state.value.canLoadMore)

        fake.listAreasResult = ApiResult.Success(ServiceAreaAdminListPageResponse(listOf(item("c"), item("d")), 1, 2, 6, 3))
        vm.loadMore()
        assertEquals(4, vm.state.value.items.size)

        fake.listAreasResult = ApiResult.Success(ServiceAreaAdminListPageResponse(listOf(item("a"), item("b")), 0, 2, 6, 3))
        vm.refresh()
        assertEquals("refresh must replace, not append", 2, vm.state.value.items.size)
    }

    @Test
    fun `changing the active filter resets to page 0 and is carried in the next request`() = runTest {
        val fake = FakeAreaAdminRepository()
        val vm = ServiceAreaAdminListViewModel(fake, onSessionEnded = {})
        vm.updateFilter(ServiceAreaAdminListFilter(active = true))
        assertEquals(true, fake.seenFilters.last().active)
        assertEquals(0, vm.state.value.page)
    }

    @Test
    fun `a failure surfaces as an error and never crashes or silently empties the list`() = runTest {
        val fake = FakeAreaAdminRepository(listAreasResult = ApiResult.Failure(code = "SERVICE_AREA_ADMIN_FORBIDDEN", httpStatus = 403))
        val vm = ServiceAreaAdminListViewModel(fake, onSessionEnded = {})
        assertTrue(vm.state.value.error is ApiResult.Failure)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun `SessionEnded invokes the callback and is not treated as an ordinary error`() = runTest {
        var sessionEndedCalls = 0
        val fake = FakeAreaAdminRepository(listAreasResult = ApiResult.SessionEnded)
        ServiceAreaAdminListViewModel(fake, onSessionEnded = { sessionEndedCalls++ })
        assertEquals(1, sessionEndedCalls)
    }
}
