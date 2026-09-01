package hu.orszem.servicecase.infrastructure

import hu.orszem.reporting.domain.ReportStatus
import hu.orszem.servicecase.domain.ActorSummary
import hu.orszem.servicecase.domain.EmbeddedEventType
import hu.orszem.servicecase.domain.Page
import hu.orszem.servicecase.domain.ServiceReportDetailView
import hu.orszem.servicecase.domain.ServiceReportListItem
import hu.orszem.servicecase.domain.ServiceReportRepository
import hu.orszem.servicecase.domain.TransitionOutcome
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class JdbcServiceReportRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : ServiceReportRepository {

    private val listColumns = """
        r.id, r.train_identifier, r.settlement, r.occurred_at, r.received_at, r.status,
        r.accepted_at, r.archived_at,
        et.code AS et_code, ec.code AS ec_code, ec.label AS ec_label, et.label AS et_label
    """.trimIndent()

    override fun findActive(statuses: Set<ReportStatus>, cursor: String?, limit: Int): Page<ServiceReportListItem> {
        val params = MapSqlParameterSource()
            .addValue("statuses", statuses.map { it.name })
            .addValue("limitPlusOne", limit + 1)

        var keyset = ""
        if (cursor != null) {
            val c = ReportCursor.decodeActive(cursor) ?: return Page(emptyList(), null)
            keyset = """
                AND (
                    ((r.status = 'NEW') = false AND :curIsNew = true)
                    OR ((r.status = 'NEW') = :curIsNew AND (r.received_at < :curReceivedAt
                        OR (r.received_at = :curReceivedAt AND r.id < :curId)))
                )
            """.trimIndent()
            params.addValue("curIsNew", c.isNew)
            params.addValue("curReceivedAt", Timestamp.from(c.receivedAt))
            params.addValue("curId", c.id)
        }

        val rows = jdbc.query(
            """
            SELECT $listColumns
            FROM reports r
            JOIN event_types et ON et.id = r.event_type_id
            JOIN event_categories ec ON ec.id = et.category_id
            WHERE r.status IN (:statuses) $keyset
            ORDER BY (r.status = 'NEW') DESC, r.received_at DESC, r.id DESC
            LIMIT :limitPlusOne
            """.trimIndent(),
            params,
        ) { rs, _ -> rs.toListItem() }

        return paginate(rows, limit) { last ->
            ReportCursor.encodeActive(
                ReportCursor.Active(last.status == ReportStatus.NEW, last.receivedAt, last.id),
            )
        }
    }

    override fun findArchived(cursor: String?, limit: Int): Page<ServiceReportListItem> {
        val params = MapSqlParameterSource().addValue("limitPlusOne", limit + 1)

        var keyset = ""
        if (cursor != null) {
            val c = ReportCursor.decodeArchived(cursor) ?: return Page(emptyList(), null)
            keyset = "AND (r.archived_at < :curArchivedAt OR (r.archived_at = :curArchivedAt AND r.id < :curId))"
            params.addValue("curArchivedAt", Timestamp.from(c.archivedAt))
            params.addValue("curId", c.id)
        }

        val rows = jdbc.query(
            """
            SELECT $listColumns
            FROM reports r
            JOIN event_types et ON et.id = r.event_type_id
            JOIN event_categories ec ON ec.id = et.category_id
            WHERE r.status = 'ARCHIVED' $keyset
            ORDER BY r.archived_at DESC, r.id DESC
            LIMIT :limitPlusOne
            """.trimIndent(),
            params,
        ) { rs, _ -> rs.toListItem() }

        return paginate(rows, limit) { last ->
            ReportCursor.encodeArchived(ReportCursor.Archived(last.archivedAt!!, last.id))
        }
    }

    override fun findDetail(id: UUID): ServiceReportDetailView? = jdbc.query(
        """
        SELECT r.id, r.train_identifier, r.settlement, r.occurred_at, r.received_at, r.status,
               r.accepted_at, r.archived_at,
               et.code AS et_code, ec.code AS ec_code, ec.label AS ec_label, et.label AS et_label,
               acc.id AS acc_id, acc.display_name AS acc_name,
               arc.id AS arc_id, arc.display_name AS arc_name
        FROM reports r
        JOIN event_types et ON et.id = r.event_type_id
        JOIN event_categories ec ON ec.id = et.category_id
        LEFT JOIN users acc ON acc.id = r.accepted_by_user_id
        LEFT JOIN users arc ON arc.id = r.archived_by_user_id
        WHERE r.id = :id
        """.trimIndent(),
        mapOf("id" to id),
    ) { rs, _ ->
        ServiceReportDetailView(
            id = rs.getObject("id", UUID::class.java),
            eventType = rs.embeddedEventType(),
            trainIdentifier = rs.getString("train_identifier"),
            settlement = rs.getString("settlement"),
            occurredAt = rs.instant("occurred_at")!!,
            receivedAt = rs.instant("received_at")!!,
            status = ReportStatus.valueOf(rs.getString("status")),
            acceptedAt = rs.instant("accepted_at"),
            archivedAt = rs.instant("archived_at"),
            acceptedBy = rs.actor("acc_id", "acc_name"),
            archivedBy = rs.actor("arc_id", "arc_name"),
        )
    }.firstOrNull()

    override fun accept(id: UUID, actorUserId: UUID, now: Instant): TransitionOutcome = transition(
        id,
        """
        UPDATE reports
        SET status = 'IN_PROGRESS', accepted_at = :now, accepted_by_user_id = :actor, updated_at = :now
        WHERE id = :id AND status = 'NEW'
        """.trimIndent(),
        actorUserId, now,
    )

    override fun archive(id: UUID, actorUserId: UUID, now: Instant): TransitionOutcome = transition(
        id,
        """
        UPDATE reports
        SET status = 'ARCHIVED', archived_at = :now, archived_by_user_id = :actor, updated_at = :now
        WHERE id = :id AND status = 'IN_PROGRESS'
        """.trimIndent(),
        actorUserId, now,
    )

    /**
     * Demo v1.1 hard delete.
     *
     * `reports` is not referenced by any foreign key, so removing the row is safe;
     * `audit_events.target_id` has no FK either, which is exactly why the workflow
     * rows pointing at this report are removed in the same transaction — otherwise
     * they would survive as references to a report that no longer exists. The
     * deletion itself is audited separately by the use case, with target_id null.
     */
    @Transactional
    override fun deletePermanently(id: UUID): ReportStatus? {
        val status = jdbc.query(
            "SELECT status FROM reports WHERE id = :id FOR UPDATE",
            MapSqlParameterSource("id", id),
        ) { rs, _ -> ReportStatus.valueOf(rs.getString("status")) }.firstOrNull() ?: return null

        jdbc.update(
            "DELETE FROM audit_events WHERE target_type = 'REPORT' AND target_id = :id",
            MapSqlParameterSource("id", id),
        )
        jdbc.update("DELETE FROM reports WHERE id = :id", MapSqlParameterSource("id", id))
        return status
    }

    private fun transition(id: UUID, sql: String, actorUserId: UUID, now: Instant): TransitionOutcome {
        val updated = jdbc.update(
            sql,
            MapSqlParameterSource()
                .addValue("id", id)
                .addValue("actor", actorUserId)
                .addValue("now", Timestamp.from(now)),
        )
        if (updated == 1) return TransitionOutcome.OK
        val exists = jdbc.queryForList("SELECT 1 FROM reports WHERE id = :id", mapOf("id" to id)).isNotEmpty()
        return if (exists) TransitionOutcome.WRONG_STATE else TransitionOutcome.NOT_FOUND
    }

    private fun <T> paginate(rows: List<T>, limit: Int, cursorOf: (T) -> String): Page<T> {
        if (rows.size <= limit) return Page(rows, null)
        val page = rows.take(limit)
        return Page(page, cursorOf(page.last()))
    }

    private fun ResultSet.toListItem() = ServiceReportListItem(
        id = getObject("id", UUID::class.java),
        eventType = embeddedEventType(),
        trainIdentifier = getString("train_identifier"),
        settlement = getString("settlement"),
        occurredAt = instant("occurred_at")!!,
        receivedAt = instant("received_at")!!,
        status = ReportStatus.valueOf(getString("status")),
        acceptedAt = instant("accepted_at"),
        archivedAt = instant("archived_at"),
    )

    private fun ResultSet.embeddedEventType() = EmbeddedEventType(
        code = getString("et_code"),
        categoryCode = getString("ec_code"),
        categoryLabel = getString("ec_label"),
        label = getString("et_label"),
    )

    private fun ResultSet.actor(idColumn: String, nameColumn: String): ActorSummary? {
        val id = getObject(idColumn, UUID::class.java) ?: return null
        return ActorSummary(id, getString(nameColumn))
    }

    private fun ResultSet.instant(column: String): Instant? = getTimestamp(column)?.toInstant()
}
