package hu.orszem.reporting.infrastructure

import hu.orszem.reporting.domain.PublicReportRepository
import hu.orszem.reporting.domain.ReportBusinessFields
import hu.orszem.reporting.domain.ReportStatus
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class JdbcPublicReportRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : PublicReportRepository {

    override fun findBusinessFieldsById(id: UUID): ReportBusinessFields? = jdbc.query(
        """
        SELECT event_type_id, train_identifier, settlement, occurred_at, received_at, status
        FROM reports
        WHERE id = :id
        """.trimIndent(),
        mapOf("id" to id),
    ) { rs, _ ->
        ReportBusinessFields(
            eventTypeId = rs.getObject("event_type_id", UUID::class.java),
            trainIdentifier = rs.getString("train_identifier"),
            settlement = rs.getString("settlement"),
            occurredAt = rs.getTimestamp("occurred_at").toInstant(),
            receivedAt = rs.getTimestamp("received_at").toInstant(),
            status = ReportStatus.valueOf(rs.getString("status")),
        )
    }.firstOrNull()

    override fun insertNew(
        id: UUID,
        eventTypeId: UUID,
        trainIdentifier: String,
        settlement: String,
        occurredAt: Instant,
        receivedAt: Instant,
    ): Boolean {
        val params = MapSqlParameterSource()
            .addValue("id", id)
            .addValue("eventTypeId", eventTypeId)
            .addValue("train", trainIdentifier)
            .addValue("settlement", settlement)
            .addValue("occurredAt", Timestamp.from(occurredAt))
            .addValue("receivedAt", Timestamp.from(receivedAt))
        val rows = jdbc.update(
            """
            INSERT INTO reports (id, event_type_id, train_identifier, settlement, occurred_at, received_at, status)
            VALUES (:id, :eventTypeId, :train, :settlement, :occurredAt, :receivedAt, 'NEW')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
            params,
        )
        return rows == 1
    }
}
