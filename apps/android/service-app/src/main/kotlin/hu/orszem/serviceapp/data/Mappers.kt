package hu.orszem.serviceapp.data

import hu.orszem.core.model.AnalyticsSummary
import hu.orszem.core.model.CategoryStat
import hu.orszem.core.model.EmbeddedEventType
import hu.orszem.core.model.EventTypeStat
import hu.orszem.core.model.ReportPage
import hu.orszem.core.model.ReportStatus
import hu.orszem.core.model.ServiceActor
import hu.orszem.core.model.ServiceReportDetail
import hu.orszem.core.model.ServiceReportSummary
import hu.orszem.core.model.SettlementStat
import hu.orszem.core.model.TrainStat
import hu.orszem.core.network.dto.AnalyticsSummaryDto
import hu.orszem.core.network.dto.CategoryStatisticsResponseDto
import hu.orszem.core.network.dto.EventTypeEmbeddedDto
import hu.orszem.core.network.dto.EventTypeStatisticsResponseDto
import hu.orszem.core.network.dto.ReportListResponseDto
import hu.orszem.core.network.dto.ServiceActorSummaryDto
import hu.orszem.core.network.dto.ServiceReportDetailDto
import hu.orszem.core.network.dto.ServiceReportListItemDto
import hu.orszem.core.network.dto.SettlementStatisticsResponseDto
import hu.orszem.core.network.dto.TrainStatisticsResponseDto
import java.time.Instant

private fun String.toStatus() = runCatching { ReportStatus.valueOf(this) }.getOrDefault(ReportStatus.NEW)
private fun String?.toInstantOrNull() = this?.let { runCatching { Instant.parse(it) }.getOrNull() }

internal fun EventTypeEmbeddedDto.toModel() = EmbeddedEventType(code, label, categoryCode, categoryLabel)
internal fun ServiceActorSummaryDto.toModel() = ServiceActor(id, displayName)

internal fun ServiceReportListItemDto.toModel() = ServiceReportSummary(
    id = id,
    eventType = eventType.toModel(),
    trainIdentifier = trainIdentifier,
    settlement = settlement,
    occurredAt = Instant.parse(occurredAt),
    receivedAt = Instant.parse(receivedAt),
    status = status.toStatus(),
    acceptedAt = acceptedAt.toInstantOrNull(),
    archivedAt = archivedAt.toInstantOrNull(),
)

internal fun ReportListResponseDto.toModel() = ReportPage(items.map { it.toModel() }, nextCursor)

internal fun ServiceReportDetailDto.toModel() = ServiceReportDetail(
    id = id,
    eventType = eventType.toModel(),
    trainIdentifier = trainIdentifier,
    settlement = settlement,
    occurredAt = Instant.parse(occurredAt),
    receivedAt = Instant.parse(receivedAt),
    status = status.toStatus(),
    acceptedAt = acceptedAt.toInstantOrNull(),
    archivedAt = archivedAt.toInstantOrNull(),
    acceptedBy = acceptedBy?.toModel(),
    archivedBy = archivedBy?.toModel(),
)

internal fun AnalyticsSummaryDto.toModel() = AnalyticsSummary(
    totalReports, todayReports, activeReports, archivedReports, Instant.parse(generatedAt),
)

internal fun EventTypeStatisticsResponseDto.toModel(): List<EventTypeStat> = items.map {
    EventTypeStat(it.eventTypeCode, it.label, it.categoryCode, it.categoryLabel, it.count, it.percentage)
}

internal fun CategoryStatisticsResponseDto.toModel(): List<CategoryStat> = items.map {
    CategoryStat(it.categoryCode, it.categoryLabel, it.count, it.percentage)
}

internal fun SettlementStatisticsResponseDto.toModel(): List<SettlementStat> = items.map {
    SettlementStat(it.settlement, it.count)
}

internal fun TrainStatisticsResponseDto.toModel(): List<TrainStat> = items.map { TrainStat(it.trainIdentifier, it.count) }
