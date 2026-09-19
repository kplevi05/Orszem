package hu.orszembejelento.app.ui.components

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Centralized Hungarian-locale date/time formatting for every user-facing timestamp this
 * app renders (Phase 15 §17: "one consistent user-facing date/time strategy per client").
 *
 * The whole Public Android UI is Hungarian regardless of the device's own locale, but
 * `DateTimeFormatter.ofLocalizedDateTime`/`ofLocalizedDate`/`ofLocalizedTime` silently fall
 * back to the JVM/device default locale unless one is fixed explicitly. That is the actual,
 * confirmed root cause of the Phase 13/14-documented bug (`9/16/26, 11:40 AM` inside an
 * otherwise Hungarian screen: an en-US device locale leaking US month/day ordering and
 * AM/PM into text this app is responsible for, not the device). Every formatter below
 * fixes `hu-HU` explicitly instead, so the rendered text is identical no matter what locale
 * the device itself is set to.
 *
 * Pattern and zone match the equivalent formatters already established on Service Android
 * (`AuditListScreen`/`AuditDetailScreen`: `"yyyy. MM. dd. HH:mm"`) for one consistent shape
 * across the product, not invented fresh here. Only rendering changes - the underlying
 * `Instant`/zone semantics these format are untouched, and the device's own zone
 * (`ZoneId.systemDefault()`) is kept exactly as before: a citizen's report timestamp is
 * shown in *their* local time, which was never the bug.
 */
object HungarianDateTime {
    private val LOCALE: Locale = Locale.forLanguageTag("hu-HU")
    private val ZONE: ZoneId = ZoneId.systemDefault()

    /** e.g. "2026. 09. 16. 11:40" - a report's occurred-at/submitted-at/last-checked timestamp. */
    val DATE_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy. MM. dd. HH:mm", LOCALE).withZone(ZONE)

    /** e.g. "2026. szept. 16." - the date half of the new-report date/time picker. */
    val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy. MMM d.", LOCALE)

    /** e.g. "11:40" - the time half of the new-report date/time picker. Always 24-hour. */
    val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", LOCALE)

    /** Formats an [Instant] using [DATE_TIME] - the common case throughout this app. */
    fun format(instant: Instant): String = DATE_TIME.format(instant)
}
