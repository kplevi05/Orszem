package hu.orszembejelento.backend.auth

import hu.orszembejelento.backend.audit.support.AuditTestSupport
import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import java.net.http.HttpResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/**
 * Phase 13 brief §5/§6/§7 - the explicit backend-authoritative authorization regression
 * matrix, and proof that current role/scope/status takes effect on the very next request
 * with the SAME still-unexpired access token, never merely at the next login or token
 * expiry.
 *
 * Every call here hits the real HTTP endpoint directly - never through anything that models
 * what the Android UI happens to expose - which is exactly what proves route visibility is
 * irrelevant to backend security (brief §5's own phrasing).
 *
 * [AuditTestSupport] is the base purely because it is the one existing inheritance chain
 * that already reaches every domain this file needs in one place: Phase 7 report-workflow,
 * Phase 9 moderation, Phase 10 area admin, Phase 11 analytics and Phase 12 audit fixtures
 * and HTTP helpers, on top of the Phase 2/6 auth/user-management ones every domain needs.
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class AuthorizationFreshnessIT : AuditTestSupport() {

    // ============================================================ §5 authorization matrix

    @Test
    fun `report workflow queue is reachable by every active role, rejected once deactivated`() {
        assertEveryActiveRoleAllowed { bearer -> newQueue(bearer) }
        assertDeactivatedRejected { bearer -> newQueue(bearer) }
    }

    @Test
    fun `archive queue is reachable by every active role, rejected once deactivated`() {
        assertEveryActiveRoleAllowed { bearer -> archiveQueue(bearer) }
        assertDeactivatedRejected { bearer -> archiveQueue(bearer) }
    }

    @Test
    fun `moderation deleted list requires MODERATOR or SUPER_ADMIN, never SERVICE_USER`() {
        val serviceUser = givenServiceUser()
        val globalServiceUser = givenGlobalServiceUser()
        val territorialModerator = givenTerritorialModerator()
        val globalModerator = givenGlobalModerator()
        val superAdmin = givenSuperAdmin()

        assertEquals(403, deletedList(bearerFor(serviceUser)).statusCode()) { "a territorial SERVICE_USER must never see moderation history" }
        assertEquals(403, deletedList(bearerFor(globalServiceUser)).statusCode()) { "a global SERVICE_USER is still a SERVICE_USER - never moderation authority" }
        assertEquals(200, deletedList(bearerFor(territorialModerator)).statusCode())
        assertEquals(200, deletedList(bearerFor(globalModerator)).statusCode())
        assertEquals(200, deletedList(bearerFor(superAdmin)).statusCode())

        assertDeactivatedRejected { bearer -> deletedList(bearer) }
    }

    @Test
    fun `user management is reachable only by MODERATOR or SUPER_ADMIN, never SERVICE_USER`() {
        val serviceUser = givenServiceUser()
        val globalServiceUser = givenGlobalServiceUser()
        val territorialModerator = givenTerritorialModerator()
        val globalModerator = givenGlobalModerator()
        val superAdmin = givenSuperAdmin()
        val target = givenServiceUser()

        // A representative, real user-management mutation endpoint (not a read-only list),
        // since that is what brief §2 gates on role alone, no scope nuance to model wrong.
        assertEquals(403, httpResetPassword(bearerFor(serviceUser), target).statusCode())
        assertEquals(403, httpResetPassword(bearerFor(globalServiceUser), target).statusCode())
        assertTrue(httpResetPassword(bearerFor(territorialModerator), target).statusCode() != 403) {
            "a MODERATOR must at least clear the role gate (scope may still apply)"
        }
        assertEquals(200, httpResetPassword(bearerFor(globalModerator), target).statusCode())
        assertEquals(200, httpResetPassword(bearerFor(superAdmin), target).statusCode())

        assertDeactivatedRejected { bearer -> httpResetPassword(bearer, target) }
    }

    @Test
    fun `ServiceArea administration is reachable only by SUPER_ADMIN`() {
        val serviceUser = givenServiceUser()
        val globalServiceUser = givenGlobalServiceUser()
        val territorialModerator = givenTerritorialModerator()
        val globalModerator = givenGlobalModerator()
        val superAdmin = givenSuperAdmin()

        assertEquals(403, listAreas(bearerFor(serviceUser)).statusCode())
        assertEquals(403, listAreas(bearerFor(globalServiceUser)).statusCode())
        assertEquals(403, listAreas(bearerFor(territorialModerator)).statusCode())
        assertEquals(403, listAreas(bearerFor(globalModerator)).statusCode()) {
            "brief §5 - even a GLOBAL MODERATOR has no ServiceArea administration authority; this is Phase 10's own frozen rule, never widened here"
        }
        assertEquals(200, listAreas(bearerFor(superAdmin)).statusCode())

        assertDeactivatedRejected { bearer -> listAreas(bearer) }
    }

    @Test
    fun `analytics summary is reachable by every active role, scoped by actor identity - never a hard role wall`() {
        // Analytics has no role gate on its own summary endpoint (AnalyticsQueryUseCase):
        // every active role gets a response, automatically scoped to their own current
        // area(s) server-side. This IS the correct, already-established Phase 11 model -
        // the matrix proves it holds, not that a 403 wall exists where none was ever
        // designed.
        // AnalyticsTestSupport is a sibling branch of AuditTestSupport (both extend
        // AreaAdminTestSupport separately) - Kotlin has no multiple inheritance, so this one
        // endpoint is called directly rather than pulling in that whole second chain, exactly
        // the same reasoning AuditTestSupport's own KDoc gives for duplicating a handful of
        // user-management endpoints instead of inheriting AbstractUserManagementIntegrationTest.
        assertEveryActiveRoleAllowed { bearer -> get("/api/v1/service/analytics/summary", bearer) }
        assertDeactivatedRejected { bearer -> get("/api/v1/service/analytics/summary", bearer) }
    }

    @Test
    fun `audit history is reachable only by SUPER_ADMIN, never any MODERATOR`() {
        val serviceUser = givenServiceUser()
        val territorialModerator = givenTerritorialModerator()
        val globalModerator = givenGlobalModerator()
        val superAdmin = givenSuperAdmin()

        assertEquals(403, auditEvents(bearerFor(serviceUser)).statusCode())
        assertEquals(403, auditEvents(bearerFor(territorialModerator)).statusCode())
        assertEquals(403, auditEvents(bearerFor(globalModerator)).statusCode()) {
            "brief §5/Phase 12's own frozen rule - audit is never area-scoped visibility, not even for a global MODERATOR"
        }
        assertEquals(200, auditEvents(bearerFor(superAdmin)).statusCode())

        assertDeactivatedRejected { bearer -> auditEvents(bearer) }
    }

    // ============================================================ §6 authorization freshness

    @Test
    fun `an area removed mid-session narrows report workflow visibility on the very next request`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)
        val bearer = bearerFor(user)

        // Before: the report is visible in this user's own-area queue.
        val before = newQueue(bearer, areaId = area.areaId)
        assertEquals(200, before.statusCode())
        assertTrue(json(before).get("items").asList().any { it.get("publicReportId").asText() == report.publicId.toString() }) {
            "the report must be visible while the area is still granted"
        }

        // A separate admin action revokes the area - the SAME bearer token is reused, never reissued.
        httpRevokeArea(adminBearer(), user, area.areaId)

        // The report-workflow query use case treats an out-of-scope areaId filter as "no
        // results in scope," not a hard 403 (unlike the role-gated domains above) - so the
        // precise, correct proof of freshness here is the report's absence from a 200
        // response, not a particular status code. A stale-scope bug would instead still
        // return the report.
        val after = newQueue(bearer, areaId = area.areaId)
        assertEquals(200, after.statusCode()) { "the endpoint itself must remain reachable - only the report's visibility narrows: ${after.body()}" }
        assertTrue(json(after).get("items").asList().none { it.get("publicReportId").asText() == report.publicId.toString() }) {
            "brief §6 - the still-unexpired access token must stop seeing a report in a revoked area on the very next " +
                "request, not just after a fresh login: ${after.body()}"
        }

        // And the actor's own default (unfiltered) scope is equally empty of that area now -
        // not merely rejecting this one areaId argument.
        val defaultScope = newQueue(bearer)
        assertEquals(200, defaultScope.statusCode())
        assertTrue(json(defaultScope).get("items").asList().none { it.get("publicReportId").asText() == report.publicId.toString() }) {
            "the report must not reappear in the actor's own default scope either: ${defaultScope.body()}"
        }
    }

    @Test
    fun `global access removed mid-session narrows analytics scope on the very next request`() {
        val user = givenGlobalServiceUser()
        val bearer = bearerFor(user)

        val before = get("/api/v1/service/analytics/areas", bearer)
        assertEquals(200, before.statusCode()) { "a global SERVICE_USER must see the analytics area picker while access holds" }

        httpRevokeGlobalAccess(adminBearer(), user)

        // Same shape as the report-workflow case above: the endpoint stays reachable (this
        // actor is still an authenticated SERVICE_USER), but the area picker it returns must
        // narrow from "every active area" to nothing, since a plain territorial SERVICE_USER
        // with no assigned areas has nothing left to pick from.
        val after = get("/api/v1/service/analytics/areas", bearer)
        assertEquals(200, after.statusCode()) { "the endpoint itself must remain reachable - only the offered areas narrow: ${after.body()}" }
        assertTrue(json(after).get("areas").asList().isEmpty()) {
            "brief §6 - global access removal must take effect on this same token's very next request: ${after.body()}"
        }
    }

    @Test
    fun `a role narrowed away from MODERATOR mid-session blocks moderation on the very next request`() {
        val moderator = givenTerritorialModerator()
        val area = givenRoutedArea()
        grantArea(moderator.id, area.areaId)
        val report = givenRoutedReport(area)
        val bearer = bearerFor(moderator)

        val before = deletedList(bearer)
        assertEquals(200, before.statusCode())

        // A real, allowed transition (MODERATOR -> SERVICE_USER) performed by a separate admin.
        httpChangeRole(adminBearer(), moderator, "SERVICE_USER")

        val after = deletedList(bearer)
        assertEquals(403, after.statusCode()) {
            "brief §6 - a role narrowed by a separate admin action must block moderation on this same still-unexpired token's very next request: ${after.body()}"
        }

        // The narrowed role is also blocked from the specific mutation it used to be allowed
        // to perform, proven against a real report rather than just the list endpoint.
        val freshVersion = reportRow(report.publicId).workflowVersion
        assertEquals(403, delete(bearer, report.publicId, freshVersion).statusCode())
    }

    @Test
    fun `an account deactivated mid-session is blocked from every domain on the very next request with the same token`() {
        val user = givenSuperAdmin()
        val bearer = bearerFor(user)

        // Before: this SUPER_ADMIN token can reach every domain, including the SUPER_ADMIN-only
        // ones (area admin, audit) - the whole point of using SUPER_ADMIN as the target here.
        assertEquals(200, newQueue(bearer).statusCode())
        assertEquals(200, listAreas(bearer).statusCode())
        assertEquals(200, auditEvents(bearer).statusCode())

        // Direct DB update, deliberately bypassing the HTTP deactivation endpoint: a SUPER_ADMIN
        // may never manage another SUPER_ADMIN (UserManagementPolicy.canManage, anti-lockout),
        // so httpDeactivate would itself be rejected here and never change anything. This test
        // proves the AUTHENTICATION layer's own freshness (AuthenticateAccessTokenUseCase reads
        // user.isActive fresh on every request) - a property that holds regardless of which
        // administrative path caused the status change.
        jdbc.sql("UPDATE users SET status = 'DEACTIVATED' WHERE id = :id").param("id", user.id).update()

        assertEquals(401, newQueue(bearer).statusCode()) { "brief §6/§7 - deactivation must block every domain on the same token's very next request" }
        assertEquals(401, listAreas(bearer).statusCode())
        assertEquals(401, auditEvents(bearer).statusCode())
    }

    // ============================================================ §7 deactivated account

    @Test
    fun `a deactivated account cannot use its still-unexpired access token`() {
        val user = givenServiceUser()
        val credentials = loginSuccessfully(user.serviceId)

        httpDeactivate(adminBearer(), user)

        val response = get("/api/v1/service/account/me", credentials.accessToken)
        assertEquals(401, response.statusCode()) { "a deactivated account's still-valid access token must be refused: ${response.body()}" }
    }

    @Test
    fun `a deactivated account cannot refresh its session`() {
        val user = givenServiceUser()
        val credentials = loginSuccessfully(user.serviceId)

        httpDeactivate(adminBearer(), user)

        val response = refresh(credentials.refreshToken)
        assertEquals(401, response.statusCode()) { "a deactivated account must not be able to rotate a fresh access token: ${response.body()}" }
    }

    @Test
    fun `a deactivated account cannot log in again`() {
        val user = givenServiceUser()

        httpDeactivate(adminBearer(), user)

        val response = login(user.serviceId, STRONG_PASSWORD)
        assertEquals(401, response.statusCode())
        // Generic body, exactly like an unknown service ID - see LoginEnumerationIT for the
        // dedicated proof; this test only confirms deactivation specifically cannot log in.
        assertEquals("INVALID_CREDENTIALS", errorCode(response))
    }

    // ================================================================================ helpers

    /** Every ACTIVE role (both scope variants of both non-admin roles, plus SUPER_ADMIN) gets a non-401/403 response. */
    private fun assertEveryActiveRoleAllowed(call: (bearer: String) -> HttpResponse<String>) {
        val roles = listOf(
            givenServiceUser(), givenGlobalServiceUser(),
            givenTerritorialModerator(), givenGlobalModerator(),
            givenSuperAdmin(),
        )
        roles.forEach { user ->
            val response = call(bearerFor(user))
            assertTrue(response.statusCode() !in setOf(401, 403)) {
                "role=${user.role} (id=${user.id}) must be authorized here, got ${response.statusCode()}: ${response.body()}"
            }
        }
    }

    /**
     * A user who was ACTIVE when their access token was issued, then deactivated before the
     * call, must be rejected.
     *
     * Uses a SERVICE_USER target deliberately - a SUPER_ADMIN target would make
     * [httpDeactivate] itself fail (`UserManagementPolicy.canManage` forbids a SUPER_ADMIN
     * from managing another SUPER_ADMIN, an anti-lockout rule), which would silently leave
     * the "deactivated" user still ACTIVE and make this helper prove nothing. The deactivation
     * call's own status is asserted first so a future misuse of this pattern fails loudly at
     * the setup step instead of producing a confusing downstream false pass.
     */
    private fun assertDeactivatedRejected(call: (bearer: String) -> HttpResponse<String>) {
        val user = givenServiceUser()
        val bearer = bearerFor(user)
        val deactivation = httpDeactivate(adminBearer(), user)
        assertEquals(200, deactivation.statusCode()) { "fixture setup failed - deactivation itself did not succeed: ${deactivation.body()}" }
        val response = call(bearer)
        assertEquals(401, response.statusCode()) { "a deactivated account's still-unexpired token must never reach this endpoint: ${response.body()}" }
    }

    private fun assertEquals(expected: Int, actual: Int, message: () -> String) {
        if (expected != actual) throw AssertionError(message())
    }
}
