package hu.orszembejelento.backend.analytics.api

import hu.orszembejelento.backend.analytics.domain.AnalyticsAreaOption
import hu.orszembejelento.backend.analytics.domain.AnalyticsAreaOptions
import hu.orszembejelento.backend.analytics.domain.AnalyticsCategoryCount
import hu.orszembejelento.backend.analytics.domain.AnalyticsEventTypeCount
import hu.orszembejelento.backend.analytics.domain.AnalyticsPeriodRange
import hu.orszembejelento.backend.analytics.domain.AnalyticsStatusCounts
import hu.orszembejelento.backend.analytics.domain.AnalyticsSummary
import hu.orszembejelento.backend.analytics.domain.AnalyticsTrendPoint
import java.time.Instant
import java.time.LocalDate

data class AnalyticsPeriodResponse(val code: String, val from: Instant, val to: Instant, val zoneId: String) {
    companion object {
        fun from(range: AnalyticsPeriodRange) = AnalyticsPeriodResponse(range.code.name, range.from, range.to, range.zoneId)
    }
}

data class AnalyticsStatusCountsResponse(val new: Int, val inProgress: Int, val archived: Int) {
    companion object {
        fun from(counts: AnalyticsStatusCounts) = AnalyticsStatusCountsResponse(counts.new, counts.inProgress, counts.archived)
    }
}

data class AnalyticsTrendPointResponse(val localDate: LocalDate, val count: Int) {
    companion object {
        fun from(point: AnalyticsTrendPoint) = AnalyticsTrendPointResponse(point.localDate, point.count)
    }
}

data class AnalyticsCategoryCountResponse(val code: String, val displayName: String, val count: Int) {
    companion object {
        fun from(row: AnalyticsCategoryCount) = AnalyticsCategoryCountResponse(row.code, row.displayName, row.count)
    }
}

data class AnalyticsEventTypeCountResponse(val code: String, val displayName: String, val categoryCode: String, val count: Int) {
    companion object {
        fun from(row: AnalyticsEventTypeCount) = AnalyticsEventTypeCountResponse(row.code, row.displayName, row.categoryCode, row.count)
    }
}

/**
 * The analytics summary response (brief §16) — aggregate-only, never a report id, user id,
 * moderation actor or capability field (brief §9/§57).
 */
data class AnalyticsSummaryResponse(
    val period: AnalyticsPeriodResponse,
    val generatedAt: Instant,
    val totalReports: Int,
    val statusCounts: AnalyticsStatusCountsResponse,
    val trend: List<AnalyticsTrendPointResponse>,
    val categories: List<AnalyticsCategoryCountResponse>,
    val topEventTypes: List<AnalyticsEventTypeCountResponse>,
) {
    companion object {
        fun from(summary: AnalyticsSummary) = AnalyticsSummaryResponse(
            period = AnalyticsPeriodResponse.from(summary.period),
            generatedAt = summary.generatedAt,
            totalReports = summary.totalReports,
            statusCounts = AnalyticsStatusCountsResponse.from(summary.statusCounts),
            trend = summary.trend.map(AnalyticsTrendPointResponse::from),
            categories = summary.categories.map(AnalyticsCategoryCountResponse::from),
            topEventTypes = summary.topEventTypes.map(AnalyticsEventTypeCountResponse::from),
        )
    }
}

data class AnalyticsAreaOptionResponse(val id: String, val name: String, val active: Boolean) {
    companion object {
        fun from(option: AnalyticsAreaOption) = AnalyticsAreaOptionResponse(option.id.toString(), option.name, option.active)
    }
}

data class AnalyticsAreaOptionsResponse(val areas: List<AnalyticsAreaOptionResponse>, val canViewUnclassified: Boolean) {
    companion object {
        fun from(options: AnalyticsAreaOptions) =
            AnalyticsAreaOptionsResponse(options.areas.map(AnalyticsAreaOptionResponse::from), options.canViewUnclassified)
    }
}
