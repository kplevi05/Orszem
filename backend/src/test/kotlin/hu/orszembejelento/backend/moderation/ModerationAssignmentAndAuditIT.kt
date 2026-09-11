package hu.orszembejelento.backend.moderation

import hu.orszembejelento.backend.moderation.support.ModerationTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/**
 * Assignment-history integration (`MODERATION_DELETED`), audit events, and the
 * `workflow_version` optimistic-concurrency contract (Phase 9 brief §10/§24/§63).
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class ModerationAssignmentAndAuditIT : ModerationTestSupport() {

    @Test
    fun `deleting an IN_PROGRESS report writes exactly one MODERATION_DELETED-ended assignment episode`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(user), report.publicId, 0).statusCode() == 200)

        val mod = givenGlobalModerator()
        check(delete(bearerFor(mod), report.publicId, 1).statusCode() == 204)

        val history = assignmentHistory(report.publicId)
        check(history.size == 1)
        check(history[0].assigneeUserId == user.id)
        check(history[0].endedByUserId == mod.id)
        check(history[0].endReason == "MODERATION_DELETED")
        check(history[0].endedAt != null)
    }

    @Test
    fun `deleting a NEW or ARCHIVED report writes no assignment episode at all`() {
        val area = givenRoutedArea()
        val mod = givenGlobalModerator()
        val newReport = givenRoutedReport(area)
        check(delete(bearerFor(mod), newReport.publicId, 0).statusCode() == 204)
        check(assignmentHistory(newReport.publicId).isEmpty())

        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val archivedReport = givenRoutedReport(area)
        check(claim(bearerFor(user), archivedReport.publicId, 0).statusCode() == 200)
        check(close(bearerFor(user), archivedReport.publicId, 1).statusCode() == 200)
        check(delete(bearerFor(mod), archivedReport.publicId, 2).statusCode() == 204)
        // The one episode from the close-flow claim, no second one from deletion.
        check(assignmentHistory(archivedReport.publicId).size == 1)
        check(assignmentHistory(archivedReport.publicId)[0].endReason == "ARCHIVED")
    }

    @Test
    fun `a successful delete writes exactly one REPORT_MODERATION_DELETED audit event`() {
        val area = givenRoutedArea()
        val mod = givenGlobalModerator()
        val report = givenRoutedReport(area)
        val before = auditEventCount("REPORT_MODERATION_DELETED")

        check(delete(bearerFor(mod), report.publicId, 0).statusCode() == 204)

        check(auditEventCount("REPORT_MODERATION_DELETED") == before + 1)
    }

    @Test
    fun `a successful restore writes exactly one REPORT_MODERATION_RESTORED audit event`() {
        val area = givenRoutedArea()
        val report = givenRoutedReport(area)
        val admin = adminBearer()
        check(delete(admin, report.publicId, 0).statusCode() == 204)
        val before = auditEventCount("REPORT_MODERATION_RESTORED")

        check(restore(admin, report.publicId, 1).statusCode() == 204)

        check(auditEventCount("REPORT_MODERATION_RESTORED") == before + 1)
    }

    @Test
    fun `a rejected delete - already deleted, stale version, out of scope - writes no audit event`() {
        val area = givenRoutedArea()
        val mod = givenGlobalModerator()
        val report = givenRoutedReport(area)
        check(delete(bearerFor(mod), report.publicId, 0).statusCode() == 204)
        val countAfterFirst = auditEventCount("REPORT_MODERATION_DELETED")

        check(delete(bearerFor(mod), report.publicId, 1).statusCode() == 409)
        check(delete(bearerFor(mod), report.publicId, 99).statusCode() == 409)

        check(auditEventCount("REPORT_MODERATION_DELETED") == countAfterFirst) { "a rejected mutation must never write an audit row" }
    }

    @Test
    fun `every successful delete and restore increments workflow_version by exactly one`() {
        val area = givenRoutedArea()
        val report = givenRoutedReport(area)
        val admin = adminBearer()
        check(reportRow(report.publicId).workflowVersion == 0L)

        check(delete(admin, report.publicId, 0).statusCode() == 204)
        check(reportRow(report.publicId).workflowVersion == 1L)

        check(restore(admin, report.publicId, 1).statusCode() == 204)
        check(reportRow(report.publicId).workflowVersion == 2L)
    }
}
