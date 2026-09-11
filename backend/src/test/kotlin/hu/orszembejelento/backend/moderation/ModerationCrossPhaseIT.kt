package hu.orszembejelento.backend.moderation

import hu.orszembejelento.backend.moderation.support.ModerationTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/**
 * Cross-phase invariant: moderation-deleting an IN_PROGRESS report ends its open assignment,
 * so a Phase 6 mutation that was blocked by that specific assignment must no longer be
 * blocked once the deletion commits (Phase 9 brief §27/§32/§63/§64). No special-casing is
 * needed in Phase 6 — `findOpenAssignmentAreas`/the deactivation guard already filter on
 * `ended_at IS NULL`, so they naturally stop seeing an episode moderation just ended.
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class ModerationCrossPhaseIT : ModerationTestSupport() {

    @Test
    fun `deactivation blocked by an open assignment succeeds once moderation deletion has ended it`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(user), report.publicId, 0).statusCode() == 200)

        val admin = adminBearer()
        val blocked = httpDeactivate(admin, user)
        check(blocked.statusCode() == 409) { "expected the pre-existing open-assignment guard to block first: ${blocked.body()}" }
        check(errorCode(blocked) == "USER_HAS_ACTIVE_REPORT_ASSIGNMENTS")

        check(delete(admin, report.publicId, 1).statusCode() == 204)

        val retried = httpDeactivate(admin, user)
        check(retried.statusCode() == 200) { "deactivation must no longer be blocked by an assignment moderation already ended: ${retried.body()}" }
        val finalStatus = jdbc.sql("SELECT status FROM users WHERE id = :id").param("id", user.id).query(String::class.java).single()
        check(finalStatus == "DEACTIVATED")
    }

    @Test
    fun `area revocation blocked by an open assignment succeeds once moderation deletion has ended it`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(user), report.publicId, 0).statusCode() == 200)

        val admin = adminBearer()
        check(httpRevokeArea(admin, user, area.areaId).statusCode() == 409)

        check(delete(admin, report.publicId, 1).statusCode() == 204)

        check(httpRevokeArea(admin, user, area.areaId).statusCode() == 200)
        check(!serviceAreas.assignedAreaIds(user.id).contains(area.areaId))
    }

    // ------------------------------------------------------- concurrent: moderation delete vs deactivation

    @Test
    fun `moderation-deleting an IN_PROGRESS report races a deactivation of its assignee - delete always succeeds, deactivate is conservatively safe either way`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(user), report.publicId, 0).statusCode() == 200)

        val mod = givenGlobalModerator()
        val modBearer = bearerFor(mod)
        val admin = adminBearer()

        val results = runConcurrently(2) { index ->
            if (index == 0) rawDelete(report.publicId, 1, modBearer) else httpDeactivate(admin, user)
        }.map { it.getOrThrow() }

        val deleteResult = results[0]
        val deactivateResult = results[1]

        // Moderation delete only ever locks the REPORT — never the assignee's own `users`
        // row — so it is never blocked by a concurrent Phase 6 mutation holding that lock,
        // exactly like ordinary close (AssigneeEligibilityCrossPhaseIT's identical proof).
        check(deleteResult.statusCode() == 204) { "moderation delete never depends on the assignee's own user lock and must always succeed: ${deleteResult.body()}" }
        val row = reportRow(report.publicId)
        check(row.status == "NEW" && row.assignedUserId == null)
        check(openAssignmentCount(report.publicId) == 0)

        val finalStatus = jdbc.sql("SELECT status FROM users WHERE id = :id").param("id", user.id).query(String::class.java).single()
        if (deactivateResult.statusCode() == 200) {
            check(finalStatus == "DEACTIVATED")
        } else {
            // The explicitly-accepted conservative false positive: Phase 6 read the
            // about-to-end assignment before the delete's commit and rejected. Always safe -
            // the user is simply left untouched (ACTIVE), never a bug (brief §32).
            check(deactivateResult.statusCode() == 409) { "a conservative false-positive conflict is acceptable, nothing else is: ${deactivateResult.body()}" }
            check(errorCode(deactivateResult) == "USER_HAS_ACTIVE_REPORT_ASSIGNMENTS")
            check(finalStatus == "ACTIVE")
        }
    }
}
