package hu.orszembejelento.service.servicearea

import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.servicearea.data.ServiceAreaAdminListFilter
import hu.orszembejelento.service.servicearea.data.ServiceAreaAdminListItemResponse
import hu.orszembejelento.service.servicearea.data.ServiceAreaAdminListPageResponse
import hu.orszembejelento.service.servicearea.data.AreaAdminRepository
import hu.orszembejelento.service.servicearea.data.RailwayLineAdminListFilter
import hu.orszembejelento.service.servicearea.data.RailwayLineAdminListPageResponse
import hu.orszembejelento.service.servicearea.data.ServiceAreaAdminDetailResponse
import hu.orszembejelento.service.servicearea.data.ServiceAreaAdminResponse
import hu.orszembejelento.service.servicearea.ui.ServiceAreaAdminListViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
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

    /** Field-test fix (§4): see `ReportQueueViewModelTest`'s equivalent test for the full rationale. */
    @Test
    fun `refresh cancels an in-flight refresh so a slower stale response can never overwrite the fresher one`() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            var calls = 0
            val repo = object : AreaAdminRepository {
                override suspend fun listAreas(page: Int, size: Int, filter: ServiceAreaAdminListFilter): ApiResult<ServiceAreaAdminListPageResponse> {
                    calls++
                    return if (calls == 1) {
                        delay(1_000)
                        ApiResult.Success(ServiceAreaAdminListPageResponse(listOf(item("stale")), 0, 1, 1, 1))
                    } else {
                        delay(10)
                        ApiResult.Success(ServiceAreaAdminListPageResponse(listOf(item("fresh")), 0, 1, 1, 1))
                    }
                }
                override suspend fun areaDetail(areaId: String): ApiResult<ServiceAreaAdminDetailResponse> = error("not used")
                override suspend fun createArea(name: String): ApiResult<ServiceAreaAdminResponse> = error("not used")
                override suspend fun renameArea(areaId: String, expectedVersion: Long, name: String): ApiResult<ServiceAreaAdminResponse> = error("not used")
                override suspend fun activateArea(areaId: String, expectedVersion: Long): ApiResult<ServiceAreaAdminResponse> = error("not used")
                override suspend fun deactivateArea(areaId: String, expectedVersion: Long): ApiResult<ServiceAreaAdminResponse> = error("not used")
                override suspend fun listRailwayLines(page: Int, size: Int, filter: RailwayLineAdminListFilter): ApiResult<RailwayLineAdminListPageResponse> = error("not used")
                override suspend fun assignRailwayLine(railwayLineId: String, targetServiceAreaId: String, expectedCurrentServiceAreaId: String?): ApiResult<Unit> = error("not used")
                override suspend fun unassignRailwayLine(railwayLineId: String, expectedCurrentServiceAreaId: String): ApiResult<Unit> = error("not used")
            }
            val vm = ServiceAreaAdminListViewModel(repo, onSessionEnded = {})
            dispatcher.scheduler.runCurrent() // let init{}'s own call #1 actually start (into its delay) first
            vm.refresh() // fired while that first call is still genuinely in flight
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf("fresh"), vm.state.value.items.map { it.id })
        } finally {
            Dispatchers.resetMain()
        }
    }
}
