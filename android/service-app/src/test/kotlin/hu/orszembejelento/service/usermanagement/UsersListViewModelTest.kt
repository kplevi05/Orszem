package hu.orszembejelento.service.usermanagement

import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.usermanagement.data.ManagedUserPageResponse
import hu.orszembejelento.service.usermanagement.data.ManagedUserResponse
import hu.orszembejelento.service.usermanagement.ui.UsersListViewModel
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

@OptIn(ExperimentalCoroutinesApi::class)
class UsersListViewModelTest {

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun user(serviceId: String, canManage: Boolean = true) = ManagedUserResponse(
        serviceId = serviceId, role = "SERVICE_USER", status = "ACTIVE",
        mustChangePassword = false, globalAreaAccess = false, areas = emptyList(), canManage = canManage,
    )

    @Test
    fun `canManage is preserved for each list row, never overridden client-side`() = runTest {
        var repoCalls = 0
        val vm = UsersListViewModel(
            fetchPage = { page, _, _ ->
                repoCalls++
                ApiResult.Success(
                    ManagedUserPageResponse(
                        items = listOf(user("SZ-1042", canManage = true), user("SZ-2088", canManage = false)),
                        page = page, size = 2, totalCount = 2,
                    ),
                )
            },
            onSessionEnded = {},
        )
        assertEquals(1, repoCalls)
        assertTrue(vm.state.value.items.first { it.serviceId == "SZ-1042" }.canManage)
        assertFalse(vm.state.value.items.first { it.serviceId == "SZ-2088" }.canManage)
    }

    @Test
    fun `updateQuery resets paging and issues a fresh search`() = runTest {
        val seenQueries = mutableListOf<String?>()
        val vm = UsersListViewModel(
            fetchPage = { page, _, query ->
                seenQueries += query
                ApiResult.Success(ManagedUserPageResponse(items = emptyList(), page = page, size = 30, totalCount = 0))
            },
            onSessionEnded = {},
        )
        vm.updateQuery("SZ-10")
        assertEquals("SZ-10", seenQueries.last())
        assertEquals(0, vm.state.value.page)
    }

    @Test
    fun `canLoadMore is false once every item has been loaded`() = runTest {
        val vm = UsersListViewModel(
            fetchPage = { page, _, _ ->
                ApiResult.Success(ManagedUserPageResponse(items = listOf(user("SZ-1")), page = page, size = 1, totalCount = 1))
            },
            onSessionEnded = {},
        )
        assertFalse(vm.state.value.canLoadMore)
    }

    @Test
    fun `SessionEnded invokes the callback rather than surfacing as an ordinary error`() = runTest {
        var sessionEndedCalls = 0
        val vm = UsersListViewModel(
            fetchPage = { _, _, _ -> ApiResult.SessionEnded },
            onSessionEnded = { sessionEndedCalls++ },
        )
        assertEquals(1, sessionEndedCalls)
        assertEquals(null, vm.state.value.error)
    }

    /** Field-test fix (§4): see `ReportQueueViewModelTest`'s equivalent test for the full rationale. */
    @Test
    fun `refresh cancels an in-flight refresh so a slower stale response can never overwrite the fresher one`() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            var calls = 0
            val vm = UsersListViewModel(
                fetchPage = { page, _, _ ->
                    calls++
                    if (calls == 1) {
                        delay(1_000)
                        ApiResult.Success(ManagedUserPageResponse(items = listOf(user("stale")), page = page, size = 1, totalCount = 1))
                    } else {
                        delay(10)
                        ApiResult.Success(ManagedUserPageResponse(items = listOf(user("fresh")), page = page, size = 1, totalCount = 1))
                    }
                },
                onSessionEnded = {},
            )
            dispatcher.scheduler.runCurrent() // let init{}'s own call #1 actually start (into its delay) first
            vm.refresh() // fired while that first call is still genuinely in flight
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf("fresh"), vm.state.value.items.map { it.serviceId })
        } finally {
            Dispatchers.resetMain()
        }
    }
}
