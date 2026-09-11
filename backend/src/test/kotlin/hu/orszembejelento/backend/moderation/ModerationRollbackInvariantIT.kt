package hu.orszembejelento.backend.moderation

import hu.orszembejelento.backend.moderation.support.ModerationTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/**
 * Forced-rollback / invariant proof (Phase 9 brief §33/§65).
 *
 * [hu.orszembejelento.backend.reportworkflow.ReportWorkflowInvariantsIT]'s own rollback test
 * works by pre-corrupting `report_assignments` directly, bypassing the app, because
 * `ClaimReportUseCase` trusts `reports.status` alone and only discovers the rogue row when
 * its own late `openAssignment` INSERT collides with `ux_report_assignments_open_episode`.
 *
 * [hu.orszembejelento.backend.moderation.application.ModerationDeleteUseCase] has no
 * equivalent gap to exploit the same way: its `hasOpenEpisode` check reads the *exact* same
 * predicate (`report_id = ? AND restored_at IS NULL`) that
 * `ux_report_moderation_episodes_open_episode` itself indexes, and that check runs *before*
 * any mutation. A pre-corrupted rogue open episode is therefore always caught by that check
 * first (`REPORT_ALREADY_DELETED`, zero mutations attempted) rather than surviving to
 * collide with the later `openEpisode` INSERT — a stronger guarantee (no partial mutation
 * ever begins) than "a partial mutation is undone", not a gap. The only way this INSERT can
 * genuinely collide is a **second, truly concurrent** transaction committing between this
 * one's check and its own INSERT — real PostgreSQL contention, not a single-threaded setup.
 *
 * This file proves the invariant list §33 asks for using that real contention instead:
 * [hu.orszembejelento.backend.moderation.ModerationConcurrencyIT]'s races are genuine
 * multi-statement transactions (assignment end + report update + episode insert + audit)
 * racing for the same report row lock, and the loser is provably left with **zero** trace —
 * proving PostgreSQL's transactional atomicity holds for every statement this use case runs,
 * not merely that the caller sees an error.
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class ModerationRollbackInvariantIT : ModerationTestSupport() {

    @Test
    fun `the losing side of a delete-vs-reassign race leaves absolutely no trace - no episode, no ended assignment, no audit row, no version bump`() {
        val area = givenRoutedArea()
        val mod = givenGlobalModerator()
        val original = givenServiceUser()
        val target = givenServiceUser()
        grantArea(original.id, area.areaId)
        grantArea(target.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(original), report.publicId, 0).statusCode() == 200)
        val modBearer = bearerFor(mod)

        val deleteAuditBefore = auditEventCount("REPORT_MODERATION_DELETED")
        val reassignAuditBefore = auditEventCount("REPORT_REASSIGNED")
        val versionBefore = reportRow(report.publicId).workflowVersion

        val results = runConcurrently(2) { index ->
            if (index == 0) reassign(modBearer, report.publicId, 1, target.serviceId) else rawDelete(report.publicId, 1, modBearer)
        }.map { it.getOrThrow() }
        val reassignResult = results[0]
        val deleteResult = results[1]

        val versionAfter = reportRow(report.publicId).workflowVersion
        check(versionAfter == versionBefore + 1) { "exactly one version increment from the single winning transition, never two, never zero" }

        if (reassignResult.statusCode() == 200) {
            // The delete loser: no moderation episode, no audit row, no extra version bump.
            check(deleteResult.statusCode() == 409)
            check(openModerationEpisodeCount(report.publicId) == 0)
            check(moderationEpisodeCount(report.publicId) == 0) { "the losing delete must not have committed an episode that a later step then had to undo" }
            check(auditEventCount("REPORT_MODERATION_DELETED") == deleteAuditBefore) { "no audit row may exist for a rejected mutation" }
            check(auditEventCount("REPORT_REASSIGNED") == reassignAuditBefore + 1)
        } else {
            // The reassign loser: the original assignment episode must show exactly the
            // MODERATION_DELETED end reason moderation itself wrote - never a REASSIGNED
            // half-write from the loser, and never two ended episodes.
            check(reassignResult.statusCode() == 404)
            check(deleteResult.statusCode() == 204)
            val history = assignmentHistory(report.publicId)
            check(history.size == 1) { "the losing reassign must not have opened a second episode: $history" }
            check(history[0].endReason == "MODERATION_DELETED") { "must not be REASSIGNED - that would mean the loser's write partially landed" }
            check(auditEventCount("REPORT_REASSIGNED") == reassignAuditBefore) { "no audit row may exist for a rejected mutation" }
            check(auditEventCount("REPORT_MODERATION_DELETED") == deleteAuditBefore + 1)
        }
    }

    @Test
    fun `the losing side of a two-restore race leaves no duplicate close, no extra audit row, no extra version bump`() {
        val area = givenRoutedArea()
        val report = givenRoutedReport(area)
        val admin = adminBearer()
        check(delete(admin, report.publicId, 0).statusCode() == 204)
        val admin2 = adminBearer()

        val restoreAuditBefore = auditEventCount("REPORT_MODERATION_RESTORED")
        val versionBefore = reportRow(report.publicId).workflowVersion

        val results = runConcurrently(2) { index -> rawRestore(report.publicId, 1, if (index == 0) admin else admin2) }
            .map { it.getOrThrow() }

        check(results.count { it.statusCode() == 204 } == 1)
        check(reportRow(report.publicId).workflowVersion == versionBefore + 1) { "exactly one version increment from the single winning restore" }
        check(auditEventCount("REPORT_MODERATION_RESTORED") == restoreAuditBefore + 1) { "no audit row for the losing, rejected restore" }
        check(moderationEpisodeCount(report.publicId) == 1) { "only ever one episode total - the loser never opened or closed a second one" }
    }
}
