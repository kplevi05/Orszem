package hu.orszembejelento.backend.reportworkflow

import hu.orszembejelento.backend.reportworkflow.support.ReportWorkflowTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/** Claim (brief §30-32, §57) against real PostgreSQL. */
@Import(AbstractAuthIntegrationTest.Containers::class)
class ClaimReportIT : ReportWorkflowTestSupport() {

    @Test
    fun `a SERVICE_USER with area access can claim a NEW report`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)

        val response = claim(bearerFor(user), report.publicId, 0)
        check(response.statusCode() == 200) { response.body() }
        val body = json(response)
        check(body.get("status").asText() == "IN_PROGRESS")
        check(body.get("workflowVersion").asLong() == 1L)
        check(body.get("assignee").get("serviceId").asText() == user.serviceId.value)

        val row = reportRow(report.publicId)
        check(row.status == "IN_PROGRESS" && row.assignedUserId == user.id && row.workflowVersion == 1L)
        val history = assignmentHistory(report.publicId)
        check(history.size == 1 && history.single().assigneeUserId == user.id && history.single().endedAt == null)
        check(auditEventCount("REPORT_CLAIMED") == 1)
    }

    @Test
    fun `a territorial MODERATOR can self-claim a NEW report within their own area`() {
        val area = givenRoutedArea()
        val mod = givenTerritorialModerator()
        grantArea(mod.id, area.areaId)
        val report = givenRoutedReport(area)

        val response = claim(bearerFor(mod), report.publicId, 0)
        check(response.statusCode() == 200) { response.body() }
        val body = json(response)
        check(body.get("status").asText() == "IN_PROGRESS")
        check(body.get("assignee").get("serviceId").asText() == mod.serviceId.value)

        val row = reportRow(report.publicId)
        check(row.status == "IN_PROGRESS" && row.assignedUserId == mod.id)
        check(auditEventCount("REPORT_CLAIMED") == 1)
    }

    @Test
    fun `SUPER_ADMIN can self-claim any routed report`() {
        val area = givenRoutedArea()
        val admin = givenSuperAdmin()
        val report = givenRoutedReport(area)

        val response = claim(bearerFor(admin), report.publicId, 0)
        check(response.statusCode() == 200) { response.body() }
        check(reportRow(report.publicId).assignedUserId == admin.id)
    }

    @Test
    fun `a territorial MODERATOR cannot self-claim a report outside their own area - hidden as 404`() {
        val ownArea = givenRoutedArea()
        val otherArea = givenRoutedArea()
        val mod = givenTerritorialModerator()
        grantArea(mod.id, ownArea.areaId)
        val report = givenRoutedReport(otherArea)

        val response = claim(bearerFor(mod), report.publicId, 0)
        check(response.statusCode() == 404) { response.body() }
        check(errorCode(response) == "REPORT_NOT_FOUND")
    }

    @Test
    fun `a deactivated MODERATOR cannot self-claim even with a still-valid bearer race`() {
        val area = givenRoutedArea()
        val mod = givenTerritorialModerator()
        grantArea(mod.id, area.areaId)
        val bearer = bearerFor(mod)
        val report = givenRoutedReport(area)

        httpDeactivate(adminBearer(), mod)

        val response = claim(bearer, report.publicId, 0)
        check(response.statusCode() == 403 || response.statusCode() == 401) {
            "a deactivated MODERATOR must gain no claim access, got ${response.statusCode()}: ${response.body()}"
        }
        check(reportRow(report.publicId).status == "NEW")
    }

    @Test
    fun `administrator self-claim never widens ordinary out-of-scope SERVICE_USER visibility`() {
        val area = givenRoutedArea()
        val outsider = givenServiceUser() // no area grant at all
        val report = givenRoutedReport(area)

        val response = claim(bearerFor(outsider), report.publicId, 0)
        check(response.statusCode() == 404) { response.body() }
        check(errorCode(response) == "REPORT_NOT_FOUND")
    }

    @Test
    fun `an out-of-scope SERVICE_USER cannot claim - hidden as 404`() {
        val area = givenRoutedArea()
        val outsider = givenServiceUser() // no area grant at all
        val report = givenRoutedReport(area)

        val response = claim(bearerFor(outsider), report.publicId, 0)
        check(response.statusCode() == 404) { response.body() }
        check(errorCode(response) == "REPORT_NOT_FOUND")
    }

    @Test
    fun `a global SERVICE_USER can claim a report in any active normal area`() {
        val area = givenRoutedArea()
        val user = givenGlobalServiceUser()
        val report = givenRoutedReport(area)

        val response = claim(bearerFor(user), report.publicId, 0)
        check(response.statusCode() == 200) { response.body() }
    }

    @Test
    fun `an UNCLASSIFIED report can never be claimed, even by a global SERVICE_USER`() {
        val user = givenGlobalServiceUser()
        val publicId = givenUnclassifiedReport()

        val response = claim(bearerFor(user), publicId, 0)
        check(response.statusCode() == 404) { "a global SERVICE_USER never sees UNCLASSIFIED at all: ${response.body()}" }
        check(errorCode(response) == "REPORT_NOT_FOUND")
    }

    @Test
    fun `a report routed into a now-inactive area cannot be claimed by a normal-scope user`() {
        val area = givenRoutedArea(areaStatus = "INACTIVE")
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)

        val response = claim(bearerFor(user), report.publicId, 0)
        check(response.statusCode() == 404) { response.body() }
    }

    @Test
    fun `claiming an already-assigned report yields REPORT_ALREADY_ASSIGNED`() {
        val area = givenRoutedArea()
        val first = givenServiceUser()
        val second = givenServiceUser()
        grantArea(first.id, area.areaId)
        grantArea(second.id, area.areaId)
        val report = givenRoutedReport(area)

        check(claim(bearerFor(first), report.publicId, 0).statusCode() == 200)
        val loser = claim(bearerFor(second), report.publicId, 0)
        check(loser.statusCode() == 409) { loser.body() }
        check(errorCode(loser) == "REPORT_ALREADY_ASSIGNED")
    }

    @Test
    fun `claiming with a stale version while still NEW yields REPORT_STATE_CHANGED`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)

        val response = claim(bearerFor(user), report.publicId, 5) // real current version is 0
        check(response.statusCode() == 409) { response.body() }
        check(errorCode(response) == "REPORT_STATE_CHANGED")
    }

    @Test
    fun `claiming an already-archived report yields REPORT_ALREADY_ARCHIVED`() {
        val area = givenRoutedArea()
        val mod = givenTerritorialModerator()
        grantArea(mod.id, area.areaId)
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)
        check(close(bearerFor(mod), report.publicId, 0).statusCode() == 200)

        val response = claim(bearerFor(user), report.publicId, 0)
        check(response.statusCode() == 409) { response.body() }
        check(errorCode(response) == "REPORT_ALREADY_ARCHIVED")
    }

    // ---------------------------------------------------------------------- §31/§61 concurrency

    @Test
    fun `exactly one of eight concurrently racing SERVICE_USERs wins the claim`() {
        val area = givenRoutedArea()
        val users = (1..8).map { givenServiceUser().also { grantArea(it.id, area.areaId) } }
        val bearers = users.map { bearerFor(it) }
        val report = givenRoutedReport(area)

        val results = runConcurrently(8) { index -> claim(bearers[index], report.publicId, 0) }.map { it.getOrThrow() }

        val winners = results.filter { it.statusCode() == 200 }
        val losers = results.filter { it.statusCode() == 409 }
        check(winners.size == 1) { "expected exactly one winner, got ${winners.size}: ${results.map { it.statusCode() }}" }
        check(losers.size == 7) { "expected exactly seven losers, got ${losers.size}" }
        losers.forEach { check(errorCode(it) == "REPORT_ALREADY_ASSIGNED") { "loser got unexpected code: ${it.body()}" } }

        val row = reportRow(report.publicId)
        check(row.status == "IN_PROGRESS")
        check(row.workflowVersion == 1L) { "version must have incremented exactly once, got ${row.workflowVersion}" }
        val winnerId = users.first { it.serviceId.value == json(winners.single()).get("assignee").get("serviceId").asText() }.id
        check(row.assignedUserId == winnerId)

        check(openAssignmentCount(report.publicId) == 1) { "no duplicate open assignment episodes may exist" }
        val history = assignmentHistory(report.publicId)
        check(history.size == 1) { "no partial/duplicate history rows may exist, found ${history.size}" }
        check(history.single().assigneeUserId == winnerId)
        check(auditEventCount("REPORT_CLAIMED") == 1) { "exactly one REPORT_CLAIMED audit event may exist" }
    }

    @Test
    fun `a SERVICE_USER racing a territorial MODERATOR for the same report - exactly one wins, either role`() {
        val area = givenRoutedArea()
        val user = givenServiceUser().also { grantArea(it.id, area.areaId) }
        val mod = givenTerritorialModerator().also { grantArea(it.id, area.areaId) }
        val report = givenRoutedReport(area)

        val results = runConcurrently(2) { index ->
            claim(if (index == 0) bearerFor(user) else bearerFor(mod), report.publicId, 0)
        }.map { it.getOrThrow() }

        val winners = results.filter { it.statusCode() == 200 }
        check(winners.size == 1) { "expected exactly one winner regardless of role, got ${results.map { it.statusCode() }}" }
        check(reportRow(report.publicId).status == "IN_PROGRESS")
        check(openAssignmentCount(report.publicId) == 1)
        check(auditEventCount("REPORT_CLAIMED") == 1)
    }
}
