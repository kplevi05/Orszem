package hu.orszembejelento.backend.reportworkflow

import hu.orszembejelento.backend.reportworkflow.support.ReportWorkflowTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/** Close (brief §35-37, §59) against real PostgreSQL, plus the required return-vs-close race (§62). */
@Import(AbstractAuthIntegrationTest.Containers::class)
class CloseReportIT : ReportWorkflowTestSupport() {

    @Test
    fun `the owning SERVICE_USER can close their own claimed IN_PROGRESS report`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(user), report.publicId, 0).statusCode() == 200)

        val response = close(bearerFor(user), report.publicId, 1)
        check(response.statusCode() == 200) { response.body() }
        val body = json(response)
        check(body.get("status").asText() == "ARCHIVED")
        check(body.get("assignee").isNull)
        check(!body.get("archivedAt").isNull)
        check(body.get("workflowVersion").asLong() == 2L)

        val row = reportRow(report.publicId)
        check(row.status == "ARCHIVED" && row.assignedUserId == null && row.archivedAt != null && row.workflowVersion == 2L)
        val history = assignmentHistory(report.publicId)
        check(history.size == 1 && history.single().endReason == "ARCHIVED")
        check(openAssignmentCount(report.publicId) == 0)
        check(auditEventCount("REPORT_ARCHIVED") == 1)
    }

    @Test
    fun `a SERVICE_USER cannot close an unclaimed NEW report - visible but forbidden, 403 not 404`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)

        val response = close(bearerFor(user), report.publicId, 0)
        check(response.statusCode() == 403) { "the report is genuinely visible, so this must be a forbidden-action rejection, not a scope-hiding 404: ${response.body()}" }
        check(errorCode(response) == "REPORT_WORKFLOW_FORBIDDEN")
    }

    @Test
    fun `a SERVICE_USER cannot close another user's claimed report - hidden as 404`() {
        val area = givenRoutedArea()
        val owner = givenServiceUser()
        val stranger = givenServiceUser()
        grantArea(owner.id, area.areaId)
        grantArea(stranger.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(owner), report.publicId, 0).statusCode() == 200)

        val response = close(bearerFor(stranger), report.publicId, 1)
        check(response.statusCode() == 404) { response.body() }
        check(errorCode(response) == "REPORT_NOT_FOUND")
    }

    @Test
    fun `MODERATOR or SUPER_ADMIN can close a visible NEW report directly, with no assignment history created`() {
        val area = givenRoutedArea()
        val mod = givenTerritorialModerator()
        grantArea(mod.id, area.areaId)
        val report = givenRoutedReport(area)

        val response = close(bearerFor(mod), report.publicId, 0)
        check(response.statusCode() == 200) { response.body() }

        val row = reportRow(report.publicId)
        check(row.status == "ARCHIVED" && row.assignedUserId == null && row.archivedAt != null)
        check(assignmentHistory(report.publicId).isEmpty()) { "closing an unclaimed NEW report must not fabricate an assignment episode" }
        check(auditEventCount("REPORT_ARCHIVED") == 1)
    }

    @Test
    fun `MODERATOR or SUPER_ADMIN can close a visible IN_PROGRESS report`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val mod = givenTerritorialModerator()
        grantArea(mod.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(user), report.publicId, 0).statusCode() == 200)

        val response = close(bearerFor(mod), report.publicId, 1)
        check(response.statusCode() == 200) { response.body() }
        check(assignmentHistory(report.publicId).single().endReason == "ARCHIVED")
    }

    @Test
    fun `closing an already-archived report yields REPORT_ALREADY_ARCHIVED`() {
        val area = givenRoutedArea()
        val mod = givenTerritorialModerator()
        grantArea(mod.id, area.areaId)
        val report = givenRoutedReport(area)
        check(close(bearerFor(mod), report.publicId, 0).statusCode() == 200)

        val response = close(bearerFor(mod), report.publicId, 1)
        check(response.statusCode() == 409) { response.body() }
        check(errorCode(response) == "REPORT_ALREADY_ARCHIVED")
    }

    @Test
    fun `closing with a stale version yields REPORT_STATE_CHANGED`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(user), report.publicId, 0).statusCode() == 200)

        val response = close(bearerFor(user), report.publicId, 0) // real current version is 1
        check(response.statusCode() == 409) { response.body() }
        check(errorCode(response) == "REPORT_STATE_CHANGED")
    }

    // -------------------------------------------------------------------------- §62 concurrency

    @Test
    fun `a return and a close racing on the same IN_PROGRESS report never leave an inconsistent final state`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val mod = givenTerritorialModerator()
        grantArea(mod.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(user), report.publicId, 0).statusCode() == 200)

        val userBearer = bearerFor(user)
        val modBearer = bearerFor(mod)
        val results = runConcurrently(2) { index ->
            if (index == 0) returnToNew(userBearer, report.publicId, 1) else close(modBearer, report.publicId, 1)
        }.map { it.getOrThrow() }

        val returnResult = results[0]
        val closeResult = results[1]
        val succeeded = results.count { it.statusCode() == 200 }
        check(succeeded == 1) { "exactly one of return/close must win, got statuses ${results.map { it.statusCode() }}" }

        val row = reportRow(report.publicId)
        when {
            returnResult.statusCode() == 200 -> {
                check(row.status == "NEW" && row.assignedUserId == null && row.archivedAt == null)
                check(closeResult.statusCode() == 409)
            }
            closeResult.statusCode() == 200 -> {
                check(row.status == "ARCHIVED" && row.assignedUserId == null && row.archivedAt != null)
                check(returnResult.statusCode() == 409)
            }
            else -> error("neither operation succeeded: return=${returnResult.statusCode()} close=${closeResult.statusCode()}")
        }

        // Never IN_PROGRESS-with-ended-assignment, never NEW-with-open-assignment, never
        // ARCHIVED-with-current-assignee (brief §62's explicit coherence requirement).
        check(openAssignmentCount(report.publicId) == 0) { "the single open episode must have been ended by whichever operation won" }
        val history = assignmentHistory(report.publicId)
        check(history.size == 1) { "no duplicate/partial history rows may exist, found ${history.size}" }
        check(history.single().endedAt != null)
    }
}
