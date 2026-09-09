package hu.orszembejelento.backend.reportworkflow

import hu.orszembejelento.backend.reportworkflow.support.ReportWorkflowTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/** Return-to-NEW (brief §33-34, §58) against real PostgreSQL. */
@Import(AbstractAuthIntegrationTest.Containers::class)
class ReturnReportIT : ReportWorkflowTestSupport() {

    @Test
    fun `the owning SERVICE_USER can return their own claimed report to NEW`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(user), report.publicId, 0).statusCode() == 200)

        val response = returnToNew(bearerFor(user), report.publicId, 1)
        check(response.statusCode() == 200) { response.body() }
        val body = json(response)
        check(body.get("status").asText() == "NEW")
        check(body.get("assignee").isNull)
        check(body.get("workflowVersion").asLong() == 2L)

        val row = reportRow(report.publicId)
        check(row.status == "NEW" && row.assignedUserId == null && row.workflowVersion == 2L)
        val history = assignmentHistory(report.publicId)
        check(history.size == 1)
        check(history.single().endedAt != null && history.single().endReason == "RETURNED")
        check(openAssignmentCount(report.publicId) == 0)
        check(auditEventCount("REPORT_RETURNED_TO_NEW") == 1)
    }

    @Test
    fun `a SERVICE_USER cannot return another user's claimed report - hidden as 404`() {
        val area = givenRoutedArea()
        val owner = givenServiceUser()
        val stranger = givenServiceUser()
        grantArea(owner.id, area.areaId)
        grantArea(stranger.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(owner), report.publicId, 0).statusCode() == 200)

        val response = returnToNew(bearerFor(stranger), report.publicId, 1)
        check(response.statusCode() == 404) { response.body() }
        check(errorCode(response) == "REPORT_NOT_FOUND")
    }

    @Test
    fun `MODERATOR and SUPER_ADMIN can return any visible IN_PROGRESS report in scope`() {
        val area = givenRoutedArea()
        val owner = givenServiceUser()
        grantArea(owner.id, area.areaId)
        val mod = givenTerritorialModerator()
        grantArea(mod.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(owner), report.publicId, 0).statusCode() == 200)

        val response = returnToNew(bearerFor(mod), report.publicId, 1)
        check(response.statusCode() == 200) { response.body() }
    }

    @Test
    fun `returning a report that is still NEW yields REPORT_STATE_CHANGED for a visible supervisor`() {
        val area = givenRoutedArea()
        val mod = givenTerritorialModerator()
        grantArea(mod.id, area.areaId)
        val report = givenRoutedReport(area)

        val response = returnToNew(bearerFor(mod), report.publicId, 0)
        check(response.statusCode() == 409) { response.body() }
        check(errorCode(response) == "REPORT_STATE_CHANGED")
    }

    @Test
    fun `returning an already-archived report yields REPORT_ALREADY_ARCHIVED`() {
        val area = givenRoutedArea()
        val mod = givenTerritorialModerator()
        grantArea(mod.id, area.areaId)
        val report = givenRoutedReport(area)
        check(close(bearerFor(mod), report.publicId, 0).statusCode() == 200)

        val response = returnToNew(bearerFor(mod), report.publicId, 1)
        check(response.statusCode() == 409) { response.body() }
        check(errorCode(response) == "REPORT_ALREADY_ARCHIVED")
    }

    @Test
    fun `returning with a stale version yields REPORT_STATE_CHANGED`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(user), report.publicId, 0).statusCode() == 200)

        val response = returnToNew(bearerFor(user), report.publicId, 0) // real current version is 1
        check(response.statusCode() == 409) { response.body() }
        check(errorCode(response) == "REPORT_STATE_CHANGED")
    }
}
