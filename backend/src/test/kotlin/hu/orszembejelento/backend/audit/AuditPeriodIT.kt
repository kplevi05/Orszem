package hu.orszembejelento.backend.audit

import hu.orszembejelento.backend.audit.domain.AuditPeriod
import hu.orszembejelento.backend.audit.domain.AuditPeriodCalculator
import hu.orszembejelento.backend.audit.support.AuditTestSupport
import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Period boundary semantics (Phase 12 brief §14/§40) - `audit_events.created_at`, local-calendar
 * boundaries in Europe/Budapest, driven entirely by [mutableClock], mirroring
 * `AnalyticsPeriodIT`'s own reasoning exactly, including the real 2026 DST transitions found
 * dynamically via [ZoneId.getRules] and the login-immediately-before-each-request ordering
 * (access tokens are only 15 minutes valid *in clock time*, and every clock jump here moves
 * clock time, not wall time).
 *
 * A real [hu.orszembejelento.backend.identity.domain.UserRole] change (`USER_ROLE_CHANGED`) is
 * the controllable-timing event under test throughout, rather than a login's own
 * `SESSION_CREATED` - a role change is not itself required to authenticate, so it cannot be
 * confused with the fresh login each assertion still needs to make its own query.
 */
class AuditPeriodIT : AuditTestSupport() {

    private val zone = ZoneId.of("Europe/Budapest")

    @Test
    fun `an event just before local midnight is excluded from TODAY`() {
        val admin = givenSuperAdmin()
        val target = givenServiceUser()
        val today = mutableClock.instant().atZone(zone).toLocalDate()

        mutableClock.set(today.atStartOfDay(zone).minusSeconds(1).toInstant())
        changeRole(bearerFor(admin), target.serviceId.value, "MODERATOR")

        mutableClock.set(today.atStartOfDay(zone).plusHours(10).toInstant())
        val bearer = bearerFor(admin)
        assertEquals(0, totalElements(auditEvents(bearer, period = "TODAY", eventType = "USER_ROLE_CHANGED", query = target.serviceId.value)))
    }

    @Test
    fun `an event exactly at local midnight is included in TODAY`() {
        val admin = givenSuperAdmin()
        val target = givenServiceUser()
        val today = mutableClock.instant().atZone(zone).toLocalDate()

        mutableClock.set(today.atStartOfDay(zone).toInstant())
        changeRole(bearerFor(admin), target.serviceId.value, "MODERATOR")

        mutableClock.set(today.atStartOfDay(zone).plusHours(10).toInstant())
        val bearer = bearerFor(admin)
        assertEquals(1, totalElements(auditEvents(bearer, period = "TODAY", eventType = "USER_ROLE_CHANGED", query = target.serviceId.value)))
    }

    @Test
    fun `LAST_7_DAYS excludes an event from 7 days ago but includes one from 6 days ago`() {
        boundaryTest(daysBack = 7, expectedInWindow = false, period = "LAST_7_DAYS")
        boundaryTest(daysBack = 6, expectedInWindow = true, period = "LAST_7_DAYS")
    }

    @Test
    fun `LAST_30_DAYS excludes an event from 30 days ago but includes one from 29 days ago`() {
        boundaryTest(daysBack = 30, expectedInWindow = false, period = "LAST_30_DAYS")
        boundaryTest(daysBack = 29, expectedInWindow = true, period = "LAST_30_DAYS")
    }

    @Test
    fun `LAST_90_DAYS excludes an event from 90 days ago but includes one from 89 days ago`() {
        boundaryTest(daysBack = 90, expectedInWindow = false, period = "LAST_90_DAYS")
        boundaryTest(daysBack = 89, expectedInWindow = true, period = "LAST_90_DAYS")
    }

    @Test
    fun `ALL has no lower bound - an event 400 days old is still visible`() {
        val admin = givenSuperAdmin()
        val target = givenServiceUser()
        val start = mutableClock.instant()

        mutableClock.set(start.minusSeconds(400L * 24 * 3600))
        changeRole(bearerFor(admin), target.serviceId.value, "MODERATOR")

        mutableClock.set(start)
        val bearer = bearerFor(admin)
        assertEquals(0, totalElements(auditEvents(bearer, period = "LAST_90_DAYS", eventType = "USER_ROLE_CHANGED", query = target.serviceId.value)))
        assertEquals(1, totalElements(auditEvents(bearer, period = "ALL", eventType = "USER_ROLE_CHANGED", query = target.serviceId.value)))
    }

    @Test
    fun `an unknown period code is rejected, not silently defaulted`() {
        val response = auditEvents(bearerFor(givenSuperAdmin()), period = "ALL_TIME")
        assertEquals(400, response.statusCode())
        assertEquals("AUDIT_PERIOD_INVALID", errorCode(response))
    }

    @Test
    fun `no period defaults to LAST_30_DAYS`() {
        val admin = givenSuperAdmin()
        val target = givenServiceUser()
        val start = mutableClock.instant()

        mutableClock.set(start.minusSeconds(40L * 24 * 3600))
        changeRole(bearerFor(admin), target.serviceId.value, "MODERATOR")

        mutableClock.set(start)
        val bearer = bearerFor(admin)
        // 40 days old: excluded from the default (LAST_30_DAYS) window, visible under ALL.
        assertEquals(0, totalElements(auditEvents(bearer, eventType = "USER_ROLE_CHANGED", query = target.serviceId.value)))
        assertEquals(1, totalElements(auditEvents(bearer, period = "ALL", eventType = "USER_ROLE_CHANGED", query = target.serviceId.value)))
    }

    // --------------------------------------------------------------------------------- DST

    @Test
    fun `an event on the real spring-forward transition day is visible in a window covering it`() {
        val transition = zone.rules.nextTransition(Instant.parse("2026-01-01T00:00:00Z"))
        assertTrue(transition.isGap, "expected the found transition to be a spring-forward gap")
        val transitionDate = transition.instant.atZone(zone).toLocalDate()

        val admin = givenSuperAdmin()
        val target = givenServiceUser()

        mutableClock.set(transition.instant.plusSeconds(7200))
        changeRole(bearerFor(admin), target.serviceId.value, "MODERATOR")

        mutableClock.set(transitionDate.plusDays(1).atStartOfDay(zone).plusHours(10).toInstant())
        val bearer = bearerFor(admin)
        assertEquals(1, totalElements(auditEvents(bearer, period = "LAST_7_DAYS", eventType = "USER_ROLE_CHANGED", query = target.serviceId.value)))
    }

    @Test
    fun `an event on the real fall-back transition day is visible in a window covering it`() {
        val spring = zone.rules.nextTransition(Instant.parse("2026-01-01T00:00:00Z"))
        val fallBack = zone.rules.nextTransition(spring.instant)
        assertTrue(fallBack.isOverlap, "expected the found transition to be a fall-back overlap")
        val transitionDate = fallBack.instant.atZone(zone).toLocalDate()

        val admin = givenSuperAdmin()
        val target = givenServiceUser()

        mutableClock.set(fallBack.instant.plusSeconds(3600))
        changeRole(bearerFor(admin), target.serviceId.value, "MODERATOR")

        mutableClock.set(transitionDate.plusDays(1).atStartOfDay(zone).plusHours(10).toInstant())
        val bearer = bearerFor(admin)
        assertEquals(1, totalElements(auditEvents(bearer, period = "LAST_7_DAYS", eventType = "USER_ROLE_CHANGED", query = target.serviceId.value)))
    }

    @Test
    fun `the pure calculator resolves ALL to a null lower bound on a fixed instant`() {
        val now = Instant.parse("2026-06-15T10:00:00Z")
        val range = AuditPeriodCalculator.resolve(AuditPeriod.ALL, now)
        assertEquals(null, range.from)
        assertEquals(now, range.to)
    }

    // ------------------------------------------------------------------------------- private

    private fun boundaryTest(daysBack: Int, expectedInWindow: Boolean, period: String) {
        val admin = givenSuperAdmin()
        val target = givenServiceUser()
        val today = mutableClock.instant().atZone(zone).toLocalDate()

        mutableClock.set(today.minusDays(daysBack.toLong()).atStartOfDay(zone).toInstant())
        changeRole(bearerFor(admin), target.serviceId.value, "MODERATOR")

        mutableClock.set(today.atStartOfDay(zone).plusHours(10).toInstant())
        val bearer = bearerFor(admin)
        val expected = if (expectedInWindow) 1 else 0
        assertEquals(expected, totalElements(auditEvents(bearer, period = period, eventType = "USER_ROLE_CHANGED", query = target.serviceId.value)))
    }

    private fun totalElements(response: java.net.http.HttpResponse<String>): Int {
        assertEquals(200, response.statusCode())
        return json(response).get("totalElements").asInt()
    }
}
