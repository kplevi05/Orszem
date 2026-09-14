package hu.orszembejelento.backend.audit.api

import hu.orszembejelento.backend.audit.domain.AuditDetailItem
import hu.orszembejelento.backend.audit.domain.AuditEventDetailView
import hu.orszembejelento.backend.audit.domain.AuditListItem
import hu.orszembejelento.backend.audit.domain.AuditListPage
import hu.orszembejelento.backend.audit.domain.AuditOptions
import java.time.Instant

/**
 * One whitelisted safe detail entry (brief §23) - [code] is one of the closed
 * [hu.orszembejelento.backend.audit.domain.AuditDetailCode] names; [value] has already passed
 * through [hu.orszembejelento.backend.audit.application.AuditEventSafeProjector]. Android maps
 * both to natural Hungarian - see brief §56/§69.
 */
data class AuditDetailItemResponse(val code: String, val value: String) {
    companion object {
        fun from(item: AuditDetailItem) = AuditDetailItemResponse(item.code.name, item.value)
    }
}

/**
 * One audit list row (brief §21). [eventType] and [targetType] are null only for a value this
 * build's enum cannot parse (brief §6) - Android renders that as a generic, safe "Rendszeresemény"
 * row rather than a raw code. Never the internal actor/target UUID - see brief §8/§9.
 */
data class AuditListItemResponse(
    val auditEventId: String,
    val occurredAt: Instant,
    val eventType: String?,
    val actorServiceId: String?,
    val targetType: String?,
    val targetDisplayLabel: String?,
    val summary: List<AuditDetailItemResponse>,
) {
    companion object {
        fun from(item: AuditListItem) = AuditListItemResponse(
            auditEventId = item.auditEventId.toString(),
            occurredAt = item.occurredAt,
            eventType = item.eventType?.name,
            actorServiceId = item.actor.serviceId,
            targetType = item.target.targetType?.name,
            targetDisplayLabel = item.target.displayLabel,
            summary = item.summary.map(AuditDetailItemResponse::from),
        )
    }
}

data class AuditListPageResponse(
    val items: List<AuditListItemResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
) {
    companion object {
        fun from(result: AuditListPage, page: Int, size: Int): AuditListPageResponse {
            val totalPages = if (size <= 0) 0 else (result.totalElements + size - 1) / size
            return AuditListPageResponse(result.items.map(AuditListItemResponse::from), page, size, result.totalElements, totalPages)
        }
    }
}

/** One event's full safe detail (brief §22/§23) - never the raw stored metadata object. */
data class AuditEventDetailResponse(
    val auditEventId: String,
    val occurredAt: Instant,
    val eventType: String?,
    val actorServiceId: String?,
    val targetType: String?,
    val targetDisplayLabel: String?,
    val details: List<AuditDetailItemResponse>,
) {
    companion object {
        fun from(view: AuditEventDetailView) = AuditEventDetailResponse(
            auditEventId = view.auditEventId.toString(),
            occurredAt = view.occurredAt,
            eventType = view.eventType?.name,
            actorServiceId = view.actor.serviceId,
            targetType = view.target.targetType?.name,
            targetDisplayLabel = view.target.displayLabel,
            details = view.details.map(AuditDetailItemResponse::from),
        )
    }
}

/** The stable codes Android's filter sheet is built from (brief §20) - never raw metadata keys. */
data class AuditOptionsResponse(val eventTypes: List<String>, val targetTypes: List<String>) {
    companion object {
        fun from(options: AuditOptions) = AuditOptionsResponse(
            eventTypes = options.eventTypes.map { it.name },
            targetTypes = options.targetTypes.map { it.name },
        )
    }
}
