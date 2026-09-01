package hu.orszem.audit

import java.util.UUID

/**
 * Security / workflow audit log port. Append-only. Never receives passwords,
 * tokens or Authorization headers.
 */
interface AuditPort {
    fun record(
        action: AuditAction,
        targetType: String,
        targetId: UUID?,
        actorUserId: UUID?,
        metadata: Map<String, Any?> = emptyMap(),
    )
}

enum class AuditAction {
    SERVICE_LOGIN_SUCCESS,
    SERVICE_LOGIN_FAILURE,
    REPORT_ACCEPTED,
    REPORT_ARCHIVED,
}
