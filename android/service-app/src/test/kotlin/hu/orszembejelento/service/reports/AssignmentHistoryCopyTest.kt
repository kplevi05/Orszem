package hu.orszembejelento.service.reports

import hu.orszembejelento.service.reports.data.AssignmentHistoryItemResponse
import hu.orszembejelento.service.reports.domain.buildAssignmentHistoryEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** brief §29 - the human timeline, built purely from `report_assignments` fields. */
class AssignmentHistoryCopyTest {

    @Test
    fun `a self-claim produces one active entry, no separate end entry while open`() {
        val history = listOf(
            AssignmentHistoryItemResponse(
                assigneeServiceId = "SZ-1042",
                assignedByServiceId = "SZ-1042",
                assignedAt = "2026-01-01T10:11:00Z",
                endedAt = null,
                endedByServiceId = null,
                endReason = null,
            ),
        )
        val entries = buildAssignmentHistoryEntries(history)
        assertEquals(1, entries.size)
        assertTrue(entries.single().label.contains("SZ-1042"))
        assertTrue(entries.single().label.contains("átvette"))
        assertTrue(entries.single().active)
    }

    @Test
    fun `a claim then reassign then close produces exactly the mockup's three lines, no duplicated reassignment text`() {
        val history = listOf(
            AssignmentHistoryItemResponse(
                assigneeServiceId = "SZ-1042", assignedByServiceId = "SZ-1042",
                assignedAt = "2026-01-01T10:11:00Z",
                endedAt = "2026-01-01T11:03:00Z", endedByServiceId = "SZ-2041", endReason = "REASSIGNED",
            ),
            AssignmentHistoryItemResponse(
                assigneeServiceId = "SZ-1059", assignedByServiceId = "SZ-2041",
                assignedAt = "2026-01-01T11:03:00Z",
                endedAt = "2026-01-01T12:00:00Z", endedByServiceId = "SZ-1059", endReason = "ARCHIVED",
            ),
        )
        val entries = buildAssignmentHistoryEntries(history)

        // Exactly 3 lines: claim, reassign, archive - the REASSIGNED end of episode 1 is
        // deliberately not a 4th line, since it would say the same thing as episode 2's start.
        assertEquals(3, entries.size)
        assertTrue(entries[0].label.contains("SZ-1042 átvette"))
        assertTrue(entries[1].label.contains("SZ-2041"))
        assertTrue(entries[1].label.contains("SZ-1059"))
        assertTrue(entries[1].label.contains("átrendelte"))
        assertTrue(entries[2].label.contains("SZ-1059"))
        assertTrue(entries[2].label.contains("lezárta"))
        assertFalse(entries.any { it.active }) // fully closed report - nothing currently active
    }

    @Test
    fun `an unexpected or missing end reason degrades to a neutral phrase, never a raw code`() {
        val history = listOf(
            AssignmentHistoryItemResponse(
                assigneeServiceId = "SZ-1042", assignedByServiceId = "SZ-1042",
                assignedAt = "2026-01-01T10:00:00Z",
                endedAt = "2026-01-01T10:30:00Z", endedByServiceId = "SZ-1042", endReason = "SOMETHING_NEW",
            ),
        )
        val entries = buildAssignmentHistoryEntries(history)
        assertEquals(2, entries.size)
        assertFalse("a raw end-reason code must never reach the label", entries[1].label.contains("SOMETHING_NEW"))
        assertTrue(entries[1].label.contains("SZ-1042"))
        assertTrue(entries[1].label.contains("lezárta az ügyintézést"))
    }

    @Test
    fun `a returned episode produces its own end entry, distinct from a reassignment`() {
        val history = listOf(
            AssignmentHistoryItemResponse(
                assigneeServiceId = "SZ-1042", assignedByServiceId = "SZ-1042",
                assignedAt = "2026-01-01T10:00:00Z",
                endedAt = "2026-01-01T10:30:00Z", endedByServiceId = "SZ-1042", endReason = "RETURNED",
            ),
        )
        val entries = buildAssignmentHistoryEntries(history)
        assertEquals(2, entries.size)
        assertTrue(entries[1].label.contains("visszaadta"))
    }
}
