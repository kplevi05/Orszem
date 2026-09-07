package hu.orszembejelento.backend.audit.infrastructure

import hu.orszembejelento.backend.audit.domain.AuditActorType
import hu.orszembejelento.backend.audit.domain.AuditEvent
import hu.orszembejelento.backend.audit.domain.AuditEventType
import hu.orszembejelento.backend.audit.domain.AuditTargetType
import java.time.Clock
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * Append-only audit writer.
 *
 * There is intentionally no update or delete method: the trail is immutable by
 * construction, not merely by convention.
 *
 * Callers invoke this inside the same transaction as the state change being recorded, so
 * an audit row and the change it describes commit or roll back together. An audit trail
 * that can disagree with reality is worse than none.
 */
@Repository
class JdbcAuditRepository(
    private val jdbc: JdbcClient,
    private val clock: Clock,
) {

    fun record(
        operationId: UUID,
        actorType: AuditActorType,
        actorUserId: UUID?,
        eventType: AuditEventType,
        targetType: AuditTargetType,
        targetId: UUID?,
        metadata: Map<String, String> = emptyMap(),
    ): AuditEvent {
        val event = AuditEvent(
            id = UUID.randomUUID(),
            operationId = operationId,
            actorType = actorType,
            actorUserId = actorUserId,
            eventType = eventType,
            targetType = targetType,
            targetId = targetId,
            metadata = metadata,
            createdAt = clock.instant(),
        )

        jdbc.sql(
            """
            INSERT INTO audit_events (
                id, operation_id, actor_type, actor_user_id, event_type,
                target_type, target_id, metadata, created_at
            ) VALUES (
                :id, :operationId, :actorType, :actorUserId, :eventType,
                :targetType, :targetId, CAST(:metadata AS jsonb), :createdAt
            )
            """.trimIndent(),
        )
            .param("id", event.id)
            .param("operationId", event.operationId)
            .param("actorType", event.actorType.name)
            .param("actorUserId", event.actorUserId)
            .param("eventType", event.eventType.name)
            .param("targetType", event.targetType.name)
            .param("targetId", event.targetId)
            .param("metadata", toJson(event.metadata))
            .param("createdAt", java.sql.Timestamp.from(event.createdAt))
            .update()

        return event
    }

    /**
     * Minimal JSON encoding for a flat string map.
     *
     * Metadata is deliberately restricted to `Map<String, String>` rather than an arbitrary
     * object graph: it keeps the encoding trivial to audit, and it makes it much harder to
     * pass a whole domain object in and accidentally serialise a password hash or token
     * along with it.
     */
    private fun toJson(metadata: Map<String, String>): String =
        metadata.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "${quote(key)}:${quote(value)}"
        }

    private fun quote(value: String): String {
        val escaped = buildString(value.length + 2) {
            value.forEach { char ->
                when (char) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
                }
            }
        }
        return "\"$escaped\""
    }
}
