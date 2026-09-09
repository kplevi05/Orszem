package hu.orszembejelento.backend.reportworkflow

import hu.orszembejelento.backend.reportworkflow.support.ReportWorkflowTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/** Reassign (brief §38-44, §60) against real PostgreSQL, plus the two required races (§63, §64). */
@Import(AbstractAuthIntegrationTest.Containers::class)
class ReassignReportIT : ReportWorkflowTestSupport() {

    @Test
    fun `a territorial moderator can reassign within their own area`() {
        val area = givenRoutedArea()
        val mod = givenTerritorialModerator()
        grantArea(mod.id, area.areaId)
        val original = givenServiceUser()
        val target = givenServiceUser()
        grantArea(original.id, area.areaId)
        grantArea(target.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(original), report.publicId, 0).statusCode() == 200)

        val response = reassign(bearerFor(mod), report.publicId, 1, target.serviceId)
        check(response.statusCode() == 200) { response.body() }
        val body = json(response)
        check(body.get("status").asText() == "IN_PROGRESS")
        check(body.get("assignee").get("serviceId").asText() == target.serviceId.value)
        check(body.get("workflowVersion").asLong() == 2L)

        val row = reportRow(report.publicId)
        check(row.assignedUserId == target.id && row.status == "IN_PROGRESS")
        val history = assignmentHistory(report.publicId)
        check(history.size == 2)
        check(history[0].endReason == "REASSIGNED" && history[0].endedAt != null)
        check(history[1].assigneeUserId == target.id && history[1].endedAt == null)
        check(openAssignmentCount(report.publicId) == 1)
        check(auditEventCount("REPORT_REASSIGNED") == 1)
    }

    @Test
    fun `a global moderator can reassign across areas`() {
        val area = givenRoutedArea()
        val mod = givenGlobalModerator()
        val original = givenServiceUser()
        val target = givenGlobalServiceUser()
        grantArea(original.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(original), report.publicId, 0).statusCode() == 200)

        val response = reassign(bearerFor(mod), report.publicId, 1, target.serviceId)
        check(response.statusCode() == 200) { response.body() }
    }

    @Test
    fun `SUPER_ADMIN can reassign any report`() {
        val area = givenRoutedArea()
        val admin = givenSuperAdmin()
        val original = givenServiceUser()
        val target = givenServiceUser()
        grantArea(original.id, area.areaId)
        grantArea(target.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(original), report.publicId, 0).statusCode() == 200)

        val response = reassign(bearerFor(admin), report.publicId, 1, target.serviceId)
        check(response.statusCode() == 200) { response.body() }
    }

    @Test
    fun `a SERVICE_USER can never reassign`() {
        val area = givenRoutedArea()
        val original = givenServiceUser()
        val target = givenServiceUser()
        val actor = givenServiceUser()
        grantArea(original.id, area.areaId)
        grantArea(target.id, area.areaId)
        grantArea(actor.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(original), report.publicId, 0).statusCode() == 200)

        val response = reassign(bearerFor(actor), report.publicId, 1, target.serviceId)
        check(response.statusCode() == 403) { response.body() }
        check(errorCode(response) == "REPORT_WORKFLOW_FORBIDDEN")
    }

    @Test
    fun `a MODERATOR or SUPER_ADMIN target is rejected as an invalid assignee`() {
        val area = givenRoutedArea()
        val mod = givenTerritorialModerator()
        grantArea(mod.id, area.areaId)
        val original = givenServiceUser()
        grantArea(original.id, area.areaId)
        val otherMod = givenTerritorialModerator()
        val report = givenRoutedReport(area)
        check(claim(bearerFor(original), report.publicId, 0).statusCode() == 200)

        val response = reassign(bearerFor(mod), report.publicId, 1, otherMod.serviceId)
        check(response.statusCode() == 400) { response.body() }
        check(errorCode(response) == "INVALID_ASSIGNEE")
    }

    @Test
    fun `a deactivated target is rejected as an invalid assignee`() {
        val area = givenRoutedArea()
        val mod = givenTerritorialModerator()
        grantArea(mod.id, area.areaId)
        val original = givenServiceUser()
        grantArea(original.id, area.areaId)
        val deactivated = givenUser(role = hu.orszembejelento.backend.identity.domain.UserRole.SERVICE_USER, status = hu.orszembejelento.backend.identity.domain.UserStatus.DEACTIVATED)
        grantArea(deactivated.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(original), report.publicId, 0).statusCode() == 200)

        val response = reassign(bearerFor(mod), report.publicId, 1, deactivated.serviceId)
        check(response.statusCode() == 400) { response.body() }
        check(errorCode(response) == "INVALID_ASSIGNEE")
    }

    @Test
    fun `a target with no access to the report's area is rejected`() {
        val area = givenRoutedArea()
        val otherArea = givenRoutedArea()
        val mod = givenTerritorialModerator()
        grantArea(mod.id, area.areaId)
        val original = givenServiceUser()
        grantArea(original.id, area.areaId)
        val outOfAreaTarget = givenServiceUser()
        grantArea(outOfAreaTarget.id, otherArea.areaId) // access to a different area only
        val report = givenRoutedReport(area)
        check(claim(bearerFor(original), report.publicId, 0).statusCode() == 200)

        val response = reassign(bearerFor(mod), report.publicId, 1, outOfAreaTarget.serviceId)
        check(response.statusCode() == 400) { response.body() }
        check(errorCode(response) == "INVALID_ASSIGNEE")
    }

    @Test
    fun `a global SERVICE_USER target is accepted for any routed active area`() {
        val area = givenRoutedArea()
        val mod = givenTerritorialModerator()
        grantArea(mod.id, area.areaId)
        val original = givenServiceUser()
        grantArea(original.id, area.areaId)
        val globalTarget = givenGlobalServiceUser()
        val report = givenRoutedReport(area)
        check(claim(bearerFor(original), report.publicId, 0).statusCode() == 200)

        val response = reassign(bearerFor(mod), report.publicId, 1, globalTarget.serviceId)
        check(response.statusCode() == 200) { response.body() }
    }

    @Test
    fun `an UNCLASSIFIED report can never be assigned, even by a global moderator`() {
        val mod = givenGlobalModerator()
        val target = givenGlobalServiceUser()
        val publicId = givenUnclassifiedReport()

        // UNCLASSIFIED reports are always NEW or ARCHIVED in Phase 7 (never IN_PROGRESS) -
        // the specific conflict must still surface rather than a generic state-changed 409.
        val response = reassign(bearerFor(mod), publicId, 0, target.serviceId)
        check(response.statusCode() == 409) { response.body() }
        check(errorCode(response) == "REPORT_UNCLASSIFIED_CANNOT_ASSIGN")
    }

    @Test
    fun `reassigning to the current assignee is an idempotent no-op`() {
        val area = givenRoutedArea()
        val mod = givenTerritorialModerator()
        grantArea(mod.id, area.areaId)
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(user), report.publicId, 0).statusCode() == 200)

        val response = reassign(bearerFor(mod), report.publicId, 1, user.serviceId)
        check(response.statusCode() == 200) { response.body() }
        check(json(response).get("workflowVersion").asLong() == 1L) { "a same-target reassignment must not increment the version" }

        val row = reportRow(report.publicId)
        check(row.workflowVersion == 1L)
        check(assignmentHistory(report.publicId).size == 1) { "a same-target reassignment must not create a duplicate history row" }
        check(auditEventCount("REPORT_REASSIGNED") == 0) { "a same-target reassignment must not produce a noisy audit event" }
    }

    @Test
    fun `reassigning a NEW report yields REPORT_STATE_CHANGED`() {
        val area = givenRoutedArea()
        val mod = givenTerritorialModerator()
        grantArea(mod.id, area.areaId)
        val target = givenServiceUser()
        grantArea(target.id, area.areaId)
        val report = givenRoutedReport(area)

        val response = reassign(bearerFor(mod), report.publicId, 0, target.serviceId)
        check(response.statusCode() == 409) { response.body() }
        check(errorCode(response) == "REPORT_STATE_CHANGED")
    }

    @Test
    fun `reassigning an already-archived report yields REPORT_ALREADY_ARCHIVED`() {
        val area = givenRoutedArea()
        val mod = givenTerritorialModerator()
        grantArea(mod.id, area.areaId)
        val target = givenServiceUser()
        grantArea(target.id, area.areaId)
        val report = givenRoutedReport(area)
        check(close(bearerFor(mod), report.publicId, 0).statusCode() == 200)

        val response = reassign(bearerFor(mod), report.publicId, 1, target.serviceId)
        check(response.statusCode() == 409) { response.body() }
        check(errorCode(response) == "REPORT_ALREADY_ARCHIVED")
    }

    @Test
    fun `reassigning with a stale version yields REPORT_STATE_CHANGED`() {
        val area = givenRoutedArea()
        val mod = givenTerritorialModerator()
        grantArea(mod.id, area.areaId)
        val original = givenServiceUser()
        val target = givenServiceUser()
        grantArea(original.id, area.areaId)
        grantArea(target.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(original), report.publicId, 0).statusCode() == 200)

        val response = reassign(bearerFor(mod), report.publicId, 0, target.serviceId) // real current version is 1
        check(response.statusCode() == 409) { response.body() }
        check(errorCode(response) == "REPORT_STATE_CHANGED")
    }

    // ------------------------------------------------------------------------ §63 concurrency

    @Test
    fun `a reassign and the current assignee's own action racing never leave an inconsistent final state`() {
        val area = givenRoutedArea()
        val mod = givenTerritorialModerator()
        grantArea(mod.id, area.areaId)
        val owner = givenServiceUser()
        val target = givenServiceUser()
        grantArea(owner.id, area.areaId)
        grantArea(target.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(owner), report.publicId, 0).statusCode() == 200)

        val ownerBearer = bearerFor(owner)
        val modBearer = bearerFor(mod)
        val results = runConcurrently(2) { index ->
            if (index == 0) returnToNew(ownerBearer, report.publicId, 1) else reassign(modBearer, report.publicId, 1, target.serviceId)
        }.map { it.getOrThrow() }

        val returnResult = results[0]
        val reassignResult = results[1]
        val succeeded = results.count { it.statusCode() == 200 }
        check(succeeded == 1) { "exactly one of return/reassign must win, got ${results.map { it.statusCode() }}" }

        val row = reportRow(report.publicId)
        if (returnResult.statusCode() == 200) {
            check(row.status == "NEW" && row.assignedUserId == null)
            check(reassignResult.statusCode() == 409)
        } else {
            check(row.status == "IN_PROGRESS" && row.assignedUserId == target.id)
            // The owner is no longer the visible assignee once reassignment has committed, so
            // their losing return is scope-hidden as 404 (brief §34), not a state-conflict 409 -
            // this is the same "no information gained" rule as any other stranger's IN_PROGRESS.
            check(returnResult.statusCode() == 404) { "expected a scope-hiding 404 once reassignment moved the assignee away from the returner, got ${returnResult.statusCode()}: ${returnResult.body()}" }
            check(errorCode(returnResult) == "REPORT_NOT_FOUND")
        }
        check(openAssignmentCount(report.publicId) <= 1) { "never more than one open episode" }
    }

    // ------------------------------------------------------------------------ §44/§64 concurrency

    @Test
    fun `a target losing area access right before the reassignment lock is never assigned based on stale scope`() {
        val area = givenRoutedArea()
        val mod = givenTerritorialModerator()
        grantArea(mod.id, area.areaId)
        val owner = givenServiceUser()
        grantArea(owner.id, area.areaId)
        val target = givenServiceUser()
        grantArea(target.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(owner), report.publicId, 0).statusCode() == 200)

        val modBearer = bearerFor(mod)
        val results = runConcurrently(2) { index ->
            if (index == 0) {
                reassign(modBearer, report.publicId, 1, target.serviceId)
            } else {
                // Revoking the target's own area access races the reassignment's target-lock step.
                serviceAreas.revokeArea(target.id, area.areaId)
                "revoked"
            }
        }.map { it.getOrThrow() }

        @Suppress("UNCHECKED_CAST")
        val reassignResult = results[0] as java.net.http.HttpResponse<String>

        val row = reportRow(report.publicId)
        if (reassignResult.statusCode() == 200) {
            // The reassignment's transaction re-read the target's scope after acquiring the
            // target-user lock and won the race against the revoke - target must genuinely
            // still be the current assignee.
            check(row.assignedUserId == target.id)
        } else {
            // The revoke won: reassignment must have rejected the now out-of-scope target,
            // never silently assigned based on the scope it read before locking.
            check(reassignResult.statusCode() == 400) { "expected INVALID_ASSIGNEE on a lost-scope target, got ${reassignResult.statusCode()}: ${reassignResult.body()}" }
            check(errorCode(reassignResult) == "INVALID_ASSIGNEE")
            check(row.assignedUserId == owner.id) { "the original assignment must be untouched when reassignment correctly rejects" }
        }
    }
}
