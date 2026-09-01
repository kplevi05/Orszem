package hu.orszem.core.network.dto

import kotlinx.serialization.Serializable

// --- Public catalog ---

@Serializable
data class EventTypeDto(
    val code: String,
    val categoryCode: String,
    val categoryLabel: String,
    val categorySortOrder: Int,
    val label: String,
    val description: String? = null,
    val sortOrder: Int,
)

@Serializable
data class EventTypeListResponseDto(val items: List<EventTypeDto>)

// --- Public report ---

@Serializable
data class PublicReportCreateRequestDto(
    val id: String,
    val eventTypeCode: String,
    val trainIdentifier: String,
    val settlement: String,
    val occurredAt: String,
)

@Serializable
data class PublicReportCreateResponseDto(
    val id: String,
    val status: String,
    val receivedAt: String,
)

// --- Service auth ---

@Serializable
data class ServiceLoginRequestDto(val username: String, val password: String)

@Serializable
data class ServiceLoginResponseDto(
    val accessToken: String,
    val tokenType: String,
    val expiresAt: String,
)

@Serializable
data class ServiceUserProfileDto(
    val id: String,
    val username: String,
    val displayName: String,
    val role: String,
    val capabilities: List<String> = emptyList(),
)

// --- Service reports ---

@Serializable
data class EventTypeEmbeddedDto(
    val code: String,
    val categoryCode: String,
    val categoryLabel: String,
    val label: String,
)

@Serializable
data class ServiceActorSummaryDto(val id: String, val displayName: String)

@Serializable
data class ServiceReportListItemDto(
    val id: String,
    val eventType: EventTypeEmbeddedDto,
    val trainIdentifier: String,
    val settlement: String,
    val occurredAt: String,
    val receivedAt: String,
    val status: String,
    val acceptedAt: String? = null,
    val archivedAt: String? = null,
)

@Serializable
data class ServiceReportDetailDto(
    val id: String,
    val eventType: EventTypeEmbeddedDto,
    val trainIdentifier: String,
    val settlement: String,
    val occurredAt: String,
    val receivedAt: String,
    val status: String,
    val acceptedAt: String? = null,
    val archivedAt: String? = null,
    val acceptedBy: ServiceActorSummaryDto? = null,
    val archivedBy: ServiceActorSummaryDto? = null,
)

@Serializable
data class ReportListResponseDto(
    val items: List<ServiceReportListItemDto>,
    val nextCursor: String? = null,
)

// --- Analytics ---

@Serializable
data class AnalyticsSummaryDto(
    val totalReports: Int,
    val todayReports: Int,
    val activeReports: Int,
    val archivedReports: Int,
    val generatedAt: String,
)

@Serializable
data class EventTypeStatDto(
    val eventTypeCode: String,
    val categoryCode: String,
    val categoryLabel: String,
    val label: String,
    val count: Int,
    val percentage: Double,
)

@Serializable
data class CategoryStatDto(
    val categoryCode: String,
    val categoryLabel: String,
    val count: Int,
    val percentage: Double,
)

@Serializable
data class SettlementStatDto(val settlement: String, val count: Int)

@Serializable
data class TrainStatDto(val trainIdentifier: String, val count: Int)

@Serializable
data class EventTypeStatisticsResponseDto(val items: List<EventTypeStatDto>, val generatedAt: String)

@Serializable
data class CategoryStatisticsResponseDto(val items: List<CategoryStatDto>, val generatedAt: String)

@Serializable
data class SettlementStatisticsResponseDto(val items: List<SettlementStatDto>, val generatedAt: String)

@Serializable
data class TrainStatisticsResponseDto(val items: List<TrainStatDto>, val generatedAt: String)

// --- Errors ---

@Serializable
data class FieldErrorDto(val field: String, val code: String, val message: String)

@Serializable
data class ProblemDetailsDto(
    val type: String? = null,
    val title: String? = null,
    val status: Int? = null,
    val code: String? = null,
    val detail: String? = null,
    val instance: String? = null,
    val correlationId: String? = null,
    val fieldErrors: List<FieldErrorDto> = emptyList(),
)
