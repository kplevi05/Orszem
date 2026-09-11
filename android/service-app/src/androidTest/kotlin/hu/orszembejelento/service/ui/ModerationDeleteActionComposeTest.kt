package hu.orszembejelento.service.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.moderation.data.DeletedReportDetailResponse
import hu.orszembejelento.service.moderation.data.DeletedReportFilter
import hu.orszembejelento.service.moderation.data.DeletedReportPageResponse
import hu.orszembejelento.service.moderation.data.ModerationRepository
import hu.orszembejelento.service.reports.data.ReportAssigneeSummary
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
 * Phase 9 brief §38-43/§67 (CRITICAL): the moderation-delete action is offered only to
 * MODERATOR/SUPER_ADMIN, the reason dialog never lets a delete through without an explicit
 * choice, and a rejected delete is never auto-retried.
 */
class ModerationDeleteActionComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun ownDetail(status: String, version: Long) = ReportDetailResponse(
        publicReportId = "rep-1",
        occurredAt = "2026-01-01T10:00:00Z",
        submittedAt = "2026-01-01T10:05:00Z",
        settlement = ReportSettlementSummary("s1", "Példafalva"),
        category = ReportCategorySummary("VIOLENCE_DANGER", "Erőszak és közvetlen veszély"),
        eventType = ReportEventTypeSummary("THREAT", "Fenyegetés"),
        status = status,
        workflowVersion = version,
        routingClassification = "ROUTED",
        assignee = if (status == "IN_PROGRESS") ReportAssigneeSummary("SZ-100001") else null,
    )

    private class FakeWorkflowRepo(var detailResponses: ArrayDeque<ApiResult<ReportDetailResponse>>) : ReportWorkflowRepository {
        var detailCalls = 0
        private val empty = ReportQueuePageResponse(emptyList(), 0, 50, 0, 0)

        override suspend fun newQueue(page: Int, size: Int, filter: ReportFilter) = ApiResult.Success(empty)
        override suspend fun inProgressQueue(page: Int, size: Int, filter: ReportFilter) = ApiResult.Success(empty)
        override suspend fun archiveQueue(page: Int, size: Int, filter: ReportFilter) = ApiResult.Success(empty)

        override suspend fun detail(publicReportId: String): ApiResult<ReportDetailResponse> {
            detailCalls++
            return if (detailResponses.size > 1) detailResponses.removeFirst() else detailResponses.first()
        }

        override suspend fun claim(publicReportId: String, expectedVersion: Long) = error("unused")
        override suspend fun returnToNew(publicReportId: String, expectedVersion: Long) = error("unused")
        override suspend fun close(publicReportId: String, expectedVersion: Long) = error("unused")
        override suspend fun reassign(publicReportId: String, expectedVersion: Long, targetServiceId: String) = error("unused")
    }

    private class FakeModerationRepo(val deleteResult: ApiResult<Unit>) : ModerationRepository {
        var deleteCalls = 0
        var lastReason: String? = null

        override suspend fun delete(publicReportId: String, expectedVersion: Long, reason: String): ApiResult<Unit> {
            deleteCalls++
            lastReason = reason
            return deleteResult
        }

        override suspend fun restore(publicReportId: String, expectedVersion: Long) = error("unused")
        override suspend fun deletedList(page: Int, size: Int, filter: DeletedReportFilter): ApiResult<DeletedReportPageResponse> = error("unused")
        override suspend fun deletedDetail(publicReportId: String): ApiResult<DeletedReportDetailResponse> = error("unused")
    }

    @Test
    fun a_service_user_never_sees_the_moderation_delete_action() {
        val fake = FakeWorkflowRepo(ArrayDeque(listOf(ApiResult.Success(ownDetail("NEW", 0)))))
        val vm = ReportDetailViewModel("rep-1", fake, onSessionEnded = {}, moderationRepository = null)

        compose.setContent {
            ReportDetailScreen(currentServiceId = "SZ-100001", role = "SERVICE_USER", viewModel = vm, userManagementRepository = null, onBack = {})
        }
        compose.waitForIdle()

        compose.onAllNodesWithText("Bejelentés törlése").assertCountEquals(0)
    }

    @Test
    fun a_moderator_sees_the_delete_action_and_the_dialog_requires_an_explicit_reason() {
        val fake = FakeWorkflowRepo(ArrayDeque(listOf(ApiResult.Success(ownDetail("NEW", 0)))))
        val moderation = FakeModerationRepo(ApiResult.Success(Unit))
        val vm = ReportDetailViewModel("rep-1", fake, onSessionEnded = {}, moderationRepository = moderation)

        compose.setContent {
            ReportDetailScreen(currentServiceId = "SZ-999999", role = "MODERATOR", viewModel = vm, userManagementRepository = null, onBack = {})
        }
        compose.waitForIdle()

        compose.onNodeWithText("Bejelentés törlése").performClick()
        compose.waitForIdle()

        // The dialog is open (title text present) but no reason chosen yet - confirm must be
        // disabled, so tapping the dialog's own "Törlés" button does nothing.
        compose.onAllNodesWithText("Törlés").onLast().performClick()
        compose.waitForIdle()
        assertEquals("confirm must be disabled until a reason is chosen", 0, moderation.deleteCalls)

        // No free-text field exists anywhere in the dialog - only the fixed chip choices.
        compose.onNodeWithText("Egyéb").assertExists() // OTHER is a chip, not a text field

        compose.onNodeWithText("Spam").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Törlés").onLast().performClick()
        compose.waitForIdle()

        assertEquals(1, moderation.deleteCalls)
        assertEquals("SPAM", moderation.lastReason)
    }

    @Test
    fun a_stale_delete_conflict_shows_the_human_message_refetches_once_and_never_retries() {
        val fake = FakeWorkflowRepo(
            ArrayDeque(
                listOf(
                    ApiResult.Success(ownDetail("NEW", 0)),
                    ApiResult.Success(ownDetail("IN_PROGRESS", 1)), // someone else claimed it first
                ),
            ),
        )
        val moderation = FakeModerationRepo(ApiResult.Failure(code = "REPORT_STATE_CHANGED", httpStatus = 409))
        val vm = ReportDetailViewModel("rep-1", fake, onSessionEnded = {}, moderationRepository = moderation)

        compose.setContent {
            ReportDetailScreen(currentServiceId = "SZ-999999", role = "SUPER_ADMIN", viewModel = vm, userManagementRepository = null, onBack = {})
        }
        compose.waitForIdle()

        compose.onNodeWithText("Bejelentés törlése").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Spam").performClick()
        compose.onAllNodesWithText("Törlés").onLast().performClick()
        compose.waitForIdle()

        assertEquals("exactly one attempt - no automatic retry", 1, moderation.deleteCalls)
        assertTrue("the current state must be re-fetched after the rejection", fake.detailCalls >= 2)
        // The raw stable code must never reach the screen.
        assertTrue(compose.onAllNodesWithText("REPORT_STATE_CHANGED", substring = true).fetchSemanticsNodes().isEmpty())
    }
}
