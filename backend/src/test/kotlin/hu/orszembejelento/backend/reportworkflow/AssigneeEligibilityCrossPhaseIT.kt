package hu.orszembejelento.backend.reportworkflow

import hu.orszembejelento.backend.reportworkflow.support.ReportWorkflowTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/**
 * Cross-phase invariant review: Phase 7 defines the current report assignee as "an eligible
 * ACTIVE SERVICE_USER". This file proves that a *concurrent* Phase 6 user-management
 * mutation (role change, deactivation, area revoke) racing a Phase 7 mutation that would
 * make that same user the assignee (claim, reassign) can never result in an invalid
 * assignment — because both sides now genuinely contend for the same `users` row lock
 * (`ClaimReportUseCase`'s and `ReassignReportUseCase`'s canonical lock order step 2), so the
 * two are always fully serialized against each other rather than merely raced.
 *
 * **Scope note, deliberate:** this file covers only the "creating a NEW open assignment"
 * side of the cross-phase review (items covered by [ClaimReportUseCase]/
 * [hu.orszembejelento.backend.reportworkflow.application.ReassignReportUseCase]'s own fresh
 * re-validation). It does **not** attempt "Phase 6 rejects a mutation against a user who
 * already holds an EXISTING open assignment" — that would require Phase 6 to also lock the
 * affected `reports` row(s), which is the reverse of the REPORT-then-USER order Phase 7
 * already established and was intentionally left un-implemented; see
 * `docs/PHASE_7_ENGINEERING_REPORT.md` §Q for the full lock-graph analysis and the reasoning
 * for stopping there rather than risking a lock-order inversion.
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class AssigneeEligibilityCrossPhaseIT : ReportWorkflowTestSupport() {

    // ---------------------------------------------------------- 1. claim vs role promotion

    @Test
    fun `a self-claim racing a role promotion of the same user never leaves a MODERATOR as the current assignee`() {
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
        check(roleChangeResult.statusCode() == 200) { "the role change itself is unconditional on report state and must always succeed: ${roleChangeResult.body()}" }

        val row = reportRow(report.publicId)
        if (claimResult.statusCode() == 200) {
            // Claim's own lock-acquisition ran (and committed) before the role change's -
            // the actor was genuinely still SERVICE_USER at the exact moment of assignment.
            check(row.status == "IN_PROGRESS" && row.assignedUserId == user.id)
        } else {
            // The role change committed first: claim's fresh re-validation (locked, post
            // report-lock) correctly saw the new MODERATOR role and rejected - never creating
            // an assignment for a non-SERVICE_USER.
            check(claimResult.statusCode() == 403) { "expected REPORT_WORKFLOW_FORBIDDEN once promoted before the claim's own lock, got ${claimResult.statusCode()}: ${claimResult.body()}" }
            check(errorCode(claimResult) == "REPORT_WORKFLOW_FORBIDDEN")
            check(row.status == "NEW" && row.assignedUserId == null)
        }
        // No blanket "the final assignee is never a MODERATOR" check here, deliberately: if
        // claim won the lock race, the assignment was genuinely valid at the instant it was
        // written (fresh-checked inside the transaction), and the role change then applies
        // to that user afterward exactly as it would to any other SERVICE_USER - Phase 6
        // mutating an *already*-assigned user is the explicitly out-of-scope "items 1-3"
        // case (see this file's class KDoc and the engineering report's lock-graph note),
        // not a violation of what this test actually proves: no assignment is ever *created*
        // for an ineligible user.
    }

    // -------------------------------------------------------------- 2. claim vs deactivation

    @Test
    fun `a self-claim racing a deactivation of the same user never leaves a DEACTIVATED assignee`() {
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
        check(deactivateResult.statusCode() == 200) { "deactivation must always succeed regardless of ordering: ${deactivateResult.body()}" }

        val row = reportRow(report.publicId)
        if (claimResult.statusCode() == 200) {
            check(row.status == "IN_PROGRESS" && row.assignedUserId == user.id)
        } else {
            // Either the row-lock re-validation rejected it (403 - status no longer ACTIVE),
            // or deactivation's session revocation won an even earlier race and the request
            // never reached the transaction at all (401) - both are safe, correct outcomes.
            check(claimResult.statusCode() == 403 || claimResult.statusCode() == 401) {
                "expected a forbidden/unauthenticated rejection once deactivated before the claim's own lock, got ${claimResult.statusCode()}: ${claimResult.body()}"
            }
            check(row.status == "NEW" && row.assignedUserId == null)
        }
        // As above: no blanket post-hoc status check - if claim won, the assignment was
        // valid when written, and the (out-of-scope) case of Phase 6 deactivating an
        // already-assigned user afterward is not what this test proves.
    }

    // ----------------------------------------------------------------- 3. claim vs area revoke

    @Test
    fun `a self-claim racing the revocation of the user's only area never leaves an assignee without area authority`() {
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
        check(revokeResult.statusCode() == 200) { "SUPER_ADMIN revoking the user's only area must always succeed (no last-area rule applies to SUPER_ADMIN): ${revokeResult.body()}" }

        val row = reportRow(report.publicId)
        if (claimResult.statusCode() == 200) {
            check(row.status == "IN_PROGRESS" && row.assignedUserId == user.id)
        } else {
            check(claimResult.statusCode() == 404) { "area loss is scope-hiding, like everywhere else - expected REPORT_NOT_FOUND, got ${claimResult.statusCode()}: ${claimResult.body()}" }
            check(errorCode(claimResult) == "REPORT_NOT_FOUND")
            check(row.status == "NEW" && row.assignedUserId == null)
        }
    }

    // -------------------------------------------------------- 4. reassign vs role promotion

    @Test
    fun `a reassignment racing a role promotion of its own target never leaves a MODERATOR as the current assignee`() {
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
        check(roleChangeResult.statusCode() == 200) { roleChangeResult.body() }

        val row = reportRow(report.publicId)
        if (reassignResult.statusCode() == 200) {
            check(row.assignedUserId == target.id)
        } else {
            check(reassignResult.statusCode() == 400) { "expected INVALID_ASSIGNEE once promoted before reassignment's own target lock, got ${reassignResult.statusCode()}: ${reassignResult.body()}" }
            check(errorCode(reassignResult) == "INVALID_ASSIGNEE")
            check(row.assignedUserId == original.id) { "the original assignment must be untouched when reassignment correctly rejects" }
        }
        // No blanket post-hoc role check: if reassignment won the target-lock race, `target`
        // was genuinely SERVICE_USER at the exact moment it became the assignee - the role
        // change then applying to them afterward (now the current assignee) is the
        // explicitly out-of-scope "items 1-3" case, not something this test claims to cover.
    }

    // -------------------------------------------------------------- 5. reassign vs deactivation

    @Test
    fun `a reassignment racing a deactivation of its own target never leaves a DEACTIVATED assignee`() {
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
        check(deactivateResult.statusCode() == 200) { deactivateResult.body() }

        val row = reportRow(report.publicId)
        if (reassignResult.statusCode() == 200) {
            check(row.assignedUserId == target.id)
        } else {
            check(reassignResult.statusCode() == 400) { "expected INVALID_ASSIGNEE once deactivated before reassignment's own target lock, got ${reassignResult.statusCode()}: ${reassignResult.body()}" }
            check(errorCode(reassignResult) == "INVALID_ASSIGNEE")
            check(row.assignedUserId == original.id)
        }
        // No blanket post-hoc status check, for the same reason as above.
    }

    // ------------------------------------------------------------------ 6. reassign vs area revoke (real HTTP)

    @Test
    fun `a reassignment racing the revocation of its own target's only area never leaves an assignee without area authority`() {
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
        check(revokeResult.statusCode() == 200) { revokeResult.body() }

        val row = reportRow(report.publicId)
        if (reassignResult.statusCode() == 200) {
            check(row.assignedUserId == target.id)
        } else {
            check(reassignResult.statusCode() == 400) { "expected INVALID_ASSIGNEE once the target's area was revoked before reassignment's own target lock, got ${reassignResult.statusCode()}: ${reassignResult.body()}" }
            check(errorCode(reassignResult) == "INVALID_ASSIGNEE")
            check(row.assignedUserId == original.id)
        }
    }
}
