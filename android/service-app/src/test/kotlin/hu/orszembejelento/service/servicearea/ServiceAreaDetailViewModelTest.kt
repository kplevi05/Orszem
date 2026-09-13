package hu.orszembejelento.service.servicearea

import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.servicearea.data.MappedRailwayLineResponse
import hu.orszembejelento.service.servicearea.data.ServiceAreaAdminDetailResponse
import hu.orszembejelento.service.servicearea.data.ServiceAreaAdminResponse
import hu.orszembejelento.service.servicearea.ui.ServiceAreaDetailViewModel
import kotlinx.coroutines.CompletableDeferred
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

/**
 * Phase 10 brief §48/§64/§65/§72: every mutation is single-flight, and whatever the outcome,
 * the view always re-fetches the real server state afterward rather than trusting the
 * request it just sent - never a blind retry.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServiceAreaDetailViewModelTest {

    @Before
    fun setUpMainDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }

    @After
    fun tearDownMainDispatcher() { Dispatchers.resetMain() }

    private fun detail(active: Boolean = true, version: Long = 3, lines: List<MappedRailwayLineResponse> = emptyList()) =
        ServiceAreaAdminDetailResponse(
            id = "area-1", name = "Terulet", active = active, adminVersion = version,
            mappedRailwayLines = lines, mappedRailwayLineCount = lines.size, openOperationalReportCount = 0,
        )

    @Test
    fun `rename passes the currently-loaded adminVersion, never a client-guessed one`() = runTest {
        val fake = FakeAreaAdminRepository(areaDetailResult = ApiResult.Success(detail(version = 5)))
        fake.renameAreaResult = ApiResult.Success(ServiceAreaAdminResponse("area-1", "Uj nev", true, 6))
        val vm = ServiceAreaDetailViewModel("area-1", fake, onSessionEnded = {})

        vm.rename("Uj nev")

        assertEquals(5L, fake.lastRenameArgs?.first)
        assertEquals("Uj nev", fake.lastRenameArgs?.second)
    }

    @Test
    fun `unassignLine passes this area's own id as expectedCurrentServiceAreaId`() = runTest {
        val line = MappedRailwayLineResponse("line-1", "L100", "Vonal", true)
        val fake = FakeAreaAdminRepository(areaDetailResult = ApiResult.Success(detail(lines = listOf(line))))
        val vm = ServiceAreaDetailViewModel("area-1", fake, onSessionEnded = {})

        vm.unassignLine("line-1")

        assertEquals("line-1" to "area-1", fake.lastUnassignArgs)
    }

    @Test
    fun `a second mutation call while one is in flight is a no-op - single-flight`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        val fake = FakeAreaAdminRepository(areaDetailResult = ApiResult.Success(detail(version = 1)))
        val vm = ServiceAreaDetailViewModel("area-1", fake, onSessionEnded = {})
        runCurrent()
        fake.activateAreaResult = ApiResult.Success(ServiceAreaAdminResponse("area-1", "Terulet", true, 2))

        vm.activate()
        vm.activate() // fired before the first call's coroutine has resumed
        runCurrent()

        assertEquals("only one request must actually reach the repository", 1, fake.activateCalls)
    }

    @Test
    fun `a rejected mutation surfaces mutationError and refreshes - never silently retried`() = runTest {
        val fake = FakeAreaAdminRepository(areaDetailResult = ApiResult.Success(detail(version = 2)))
        fake.deactivateAreaResult = ApiResult.Failure(code = "SERVICE_AREA_HAS_RAILWAY_LINES", httpStatus = 409)
        val vm = ServiceAreaDetailViewModel("area-1", fake, onSessionEnded = {})

        vm.deactivate()

        assertEquals("exactly one attempt - no automatic retry", 1, fake.deactivateCalls)
        assertTrue(vm.state.value.mutationError is ApiResult.Failure)
        assertTrue("detail must be refetched after a rejection", fake.areaDetailCalls >= 2)
        assertFalse(vm.state.value.mutationInFlight)
    }

    @Test
    fun `SessionEnded during a mutation invokes the callback and never surfaces as mutationError`() = runTest {
        var sessionEndedCalls = 0
        val fake = FakeAreaAdminRepository(areaDetailResult = ApiResult.Success(detail()))
        fake.activateAreaResult = ApiResult.SessionEnded
        val vm = ServiceAreaDetailViewModel("area-1", fake, onSessionEnded = { sessionEndedCalls++ })

        vm.activate()

        assertEquals(1, sessionEndedCalls)
        assertNull(vm.state.value.mutationError)
    }
}
