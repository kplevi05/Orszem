package hu.orszembejelento.backend.audit.domain

import java.time.Instant
import java.time.ZoneId

/**
 * Resolves an [AuditPeriod] to a concrete `created_at` window (brief §14), mirroring Phase
 * 11's `AnalyticsPeriodCalculator` exactly: pure, Clock-free (the caller passes `now` from the
 * injected backend [java.time.Clock], never device-local time), local-calendar boundaries in
 * [ZONE] ("Europe/Budapest", DST-aware via the JDK's own IANA tz database), converted to an
 * [Instant] only at the end. `to` is always `now` itself so a row written at the exact query
 * instant is still counted - the same inclusive-upper-bound reasoning Phase 11 needed.
 *
 * [AuditPeriod.ALL] resolves to a null [AuditPeriodRange.from] - genuinely no lower bound,
 * not a very-old sentinel date.
 */
object AuditPeriodCalculator {

    val ZONE: ZoneId = ZoneId.of("Europe/Budapest")

    fun resolve(period: AuditPeriod, now: Instant): AuditPeriodRange {
        val today = now.atZone(ZONE).toLocalDate()
        val from = when (period) {
            AuditPeriod.TODAY -> today.atStartOfDay(ZONE).toInstant()
            AuditPeriod.LAST_7_DAYS -> today.minusDays(6).atStartOfDay(ZONE).toInstant()
            AuditPeriod.LAST_30_DAYS -> today.minusDays(29).atStartOfDay(ZONE).toInstant()
            AuditPeriod.LAST_90_DAYS -> today.minusDays(89).atStartOfDay(ZONE).toInstant()
            AuditPeriod.ALL -> null
        }
        return AuditPeriodRange(code = period, from = from, to = now, zoneId = ZONE.id)
    }
}
