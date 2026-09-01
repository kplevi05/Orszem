package hu.orszem.audit.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import hu.orszem.audit.AuditAction
import hu.orszem.audit.AuditPort
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JdbcAuditRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : AuditPort {

    // Audit rows must survive even when the surrounding business transaction rolls back.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun record(
        action: AuditAction,
        targetType: String,
        targetId: UUID?,
        actorUserId: UUID?,
        metadata: Map<String, Any?>,
    ) {
        val params = MapSqlParameterSource()
            .addValue("id", UUID.randomUUID())
            .addValue("actor", actorUserId)
            .addValue("action", action.name)
            .addValue("targetType", targetType)
            .addValue("targetId", targetId)
            .addValue("occurredAt", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
            .addValue("metadata", objectMapper.writeValueAsString(metadata))
        jdbc.update(
            """
            INSERT INTO audit_events (id, actor_user_id, action, target_type, target_id, occurred_at, metadata_json)
            VALUES (:id, :actor, :action, :targetType, :targetId, :occurredAt, CAST(:metadata AS jsonb))
            """.trimIndent(),
            params,
        )
    }
}
