package hu.orszembejelento.app.ui.components

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Phase 15 §F: the fix for the reported US-format date/time bug
 * (`9/16/26, 11:40 AM` instead of Hungarian formatting). Fixes an explicit `hu-HU` locale so
 * the rendered text cannot depend on the JVM/device default locale, which is what caused the
 * original bug via `DateTimeFormatter.ofLocalizedDateTime`.
 */
class HungarianDateTimeTest {

    @Test
    fun `format renders Hungarian year-month-day and 24-hour time, never US month-slash-day or AM-PM`() {
        val instant = Instant.parse("2026-09-16T11:40:00Z").atZone(ZoneOffset.UTC).toInstant()
        val rendered = HungarianDateTime.DATE_TIME.withZone(ZoneOffset.UTC).format(instant)

        assertEquals("2026. 09. 16. 11:40", rendered)
        assertFalse("must never contain a US-style AM/PM marker", rendered.contains("AM") || rendered.contains("PM"))
        assertFalse("must never contain a slash-separated US date", rendered.contains("/"))
    }

    @Test
    fun `an afternoon instant stays in 24-hour form, not 12-hour with AM-PM`() {
        val instant = Instant.parse("2026-09-16T23:40:00Z").atZone(ZoneOffset.UTC).toInstant()
        val rendered = HungarianDateTime.DATE_TIME.withZone(ZoneOffset.UTC).format(instant)

        assertEquals("2026. 09. 16. 23:40", rendered)
    }

    @Test
    fun `DATE and TIME split the same instant consistently with DATE_TIME`() {
        val zoned = Instant.parse("2026-09-16T11:40:00Z").atZone(ZoneOffset.UTC)

        assertEquals("2026. szept. 16.", zoned.toLocalDate().format(HungarianDateTime.DATE))
        assertEquals("11:40", zoned.toLocalTime().format(HungarianDateTime.TIME))
    }
}
