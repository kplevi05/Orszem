package hu.orszembejelento.backend.reportworkflow

import hu.orszembejelento.backend.reportworkflow.support.ReportWorkflowTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource

/**
 * The Nationwide KSH Settlement Fallback's operational policy
 * (`orszem.workflow.unclassified-service-user-access-enabled`), end to end over the real
 * HTTP stack against PostgreSQL — matches brief items 4-6 and their required test matrix
 * (item 10) exactly: an ACTIVE SERVICE_USER's NEW/IN_PROGRESS/claim/return/close/reassign
 * working set over UNCLASSIFIED reports, nationwide and regardless of area grants, only
 * while this flag is on. [ReportWorkflowVisibilityIT] and [ReportWorkflowPolicyTest] already
 * prove the flag's `false` default preserves the strict prior behaviour byte-for-byte; this
 * class only ever asserts the *additional* surface the flag opens (plus one disabled-state
 * control test, for a belt-and-braces regression check under the identical HTTP stack).
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
@TestPropertySource(properties = ["orszem.workflow.unclassified-service-user-access-enabled=true"])
class NationwideUnclassifiedFallbackIT : ReportWorkflowTestSupport() {

    // ------------------------------------------------------------------------------ visibility

    @Test
    fun `an ACTIVE SERVICE_USER with zero area grants sees a NEW UNCLASSIFIED report nationwide`() {
        val user = givenServiceUser() // no grantArea call at all - zero area grants, deliberately
        val unclassifiedId = givenUnclassifiedReport()
        givenRoutedReport() // a routed report elsewhere - must not appear for this user either way

        val ids = json(newQueue(bearerFor(user))).get("items").asList().map { it.get("publicReportId").asText() }
        check(ids == listOf(unclassifiedId.toString())) { "expected only the nationwide UNCLASSIFIED report, got $ids" }
    }

    @Test
    fun `a SERVICE_USER sees their own claimed IN_PROGRESS UNCLASSIFIED report but not a stranger's`() {
        val owner = givenServiceUser()
        val stranger = givenServiceUser()
        val reportId = givenUnclassifiedReport()

        claim(bearerFor(owner), reportId, 0)

        val ownerView = json(inProgressQueue(bearerFor(owner))).get("items").asList().map { it.get("publicReportId").asText() }
        check(ownerView == listOf(reportId.toString()))

        val strangerView = json(inProgressQueue(bearerFor(stranger))).get("items").asList()
        check(strangerView.isEmpty()) { "a stranger SERVICE_USER must not see another's UNCLASSIFIED claim" }

        val strangerDetail = detail(bearerFor(stranger), reportId)
        check(strangerDetail.statusCode() == 404) { "an IN_PROGRESS UNCLASSIFIED report not assigned to the actor stays scope-hidden" }
    }

    @Test
    fun `the closer of an UNCLASSIFIED report can immediately see their own ARCHIVED confirmation`() {
        // Regression proof for the exact bug this phase's own live verification caught:
        // canViewReport must admit ARCHIVED nationwide, or the close endpoint's own
        // detail-response step 404s the very actor who just closed the report.
        val user = givenServiceUser()
        val reportId = givenUnclassifiedReport()
        claim(bearerFor(user), reportId, 0)

        val response = close(bearerFor(user), reportId, 1)
        check(response.statusCode() == 200) { "closing must succeed and return the closer's own confirmation, got ${response.statusCode()}: ${response.body()}" }
        check(json(response).get("status").asText() == "ARCHIVED")

        val anotherUser = givenServiceUser()
        val otherDetail = detail(bearerFor(anotherUser), reportId)
        check(otherDetail.statusCode() == 200) { "ARCHIVED UNCLASSIFIED visibility is nationwide under the flag, not ownership-only" }
    }

    // ----------------------------------------------------------------------------------- claim

    @Test
    fun `an ACTIVE SERVICE_USER with zero area grants can claim an UNCLASSIFIED report`() {
        val user = givenServiceUser()
        val reportId = givenUnclassifiedReport()

        val response = claim(bearerFor(user), reportId, 0)
        check(response.statusCode() == 200) { response.body() }
        check(reportRow(reportId).status == "IN_PROGRESS")
    }

    @Test
    fun `two SERVICE_USERs racing to claim the same UNCLASSIFIED report - exactly one wins`() {
        val a = givenServiceUser()
        val b = givenServiceUser()
        val reportId = givenUnclassifiedReport()

        val results = runConcurrently(2) { index ->
            val bearer = if (index == 0) bearerFor(a) else bearerFor(b)
            claim(bearer, reportId, 0)
        }

        val statusCodes = results.map { it.getOrThrow().statusCode() }.sorted()
        check(statusCodes == listOf(200, 409)) { "expected exactly one winner (200) and one conflict (409), got $statusCodes" }
        check(reportRow(reportId).status == "IN_PROGRESS")
        check(openAssignmentCount(reportId) == 1) { "exactly one open assignment must exist after the race" }
    }

    @Test
    fun `an inactive SERVICE_USER cannot claim an UNCLASSIFIED report even with the flag enabled`() {
        val user = givenServiceUser()
        val bearer = bearerFor(user)
        val reportId = givenUnclassifiedReport()

        httpDeactivate(adminBearer(), user)

        val response = claim(bearer, reportId, 0)
        // Mirrors AssigneeEligibilityCrossPhaseIT's identical two safe outcomes: either the
        // row-lock re-validation rejected it (403 - status no longer ACTIVE), or
        // deactivation's session revocation won an even earlier race (401) - never a
        // successful claim, and the fallback flag opens no third way past either gate.
        check(response.statusCode() == 403 || response.statusCode() == 401) {
            "a deactivated SERVICE_USER must gain no operational access through this flag, got ${response.statusCode()}: ${response.body()}"
        }
        check(reportRow(reportId).status == "NEW") { "the report must remain unclaimed" }
    }

    // ------------------------------------------------------------------------------ return/close

    @Test
    fun `return sends an UNCLASSIFIED report back to the nationwide shared pool, visible to every ACTIVE SERVICE_USER again`() {
        val owner = givenServiceUser()
        val otherUser = givenServiceUser()
        val reportId = givenUnclassifiedReport()

        claim(bearerFor(owner), reportId, 0)
        val returned = returnToNew(bearerFor(owner), reportId, 1)
        check(returned.statusCode() == 200) { returned.body() }
        check(reportRow(reportId).status == "NEW")

        val poolView = json(newQueue(bearerFor(otherUser))).get("items").asList().map { it.get("publicReportId").asText() }
        check(poolView.contains(reportId.toString())) { "a returned UNCLASSIFIED report must reappear in the nationwide pool" }
    }

    @Test
    fun `close from IN_PROGRESS archives an UNCLASSIFIED report exactly like the normal workflow`() {
        val user = givenServiceUser()
        val reportId = givenUnclassifiedReport()
        claim(bearerFor(user), reportId, 0)

        val response = close(bearerFor(user), reportId, 1)
        check(response.statusCode() == 200) { response.body() }
        val row = reportRow(reportId)
        check(row.status == "ARCHIVED")
        check(row.archivedAt != null)
        check(row.assignedUserId == null)
    }

    // --------------------------------------------------------------------------------- reassign

    @Test
    fun `MODERATOR SUPER_ADMIN may reassign an UNCLASSIFIED report to any ACTIVE SERVICE_USER - no area check`() {
        val original = givenServiceUser()
        val target = givenServiceUser() // deliberately zero area grants
        val admin = givenSuperAdmin()
        val reportId = givenUnclassifiedReport()

        claim(bearerFor(original), reportId, 0)
        val response = reassign(bearerFor(admin), reportId, 1, target.serviceId)
        check(response.statusCode() == 200) { response.body() }
        check(reportRow(reportId).assignedUserId == target.id)
    }

    @Test
    fun `reassign of an UNCLASSIFIED report rejects a target with no area access requirement bypassed inappropriately - still requires ACTIVE SERVICE_USER`() {
        val original = givenServiceUser()
        val inactiveTarget = givenServiceUser()
        val admin = givenSuperAdmin()
        val reportId = givenUnclassifiedReport()
        claim(bearerFor(original), reportId, 0)
        httpDeactivate(adminBearer(), inactiveTarget)

        val response = reassign(bearerFor(admin), reportId, 1, inactiveTarget.serviceId)
        check(response.statusCode() == 400) { "an inactive target must still be refused, got ${response.statusCode()}: ${response.body()}" }
        check(errorCode(response) == "INVALID_ASSIGNEE")
    }

    // ------------------------------------------------------------------------ disabled-state control

    @Test
    fun `routed-report authorization is completely unaffected by the flag - a territorial SERVICE_USER still cannot see another area's report`() {
        val ownArea = givenRoutedArea()
        val otherArea = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, ownArea.areaId)

        givenRoutedReport(otherArea)

        val ids = json(newQueue(bearerFor(user))).get("items").asList().map { it.get("publicReportId").asText() }
        check(ids.isEmpty()) { "the fallback flag must never broaden ordinary routed-report authorization" }
    }
}
