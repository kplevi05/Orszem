package hu.orszem.servicecase.api

import hu.orszem.servicecase.domain.Page
import hu.orszem.servicecase.domain.ServiceReportDetailView
import hu.orszem.servicecase.domain.ServiceReportListItem
import java.time.Instant

/** Matches OpenAPI `EventTypeEmbedded`. */
data class EventTypeEmbeddedResponse(
    val code: String,
    val categoryCode: String,
    val categoryLabel: String,
    val label: String,
)

/** Matches OpenAPI `ServiceActorSummary`. */
data class ServiceActorSummaryResponse(
    val id: String,
    val displayName: String,
)

/** Matches OpenAPI `ServiceReportListItem`. */
data class ServiceReportListItemResponse(
    val id: String,
    val eventType: EventTypeEmbeddedResponse,
    val trainIdentifier: String,
    val settlement: String,
    val occurredAt: Instant,
    val receivedAt: Instant,
    val status: String,
    val acceptedAt: Instant?,
    val archivedAt: Instant?,
)

/** Matches OpenAPI `ServiceReportDetail`. */
data class ServiceReportDetailResponse(
    val id: String,
    val eventType: EventTypeEmbeddedResponse,
    val trainIdentifier: String,
    val settlement: String,
    val occurredAt: Instant,
    val receivedAt: Instant,
    val status: String,
    val acceptedAt: Instant?,
    val archivedAt: Instant?,
    val acceptedBy: ServiceActorSummaryResponse?,
    val archivedBy: ServiceActorSummaryResponse?,
)

/** Matches OpenAPI `ReportListResponse`. */
data class ReportListResponse(
    val items: List<ServiceReportListItemResponse>,
    val nextCursor: String?,
)

fun ServiceReportListItem.toResponse() = ServiceReportListItemResponse(
    id = id.toString(),
    eventType = EventTypeEmbeddedResponse(eventType.code, eventType.categoryCode, eventType.categoryLabel, eventType.label),
    trainIdentifier = trainIdentifier,
    settlement = settlement,
    occurredAt = occurredAt,
    receivedAt = receivedAt,
    status = status.name,
    acceptedAt = acceptedAt,
    archivedAt = archivedAt,
)

fun Page<ServiceReportListItem>.toResponse() = ReportListResponse(items.map { it.toResponse() }, nextCursor)

fun ServiceReportDetailView.toResponse() = ServiceReportDetailResponse(
    id = id.toString(),
    eventType = EventTypeEmbeddedResponse(eventType.code, eventType.categoryCode, eventType.categoryLabel, eventType.label),
    trainIdentifier = trainIdentifier,
    settlement = settlement,
    occurredAt = occurredAt,
    receivedAt = receivedAt,
    status = status.name,
    acceptedAt = acceptedAt,
    archivedAt = archivedAt,
    acceptedBy = acceptedBy?.let { ServiceActorSummaryResponse(it.id.toString(), it.displayName) },
    archivedBy = archivedBy?.let { ServiceActorSummaryResponse(it.id.toString(), it.displayName) },
)
