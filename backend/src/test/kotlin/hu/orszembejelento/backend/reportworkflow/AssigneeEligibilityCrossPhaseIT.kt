package hu.orszembejelento.backend.reportworkflow

import hu.orszembejelento.backend.reportworkflow.support.ReportWorkflowTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/**
 * Cross-phase invariant review: Phase 7 defines the current report assignee as "an eligible
 * ACTIVE SERVICE_USER". This file proves that a *concurrent* Phase 6 user-management
 * mutation (role change, deactivation, area/global-access revoke) racing a Phase 7 mutation
 * that would make that same user the assignee (claim, reassign) can never result in an
 * invalid assignment — because both sides now genuinely contend for the same `users` row
 * lock (`ClaimReportUseCase`'s and `ReassignReportUseCase`'s canonical lock order step 2,
 * and the Phase 6 mutations' own existing step 1), so the two are always fully serialized
 * against each other rather than merely raced.
 *
 * **Mutual exclusion, not just "no invalid state":** since the post-review Phase 6 addendum
 * (`docs/PHASE_7_ENGINEERING_REPORT.md` §R) makes role-promotion/deactivation/scope-revoke
 * *themselves* reject when the target already holds an open assignment, racing exactly one
 * of these against exactly one claim/reassign on the same user now has a genuinely clean
 * invariant: **exactly one side succeeds, never both, never neither.** Whichever wins the
 * `users` row lock first commits normally; the loser, re-reading fresh state after being
 * unblocked, always finds a reason to reject. This file's tests assert that mutual exclusion
 * directly rather than only "no invalid state survives".
 *
 * The last test (§11 of the review) covers the complementary, deliberately-asymmetric case:
 * return/close/reassign-away end an assignment through the REPORT lock alone, never the
 * assignee's own `users` row lock, so they can race a Phase 6 mutation without either side
 * blocking the other. The review explicitly accepts a conservative false-positive 409 from
 * Phase 6 in that case; what must never happen is an unsafe false negative.
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class AssigneeEligibilityCrossPhaseIT : ReportWorkflowTestSupport() {

    // ---------------------------------------------------------- 1. claim vs role promotion

    @Test
    fun `a self-claim racing a role promotion of the same user - exactly one side succeeds`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)

        val userBearer = bearerFor(user)
        val admin = adminBearer()

        val results = runConcurrently(2) { index ->
            if (index == 0) claim(userBearer, report.publicId, 0) else httpChangeRole(admin, user, "MODERATOR")
        }.map { it.getOrThrow() }

        val claimResult = results[0]
        val roleChangeResult = results[1]
        val succeeded = listOf(claimResult, roleChangeResult).count { it.statusCode() == 200 }
        check(succeeded == 1) { "expected exactly one winner, got claim=${claimResult.statusCode()} roleChange=${roleChangeResult.statusCode()}" }

        val row = reportRow(report.publicId)
        val finalRole = jdbc.sql("SELECT role FROM users WHERE id = :id").param("id", user.id).query(String::class.java).single()

        if (claimResult.statusCode() == 200) {
            // Claim won the user-lock race: the actor was genuinely still SERVICE_USER at
            // the exact moment of assignment. The role change, unblocked afterward, now
            // finds the fresh open assignment and correctly rejects it instead of silently
            // orphaning it (§R).
            check(row.status == "IN_PROGRESS" && row.assignedUserId == user.id)
            check(roleChangeResult.statusCode() == 409) { "expected USER_HAS_ACTIVE_REPORT_ASSIGNMENTS once the assignment was created first, got ${roleChangeResult.statusCode()}: ${roleChangeResult.body()}" }
            check(errorCode(roleChangeResult) == "USER_HAS_ACTIVE_REPORT_ASSIGNMENTS")
            check(finalRole == "SERVICE_USER") { "the rejected role change must not have partially applied" }
        } else {
            check(roleChangeResult.statusCode() == 200) { roleChangeResult.body() }
            check(finalRole == "MODERATOR")
            check(claimResult.statusCode() == 403) { "expected REPORT_WORKFLOW_FORBIDDEN once promoted before the claim's own lock, got ${claimResult.statusCode()}: ${claimResult.body()}" }
            check(errorCode(claimResult) == "REPORT_WORKFLOW_FORBIDDEN")
            check(row.status == "NEW" && row.assignedUserId == null)
        }
    }

    // -------------------------------------------------------------- 2. claim vs deactivation

    @Test
    fun `a self-claim racing a deactivation of the same user - exactly one side succeeds`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)

        val userBearer = bearerFor(user)
        val admin = adminBearer()

        val results = runConcurrently(2) { index ->
            if (index == 0) claim(userBearer, report.publicId, 0) else httpDeactivate(admin, user)
        }.map { it.getOrThrow() }

        val claimResult = results[0]
        val deactivateResult = results[1]

        val row = reportRow(report.publicId)
        val finalStatus = jdbc.sql("SELECT status FROM users WHERE id = :id").param("id", user.id).query(String::class.java).single()

        if (claimResult.statusCode() == 200) {
            check(row.status == "IN_PROGRESS" && row.assignedUserId == user.id)
            check(deactivateResult.statusCode() == 409) { "expected USER_HAS_ACTIVE_REPORT_ASSIGNMENTS once the assignment was created first, got ${deactivateResult.statusCode()}: ${deactivateResult.body()}" }
            check(errorCode(deactivateResult) == "USER_HAS_ACTIVE_REPORT_ASSIGNMENTS")
            check(finalStatus == "ACTIVE")
        } else {
            check(deactivateResult.statusCode() == 200) { deactivateResult.body() }
            check(finalStatus == "DEACTIVATED")
            // Either the row-lock re-validation rejected it (403 - status no longer ACTIVE),
            // or deactivation's session revocation won an even earlier race and the request
            // never reached the transaction at all (401) - both are safe, correct outcomes.
            check(claimResult.statusCode() == 403 || claimResult.statusCode() == 401) {
                "expected a forbidden/unauthenticated rejection once deactivated before the claim's own lock, got ${claimResult.statusCode()}: ${claimResult.body()}"
            }
            check(row.status == "NEW" && row.assignedUserId == null)
        }
    }

    // ----------------------------------------------------------------- 3. claim vs area revoke

    @Test
    fun `a self-claim racing the revocation of the user's only area - exactly one side succeeds`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)

        val userBearer = bearerFor(user)
        val admin = adminBearer()

        val results = runConcurrently(2) { index ->
            if (index == 0) claim(userBearer, report.publicId, 0) else httpRevokeArea(admin, user, area.areaId)
        }.map { it.getOrThrow() }

        val claimResult = results[0]
        val revokeResult = results[1]

        val row = reportRow(report.publicId)
        val stillGranted = serviceAreas.assignedAreaIds(user.id).contains(area.areaId)

        if (claimResult.statusCode() == 200) {
            check(row.status == "IN_PROGRESS" && row.assignedUserId == user.id)
            check(revokeResult.statusCode() == 409) { "expected USER_HAS_ACTIVE_REPORT_ASSIGNMENTS once the assignment was created first, got ${revokeResult.statusCode()}: ${revokeResult.body()}" }
            check(errorCode(revokeResult) == "USER_HAS_ACTIVE_REPORT_ASSIGNMENTS")
            check(stillGranted) { "the rejected revoke must not have partially applied" }
        } else {
            check(revokeResult.statusCode() == 200) { revokeResult.body() }
            check(!stillGranted)
            check(claimResult.statusCode() == 404) { "area loss is scope-hiding, like everywhere else - expected REPORT_NOT_FOUND, got ${claimResult.statusCode()}: ${claimResult.body()}" }
            check(errorCode(claimResult) == "REPORT_NOT_FOUND")
            check(row.status == "NEW" && row.assignedUserId == null)
        }
    }

    // -------------------------------------------------------- 4. reassign vs role promotion

    @Test
    fun `a reassignment racing a role promotion of its own target - exactly one side succeeds`() {
        val area = givenRoutedArea()
        val mod = givenTerritorialModerator()
        grantArea(mod.id, area.areaId)
        val original = givenServiceUser()
        val target = givenServiceUser()
        grantArea(original.id, area.areaId)
        grantArea(target.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(original), report.publicId, 0).statusCode() == 200)

        val modBearer = bearerFor(mod)
        val admin = adminBearer()

        val results = runConcurrently(2) { index ->
            if (index == 0) reassign(modBearer, report.publicId, 1, target.serviceId) else httpChangeRole(admin, target, "MODERATOR")
        }.map { it.getOrThrow() }

        val reassignResult = results[0]
        val roleChangeResult = results[1]

        val row = reportRow(report.publicId)
        val finalRole = jdbc.sql("SELECT role FROM users WHERE id = :id").param("id", target.id).query(String::class.java).single()

        if (reassignResult.statusCode() == 200) {
            check(row.assignedUserId == target.id)
            check(roleChangeResult.statusCode() == 409) { "expected USER_HAS_ACTIVE_REPORT_ASSIGNMENTS once reassignment committed first, got ${roleChangeResult.statusCode()}: ${roleChangeResult.body()}" }
            check(errorCode(roleChangeResult) == "USER_HAS_ACTIVE_REPORT_ASSIGNMENTS")
            check(finalRole == "SERVICE_USER")
        } else {
            check(roleChangeResult.statusCode() == 200) { roleChangeResult.body() }
            check(finalRole == "MODERATOR")
            check(reassignResult.statusCode() == 400) { "expected INVALID_ASSIGNEE once promoted before reassignment's own target lock, got ${reassignResult.statusCode()}: ${reassignResult.body()}" }
            check(errorCode(reassignResult) == "INVALID_ASSIGNEE")
            check(row.assignedUserId == original.id) { "the original assignment must be untouched when reassignment correctly rejects" }
        }
    }

    // -------------------------------------------------------------- 5. reassign vs deactivation

    @Test
    fun `a reassignment racing a deactivation of its own target - exactly one side succeeds`() {
        val area = givenRoutedArea()
        val mod = givenTerritorialModerator()
        grantArea(mod.id, area.areaId)
        val original = givenServiceUser()
        val target = givenServiceUser()
        grantArea(original.id, area.areaId)
        grantArea(target.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(original), report.publicId, 0).statusCode() == 200)

        val modBearer = bearerFor(mod)
        val admin = adminBearer()

        val results = runConcurrently(2) { index ->
            if (index == 0) reassign(modBearer, report.publicId, 1, target.serviceId) else httpDeactivate(admin, target)
        }.map { it.getOrThrow() }

        val reassignResult = results[0]
        val deactivateResult = results[1]

        val row = reportRow(report.publicId)
        val finalStatus = jdbc.sql("SELECT status FROM users WHERE id = :id").param("id", target.id).query(String::class.java).single()

        if (reassignResult.statusCode() == 200) {
            check(row.assignedUserId == target.id)
            check(deactivateResult.statusCode() == 409) { "expected USER_HAS_ACTIVE_REPORT_ASSIGNMENTS once reassignment committed first, got ${deactivateResult.statusCode()}: ${deactivateResult.body()}" }
            check(errorCode(deactivateResult) == "USER_HAS_ACTIVE_REPORT_ASSIGNMENTS")
            check(finalStatus == "ACTIVE")
        } else {
            check(deactivateResult.statusCode() == 200) { deactivateResult.body() }
            check(finalStatus == "DEACTIVATED")
            check(reassignResult.statusCode() == 400) { "expected INVALID_ASSIGNEE once deactivated before reassignment's own target lock, got ${reassignResult.statusCode()}: ${reassignResult.body()}" }
            check(errorCode(reassignResult) == "INVALID_ASSIGNEE")
            check(row.assignedUserId == original.id)
        }
    }

    // ------------------------------------------------------------------ 6. reassign vs area revoke

    @Test
    fun `a reassignment racing the revocation of its own target's only area - exactly one side succeeds`() {
        val area = givenRoutedArea()
        val mod = givenTerritorialModerator()
        grantArea(mod.id, area.areaId)
        val original = givenServiceUser()
        val target = givenServiceUser()
        grantArea(original.id, area.areaId)
        grantArea(target.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(original), report.publicId, 0).statusCode() == 200)

        val modBearer = bearerFor(mod)
        val admin = adminBearer()

        val results = runConcurrently(2) { index ->
            if (index == 0) reassign(modBearer, report.publicId, 1, target.serviceId) else httpRevokeArea(admin, target, area.areaId)
        }.map { it.getOrThrow() }

        val reassignResult = results[0]
        val revokeResult = results[1]

        val row = reportRow(report.publicId)
        val stillGranted = serviceAreas.assignedAreaIds(target.id).contains(area.areaId)

        if (reassignResult.statusCode() == 200) {
            check(row.assignedUserId == target.id)
            check(revokeResult.statusCode() == 409) { "expected USER_HAS_ACTIVE_REPORT_ASSIGNMENTS once reassignment committed first, got ${revokeResult.statusCode()}: ${revokeResult.body()}" }
            check(errorCode(revokeResult) == "USER_HAS_ACTIVE_REPORT_ASSIGNMENTS")
            check(stillGranted)
        } else {
            check(revokeResult.statusCode() == 200) { revokeResult.body() }
            check(!stillGranted)
            check(reassignResult.statusCode() == 400) { "expected INVALID_ASSIGNEE once the target's area was revoked before reassignment's own target lock, got ${reassignResult.statusCode()}: ${reassignResult.body()}" }
            check(errorCode(reassignResult) == "INVALID_ASSIGNEE")
            check(row.assignedUserId == original.id)
        }
    }

    // ------------------------------------------------------- 7. return/close vs blocked Phase 6

    @Test
    fun `closing a report races a deactivation of its assignee - close always succeeds, deactivate is conservatively safe either way`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(user), report.publicId, 0).statusCode() == 200)

        val userBearer = bearerFor(user)
        val admin = adminBearer()

        val results = runConcurrently(2) { index ->
            if (index == 0) close(userBearer, report.publicId, 1) else httpDeactivate(admin, user)
        }.map { it.getOrThrow() }

        val closeResult = results[0]
        val deactivateResult = results[1]

        // Close only ever locks the REPORT - never the assignee's own `users` row - so it is
        // never blocked by a concurrent Phase 6 mutation holding that lock, and must always
        // succeed regardless of ordering.
        check(closeResult.statusCode() == 200) { "close never depends on the assignee's own user lock and must always succeed: ${closeResult.body()}" }
        val row = reportRow(report.publicId)
        check(row.status == "ARCHIVED" && row.assignedUserId == null)

        val finalStatus = jdbc.sql("SELECT status FROM users WHERE id = :id").param("id", user.id).query(String::class.java).single()
        if (deactivateResult.statusCode() == 200) {
            check(finalStatus == "DEACTIVATED")
            check(openAssignmentCount(report.publicId) == 0)
        } else {
            // The explicitly-accepted conservative false positive: Phase 6 read the
            // about-to-end assignment before close's commit and rejected. Still always safe
            // - the user is simply left untouched (ACTIVE), never a bug.
            check(deactivateResult.statusCode() == 409) { "a conservative false-positive conflict is acceptable, nothing else is: ${deactivateResult.body()}" }
            check(errorCode(deactivateResult) == "USER_HAS_ACTIVE_REPORT_ASSIGNMENTS")
            check(finalStatus == "ACTIVE")
        }
    }
}
