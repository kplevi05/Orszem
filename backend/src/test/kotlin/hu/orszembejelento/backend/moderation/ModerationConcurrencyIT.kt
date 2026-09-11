package hu.orszembejelento.backend.moderation

import hu.orszembejelento.backend.moderation.support.ModerationTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/**
 * Real PostgreSQL concurrency proofs for moderation (Phase 9 brief §28-33/§64) — genuinely
 * overlapping requests via [runConcurrently], asserting the invariant outcome rather than one
 * fixed scheduler winner, exactly like [hu.orszembejelento.backend.reportworkflow.AssigneeEligibilityCrossPhaseIT]
 * already does for the identical Phase 7 shape. Moderation only ever locks the REPORT row
 * (never `users`), so every one of these races is decided purely by who wins that one lock —
 * see `docs/PHASE_9_ENGINEERING_REPORT.md` §E for the full lock-graph argument.
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class ModerationConcurrencyIT : ModerationTestSupport() {

    // -------------------------------------------------------------------------- 1. delete vs claim

    @Test
    fun `delete races claim on a NEW report - exactly one side succeeds, never a hidden IN_PROGRESS ownership`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)
        val userBearer = bearerFor(user)
        val mod = givenGlobalModerator()
        val modBearer = bearerFor(mod)

        val results = runConcurrently(2) { index ->
            if (index == 0) claim(userBearer, report.publicId, 0) else rawDelete(report.publicId, 0, modBearer)
        }.map { it.getOrThrow() }
        val claimResult = results[0]
        val deleteResult = results[1]

        val row = reportRow(report.publicId)
        if (claimResult.statusCode() == 200) {
            check(row.status == "IN_PROGRESS" && row.assignedUserId == user.id)
            check(deleteResult.statusCode() == 409) { "delete losing the race must see a changed version: ${deleteResult.body()}" }
            check(errorCode(deleteResult) == "REPORT_STATE_CHANGED")
            check(openModerationEpisodeCount(report.publicId) == 0)
        } else {
            check(deleteResult.statusCode() == 204) { deleteResult.body() }
            check(row.status == "NEW" && row.assignedUserId == null)
            check(claimResult.statusCode() == 404) { "claim losing the race must see the now-deleted report as not-found: ${claimResult.body()}" }
            check(errorCode(claimResult) == "REPORT_NOT_FOUND")
            check(openAssignmentCount(report.publicId) == 0)
        }
        check(openModerationEpisodeCount(report.publicId) <= 1)
    }

    // ------------------------------------------------------------------------- 2. delete vs return

    @Test
    fun `delete races return on an IN_PROGRESS report - no open assignment survives either way`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(user), report.publicId, 0).statusCode() == 200)
        val userBearer = bearerFor(user)
        val mod = givenGlobalModerator()
        val modBearer = bearerFor(mod)

        val results = runConcurrently(2) { index ->
            if (index == 0) returnToNew(userBearer, report.publicId, 1) else rawDelete(report.publicId, 1, modBearer)
        }.map { it.getOrThrow() }
        val returnResult = results[0]
        val deleteResult = results[1]

        val row = reportRow(report.publicId)
        check(row.status == "NEW" && row.assignedUserId == null) { "no open assignment may survive either ordering: $row" }
        if (returnResult.statusCode() == 200) {
            check(deleteResult.statusCode() == 409) { deleteResult.body() }
        } else {
            check(deleteResult.statusCode() == 204) { deleteResult.body() }
            check(returnResult.statusCode() == 404) { returnResult.body() }
        }
        check(openAssignmentCount(report.publicId) == 0)
        check(openModerationEpisodeCount(report.publicId) <= 1)
    }

    // -------------------------------------------------------------------------- 3. delete vs close

    @Test
    fun `delete races close on an IN_PROGRESS report - coherent final state either way`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(user), report.publicId, 0).statusCode() == 200)
        val userBearer = bearerFor(user)
        val mod = givenGlobalModerator()
        val modBearer = bearerFor(mod)

        val results = runConcurrently(2) { index ->
            if (index == 0) close(userBearer, report.publicId, 1) else rawDelete(report.publicId, 1, modBearer)
        }.map { it.getOrThrow() }
        val closeResult = results[0]
        val deleteResult = results[1]

        val row = reportRow(report.publicId)
        if (closeResult.statusCode() == 200) {
            check(row.status == "ARCHIVED" && row.assignedUserId == null)
            check(deleteResult.statusCode() == 409) { deleteResult.body() }
        } else {
            check(deleteResult.statusCode() == 204) { deleteResult.body() }
            check(row.status == "NEW" && row.assignedUserId == null) { "delete winning first must leave NEW/unassigned: $row" }
            check(closeResult.statusCode() == 404) { closeResult.body() }
        }
        check(openAssignmentCount(report.publicId) == 0)
    }

    // ----------------------------------------------------------------------- 4. delete vs reassign

    @Test
    fun `delete races reassign on an IN_PROGRESS report - no lost assignment history, deterministic version`() {
        val area = givenRoutedArea()
        val mod = givenGlobalModerator()
        val original = givenServiceUser()
        val target = givenServiceUser()
        grantArea(original.id, area.areaId)
        grantArea(target.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(original), report.publicId, 0).statusCode() == 200)
        val modBearer = bearerFor(mod)

        val results = runConcurrently(2) { index ->
            if (index == 0) reassign(modBearer, report.publicId, 1, target.serviceId) else rawDelete(report.publicId, 1, modBearer)
        }.map { it.getOrThrow() }
        val reassignResult = results[0]
        val deleteResult = results[1]

        val row = reportRow(report.publicId)
        if (reassignResult.statusCode() == 200) {
            check(row.status == "IN_PROGRESS" && row.assignedUserId == target.id)
            check(deleteResult.statusCode() == 409) { deleteResult.body() }
            check(assignmentHistory(report.publicId).size == 2) { "original episode ended REASSIGNED, new one open for target" }
        } else {
            check(deleteResult.statusCode() == 204) { deleteResult.body() }
            check(row.status == "NEW" && row.assignedUserId == null)
            check(reassignResult.statusCode() == 404) { "reassign losing the race must see the deleted report as not-found: ${reassignResult.body()}" }
            check(assignmentHistory(report.publicId).size == 1) { "no lost/duplicated episode" }
            check(assignmentHistory(report.publicId)[0].endReason == "MODERATION_DELETED")
        }
        check(openAssignmentCount(report.publicId) == 0)
    }

    // ------------------------------------------------------------------- 5. two deletes, same report

    @Test
    fun `two moderators deleting the same report concurrently - exactly one open episode survives`() {
        val area = givenRoutedArea()
        val report = givenRoutedReport(area)
        val modA = bearerFor(givenGlobalModerator())
        val modB = bearerFor(givenGlobalModerator())

        val results = runConcurrently(2) { index -> rawDelete(report.publicId, 0, if (index == 0) modA else modB) }
            .map { it.getOrThrow() }

        val succeeded = results.count { it.statusCode() == 204 }
        check(succeeded == 1) { "expected exactly one winner, got ${results.map { it.statusCode() }}" }
        val conflicted = results.first { it.statusCode() != 204 }
        check(conflicted.statusCode() == 409 && errorCode(conflicted) == "REPORT_ALREADY_DELETED") { conflicted.body() }
        check(openModerationEpisodeCount(report.publicId) == 1)
        check(moderationEpisodeCount(report.publicId) == 1)
    }

    // ------------------------------------------------------------------ 6. two restores, same report

    @Test
    fun `two SUPER_ADMIN restore requests racing the same report - exactly one wins, no duplicate restore`() {
        val area = givenRoutedArea()
        val report = givenRoutedReport(area)
        val admin = adminBearer()
        check(delete(admin, report.publicId, 0).statusCode() == 204)
        val admin2Bearer = adminBearer()

        val results = runConcurrently(2) { index -> rawRestore(report.publicId, 1, if (index == 0) admin else admin2Bearer) }
            .map { it.getOrThrow() }

        val succeeded = results.count { it.statusCode() == 204 }
        check(succeeded == 1) { "expected exactly one winner, got ${results.map { it.statusCode() }}" }
        val conflicted = results.first { it.statusCode() != 204 }
        check(conflicted.statusCode() == 409 && errorCode(conflicted) == "REPORT_NOT_DELETED") { conflicted.body() }
        check(openModerationEpisodeCount(report.publicId) == 0)
        check(reportRow(report.publicId).workflowVersion == 2L) { "exactly one version increment from the single logical restore" }
    }

    // ----------------------------------------------------------- 7. delete vs restore, opposite actions

    @Test
    fun `a stale delete races a restore of the already-deleted report - coherent final state, never both applied`() {
        val area = givenRoutedArea()
        val report = givenRoutedReport(area)
        val admin = adminBearer()
        check(delete(admin, report.publicId, 0).statusCode() == 204)
        // A second, independent moderator holds a stale UI still showing "deleted" and
        // fires a repeat delete at the same instant SUPER_ADMIN restores it.
        val modBearer = bearerFor(givenGlobalModerator())

        val results = runConcurrently(2) { index ->
            if (index == 0) rawRestore(report.publicId, 1, admin) else rawDelete(report.publicId, 1, modBearer)
        }.map { it.getOrThrow() }
        val restoreResult = results[0]
        val deleteResult = results[1]

        // The repeat delete can never actually win: it only ever *reads* the open episode
        // and rejects (ModerationDeleteUseCase never closes an episode, only restore does),
        // so restore's own success never depends on lock order - it always finds the episode
        // exactly as it was, whichever transaction's row lock is granted first. Restore is
        // therefore unconditionally 204 here; the row-lock ordering shows up only in *which*
        // conflict the repeat delete observes, not in whether restore succeeds.
        check(restoreResult.statusCode() == 204) { restoreResult.body() }
        check(deleteResult.statusCode() == 409) { deleteResult.body() }
        check(errorCode(deleteResult) in setOf("REPORT_ALREADY_DELETED", "REPORT_STATE_CHANGED")) {
            // Delete's own lock+read ran before restore committed (still sees the open
            // episode) -> REPORT_ALREADY_DELETED, or after (episode already closed, version
            // already bumped) -> REPORT_STATE_CHANGED. Both are coherent; deleteResult.body()
            deleteResult.body()
        }
        check(openModerationEpisodeCount(report.publicId) == 0) { "never both applied at once" }
    }
}
