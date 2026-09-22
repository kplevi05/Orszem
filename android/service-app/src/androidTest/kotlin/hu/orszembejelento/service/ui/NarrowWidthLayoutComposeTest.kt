package hu.orszembejelento.service.ui

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.reports.data.ReportAreaSummary
import hu.orszembejelento.service.reports.data.ReportAssigneeSummary
import hu.orszembejelento.service.reports.data.ReportCategorySummary
import hu.orszembejelento.service.reports.data.ReportDetailResponse
import hu.orszembejelento.service.reports.data.ReportEventTypeSummary
import hu.orszembejelento.service.reports.data.ReportFilter
import hu.orszembejelento.service.reports.data.ReportListItemResponse
import hu.orszembejelento.service.reports.data.ReportQueuePageResponse
import hu.orszembejelento.service.reports.data.ReportSettlementSummary
import hu.orszembejelento.service.reports.data.ReportWorkflowRepository
import hu.orszembejelento.service.reports.ui.ReportDetailScreen
import hu.orszembejelento.service.reports.ui.ReportDetailViewModel
import hu.orszembejelento.service.reports.ui.ReportListItemCard
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Field-test fix (§1): a long, real [ReportAreaSummary]/ServiceArea name must never be
 * truncated or abbreviated - the layout wraps instead, at a narrow phone width and at a
 * large (1.3x) font scale. [ReportListItemCard] and [ReportDetailScreen]'s field rows are
 * the two places the field test found this breaking.
 */
class NarrowWidthLayoutComposeTest {

    @get:Rule
    val compose = createComposeRule()

    /** A realistic but deliberately long, real-shaped area name - never abbreviated by this test's assertions either. */
    private val longAreaName = "Nyugat-Dunántúli Vasúti Szolgálati Terület – Győr–Sopron–Szombathely térség"

    @Composable
    private fun narrowAndLargeFontScale(content: @Composable () -> Unit) {
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density = base.density, fontScale = 1.3f)) {
            androidx.compose.foundation.layout.Box(modifier = Modifier.width(320.dp)) {
                content()
            }
        }
    }

    // ---------------------------------------------------------------------------- list card

    @Test
    fun the_report_card_shows_the_full_area_name_at_narrow_width_and_1_3x_font_scale_never_abbreviated() {
        val shortItem = reportListItem(areaName = "Terület")
        val longItem = reportListItem(areaName = longAreaName)

        var longNameHeightDp = 0f
        var shortNameHeightDp = 0f

        compose.setContent {
            narrowAndLargeFontScale {
                androidx.compose.foundation.layout.Column {
                    ReportListItemCard(item = shortItem, onClick = {})
                    ReportListItemCard(item = longItem, onClick = {})
                }
            }
        }
        compose.waitForIdle()

        // The exact, full, canonical name - never truncated, never abbreviated.
        compose.onNodeWithText(longAreaName).assertExists()

        val shortBounds = compose.onAllNodesWithText("Terület").fetchSemanticsNodes()
        // A long name that wraps onto several lines makes its own card visibly taller than
        // the short-name card - the old bug instead kept both the same height and let the
        // long name overflow/clip/shift the row it shared with the other meta chips.
        longNameHeightDp = compose.onNodeWithText(longAreaName).getUnclippedBoundsInRoot().height.value
        shortNameHeightDp = if (shortBounds.isNotEmpty()) compose.onAllNodesWithText("Terület")[0].getUnclippedBoundsInRoot().height.value else 0f
        assertTrue(
            "the long area name must wrap onto more lines (taller) than a short one, never stay pinned to one clipped/shifted line",
            longNameHeightDp > shortNameHeightDp,
        )
    }

    // ---------------------------------------------------------------------------- detail screen

    private class FixedDetailRepo(private val detail: ReportDetailResponse) : ReportWorkflowRepository {
        private val empty = ReportQueuePageResponse(emptyList(), 0, 50, 0, 0)
        override suspend fun newQueue(page: Int, size: Int, filter: ReportFilter) = ApiResult.Success(empty)
        override suspend fun inProgressQueue(page: Int, size: Int, filter: ReportFilter) = ApiResult.Success(empty)
        override suspend fun archiveQueue(page: Int, size: Int, filter: ReportFilter) = ApiResult.Success(empty)
        override suspend fun detail(publicReportId: String) = ApiResult.Success(detail)
        override suspend fun claim(publicReportId: String, expectedVersion: Long) = ApiResult.Success(detail)
        override suspend fun returnToNew(publicReportId: String, expectedVersion: Long) = ApiResult.Success(detail)
        override suspend fun close(publicReportId: String, expectedVersion: Long) = ApiResult.Success(detail)
        override suspend fun reassign(publicReportId: String, expectedVersion: Long, targetServiceId: String) = ApiResult.Success(detail)
    }

    @Test
    fun the_detail_screen_shows_the_full_area_name_stacked_below_its_label_at_narrow_width_never_overlapping() {
        val detail = ReportDetailResponse(
            publicReportId = "rep-1",
            occurredAt = "2026-01-01T10:00:00Z",
            submittedAt = "2026-01-01T10:05:00Z",
            settlement = ReportSettlementSummary("s1", "Példafalva"),
            category = ReportCategorySummary("VIOLENCE_DANGER", "Erőszak és közvetlen veszély"),
            eventType = ReportEventTypeSummary("THREAT", "Fenyegetés"),
            status = "NEW",
            workflowVersion = 1,
            routingClassification = "ROUTED",
            serviceArea = ReportAreaSummary("a1", longAreaName),
            assignee = ReportAssigneeSummary("SZ-100001"),
        )
        val vm = ReportDetailViewModel("rep-1", FixedDetailRepo(detail), onSessionEnded = {})

        compose.setContent {
            narrowAndLargeFontScale {
                ReportDetailScreen(
                    currentServiceId = "SZ-100001",
                    role = "SERVICE_USER",
                    viewModel = vm,
                    userManagementRepository = null,
                    onBack = {},
                )
            }
        }
        compose.waitForIdle()

        // Full canonical name present - never truncated or abbreviated.
        compose.onNodeWithText(longAreaName).assertExists()
        val labelBounds = compose.onNodeWithText("Szolgálati terület").getUnclippedBoundsInRoot()
        val valueBounds = compose.onNodeWithText(longAreaName).getUnclippedBoundsInRoot()
        // Stacked (label strictly above the value), never side-by-side - the old SpaceBetween
        // Row put both on the same line with no width guard, which is what let a long value
        // overlap or push past the label at a narrow width.
        assertTrue(
            "the area name must be stacked below its label, never sharing a row with it",
            valueBounds.top >= labelBounds.bottom,
        )
    }

    private fun reportListItem(areaName: String) = ReportListItemResponse(
        publicReportId = "11111111-1111-1111-1111-111111111111",
        occurredAt = "2026-01-01T10:00:00Z",
        submittedAt = "2026-01-01T10:05:00Z",
        settlement = ReportSettlementSummary("s1", "Példafalva"),
        category = ReportCategorySummary("VIOLENCE_DANGER", "Erőszak és közvetlen veszély"),
        eventType = ReportEventTypeSummary("THREAT", "Fenyegetés"),
        routingClassification = "ROUTED",
        serviceArea = ReportAreaSummary("a1", areaName),
        workflowVersion = 1,
    )
}
