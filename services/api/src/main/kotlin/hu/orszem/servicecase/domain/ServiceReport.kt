package hu.orszem.servicecase.domain

import hu.orszem.reporting.domain.ReportStatus
import java.time.Instant
import java.util.UUID

data class EmbeddedEventType(
    val code: String,
    val categoryCode: String,
    val categoryLabel: String,
    val label: String,
)

data class ActorSummary(
    val id: UUID,
    val displayName: String,
)

/** Row in the active / archive list. */
data class ServiceReportListItem(
    val id: UUID,
    val eventType: EmbeddedEventType,
    val trainIdentifier: String,
    val settlement: String,
    val occurredAt: Instant,
    val receivedAt: Instant,
    val status: ReportStatus,
    val acceptedAt: Instant?,
    val archivedAt: Instant?,
)

/** Full case detail. */
data class ServiceReportDetailView(
    val id: UUID,
    val eventType: EmbeddedEventType,
    val trainIdentifier: String,
    val settlement: String,
    val occurredAt: Instant,
    val receivedAt: Instant,
    val status: ReportStatus,
    val acceptedAt: Instant?,
    val archivedAt: Instant?,
    val acceptedBy: ActorSummary?,
    val archivedBy: ActorSummary?,
)

data class Page<T>(
    val items: List<T>,
    val nextCursor: String?,
)

enum class TransitionOutcome { OK, NOT_FOUND, WRONG_STATE }
