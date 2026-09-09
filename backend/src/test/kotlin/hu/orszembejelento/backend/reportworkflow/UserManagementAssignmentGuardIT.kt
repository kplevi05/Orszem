package hu.orszembejelento.backend.reportworkflow

import hu.orszembejelento.backend.reportworkflow.support.ReportWorkflowTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/**
 * Sequential (non-concurrent) functional coverage of the cross-phase invariant review's
 * Phase 6 addendum (`docs/PHASE_7_ENGINEERING_REPORT.md` §R): a user-management mutation
 * that would invalidate an existing open report assignment is rejected with
 * `409 USER_HAS_ACTIVE_REPORT_ASSIGNMENTS`, never silently applied.
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class UserManagementAssignmentGuardIT : ReportWorkflowTestSupport() {

    // ---------------------------------------------------------------------------------- 1-2

    @Test
    fun `promoting a SERVICE_USER with an open assignment to MODERATOR is rejected, no state change`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(user), report.publicId, 0).statusCode() == 200)

        val admin = adminBearer()
        val response = httpChangeRole(admin, user, "MODERATOR")

        check(response.statusCode() == 409) { response.body() }
        check(errorCode(response) == "USER_HAS_ACTIVE_REPORT_ASSIGNMENTS")

        val finalRole = jdbc.sql("SELECT role FROM users WHERE id = :id").param("id", user.id).query(String::class.java).single()
        check(finalRole == "SERVICE_USER") { "no partial role change may have applied" }
        val row = reportRow(report.publicId)
        check(row.status == "IN_PROGRESS" && row.assignedUserId == user.id) { "the report's assignment must be untouched" }
    }

    @Test
    fun `deactivating a SERVICE_USER with an open assignment is rejected, user remains ACTIVE`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(user), report.publicId, 0).statusCode() == 200)

        val admin = adminBearer()
        val response = httpDeactivate(admin, user)

        check(response.statusCode() == 409) { response.body() }
        check(errorCode(response) == "USER_HAS_ACTIVE_REPORT_ASSIGNMENTS")

        val finalStatus = jdbc.sql("SELECT status FROM users WHERE id = :id").param("id", user.id).query(String::class.java).single()
        check(finalStatus == "ACTIVE")
        check(activeSessionCount(user.id) > 0) { "no session may have been revoked by a rejected deactivation" }
    }

    // ------------------------------------------------------------------------------------ 3-4

    @Test
    fun `revoking the only area covering an open assignment is rejected`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(user), report.publicId, 0).statusCode() == 200)

        val admin = adminBearer()
        val response = httpRevokeArea(admin, user, area.areaId)

        check(response.statusCode() == 409) { response.body() }
        check(errorCode(response) == "USER_HAS_ACTIVE_REPORT_ASSIGNMENTS")
        check(serviceAreas.assignedAreaIds(user.id).contains(area.areaId)) { "the rejected revoke must not have partially applied" }
    }

    @Test
    fun `revoking an explicit area grant succeeds when global access still covers the open assignment`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        setGlobalAccess(user.id, true)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(user), report.publicId, 0).statusCode() == 200)

        val admin = adminBearer()
        val response = httpRevokeArea(admin, user, area.areaId)

        check(response.statusCode() == 200) { "global access still covers the assignment, so the explicit grant may be safely revoked: ${response.body()}" }
        check(!serviceAreas.assignedAreaIds(user.id).contains(area.areaId))
    }

    // ------------------------------------------------------------------------------------ 5-6

    @Test
    fun `revoking global access succeeds when an explicit area grant still covers every open assignment`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        setGlobalAccess(user.id, true)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(user), report.publicId, 0).statusCode() == 200)

        val admin = adminBearer()
        val response = httpRevokeGlobalAccess(admin, user)

        check(response.statusCode() == 200) { "the explicit area grant still covers the assignment, so global access may be safely revoked: ${response.body()}" }
    }

    @Test
    fun `revoking global access is rejected when it is the only thing covering an open assignment`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        setGlobalAccess(user.id, true) // no explicit area grant at all
        val report = givenRoutedReport(area)
        check(claim(bearerFor(user), report.publicId, 0).statusCode() == 200)

        val admin = adminBearer()
        val response = httpRevokeGlobalAccess(admin, user)

        check(response.statusCode() == 409) { response.body() }
        check(errorCode(response) == "USER_HAS_ACTIVE_REPORT_ASSIGNMENTS")
    }

    // ---------------------------------------------------------------- multiple simultaneous assignments

    @Test
    fun `multiple simultaneous open assignments across different areas are each independently evaluated`() {
        val areaA = givenRoutedArea()
        val areaB = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, areaA.areaId)
        grantArea(user.id, areaB.areaId)
        val reportA = givenRoutedReport(areaA)
        val reportB = givenRoutedReport(areaB)
        check(claim(bearerFor(user), reportA.publicId, 0).statusCode() == 200)
        check(claim(bearerFor(user), reportB.publicId, 0).statusCode() == 200)

        val admin = adminBearer()

        // Revoking areaA must fail: it would orphan reportA's assignment, even though
        // reportB's assignment (via the untouched areaB grant) would remain perfectly fine.
        val revokeA = httpRevokeArea(admin, user, areaA.areaId)
        check(revokeA.statusCode() == 409) { revokeA.body() }
        check(errorCode(revokeA) == "USER_HAS_ACTIVE_REPORT_ASSIGNMENTS")
        check(serviceAreas.assignedAreaIds(user.id).containsAll(setOf(areaA.areaId, areaB.areaId)))

        // Once reportA's assignment ends, areaA no longer covers anything - the SAME revoke
        // now succeeds, proving both assignments were genuinely evaluated, not just the first.
        check(close(bearerFor(user), reportA.publicId, 1).statusCode() == 200)
        val revokeAAfter = httpRevokeArea(admin, user, areaA.areaId)
        check(revokeAAfter.statusCode() == 200) { revokeAAfter.body() }
        check(!serviceAreas.assignedAreaIds(user.id).contains(areaA.areaId))
        check(serviceAreas.assignedAreaIds(user.id).contains(areaB.areaId)) { "areaB's grant, and reportB's assignment, must be untouched throughout" }
    }

    // -------------------------------------------------------------------- never blocked

    @Test
    fun `granting an area or global access, resetting a password, and reactivating are never blocked by an existing open assignment`() {
        val area = givenRoutedArea()
        val otherArea = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)
        val userBearer = bearerFor(user)
        check(claim(userBearer, report.publicId, 0).statusCode() == 200)

        val admin = adminBearer()

        // Grants only ever widen scope - never a threat to an existing assignment.
        check(httpGrantArea(admin, user, otherArea.areaId).statusCode() == 200)
        check(httpGrantGlobalAccess(admin, user).statusCode() == 200)

        // Close the assignment first (using the bearer obtained before the password reset
        // below invalidates it), so deactivation - which the guard DOES check - has nothing
        // left to reject, and reactivation itself can then be exercised cleanly.
        check(close(userBearer, report.publicId, 1).statusCode() == 200)
        check(httpDeactivate(admin, user).statusCode() == 200)
        check(httpReactivate(admin, user).statusCode() == 200)

        // Entirely unrelated to report workflow - exercised last since it invalidates the
        // fixture password this test's own login helper relies on.
        check(httpResetPassword(admin, user).statusCode() == 200)
    }

    @Test
    fun `demoting a MODERATOR to SERVICE_USER is never blocked - a MODERATOR can never already hold an assignment`() {
        val mod = givenTerritorialModerator()
        val admin = adminBearer()

        val response = httpChangeRole(admin, mod, "SERVICE_USER")
        check(response.statusCode() == 200) { response.body() }
    }
}
