package hu.orszembejelento.service.ui

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
import hu.orszembejelento.service.moderation.ui.DeletedReportDetailScreen
import hu.orszembejelento.service.moderation.ui.DeletedReportDetailViewModel
import hu.orszembejelento.service.reports.data.ReportCategorySummary
import hu.orszembejelento.service.reports.data.ReportEventTypeSummary
import hu.orszembejelento.service.reports.data.ReportSettlementSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Phase 9 brief §46-50/§67 (CRITICAL): restore is SUPER_ADMIN-only, MODERATOR gets a
 * read-only hint instead, the restore confirmation shows the exact copy for the report's
 * former state, and the moderation reason never appears as a raw backend code.
 */
class DeletedReportDetailComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun detail(statusBeforeDelete: String, version: Long) = DeletedReportDetailResponse(
        publicReportId = "rep-1",
        occurredAt = "2026-01-01T10:00:00Z",
        submittedAt = "2026-01-01T10:05:00Z",
        settlement = ReportSettlementSummary("s1", "Példafalva"),
        category = ReportCategorySummary("VIOLENCE_DANGER", "Erőszak és közvetlen veszély"),
        eventType = ReportEventTypeSummary("THREAT", "Fenyegetés"),
        workflowVersion = version,
        reason = "TROLL_OR_FALSE_REPORT",
        deletedAt = "2026-01-02T00:00:00Z",
        deletedByServiceId = "SZ-200002",
        statusBeforeDelete = statusBeforeDelete,
        restoreTargetStatus = if (statusBeforeDelete == "ARCHIVED") "ARCHIVED" else "NEW",
    )

    private class FakeModerationRepo(
        var detailResponses: ArrayDeque<ApiResult<DeletedReportDetailResponse>>,
        val restoreResult: ApiResult<Unit> = ApiResult.SessionEnded,
    ) : ModerationRepository {
        var restoreCalls = 0

        override suspend fun delete(publicReportId: String, expectedVersion: Long, reason: String) = error("unused")
        override suspend fun deletedList(page: Int, size: Int, filter: DeletedReportFilter): ApiResult<DeletedReportPageResponse> = error("unused")

        override suspend fun deletedDetail(publicReportId: String): ApiResult<DeletedReportDetailResponse> =
            if (detailResponses.size > 1) detailResponses.removeFirst() else detailResponses.first()

        override suspend fun restore(publicReportId: String, expectedVersion: Long): ApiResult<Unit> {
            restoreCalls++
            return restoreResult
        }
    }

    @Test
    fun a_moderator_sees_no_restore_action_only_the_read_only_hint() {
        val fake = FakeModerationRepo(ArrayDeque(listOf(ApiResult.Success(detail("NEW", 1)))))
        val vm = DeletedReportDetailViewModel("rep-1", fake, onSessionEnded = {})

        compose.setContent { DeletedReportDetailScreen(role = "MODERATOR", viewModel = vm, onBack = {}) }
        compose.waitForIdle()

        compose.onNodeWithText("A törölt bejelentést csak főadminisztrátor állíthatja vissza.").assertExists()
        assertTrue(compose.onAllNodesWithText("Bejelentés visszaállítása").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun a_super_admin_restoring_a_formerly_in_progress_report_sees_the_exact_matching_copy() {
        val fake = FakeModerationRepo(
            detailResponses = ArrayDeque(listOf(ApiResult.Success(detail("IN_PROGRESS", 1)))),
            restoreResult = ApiResult.Success(Unit),
        )
        val vm = DeletedReportDetailViewModel("rep-1", fake, onSessionEnded = {})
        var restoredCalls = 0

        compose.setContent { DeletedReportDetailScreen(role = "SUPER_ADMIN", viewModel = vm, onBack = {}, onRestored = { restoredCalls++ }) }
        compose.waitForIdle()

        compose.onNodeWithText("Bejelentés visszaállítása").performClick()
        compose.waitForIdle()

        // The exact IN_PROGRESS-specific copy (brief §48-49), never the NEW/ARCHIVED variant.
        compose.onNodeWithText("A korábbi ügyintézői hozzárendelés nem áll vissza. A bejelentés az Új bejelentések közé kerül.").assertExists()

        compose.onAllNodesWithText("Bejelentés visszaállítása").onLast().performClick() // the dialog's own confirm button
        compose.waitForIdle()

        assertEquals(1, fake.restoreCalls)
        assertEquals(1, restoredCalls)
    }

    @Test
    fun the_moderation_section_shows_the_localized_reason_label_never_the_raw_backend_code() {
        val fake = FakeModerationRepo(ArrayDeque(listOf(ApiResult.Success(detail("NEW", 1)))))
        val vm = DeletedReportDetailViewModel("rep-1", fake, onSessionEnded = {})

        compose.setContent { DeletedReportDetailScreen(role = "SUPER_ADMIN", viewModel = vm, onBack = {}) }
        compose.waitForIdle()

        compose.onNodeWithText("Troll vagy hamis bejelentés").assertExists()
        assertTrue(compose.onAllNodesWithText("TROLL_OR_FALSE_REPORT", substring = true).fetchSemanticsNodes().isEmpty())
    }
}
