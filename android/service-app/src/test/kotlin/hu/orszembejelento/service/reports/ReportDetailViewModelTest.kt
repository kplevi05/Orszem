package hu.orszembejelento.service.reports

import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.reports.data.ReportAreaSummary
import hu.orszembejelento.service.reports.data.ReportCategorySummary
import hu.orszembejelento.service.reports.data.ReportDetailResponse
import hu.orszembejelento.service.reports.data.ReportEventTypeSummary
import hu.orszembejelento.service.reports.data.ReportFilter
import hu.orszembejelento.service.reports.data.ReportQueuePageResponse
import hu.orszembejelento.service.reports.data.ReportSettlementSummary
import hu.orszembejelento.service.reports.data.ReportWorkflowRepository
import hu.orszembejelento.service.reports.ui.ReportDetailViewModel
import hu.orszembejelento.service.reports.ui.WorkflowMutationKind
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
 * brief §22-25/§76-77 (and the "critical" §96/§97 UI-level requirements, exercised here at
 * the ViewModel level that actually implements them): a rejected mutation is never blindly
 * resent, a second tap while one is in flight is a no-op, and the screen always ends up
 * reflecting the server's real current state rather than a synthesized guess.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportDetailViewModelTest {

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun detail(status: String, version: Long) = ReportDetailResponse(
        publicReportId = "r1",
        occurredAt = "2026-01-01T00:00:00Z",
        submittedAt = "2026-01-01T00:00:00Z",
        settlement = ReportSettlementSummary("s", "Település"),
        category = ReportCategorySummary("C", "Kategória"),
        eventType = ReportEventTypeSummary("E", "Esemény"),
        status = status,
        workflowVersion = version,
        routingClassification = "ROUTED",
        serviceArea = ReportAreaSummary("a", "Terület"),
    )

    /** A minimal fake implementing the repository interface directly - no network types at all. */
    private class FakeRepository(
        var detailResponses: MutableList<ApiResult<ReportDetailResponse>>,
        var claimResult: ApiResult<ReportDetailResponse> = ApiResult.SessionEnded,
    ) : ReportWorkflowRepository {
        var detailCalls = 0
        var claimCalls = 0

        override suspend fun newQueue(page: Int, size: Int, filter: ReportFilter): ApiResult<ReportQueuePageResponse> = error("unused")
        override suspend fun inProgressQueue(page: Int, size: Int, filter: ReportFilter): ApiResult<ReportQueuePageResponse> = error("unused")
        override suspend fun archiveQueue(page: Int, size: Int, filter: ReportFilter): ApiResult<ReportQueuePageResponse> = error("unused")

        override suspend fun detail(publicReportId: String): ApiResult<ReportDetailResponse> {
            detailCalls++
            return if (detailResponses.size > 1) detailResponses.removeAt(0) else detailResponses.first()
        }

        override suspend fun claim(publicReportId: String, expectedVersion: Long): ApiResult<ReportDetailResponse> {
            claimCalls++
            return claimResult
        }

        override suspend fun returnToNew(publicReportId: String, expectedVersion: Long) = error("unused")
        override suspend fun close(publicReportId: String, expectedVersion: Long) = error("unused")
        override suspend fun reassign(publicReportId: String, expectedVersion: Long, targetServiceId: String) = error("unused")
    }

    @Test
    fun `claim race - REPORT_ALREADY_ASSIGNED never retries the claim and refreshes to the real state`() = runTest {
        val fake = FakeRepository(
            detailResponses = mutableListOf(
                ApiResult.Success(detail("NEW", 0)),
                ApiResult.Success(detail("IN_PROGRESS", 1)), // someone else won the race
            ),
            claimResult = ApiResult.Failure(code = "REPORT_ALREADY_ASSIGNED", httpStatus = 409),
        )
        val vm = ReportDetailViewModel("r1", fake, onSessionEnded = {})
        assertEquals("NEW", vm.state.value.detail?.status)

        vm.claim()

        assertEquals(1, fake.claimCalls) // exactly one attempt - no automatic retry
        assertEquals(2, fake.detailCalls) // initial load + the post-failure refresh
        assertEquals("the screen must show the report's real current state after the race", "IN_PROGRESS", vm.state.value.detail?.status)
        assertTrue(vm.state.value.mutationError is ApiResult.Failure)
        assertEquals("REPORT_ALREADY_ASSIGNED", (vm.state.value.mutationError as ApiResult.Failure).code)
        assertFalse(vm.state.value.mutationInFlight)
    }

    @Test
    fun `stale workflow version - REPORT_STATE_CHANGED never retries and refreshes to the current version`() = runTest {
        val fake = FakeRepository(
            detailResponses = mutableListOf(
                ApiResult.Success(detail("IN_PROGRESS", 3)), // stale local version
                ApiResult.Success(detail("IN_PROGRESS", 5)), // the real, current version
            ),
            claimResult = ApiResult.Failure(code = "REPORT_STATE_CHANGED", httpStatus = 409),
        )
        val vm = ReportDetailViewModel("r1", fake, onSessionEnded = {})

        vm.claim() // any mutation call exercises the same refresh-not-retry path

        assertEquals(1, fake.claimCalls)
        assertEquals(5L, vm.state.value.detail?.workflowVersion)
        assertEquals("REPORT_STATE_CHANGED", (vm.state.value.mutationError as ApiResult.Failure).code)
    }

    @Test
    fun `a successful mutation replaces local state with the committed response, not a guess`() = runTest {
        val fake = FakeRepository(
            detailResponses = mutableListOf(ApiResult.Success(detail("NEW", 0))),
            claimResult = ApiResult.Success(detail("IN_PROGRESS", 1)),
        )
        val vm = ReportDetailViewModel("r1", fake, onSessionEnded = {})

        vm.claim()

        assertEquals("IN_PROGRESS", vm.state.value.detail?.status)
        assertEquals(1L, vm.state.value.detail?.workflowVersion)
        assertEquals(WorkflowMutationKind.CLAIM, vm.state.value.lastMutation)
        assertEquals(null, vm.state.value.mutationError)
    }

    @Test
    fun `SessionEnded from a mutation invokes the callback rather than surfacing as a mutation error`() = runTest {
        var sessionEndedCalls = 0
        val fake = FakeRepository(
            detailResponses = mutableListOf(ApiResult.Success(detail("NEW", 0))),
            claimResult = ApiResult.SessionEnded,
        )
        val vm = ReportDetailViewModel("r1", fake, onSessionEnded = { sessionEndedCalls++ })

        vm.claim()

        assertEquals(1, sessionEndedCalls)
        assertEquals(null, vm.state.value.mutationError)
    }
}
