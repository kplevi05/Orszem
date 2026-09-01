package hu.orszem.analytics.api

import hu.orszem.analytics.application.AnalyticsService
import hu.orszem.analytics.domain.CategoryStat
import hu.orszem.analytics.domain.EventTypeStat
import hu.orszem.analytics.domain.SettlementStat
import hu.orszem.analytics.domain.TrainStat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class AnalyticsSummaryResponse(
    val totalReports: Int,
    val todayReports: Int,
    val activeReports: Int,
    val archivedReports: Int,
    val generatedAt: Instant,
)

data class EventTypeStatResponse(
    val eventTypeCode: String,
    val categoryCode: String,
    val categoryLabel: String,
    val label: String,
    val count: Int,
    val percentage: Double,
)

data class CategoryStatResponse(
    val categoryCode: String,
    val categoryLabel: String,
    val count: Int,
    val percentage: Double,
)

data class SettlementStatResponse(val settlement: String, val count: Int)
data class TrainStatResponse(val trainIdentifier: String, val count: Int)

data class EventTypeStatisticsResponse(val items: List<EventTypeStatResponse>, val generatedAt: Instant)
data class CategoryStatisticsResponse(val items: List<CategoryStatResponse>, val generatedAt: Instant)
data class SettlementStatisticsResponse(val items: List<SettlementStatResponse>, val generatedAt: Instant)
data class TrainStatisticsResponse(val items: List<TrainStatResponse>, val generatedAt: Instant)

@RestController
@RequestMapping("/api/v1/service/analytics")
class AnalyticsController(
    private val analytics: AnalyticsService,
) {

    @GetMapping("/summary")
    fun summary(): AnalyticsSummaryResponse {
        val s = analytics.summary()
        return AnalyticsSummaryResponse(s.totalReports, s.todayReports, s.activeReports, s.archivedReports, analytics.generatedAt())
    }

    @GetMapping("/event-types")
    fun eventTypes() = EventTypeStatisticsResponse(analytics.eventTypeStats().map { it.toResponse() }, analytics.generatedAt())

    @GetMapping("/categories")
    fun categories() = CategoryStatisticsResponse(analytics.categoryStats().map { it.toResponse() }, analytics.generatedAt())

    @GetMapping("/settlements")
    fun settlements() = SettlementStatisticsResponse(analytics.settlementStats().map { SettlementStatResponse(it.settlement, it.count) }, analytics.generatedAt())

    @GetMapping("/trains")
    fun trains() = TrainStatisticsResponse(analytics.trainStats().map { TrainStatResponse(it.trainIdentifier, it.count) }, analytics.generatedAt())

    private fun EventTypeStat.toResponse() =
        EventTypeStatResponse(eventTypeCode, categoryCode, categoryLabel, label, count, percentage)

    private fun CategoryStat.toResponse() = CategoryStatResponse(categoryCode, categoryLabel, count, percentage)
}
