package hu.orszembejelento.backend.reportworkflow.infrastructure

import hu.orszembejelento.backend.reportworkflow.domain.AssignmentEndReason
import hu.orszembejelento.backend.reportworkflow.domain.ReportAssignment
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * `report_assignments`: operational-ownership history (brief §4/§6/§48) — a different
 * question from `audit_events` ("who performed this mutation"), and never merged with it.
 *
 * Every method here is called only after the caller already holds the target report's row
 * lock (canonical order, brief §9) — a plain `INSERT` for [openAssignment] is deliberate,
 * not an oversight: unlike a service-ID or an idempotent area grant, a collision on the
 * partial unique index `ux_report_assignments_open_episode` can only happen if a caller
 * attempts to open a second episode for a report that already has one, which the report
 * lock plus the calling use case's own status check make impossible in correct operation.
 * If it ever happened anyway, that would be a real application bug the transaction should
 * roll back on, not a routine, gracefully-absorbed collision (contrast
 * [hu.orszembejelento.backend.scope.infrastructure.JdbcServiceAreaRepository.grantAreaIfAbsent]).
 */
@Repository
class JdbcReportAssignmentRepository(private val jdbc: JdbcClient) {

    fun openAssignment(
        reportId: UUID,
        assigneeUserId: UUID,
        assignedByUserId: UUID,
        assignedAt: Instant,
    ): ReportAssignment {
        val id = UUID.randomUUID()
        jdbc.sql(
            """
            INSERT INTO report_assignments (id, report_id, assignee_user_id, assigned_by_user_id, assigned_at)
            VALUES (:id, :reportId, :assignee, :assignedBy, :assignedAt)
            """.trimIndent(),
        )
            .param("id", id)
            .param("reportId", reportId)
            .param("assignee", assigneeUserId)
            .param("assignedBy", assignedByUserId)
            .param("assignedAt", timestamp(assignedAt))
            .update()

        return ReportAssignment(id, reportId, assigneeUserId, assignedByUserId, assignedAt, null, null, null)
    }

    /** Ends the currently-open episode for [reportId], if one exists. Returns the number of rows updated (0 or 1). */
    fun endOpenAssignment(
        reportId: UUID,
        endedAt: Instant,
        endedByUserId: UUID,
        reason: AssignmentEndReason,
    ): Int =
        jdbc.sql(
            """
            UPDATE report_assignments
               SET ended_at = :endedAt, ended_by_user_id = :endedBy, end_reason = :reason
             WHERE report_id = :reportId AND ended_at IS NULL
            """.trimIndent(),
        )
            .param("endedAt", timestamp(endedAt))
            .param("endedBy", endedByUserId)
            .param("reason", reason.name)
            .param("reportId", reportId)
            .update()

    fun findOpenAssignment(reportId: UUID): ReportAssignment? =
        jdbc.sql("$SELECT_ASSIGNMENT WHERE report_id = :reportId AND ended_at IS NULL")
            .param("reportId", reportId)
            .query(::mapAssignment)
            .optional()
            .orElse(null)

    /** Full history for one report, oldest episode first (brief §25). */
    fun findHistoryByReport(reportId: UUID): List<ReportAssignment> =
        jdbc.sql("$SELECT_ASSIGNMENT WHERE report_id = :reportId ORDER BY assigned_at ASC")
            .param("reportId", reportId)
            .query(::mapAssignment)
            .list()

    private fun mapAssignment(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = ReportAssignment(
        id = rs.getObject("id", UUID::class.java),
        reportId = rs.getObject("report_id", UUID::class.java),
        assigneeUserId = rs.getObject("assignee_user_id", UUID::class.java),
        assignedByUserId = rs.getObject("assigned_by_user_id", UUID::class.java),
        assignedAt = rs.getTimestamp("assigned_at").toInstant(),
        endedAt = rs.getTimestamp("ended_at")?.toInstant(),
        endedByUserId = rs.getObject("ended_by_user_id", UUID::class.java),
        endReason = rs.getString("end_reason")?.let(AssignmentEndReason::valueOf),
    )

    private fun timestamp(instant: Instant): java.sql.Timestamp = java.sql.Timestamp.from(instant)

    private companion object {
        const val SELECT_ASSIGNMENT = """
            SELECT id, report_id, assignee_user_id, assigned_by_user_id, assigned_at,
                   ended_at, ended_by_user_id, end_reason
              FROM report_assignments
        """
    }
}
