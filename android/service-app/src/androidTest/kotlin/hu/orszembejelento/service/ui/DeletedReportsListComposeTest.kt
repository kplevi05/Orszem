package hu.orszembejelento.service.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.moderation.data.DeletedReportListItemResponse
import hu.orszembejelento.service.moderation.data.DeletedReportPageResponse
import hu.orszembejelento.service.moderation.ui.DeletedReportsListScreen
import hu.orszembejelento.service.moderation.ui.DeletedReportsListViewModel
import hu.orszembejelento.service.reports.data.ReportCategorySummary
import hu.orszembejelento.service.reports.data.ReportEventTypeSummary
import hu.orszembejelento.service.reports.data.ReportSettlementSummary
import org.junit.Rule
import org.junit.Test

/** Phase 9 brief §44-45/§67 - the deleted list shows natural labels, never a raw reason code or backend status. */
class DeletedReportsListComposeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun a_deleted_report_card_shows_its_localized_reason_and_the_TOROLVE_badge_never_a_raw_code() {
        val item = DeletedReportListItemResponse(
            publicReportId = "11111111-1111-1111-1111-111111111111",
            occurredAt = "2026-01-01T10:00:00Z",
            submittedAt = "2026-01-01T10:05:00Z",
            settlement = ReportSettlementSummary("s1", "Példafalva"),
            category = ReportCategorySummary("VIOLENCE_DANGER", "Erőszak és közvetlen veszély"),
            eventType = ReportEventTypeSummary("THREAT", "Fenyegetés"),
            serviceArea = null,
            reason = "DUPLICATE",
            deletedAt = "2026-01-02T00:00:00Z",
            deletedByServiceId = "SZ-300003",
            statusBeforeDelete = "NEW",
            restoreTargetStatus = "NEW",
            workflowVersion = 1,
        )
        val vm = DeletedReportsListViewModel(
            fetchPage = { page, _, _ ->
                ApiResult.Success(DeletedReportPageResponse(items = listOf(item), page = page, size = 50, totalElements = 1, totalPages = 1))
            },
            onSessionEnded = {},
        )

        compose.setContent { DeletedReportsListScreen(viewModel = vm, onOpenReport = {}, areaChoices = emptyList()) }
        compose.waitForIdle()

        compose.onNodeWithText("Duplikált bejelentés").assertExists() // localized reason, not "DUPLICATE"
        compose.onNodeWithText("TÖRÖLVE").assertExists() // UI-only status label
        compose.onNodeWithText("SZ-300003").assertExists() // deleting service id, human-readable
        compose.onAllNodesWithText("DUPLICATE", substring = true).assertCountEquals(0)
    }
}
