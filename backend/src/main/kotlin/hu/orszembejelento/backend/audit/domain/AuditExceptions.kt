package hu.orszembejelento.backend.audit.domain

import java.util.UUID

/** The actor's role has no authority to call any audit-query endpoint at all - SUPER_ADMIN only (brief §2). */
class AuditForbiddenException : RuntimeException("audit query access is SUPER_ADMIN only")

/** No audit event exists with this id, visible or not - a closed, immutable table has no "hidden" rows either way. */
class AuditEventNotFoundException(val auditEventId: UUID) : RuntimeException("no such audit event: $auditEventId")

/** `period` did not parse to one of the five fixed period codes (brief §14/§32). */
class AuditPeriodInvalidException(val raw: String) : RuntimeException("invalid audit period: $raw")

/** `eventType` did not parse to one of the actual current backend event types (brief §18/§32). */
class AuditEventTypeInvalidException(val raw: String) : RuntimeException("invalid audit event type: $raw")

/** `targetType` did not parse to one of the actual current backend target types (brief §19/§32). */
class AuditTargetTypeInvalidException(val raw: String) : RuntimeException("invalid audit target type: $raw")

/** A non-blank `query` shorter than the minimum useful length (brief §17: 2 characters unless blank). Reuses the generic `VALIDATION_ERROR` code, mirroring `AnalyticsFilterInvalidException`. */
class AuditQueryInvalidException(message: String) : RuntimeException(message)
