package hu.orszembejelento.service.reports

import hu.orszembejelento.service.R
import hu.orszembejelento.service.reports.data.ReportAreaSummary
import hu.orszembejelento.service.reports.data.ReportCategorySummary
import hu.orszembejelento.service.reports.data.ReportEventTypeSummary
import hu.orszembejelento.service.reports.data.ReportListItemResponse
import hu.orszembejelento.service.reports.data.ReportSettlementSummary
import hu.orszembejelento.service.reports.domain.isUnclassified
import hu.orszembejelento.service.reports.domain.shortReportId
import hu.orszembejelento.service.reports.domain.statusLabelRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportModelsTest {

    @Test
    fun `status labels never leak the raw backend enum name`() {
        assertEquals(R.string.status_new, statusLabelRes("NEW"))
        assertEquals(R.string.status_in_progress, statusLabelRes("IN_PROGRESS"))
        assertEquals(R.string.status_archived, statusLabelRes("ARCHIVED"))
    }

    @Test
    fun `isUnclassified reflects routingClassification only, never the workflow status`() {
        val unclassified = fixture(routingClassification = "UNCLASSIFIED")
        val routed = fixture(routingClassification = "ROUTED")
        assertTrue(unclassified.isUnclassified())
        assertFalse(routed.isUnclassified())
    }

    @Test
    fun `shortReportId is a presentational prefix, never the full backend id`() {
        val full = "1513f84e-e10a-4ce8-8ed8-48f4438d0b76"
        val short = shortReportId(full)
        assertTrue(short.startsWith("#"))
        assertTrue(short.length < full.length)
        assertNotEquals(full, short)
    }

    @Test
    fun `two reports with the same first 8 characters produce the same short id - it is presentational, not a real identity guarantee`() {
        val a = shortReportId("1513f84e-aaaa-0000-0000-000000000000")
        val b = shortReportId("1513f84e-bbbb-1111-1111-111111111111")
        assertEquals(a, b) // documents the known non-uniqueness explicitly, per brief §19
    }

    private fun assertNotEquals(a: Any, b: Any) = org.junit.Assert.assertNotEquals(a, b)

    private fun fixture(routingClassification: String) = ReportListItemResponse(
        publicReportId = "11111111-1111-1111-1111-111111111111",
        occurredAt = "2026-01-01T00:00:00Z",
        submittedAt = "2026-01-01T00:00:00Z",
        settlement = ReportSettlementSummary("s1", "Alfaváros"),
        category = ReportCategorySummary("CAT", "Kategória"),
        eventType = ReportEventTypeSummary("EVT", "Esemény"),
        routingClassification = routingClassification,
        serviceArea = if (routingClassification == "ROUTED") ReportAreaSummary("a1", "Terület") else null,
        workflowVersion = 0,
    )
}
