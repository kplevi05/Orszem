package hu.orszembejelento.backend.audit.domain

import java.time.Instant
import java.util.UUID

/**
 * Security-sensitive events worth an immutable record.
 *
 * Deliberately a closed set of explicit domain events rather than free-form strings, so
 * the trail stays queryable and a typo cannot invent a new event type.
 *
 * Note what is absent: successful refresh rotation and failed login attempts. Rotation
 * happens every 15 minutes per active session and would bury the interesting rows in
 * noise; failed guesses are security logging, and auditing them would let an attacker
 * inflate the table at will.
 */
enum class AuditEventType {
    SUPER_ADMIN_CREATED,
    SUPER_ADMIN_PASSWORD_RESET,
    INITIAL_PASSWORD_CHANGED,
    PASSWORD_CHANGED,
    SESSION_CREATED,
    SESSION_REVOKED,
    LOGOUT_ALL,
    REFRESH_TOKEN_REUSE_DETECTED,
    REFERENCE_DATASET_IMPORTED,
}

enum class AuditActorType { USER, SYSTEM }

enum class AuditTargetType { USER, SESSION, REFERENCE_DATASET }

/**
 * One append-only audit row.
 *
 * [operationId] groups the rows produced by a single logical operation — a password change
 * that revokes four sessions writes several rows sharing one operation ID, so the whole
 * action can be reconstructed.
 *
 * [metadata] must never contain a password, password hash, temporary credential, access
 * token, refresh token or token hash.
 */
data class AuditEvent(
    val id: UUID,
    val operationId: UUID,
    val actorType: AuditActorType,
    val actorUserId: UUID?,
    val eventType: AuditEventType,
    val targetType: AuditTargetType,
    val targetId: UUID?,
    val metadata: Map<String, String>,
    val createdAt: Instant,
) {
    init {
        require((actorType == AuditActorType.USER) == (actorUserId != null)) {
            "a USER actor must identify the user, and a SYSTEM actor must not claim one"
        }
    }
}
