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
    fun `MODERATOR and SUPER_ADMIN cannot self-claim`() {
        val area = givenRoutedArea()
        val mod = givenTerritorialModerator()
        grantArea(mod.id, area.areaId)
        val admin = givenSuperAdmin()
        val reportForMod = givenRoutedReport(area)
        val reportForAdmin = givenRoutedReport(area)

        val modResponse = claim(bearerFor(mod), reportForMod.publicId, 0)
        val adminResponse = claim(bearerFor(admin), reportForAdmin.publicId, 0)

        check(modResponse.statusCode() == 403) { modResponse.body() }
        check(errorCode(modResponse) == "REPORT_WORKFLOW_FORBIDDEN")
        check(adminResponse.statusCode() == 403) { adminResponse.body() }
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
}
