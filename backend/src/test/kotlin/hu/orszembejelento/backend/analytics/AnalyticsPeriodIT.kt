package hu.orszembejelento.backend.analytics

import hu.orszembejelento.backend.analytics.domain.AnalyticsPeriod
import hu.orszembejelento.backend.analytics.domain.AnalyticsPeriodCalculator
import hu.orszembejelento.backend.analytics.support.AnalyticsTestSupport
import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Period boundary semantics (Phase 11 brief §3/§29): `reports.submitted_at`, local-calendar
 * boundaries in Europe/Budapest, driven entirely by [mutableClock] rather than by sleeping or
 * by faking timezone math — the real JDK/PostgreSQL IANA tz database resolves the real 2026
 * Hungarian DST transitions, found dynamically here (never a hardcoded guessed date) via
 * [ZoneId.getRules].
 *
 * The access token issued by [bearerFor] is only 15 minutes valid (brief-unrelated, existing
 * Phase 2 policy) — every test here creates its fixture user first, submits its report(s) at
 * whatever earlier instant the test needs, and calls [bearerFor] (logging in) only once,
 * immediately before the request, at the exact instant the request itself is made — mirroring
 * [hu.orszembejelento.backend.reportworkflow.ReportWorkflowVisibilityIT]'s own 168h-boundary
 * test, which uses the same login-last ordering for the same reason.
 */
class AnalyticsPeriodIT : AnalyticsTestSupport() {

    private val zone = ZoneId.of("Europe/Budapest")

    @Test
    fun `a report submitted just before local midnight is excluded from TODAY`() {
        val area = givenRoutedArea()
        val admin = givenSuperAdmin()
        val today = mutableClock.instant().atZone(zone).toLocalDate()

        mutableClock.set(today.atStartOfDay(zone).minusSeconds(1).toInstant()) // 23:59:59 yesterday, local
        givenRoutedReport(area)

        mutableClock.set(today.atStartOfDay(zone).plusHours(10).toInstant()) // back to "today", mid-morning
        val bearer = bearerFor(admin)
        assertEquals(0, json(summary(bearer, period = "TODAY", areaId = area.areaId)).get("totalReports").asInt())
    }

    @Test
    fun `a report submitted exactly at local midnight is included in TODAY`() {
        val area = givenRoutedArea()
        val admin = givenSuperAdmin()
        val today = mutableClock.instant().atZone(zone).toLocalDate()

        mutableClock.set(today.atStartOfDay(zone).toInstant())
        givenRoutedReport(area)

        mutableClock.set(today.atStartOfDay(zone).plusHours(10).toInstant())
        val bearer = bearerFor(admin)
        assertEquals(1, json(summary(bearer, period = "TODAY", areaId = area.areaId)).get("totalReports").asInt())
    }

    @Test
    fun `LAST_7_DAYS spans exactly 7 local dates`() {
        val bearer = bearerFor(givenSuperAdmin())
        val body = json(summary(bearer, period = "LAST_7_DAYS"))
        assertEquals(7, body.get("trend").asList().size)
    }

    @Test
    fun `LAST_30_DAYS spans exactly 30 local dates and LAST_90_DAYS exactly 90`() {
        val bearer = bearerFor(givenSuperAdmin())
        assertEquals(30, json(summary(bearer, period = "LAST_30_DAYS")).get("trend").asList().size)
        assertEquals(90, json(summary(bearer, period = "LAST_90_DAYS")).get("trend").asList().size)
    }

    @Test
    fun `an unknown period code is rejected, not silently defaulted`() {
        val response = summary(bearerFor(givenSuperAdmin()), period = "ALL_TIME")
        assertEquals(400, response.statusCode())
        assertEquals("ANALYTICS_PERIOD_INVALID", errorCode(response))
    }

    @Test
    fun `no period defaults to LAST_30_DAYS`() {
        val bearer = bearerFor(givenSuperAdmin())
        assertEquals("LAST_30_DAYS", json(summary(bearer)).get("period").get("code").asText())
    }

    // --------------------------------------------------------------------------------- DST

    @Test
    fun `a report submitted on the real spring-forward transition day buckets into that local date`() {
        val transition = zone.rules.nextTransition(Instant.parse("2026-01-01T00:00:00Z"))
        assertTrue(transition.isGap, "expected the found transition to be a spring-forward gap")
        val transitionDate = transition.instant.atZone(zone).toLocalDate()

        val area = givenRoutedArea()
        val admin = givenSuperAdmin()

        // Two hours after the gap closes - unambiguously inside the transition day, real tz rules only.
        mutableClock.set(transition.instant.plusSeconds(7200))
        givenRoutedReport(area)

        mutableClock.set(transitionDate.plusDays(1).atStartOfDay(zone).plusHours(10).toInstant())
        val bearer = bearerFor(admin)
        val trend = json(summary(bearer, period = "LAST_7_DAYS", areaId = area.areaId)).get("trend").asList()
        val bucket = trend.firstOrNull { it.get("localDate").asText() == transitionDate.toString() }
        assertEquals(1, bucket?.get("count")?.asInt() ?: 0)
    }

    @Test
    fun `a report submitted on the real fall-back transition day buckets into that local date`() {
        val spring = zone.rules.nextTransition(Instant.parse("2026-01-01T00:00:00Z"))
        val fallBack = zone.rules.nextTransition(spring.instant)
        assertTrue(fallBack.isOverlap, "expected the found transition to be a fall-back overlap")
        val transitionDate = fallBack.instant.atZone(zone).toLocalDate()

        val area = givenRoutedArea()
        val admin = givenSuperAdmin()

        mutableClock.set(fallBack.instant.plusSeconds(3600))
        givenRoutedReport(area)

        mutableClock.set(transitionDate.plusDays(1).atStartOfDay(zone).plusHours(10).toInstant())
        val bearer = bearerFor(admin)
        val trend = json(summary(bearer, period = "LAST_7_DAYS", areaId = area.areaId)).get("trend").asList()
        val bucket = trend.firstOrNull { it.get("localDate").asText() == transitionDate.toString() }
        assertEquals(1, bucket?.get("count")?.asInt() ?: 0)
    }

    @Test
    fun `the pure calculator agrees with the real HTTP response on a fixed instant`() {
        val now = Instant.parse("2026-06-15T10:00:00Z")
        val range = AnalyticsPeriodCalculator.resolve(AnalyticsPeriod.LAST_7_DAYS, now)
        assertEquals(7, range.localDates.size)
        assertEquals(range.localDates.last(), now.atZone(zone).toLocalDate())
    }
}
