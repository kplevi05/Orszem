package hu.orszembejelento.backend.analytics.domain

import java.time.Instant
import java.time.ZoneId

/**
 * Resolves an [AnalyticsPeriod] to a concrete, inclusive `[from, to]` `submitted_at` window (brief §2/§3).
 *
 * Pure and deliberately Clock-free: the caller passes `now` (from the injected backend
 * [java.time.Clock], never device-local time — brief §3), so this stays trivially testable
 * against fixed instants, including both 2026 Hungarian DST transitions (brief §29).
 *
 * Day boundaries are local-calendar boundaries in [ZONE] ("Europe/Budapest", DST-aware via
 * the JDK's own IANA tz database — never a hardcoded UTC offset, brief §29), converted back
 * to an [Instant] only at the very end. `to` is always `now` itself, not a rounded boundary:
 * TODAY's "so far" is exactly what brief §3 asks for.
 */
object AnalyticsPeriodCalculator {

    val ZONE: ZoneId = ZoneId.of("Europe/Budapest")

    fun resolve(period: AnalyticsPeriod, now: Instant): AnalyticsPeriodRange {
        val today = now.atZone(ZONE).toLocalDate()
        val startDate = when (period) {
            AnalyticsPeriod.TODAY -> today
            AnalyticsPeriod.LAST_7_DAYS -> today.minusDays(6)
            AnalyticsPeriod.LAST_30_DAYS -> today.minusDays(29)
            AnalyticsPeriod.LAST_90_DAYS -> today.minusDays(89)
        }
        val from = startDate.atStartOfDay(ZONE).toInstant()
        val localDates = generateSequence(startDate) { it.plusDays(1) }
            .takeWhile { !it.isAfter(today) }
            .toList()
        return AnalyticsPeriodRange(code = period, from = from, to = now, zoneId = ZONE.id, localDates = localDates)
    }
}
