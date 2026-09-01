package hu.orszem.core.model

import java.time.Instant

/** An event category with its selectable types (Public App picker grouping). */
data class EventCategory(
    val code: String,
    val label: String,
    val sortOrder: Int,
    val eventTypes: List<EventType>,
)

data class EventType(
    val code: String,
    val label: String,
    val description: String?,
    val sortOrder: Int,
    val categoryCode: String,
    val categoryLabel: String,
    val categorySortOrder: Int,
)

enum class ReportStatus { NEW, IN_PROGRESS, ARCHIVED }

/** Draft the Public App builds before submitting. */
data class ReportDraft(
    val occurredAt: Instant,
    val trainIdentifier: String,
    val settlement: String,
    val eventType: EventType?,
)

data class SubmittedReport(
    val id: String,
    val status: ReportStatus,
    val receivedAt: Instant,
)

data class EmbeddedEventType(
    val code: String,
    val label: String,
    val categoryCode: String,
    val categoryLabel: String,
)

data class ServiceActor(
    val id: String,
    val displayName: String,
)

/** Row in the Service App active / archive lists. */
data class ServiceReportSummary(
    val id: String,
    val eventType: EmbeddedEventType,
    val trainIdentifier: String,
    val settlement: String,
    val occurredAt: Instant,
    val receivedAt: Instant,
    val status: ReportStatus,
    val acceptedAt: Instant?,
    val archivedAt: Instant?,
)

data class ServiceReportDetail(
    val id: String,
    val eventType: EmbeddedEventType,
    val trainIdentifier: String,
    val settlement: String,
    val occurredAt: Instant,
    val receivedAt: Instant,
    val status: ReportStatus,
    val acceptedAt: Instant?,
    val archivedAt: Instant?,
    val acceptedBy: ServiceActor?,
    val archivedBy: ServiceActor?,
)

data class ReportPage(
    val items: List<ServiceReportSummary>,
    val nextCursor: String?,
)

enum class ServiceCapability {
    REPORT_READ_ACTIVE,
    REPORT_ACCEPT,
    REPORT_ARCHIVE,
    ARCHIVE_READ,
    ANALYTICS_READ,

    /**
     * Demo v1.1: permanent deletion for pilot/test-data cleanup. Granted only by
     * deployments that enable demo deletion, so the UI must drive the delete
     * affordance from this capability rather than from the role.
     */
    REPORT_DELETE,
}

data class ServiceProfile(
    val id: String,
    val username: String,
    val displayName: String,
    val role: String,
    val capabilities: Set<ServiceCapability>,
)

data class ServiceSession(
    val accessToken: String,
    val expiresAt: Instant,
)

data class AnalyticsSummary(
    val totalReports: Int,
    val todayReports: Int,
    val activeReports: Int,
    val archivedReports: Int,
    val generatedAt: Instant,
)

data class EventTypeStat(
    val eventTypeCode: String,
    val label: String,
    val categoryCode: String,
    val categoryLabel: String,
    val count: Int,
    val percentage: Double,
)

data class CategoryStat(
    val categoryCode: String,
    val categoryLabel: String,
    val count: Int,
    val percentage: Double,
)

data class SettlementStat(val settlement: String, val count: Int)
data class TrainStat(val trainIdentifier: String, val count: Int)
