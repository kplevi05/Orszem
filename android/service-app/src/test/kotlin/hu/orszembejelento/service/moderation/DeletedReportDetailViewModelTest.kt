package hu.orszembejelento.service.moderation

import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.moderation.data.DeletedReportDetailResponse
import hu.orszembejelento.service.moderation.data.DeletedReportFilter
import hu.orszembejelento.service.moderation.data.DeletedReportPageResponse
import hu.orszembejelento.service.moderation.data.ModerationRepository
import hu.orszembejelento.service.moderation.ui.DeletedReportDetailViewModel
import hu.orszembejelento.service.reports.data.ReportCategorySummary
import hu.orszembejelento.service.reports.data.ReportEventTypeSummary
import hu.orszembejelento.service.reports.data.ReportSettlementSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** brief §48-50 - restore is single-flight, never blindly retried, and trusts the committed response. */
@OptIn(ExperimentalCoroutinesApi::class)
class DeletedReportDetailViewModelTest {

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun detail(statusBeforeDelete: String, version: Long) = DeletedReportDetailResponse(
        publicReportId = "r1",
        occurredAt = "2026-01-01T00:00:00Z",
        submittedAt = "2026-01-01T00:00:00Z",
        settlement = ReportSettlementSummary("s", "Település"),
        category = ReportCategorySummary("C", "Kategória"),
        eventType = ReportEventTypeSummary("E", "Esemény"),
        workflowVersion = version,
        reason = "SPAM",
        deletedAt = "2026-01-02T00:00:00Z",
        deletedByServiceId = "SZ-1042",
        statusBeforeDelete = statusBeforeDelete,
        restoreTargetStatus = if (statusBeforeDelete == "ARCHIVED") "ARCHIVED" else "NEW",
    )

    private class FakeRepository(
        var detailResponses: MutableList<ApiResult<DeletedReportDetailResponse>>,
        var restoreResult: ApiResult<Unit> = ApiResult.SessionEnded,
    ) : ModerationRepository {
        var detailCalls = 0
        var restoreCalls = 0

        override suspend fun delete(publicReportId: String, expectedVersion: Long, reason: String) = error("unused")
        override suspend fun deletedList(page: Int, size: Int, filter: DeletedReportFilter): ApiResult<DeletedReportPageResponse> = error("unused")

        override suspend fun deletedDetail(publicReportId: String): ApiResult<DeletedReportDetailResponse> {
            detailCalls++
            return if (detailResponses.size > 1) detailResponses.removeAt(0) else detailResponses.first()
        }

        override suspend fun restore(publicReportId: String, expectedVersion: Long): ApiResult<Unit> {
            restoreCalls++
            return restoreResult
        }
    }

    @Test
    fun `a successful restore flips restoreCompleted without a second network call`() = runTest {
        val fake = FakeRepository(
            detailResponses = mutableListOf(ApiResult.Success(detail("NEW", 1))),
            restoreResult = ApiResult.Success(Unit),
        )
        val vm = DeletedReportDetailViewModel("r1", fake, onSessionEnded = {})

        vm.restore()

        assertEquals(1, fake.restoreCalls)
        assertTrue(vm.state.value.restoreCompleted)
        assertFalse(vm.state.value.restoreInFlight)
        assertEquals(null, vm.state.value.restoreError)
    }

    @Test
    fun `a second restore call while one is genuinely in flight is a no-op - single-flight`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var restoreCalls = 0
        val fake = object : ModerationRepository {
            override suspend fun delete(publicReportId: String, expectedVersion: Long, reason: String) = error("unused")
            override suspend fun deletedList(page: Int, size: Int, filter: DeletedReportFilter) = error("unused")
            override suspend fun deletedDetail(publicReportId: String): ApiResult<DeletedReportDetailResponse> =
                ApiResult.Success(detail("NEW", 1))

            override suspend fun restore(publicReportId: String, expectedVersion: Long): ApiResult<Unit> {
                restoreCalls++
                gate.await() // held open so the second restore() call genuinely arrives mid-flight
                return ApiResult.Success(Unit)
            }
        }
        val vm = DeletedReportDetailViewModel("r1", fake, onSessionEnded = {})

        vm.restore() // suspends on the gate, but restoreInFlight is already true by the time this returns
        assertTrue(vm.state.value.restoreInFlight)
        vm.restore() // must see restoreInFlight and return without calling the repository again

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("the second call must not have started a second network request", 1, restoreCalls)
        assertTrue(vm.state.value.restoreCompleted)
    }

    @Test
    fun `REPORT_NOT_DELETED is treated as already-gone, not surfaced as an error`() = runTest {
        // Someone else already restored it, or a stale double-tap - either way the report is
        // gone from the deleted set, so the screen should navigate away, not show a dead end.
        val fake = FakeRepository(
            detailResponses = mutableListOf(ApiResult.Success(detail("NEW", 1))),
            restoreResult = ApiResult.Failure(code = "REPORT_NOT_DELETED", httpStatus = 409),
        )
        val vm = DeletedReportDetailViewModel("r1", fake, onSessionEnded = {})

        vm.restore()

        assertTrue(vm.state.value.restoreCompleted)
        assertEquals(null, vm.state.value.restoreError)
    }

    @Test
    fun `a stale-version rejection never retries and silently refreshes to the current detail`() = runTest {
        val fake = FakeRepository(
            detailResponses = mutableListOf(
                ApiResult.Success(detail("NEW", 1)),
                ApiResult.Success(detail("NEW", 2)), // the silent post-failure refresh
            ),
            restoreResult = ApiResult.Failure(code = "REPORT_STATE_CHANGED", httpStatus = 409),
        )
        val vm = DeletedReportDetailViewModel("r1", fake, onSessionEnded = {})

        vm.restore()

        assertEquals(1, fake.restoreCalls) // exactly one attempt - no automatic retry
        assertEquals(2, fake.detailCalls) // initial load + the post-failure refresh
        assertFalse(vm.state.value.restoreCompleted)
        assertTrue(vm.state.value.restoreError is ApiResult.Failure)
        assertEquals(2L, vm.state.value.detail?.workflowVersion)
    }

    @Test
    fun `SessionEnded from restore invokes the callback rather than surfacing as a restore error`() = runTest {
        var sessionEndedCalls = 0
        val fake = FakeRepository(
            detailResponses = mutableListOf(ApiResult.Success(detail("NEW", 1))),
            restoreResult = ApiResult.SessionEnded,
        )
        val vm = DeletedReportDetailViewModel("r1", fake, onSessionEnded = { sessionEndedCalls++ })

        vm.restore()

        assertEquals(1, sessionEndedCalls)
        assertEquals(null, vm.state.value.restoreError)
    }
}
