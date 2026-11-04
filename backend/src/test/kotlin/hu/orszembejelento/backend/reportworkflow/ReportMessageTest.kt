package hu.orszembejelento.backend.reportworkflow

import hu.orszembejelento.backend.reportworkflow.domain.ReportMessage
import hu.orszembejelento.backend.reportworkflow.domain.ReportMessageInvalidException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ReportMessageTest {

    @Test
    fun `normalizeText trims whitespace and preserves interior newlines`() {
        val normalized = ReportMessage.normalizeText("  Tisztelt Bejelentő!\r\nKöszönjük a jelzést.  ")
        assertEquals("Tisztelt Bejelentő!\nKöszönjük a jelzést.", normalized)
    }

    @Test
    fun `normalizeText accepts exactly 1000 code points`() {
        val text = "Ő".repeat(1000)
        assertEquals(text, ReportMessage.normalizeText(text))
    }

    @Test
    fun `normalizeText rejects blank message`() {
        assertThrows<ReportMessageInvalidException> {
            ReportMessage.normalizeText("   \n\t  ")
        }
    }

    @Test
    fun `normalizeText rejects over 1000 code points`() {
        val text = "a".repeat(1001)
        assertThrows<ReportMessageInvalidException> {
            ReportMessage.normalizeText(text)
        }
    }

    @Test
    fun `normalizeText rejects NUL character`() {
        assertThrows<ReportMessageInvalidException> {
            ReportMessage.normalizeText("Üzenet\u0000szöveg")
        }
    }
}
