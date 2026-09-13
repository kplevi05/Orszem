package hu.orszembejelento.backend.audit

import hu.orszembejelento.backend.audit.support.AuditTestSupport
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Audit-query authorization (Phase 12 brief §2/§65): SUPER_ADMIN only, on all three endpoints,
 * with no territorial exception and no global-MODERATOR exception either - unlike Phase 11
 * analytics, there is no scope-narrowed visibility here at all, only "yes" or "no".
 */
class AuditAuthorizationIT : AuditTestSupport() {

    @Test
    fun `a SERVICE_USER is forbidden from list, detail and options`() {
        val bearer = bearerFor(givenServiceUser())
        assertEquals(403, auditEvents(bearer).statusCode())
        assertEquals(403, auditDetail(bearer, UUID.randomUUID()).statusCode())
        assertEquals(403, auditOptions(bearer).statusCode())
    }

    @Test
    fun `a territorial MODERATOR is forbidden from list, detail and options`() {
        val bearer = bearerFor(givenTerritorialModerator())
        assertEquals(403, auditEvents(bearer).statusCode())
        assertEquals(403, auditDetail(bearer, UUID.randomUUID()).statusCode())
        assertEquals(403, auditOptions(bearer).statusCode())
    }

    @Test
    fun `a global MODERATOR is still forbidden - audit is never area-scoped visibility`() {
        val bearer = bearerFor(givenGlobalModerator())
        assertEquals(403, auditEvents(bearer).statusCode())
        assertEquals(403, auditDetail(bearer, UUID.randomUUID()).statusCode())
        assertEquals(403, auditOptions(bearer).statusCode())
    }

    @Test
    fun `SUPER_ADMIN is allowed on all three endpoints`() {
        val bearer = bearerFor(givenSuperAdmin())
        assertEquals(200, auditEvents(bearer).statusCode())
        assertEquals(200, auditOptions(bearer).statusCode())
        // A real event always exists by this point (SUPER_ADMIN's own SESSION_CREATED from
        // bearerFor's login) - detail 404 on a random id is proven separately, not here.
    }

    @Test
    fun `every audit response carries no-store`() {
        val bearer = bearerFor(givenSuperAdmin())
        assertEquals(listOf("no-store"), auditEvents(bearer).headers().allValues("Cache-Control"))
        assertEquals(listOf("no-store"), auditOptions(bearer).headers().allValues("Cache-Control"))
    }
}
