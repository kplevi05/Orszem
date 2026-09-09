package hu.orszembejelento.backend.reportworkflow

import hu.orszembejelento.backend.reportworkflow.support.ReportWorkflowTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import java.util.UUID
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/**
 * The transaction-boundary invariants the brief requires explicitly (§52-53), plus a genuine
 * transaction-rollback proof (§66): a mutation that fails partway through must leave no
 * partial state, not merely "the caller sees an error".
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class ReportWorkflowInvariantsIT : ReportWorkflowTestSupport() {

    @Test
    fun `a NEW report has no assignee and no open assignment episode`() {
        val area = givenRoutedArea()
        val report = givenRoutedReport(area)

        val row = reportRow(report.publicId)
        check(row.status == "NEW" && row.assignedUserId == null && row.archivedAt == null)
        check(openAssignmentCount(report.publicId) == 0)
        check(assignmentHistory(report.publicId).isEmpty())
    }

    @Test
    fun `an IN_PROGRESS report's assignee is always exactly the open assignment episode's assignee`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(user), report.publicId, 0).statusCode() == 200)

        val row = reportRow(report.publicId)
        check(row.status == "IN_PROGRESS" && row.assignedUserId == user.id)
        check(openAssignmentCount(report.publicId) == 1)
        val open = assignmentHistory(report.publicId).single { it.endedAt == null }
        check(open.assigneeUserId == row.assignedUserId) { "the report's current assignee must always equal the open episode's assignee" }
    }

    @Test
    fun `an ARCHIVED report has no assignee and no open assignment episode`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(user), report.publicId, 0).statusCode() == 200)
        check(close(bearerFor(user), report.publicId, 1).statusCode() == 200)

        val row = reportRow(report.publicId)
        check(row.status == "ARCHIVED" && row.assignedUserId == null && row.archivedAt != null)
        check(openAssignmentCount(report.publicId) == 0)
        // History remains - assignment history is never deleted (brief §6).
        check(assignmentHistory(report.publicId).size == 1)
    }

    @Test
    fun `any report's current assignee, whenever set, is always an ACTIVE SERVICE_USER`() {
        val area = givenRoutedArea()
        val original = givenServiceUser()
        val target = givenServiceUser()
        val mod = givenTerritorialModerator()
        grantArea(original.id, area.areaId)
        grantArea(target.id, area.areaId)
        grantArea(mod.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(original), report.publicId, 0).statusCode() == 200)
        check(reassign(bearerFor(mod), report.publicId, 1, target.serviceId).statusCode() == 200)

        val assignedRoles = jdbc.sql(
            "SELECT u.role, u.status FROM reports r JOIN users u ON u.id = r.assigned_user_id WHERE r.assigned_user_id IS NOT NULL",
        ).query { rs, _ -> rs.getString("role") to rs.getString("status") }.list()

        check(assignedRoles.isNotEmpty())
        assignedRoles.forEach { (role, status) ->
            check(role == "SERVICE_USER") { "a MODERATOR/SUPER_ADMIN must never be persisted as the current assignee, found role=$role" }
            check(status == "ACTIVE") { "an assignee must always be ACTIVE, found status=$status" }
        }
    }

    // -------------------------------------------------------------------------------- §66

    @Test
    fun `a mutation that fails partway through a transaction leaves no partial state`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)

        // Corrupt the invariant directly (bypassing the application, which never allows
        // this): a rogue already-open assignment episode for a report that is genuinely
        // still NEW. When claim() reaches its own `openAssignment` INSERT later in the same
        // transaction, the partial unique index ux_report_assignments_open_episode (brief
        // §6) rejects the second open row for the same report_id and the whole transaction
        // aborts - including the `updateWorkflowState` UPDATE that already ran moments
        // earlier in that same transaction.
        val reportId = internalReportId(report.publicId)
        val rogueAssignee = givenServiceUser()
        jdbc.sql(
            "INSERT INTO report_assignments (id, report_id, assignee_user_id, assigned_by_user_id, assigned_at) " +
                "VALUES (:id, :rid, :assignee, :assignee, now())",
        )
            .param("id", UUID.randomUUID())
            .param("rid", reportId)
            .param("assignee", rogueAssignee.id)
            .update()

        val response = claim(bearerFor(user), report.publicId, 0)
        check(response.statusCode() >= 500) { "expected the forced constraint violation to surface as a server error, got ${response.statusCode()}: ${response.body()}" }

        // The report row must be exactly as it was before the doomed transaction - status
        // still NEW, no assignee, version still 0 - proving the UPDATE that ran earlier in
        // the same transaction did not survive the later failure.
        val row = reportRow(report.publicId)
        check(row.status == "NEW") { "the status UPDATE must have been rolled back, found ${row.status}" }
        check(row.assignedUserId == null) { "the assignee UPDATE must have been rolled back" }
        check(row.workflowVersion == 0L) { "the version increment must have been rolled back, found ${row.workflowVersion}" }
        check(row.archivedAt == null)

        // Only the pre-existing rogue row survives - claim()'s own attempted insert did not.
        val history = assignmentHistory(report.publicId)
        check(history.size == 1) { "no partial second history row may have survived the rollback, found ${history.size}" }
        check(history.single().assigneeUserId == rogueAssignee.id)

        // And no audit event was ever committed for the doomed attempt.
        check(auditEventCount("REPORT_CLAIMED") == 0)
    }
}
