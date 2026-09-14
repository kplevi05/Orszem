package hu.orszembejelento.backend.audit.application

import hu.orszembejelento.backend.audit.domain.AuditEventDetailView
import hu.orszembejelento.backend.audit.domain.AuditEventNotFoundException
import hu.orszembejelento.backend.audit.domain.AuditEventType
import hu.orszembejelento.backend.audit.domain.AuditEventTypeInvalidException
import hu.orszembejelento.backend.audit.domain.AuditListPage
import hu.orszembejelento.backend.audit.domain.AuditOptions
import hu.orszembejelento.backend.audit.domain.AuditPeriod
import hu.orszembejelento.backend.audit.domain.AuditPeriodCalculator
import hu.orszembejelento.backend.audit.domain.AuditPeriodInvalidException
import hu.orszembejelento.backend.audit.domain.AuditQueryFilter
import hu.orszembejelento.backend.audit.domain.AuditQueryInvalidException
import hu.orszembejelento.backend.audit.domain.AuditTargetType
import hu.orszembejelento.backend.audit.domain.AuditTargetTypeInvalidException
import hu.orszembejelento.backend.audit.infrastructure.JdbcAuditQueryRepository
import hu.orszembejelento.backend.auth.application.AuthenticatedActor
import java.time.Clock
import java.util.UUID
import org.springframework.stereotype.Service

/**
 * Read-only audit queries (Phase 12 brief) - SUPER_ADMIN only, enforced by [AuditActorLoader]
 * before any query runs. Reads `audit_events` through [JdbcAuditQueryRepository] and never
 * anything else; nothing here writes, so opening the audit trail can never itself become an
 * audited event (brief §3).
 */
@Service
class AuditQueryUseCase(
    private val repository: JdbcAuditQueryRepository,
    private val projector: AuditEventSafeProjector,
    private val actorLoader: AuditActorLoader,
    private val clock: Clock,
) {

    fun list(
        actor: AuthenticatedActor,
        periodCode: String?,
        eventTypeCode: String?,
        targetTypeCode: String?,
        query: String?,
        page: Int,
        size: Int,
    ): AuditListPage {
        actorLoader.load(actor)
        val period = parsePeriod(periodCode)
        val eventType = parseEventType(eventTypeCode)
        val targetType = parseTargetType(targetTypeCode)
        val safeQuery = parseQuery(query)
        val range = AuditPeriodCalculator.resolve(period, clock.instant())
        val filter = AuditQueryFilter(period = period, eventType = eventType, targetType = targetType, query = safeQuery)

        val raw = repository.findPage(range, filter, page, size)
        val areaNames = resolveAreaNames(raw.items.map { it })
        return AuditListPage(raw.items.map { projector.listItem(it, areaNames) }, raw.totalElements)
    }

    fun detail(actor: AuthenticatedActor, auditEventId: UUID): AuditEventDetailView {
        actorLoader.load(actor)
        val row = repository.findById(auditEventId) ?: throw AuditEventNotFoundException(auditEventId)
        val areaNames = resolveAreaNames(listOf(row))
        return projector.detail(row, areaNames)
    }

    fun options(actor: AuthenticatedActor): AuditOptions {
        actorLoader.load(actor)
        return AuditOptions(eventTypes = AuditEventType.entries, targetTypes = AuditTargetType.entries)
    }

    // ------------------------------------------------------------------------------- private

    /** One batch resolution for the whole page (brief §29) - never one lookup per row. */
    private fun resolveAreaNames(rows: List<hu.orszembejelento.backend.audit.infrastructure.AuditRawRow>): Map<UUID, String> {
        val ids = rows.flatMap(projector::embeddedServiceAreaIds).toSet()
        return repository.serviceAreaNamesByIds(ids)
    }

    private fun parsePeriod(raw: String?): AuditPeriod {
        if (raw == null) return AuditPeriod.LAST_30_DAYS
        return runCatching { AuditPeriod.valueOf(raw) }.getOrElse { throw AuditPeriodInvalidException(raw) }
    }

    private fun parseEventType(raw: String?): AuditEventType? =
        raw?.let { code -> runCatching { AuditEventType.valueOf(code) }.getOrElse { throw AuditEventTypeInvalidException(code) } }

    private fun parseTargetType(raw: String?): AuditTargetType? =
        raw?.let { code -> runCatching { AuditTargetType.valueOf(code) }.getOrElse { throw AuditTargetTypeInvalidException(code) } }

    /** Blank/absent is "no search" (brief §17); a non-blank query below [MIN_QUERY_LENGTH] is a validation error, not a silent no-op. */
    private fun parseQuery(raw: String?): String? {
        val trimmed = raw?.trim()
        if (trimmed.isNullOrEmpty()) return null
        if (trimmed.length < MIN_QUERY_LENGTH) throw AuditQueryInvalidException("query must be at least $MIN_QUERY_LENGTH characters.")
        return trimmed
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
        const val MAX_PAGE_SIZE = 100
        const val MIN_QUERY_LENGTH = 2
    }
}
