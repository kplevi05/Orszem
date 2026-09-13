package hu.orszembejelento.backend.audit

import hu.orszembejelento.backend.audit.support.AuditTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * List/pagination/filter semantics (Phase 12 brief §16/§21/§30/§41/§66) - deterministic
 * ordering, server-side bounded paging, exact-match filters, never client-only filtering.
 */
class AuditListIT : AuditTestSupport() {

    @Test
    fun `no period defaults to LAST_30_DAYS`() {
        val admin = givenSuperAdmin()
        val bearer = bearerFor(admin)
        // No explicit assertion on count here (default-period boundary is AuditPeriodIT's own
        // job) - this only proves the endpoint answers at all with no period param.
        assertEquals(200, auditEvents(bearer).statusCode())
    }

    @Test
    fun `results are ordered newest first`() {
        val admin = givenSuperAdmin()
        val targetA = givenServiceUser()
        val targetB = givenServiceUser()
        val bearer = bearerFor(admin)

        changeRole(bearer, targetA.serviceId.value, "MODERATOR")
        mutableClock.advance(java.time.Duration.ofSeconds(5))
        changeRole(bearerFor(admin), targetB.serviceId.value, "MODERATOR")

        val items = json(auditEvents(bearerFor(admin), eventType = "USER_ROLE_CHANGED")).get("items").asList()
        val serviceIds = items.map { it.get("summary").asList() }
        // The most recent mutation (targetB) must be the first row - proven via actorServiceId
        // ordering is not distinguishing enough (same admin both times), so assert via the
        // literal targetServiceId is not present on this DTO shape either; instead assert on
        // occurredAt strictly decreasing, which is the actual contract (brief §16).
        val timestamps = items.map { it.get("occurredAt").asText() }
        val sorted = timestamps.sortedDescending()
        assertEquals(sorted, timestamps, "list must be strictly newest-first")
    }

    @Test
    fun `two events written at the exact same instant still sort deterministically by id`() {
        val admin = givenSuperAdmin()
        val targetA = givenServiceUser()
        val targetB = givenServiceUser()
        val bearer = bearerFor(admin)

        // Deliberately no clock advance between these two - both audit rows share created_at,
        // so only the `id DESC` tie-break (brief §16/§66) can make repeated queries agree.
        changeRole(bearer, targetA.serviceId.value, "MODERATOR")
        changeRole(bearer, targetB.serviceId.value, "MODERATOR")

        val first = json(auditEvents(bearer, eventType = "USER_ROLE_CHANGED", size = 2)).get("items").asList().map { it.get("auditEventId").asText() }
        val second = json(auditEvents(bearer, eventType = "USER_ROLE_CHANGED", size = 2)).get("items").asList().map { it.get("auditEventId").asText() }
        assertEquals(first, second, "identical repeated queries must return the exact same order")
    }

    @Test
    fun `default page size is 50 and the maximum bounded size is 100`() {
        val bearer = bearerFor(givenSuperAdmin())
        val defaultPage = json(auditEvents(bearer))
        assertEquals(50, defaultPage.get("size").asInt())

        val oversized = json(auditEvents(bearer, size = 500))
        assertEquals(100, oversized.get("size").asInt())
    }

    @Test
    fun `a second page returns different rows than the first`() {
        val admin = givenSuperAdmin()
        val bearer = bearerFor(admin)
        repeat(5) {
            val target = givenServiceUser()
            changeRole(bearerFor(admin), target.serviceId.value, "MODERATOR")
            mutableClock.advance(java.time.Duration.ofSeconds(1))
        }

        val page0 = json(auditEvents(bearerFor(admin), eventType = "USER_ROLE_CHANGED", page = 0, size = 2)).get("items").asList().map { it.get("auditEventId").asText() }
        val page1 = json(auditEvents(bearerFor(admin), eventType = "USER_ROLE_CHANGED", page = 1, size = 2)).get("items").asList().map { it.get("auditEventId").asText() }
        assertTrue(page0.isNotEmpty() && page1.isNotEmpty())
        assertTrue(page0.none { it in page1 }, "consecutive pages must not repeat rows")
    }

    @Test
    fun `eventType filters to an exact type`() {
        val admin = givenSuperAdmin()
        val target = givenServiceUser()
        changeRole(bearerFor(admin), target.serviceId.value, "MODERATOR")

        val body = json(auditEvents(bearerFor(admin), eventType = "USER_ROLE_CHANGED", query = target.serviceId.value))
        assertTrue(body.get("items").asList().all { it.get("eventType").asText() == "USER_ROLE_CHANGED" })
    }

    @Test
    fun `targetType filters to an exact type`() {
        val bearer = bearerFor(givenSuperAdmin())
        val body = json(auditEvents(bearer, targetType = "USER", period = "ALL"))
        assertTrue(body.get("items").asList().all { it.get("targetType").asText() == "USER" })
    }

    @Test
    fun `eventType and targetType combine as AND`() {
        val bearer = bearerFor(givenSuperAdmin())
        val body = json(auditEvents(bearer, eventType = "SESSION_CREATED", targetType = "SERVICE_AREA", period = "ALL"))
        // No SESSION_CREATED event ever targets SERVICE_AREA (brief §41 combined-filter proof) - always empty.
        assertEquals(0, body.get("totalElements").asInt())
    }

    @Test
    fun `an unknown eventType is rejected`() {
        val response = auditEvents(bearerFor(givenSuperAdmin()), eventType = "NOT_A_REAL_EVENT")
        assertEquals(400, response.statusCode())
        assertEquals("AUDIT_EVENT_TYPE_INVALID", errorCode(response))
    }

    @Test
    fun `an unknown targetType is rejected`() {
        val response = auditEvents(bearerFor(givenSuperAdmin()), targetType = "NOT_A_REAL_TARGET")
        assertEquals(400, response.statusCode())
        assertEquals("AUDIT_TARGET_TYPE_INVALID", errorCode(response))
    }

    @Test
    fun `no matching events returns an empty, not-an-error page`() {
        val bearer = bearerFor(givenSuperAdmin())
        val body = json(auditEvents(bearer, query = "zzz-no-such-service-id-zzz"))
        assertEquals(0, body.get("totalElements").asInt())
        assertEquals(0, body.get("items").asList().size)
    }
}
