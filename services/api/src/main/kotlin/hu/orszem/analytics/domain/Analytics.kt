package hu.orszem.analytics.domain

data class AnalyticsSummary(
    val totalReports: Int,
    val todayReports: Int,
    val activeReports: Int,
    val archivedReports: Int,
)

data class EventTypeStat(
    val eventTypeCode: String,
    val categoryCode: String,
    val categoryLabel: String,
    val label: String,
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

/**
 * Deterministic, read-only aggregations over the canonical report data.
 * No LLM / NLP. `today` uses the configured business calendar day.
 */
interface AnalyticsQueryPort {
    fun summary(): AnalyticsSummary
    fun eventTypeStats(): List<EventTypeStat>
    fun categoryStats(): List<CategoryStat>
    fun settlementStats(): List<SettlementStat>
    fun trainStats(): List<TrainStat>
}
