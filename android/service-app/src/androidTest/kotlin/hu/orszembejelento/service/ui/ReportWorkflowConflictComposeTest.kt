package hu.orszembejelento.service.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.reports.data.ReportCategorySummary
import hu.orszembejelento.service.reports.data.ReportDetailResponse
import hu.orszembejelento.service.reports.data.ReportEventTypeSummary
import hu.orszembejelento.service.reports.data.ReportFilter
import hu.orszembejelento.service.reports.data.ReportQueuePageResponse
import hu.orszembejelento.service.reports.data.ReportSettlementSummary
import hu.orszembejelento.service.reports.data.ReportWorkflowRepository
import hu.orszembejelento.service.reports.ui.ReportDetailScreen
import hu.orszembejelento.service.reports.ui.ReportDetailViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * brief §23-25 / §96-97 (CRITICAL): a workflow mutation that the server rejects with a stale
 * conflict is never auto-repeated. The screen re-fetches the current state once and shows a
 * human message - no raw code, no retry POST.
 */
class ReportWorkflowConflictComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun ownDetail(status: String, version: Long, assignee: String?) = ReportDetailResponse(
        publicReportId = "rep-1",
        occurredAt = "2026-01-01T10:00:00Z",
        submittedAt = "2026-01-01T10:05:00Z",
        settlement = ReportSettlementSummary("s1", "Példafalva"),
        category = ReportCategorySummary("VIOLENCE_DANGER", "Erőszak és közvetlen veszély"),
        eventType = ReportEventTypeSummary("THREAT", "Fenyegetés"),
        status = status,
        workflowVersion = version,
        routingClassification = "ROUTED",
        assignee = assignee?.let { hu.orszembejelento.service.reports.data.ReportAssigneeSummary(it) },
    )

    private class FakeRepo(
        var detailResponses: ArrayDeque<ApiResult<ReportDetailResponse>>,
        val closeResult: ApiResult<ReportDetailResponse>,
    ) : ReportWorkflowRepository {
        var detailCalls = 0
        var closeCalls = 0
        private val empty = ReportQueuePageResponse(emptyList(), 0, 50, 0, 0)

        override suspend fun newQueue(page: Int, size: Int, filter: ReportFilter) = ApiResult.Success(empty)
        override suspend fun inProgressQueue(page: Int, size: Int, filter: ReportFilter) = ApiResult.Success(empty)
        override suspend fun archiveQueue(page: Int, size: Int, filter: ReportFilter) = ApiResult.Success(empty)

        override suspend fun detail(publicReportId: String): ApiResult<ReportDetailResponse> {
            detailCalls++
            return if (detailResponses.size > 1) detailResponses.removeFirst() else detailResponses.first()
        }

        override suspend fun claim(publicReportId: String, expectedVersion: Long) = closeResult
        override suspend fun returnToNew(publicReportId: String, expectedVersion: Long) = closeResult

        override suspend fun close(publicReportId: String, expectedVersion: Long): ApiResult<ReportDetailResponse> {
            closeCalls++
            return closeResult
        }

        override suspend fun reassign(publicReportId: String, expectedVersion: Long, targetServiceId: String) = closeResult
    }

    @Test
    fun a_stale_close_shows_the_human_conflict_message_refetches_once_and_never_retries() {
        val fake = FakeRepo(
            detailResponses = ArrayDeque(
                listOf(
                    ApiResult.Success(ownDetail("IN_PROGRESS", version = 3, assignee = "SZ-100001")),
                    // the silent re-fetch after the rejection - server has moved on
                    ApiResult.Success(ownDetail("ARCHIVED", version = 4, assignee = null)),
                ),
            ),
            closeResult = ApiResult.Failure(code = "REPORT_STATE_CHANGED", httpStatus = 409),
        )
        val vm = ReportDetailViewModel("rep-1", fake, onSessionEnded = {})

        compose.setContent {
            ReportDetailScreen(
                currentServiceId = "SZ-100001",
                role = "SERVICE_USER",
                viewModel = vm,
                userManagementRepository = null,
                onBack = {},
            )
        }
        compose.waitForIdle()

        // Own IN_PROGRESS report -> "Lezárás" is offered; confirm the dialog.
        compose.onAllNodesWithText("Lezárás").onLast().performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Lezárás").onLast().performClick() // dialog confirm
        compose.waitForIdle()

        assertEquals("the mutation must be sent exactly once, never retried", 1, fake.closeCalls)
        assertTrue("the current state must be re-fetched after the rejection", fake.detailCalls >= 2)
        compose.onNodeWithText("A bejelentés időközben megváltozott. Frissítettük az aktuális állapotot.").assertExists()
        // the raw stable code is never shown
        assertTrue(compose.onAllNodesWithText("REPORT_STATE_CHANGED", substring = true).fetchSemanticsNodes().isEmpty())
    }
}
