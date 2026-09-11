package hu.orszembejelento.backend.moderation.infrastructure

import hu.orszembejelento.backend.moderation.domain.ModerationEpisode
import hu.orszembejelento.backend.moderation.domain.ModerationReason
import hu.orszembejelento.backend.reports.domain.ReportStatus
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * `report_moderation_episodes`: the single source of truth for "is this report currently
 * moderation-deleted" everywhere in the backend (brief §6) - every caller here is expected
 * to already hold the target report's row lock (canonical order: REPORT first, brief §11),
 * exactly like [hu.orszembejelento.backend.reportworkflow.infrastructure.JdbcReportAssignmentRepository]
 * documents for `report_assignments`. A plain `INSERT` for [openEpisode] is deliberate for
 * the identical reason that repository gives for its own `openAssignment`: the report lock
 * plus the calling use case's own "not already deleted" check make a collision on
 * `ux_report_moderation_episodes_open_episode` impossible in correct operation.
 */
@Repository
class JdbcModerationRepository(private val jdbc: JdbcClient) {

    fun openEpisode(
        reportId: UUID,
        reason: ModerationReason,
        deletedByUserId: UUID,
        deletedAt: Instant,
        statusBeforeDelete: ReportStatus,
    ): ModerationEpisode {
        val id = UUID.randomUUID()
        jdbc.sql(
            """
            INSERT INTO report_moderation_episodes (id, report_id, reason, deleted_by_user_id, deleted_at, status_before_delete)
            VALUES (:id, :reportId, :reason, :deletedBy, :deletedAt, :statusBefore)
            """.trimIndent(),
        )
            .param("id", id)
            .param("reportId", reportId)
            .param("reason", reason.name)
            .param("deletedBy", deletedByUserId)
            .param("deletedAt", timestamp(deletedAt))
            .param("statusBefore", statusBeforeDelete.name)
            .update()

        return ModerationEpisode(id, reportId, reason, deletedByUserId, deletedAt, statusBeforeDelete, null, null)
    }

    /** Closes [reportId]'s currently-open episode, if one exists. Returns the number of rows updated (0 or 1). */
    fun closeOpenEpisode(reportId: UUID, restoredAt: Instant, restoredByUserId: UUID): Int =
        jdbc.sql(
            """
            UPDATE report_moderation_episodes
               SET restored_at = :restoredAt, restored_by_user_id = :restoredBy
             WHERE report_id = :reportId AND restored_at IS NULL
            """.trimIndent(),
        )
            .param("restoredAt", timestamp(restoredAt))
            .param("restoredBy", restoredByUserId)
            .param("reportId", reportId)
            .update()

    /** The single fact every ordinary-workflow and Public lookup asks (brief §12/§14). */
    fun hasOpenEpisode(reportId: UUID): Boolean =
        jdbc.sql("SELECT 1 FROM report_moderation_episodes WHERE report_id = :reportId AND restored_at IS NULL")
            .param("reportId", reportId)
            .query(Int::class.java)
            .optional()
            .isPresent

    fun findOpenEpisode(reportId: UUID): ModerationEpisode? =
        jdbc.sql("$SELECT_EPISODE WHERE report_id = :reportId AND restored_at IS NULL")
            .param("reportId", reportId)
            .query(::mapEpisode)
            .optional()
            .orElse(null)

    /** Full moderation history for one report, oldest episode first - mirrors [hu.orszembejelento.backend.reportworkflow.infrastructure.JdbcReportAssignmentRepository.findHistoryByReport]. */
    fun findHistoryByReport(reportId: UUID): List<ModerationEpisode> =
        jdbc.sql("$SELECT_EPISODE WHERE report_id = :reportId ORDER BY deleted_at ASC")
            .param("reportId", reportId)
            .query(::mapEpisode)
            .list()

    private fun mapEpisode(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = ModerationEpisode(
        id = rs.getObject("id", UUID::class.java),
        reportId = rs.getObject("report_id", UUID::class.java),
        reason = ModerationReason.valueOf(rs.getString("reason")),
        deletedByUserId = rs.getObject("deleted_by_user_id", UUID::class.java),
        deletedAt = rs.getTimestamp("deleted_at").toInstant(),
        statusBeforeDelete = ReportStatus.valueOf(rs.getString("status_before_delete")),
        restoredByUserId = rs.getObject("restored_by_user_id", UUID::class.java),
        restoredAt = rs.getTimestamp("restored_at")?.toInstant(),
    )

    private fun timestamp(instant: Instant): java.sql.Timestamp = java.sql.Timestamp.from(instant)

    private companion object {
        const val SELECT_EPISODE = """
            SELECT id, report_id, reason, deleted_by_user_id, deleted_at, status_before_delete,
                   restored_by_user_id, restored_at
              FROM report_moderation_episodes
        """
    }
}
