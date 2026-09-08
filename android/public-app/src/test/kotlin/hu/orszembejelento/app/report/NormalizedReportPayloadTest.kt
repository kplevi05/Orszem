package hu.orszembejelento.app.report

import hu.orszembejelento.app.report.domain.ReportDraft
import hu.orszembejelento.app.report.domain.normalize
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NormalizedReportPayloadTest {

    private val occurredAt = Instant.parse("2026-01-01T12:00:00Z")
    private val settlementId = UUID.randomUUID()

    private fun draft(trainIdentifier: String?) = ReportDraft(
        occurredAt = occurredAt,
        trainIdentifierInput = trainIdentifier,
        settlementId = settlementId,
        railwayLineId = null,
        eventTypeCode = "FIGHT",
    )

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("G123", draft("  G123  ").normalize().trainIdentifier)
    }

    @Test
    fun `a blank value normalizes to null`() {
        assertNull(draft("   ").normalize().trainIdentifier)
    }

    @Test
    fun `an empty string normalizes to null`() {
        assertNull(draft("").normalize().trainIdentifier)
    }

    @Test
    fun `null stays null`() {
        assertNull(draft(null).normalize().trainIdentifier)
    }

    @Test
    fun `internal whitespace is preserved`() {
        assertEquals("G 123", draft(" G 123 ").normalize().trainIdentifier)
    }

    @Test
    fun `two logically identical drafts normalize to equal payloads`() {
        val a = draft("  G123  ").normalize()
        val b = draft("G123").normalize()
        assertEquals(a, b)
    }
}
