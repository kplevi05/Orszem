package hu.orszembejelento.backend.audit.domain

import java.time.Instant
import java.util.UUID

/**
 * The five fixed audit reporting windows (Phase 12 brief §14) - frozen product vocabulary,
 * no arbitrary custom range. [ALL] has no lower bound at all, unlike Phase 11's analytics
 * periods (which never included an unbounded option).
 */
enum class AuditPeriod { TODAY, LAST_7_DAYS, LAST_30_DAYS, LAST_90_DAYS, ALL }

/**
 * One resolved audit query window (brief §14). [from] is null only for [AuditPeriod.ALL].
 * `to` is always "now" itself (the instant the query actually ran), mirroring Phase 11's own
 * inclusive-upper-bound reasoning - a row written at the exact query instant must still count.
 */
data class AuditPeriodRange(val code: AuditPeriod, val from: Instant?, val to: Instant, val zoneId: String)

/**
 * Server-side audit filters (brief §16-19). [query] is a safe, whitelisted-column search term
 * only - never raw metadata search (brief §17). Mutually independent: any combination of
 * [eventType]/[targetType]/[query]/[period] may be set at once.
 */
data class AuditQueryFilter(
    val period: AuditPeriod = AuditPeriod.LAST_30_DAYS,
    val eventType: AuditEventType? = null,
    val targetType: AuditTargetType? = null,
    val query: String? = null,
)

/** The actor of one audit row, already safely resolved (brief §9/§10) - never the internal user UUID. */
data class AuditActorIdentity(val serviceId: String?)

/**
 * The target of one audit row, already safely resolved (brief §11) - never the internal
 * target UUID. [targetType] is null only for a target type unknown to this build (brief §6) -
 * a forward-compatibility guard, not a state any of today's writers can produce.
 */
data class AuditTargetIdentity(val targetType: AuditTargetType?, val displayLabel: String?)

/**
 * One whitelisted, event-specific safe detail entry (brief §23). [code] is drawn from the
 * closed set [AuditDetailCode]; [value] has already been proven safe by
 * [hu.orszembejelento.backend.audit.application.AuditEventSafeProjector] before this type is
 * ever constructed - there is no path that lets raw stored metadata reach this type directly.
 */
data class AuditDetailItem(val code: AuditDetailCode, val value: String)

/**
 * The closed set of detail codes Android knows how to localize (brief §23/§69). Adding a new
 * value here is a deliberate, explicit decision - never an automatic consequence of a new audit
 * writer's metadata key.
 */
enum class AuditDetailCode {
    OLD_ROLE, NEW_ROLE,
    OLD_NAME, NEW_NAME,
    FROM_AREA, TO_AREA,
    AREA, AREAS,
    GLOBAL_ACCESS,
    FROM_STATUS, TO_STATUS,
    STATUS_BEFORE_DELETE, RESULTING_STATUS,
    PREVIOUS_ASSIGNEE, NEW_ASSIGNEE,
    REASON,
    REVOCATION_REASON,
    REVOKED_SESSIONS,
    RAILWAY_LINE,
    SOURCE,
    DATASET_VERSION, SETTLEMENTS_IMPORTED, RAILWAY_LINES_IMPORTED, MAPPINGS_IMPORTED,
}

/**
 * One row of the audit list (brief §21) - a compact projection, never the full detail array.
 * [eventType] is null only for an event type unknown to this build (brief §6/§27) - the
 * forward-compatibility guard, never reachable from today's closed writer set.
 */
data class AuditListItem(
    val auditEventId: UUID,
    val occurredAt: Instant,
    val eventType: AuditEventType?,
    val actor: AuditActorIdentity,
    val target: AuditTargetIdentity,
    /** A short, event-specific safe summary (brief §21) - at most a couple of [AuditDetailItem]s, never the full detail set. */
    val summary: List<AuditDetailItem>,
)

data class AuditListPage(val items: List<AuditListItem>, val totalElements: Int)

/** The full detail projection (brief §22/§23) - one event, entirely safe fields. */
data class AuditEventDetailView(
    val auditEventId: UUID,
    val occurredAt: Instant,
    val eventType: AuditEventType?,
    val actor: AuditActorIdentity,
    val target: AuditTargetIdentity,
    val details: List<AuditDetailItem>,
)

/** The stable codes/labels Android needs to build its filter UI (brief §20), never raw metadata keys. */
data class AuditOptions(val eventTypes: List<AuditEventType>, val targetTypes: List<AuditTargetType>)
