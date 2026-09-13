package hu.orszembejelento.service.analytics

import hu.orszembejelento.service.analytics.data.AnalyticsAreaOptionsResponse
import hu.orszembejelento.service.analytics.data.AnalyticsFilter
import hu.orszembejelento.service.analytics.data.AnalyticsRepository
import hu.orszembejelento.service.analytics.data.AnalyticsStatusCountsResponse
import hu.orszembejelento.service.analytics.data.AnalyticsSummaryResponse
import hu.orszembejelento.service.analytics.data.AnalyticsPeriodResponse
import hu.orszembejelento.service.common.data.ApiResult

/** A fully configurable test double, mirroring the fakes `ServiceAreaAdminListViewModelTest`/`AreaAdminRepository` fakes use. */
class FakeAnalyticsRepository(
    var summaryResult: ApiResult<AnalyticsSummaryResponse> = ApiResult.Success(defaultSummary()),
    var areaOptionsResult: ApiResult<AnalyticsAreaOptionsResponse> = ApiResult.Success(AnalyticsAreaOptionsResponse(emptyList(), false)),
) : AnalyticsRepository {

    var summaryCalls = 0
    var areaOptionsCalls = 0
    val seenFilters = mutableListOf<AnalyticsFilter>()

    override suspend fun summary(filter: AnalyticsFilter): ApiResult<AnalyticsSummaryResponse> {
        summaryCalls++
        seenFilters += filter
        return summaryResult
    }

    override suspend fun areaOptions(): ApiResult<AnalyticsAreaOptionsResponse> {
        areaOptionsCalls++
        return areaOptionsResult
    }

    companion object {
        fun defaultSummary(total: Int = 0, new: Int = 0, inProgress: Int = 0, archived: Int = 0) = AnalyticsSummaryResponse(
            period = AnalyticsPeriodResponse("LAST_30_DAYS", "2026-08-01T00:00:00Z", "2026-08-31T10:00:00Z", "Europe/Budapest"),
            generatedAt = "2026-08-31T10:00:00Z",
            totalReports = total,
            statusCounts = AnalyticsStatusCountsResponse(new, inProgress, archived),
            trend = emptyList(),
            categories = emptyList(),
            topEventTypes = emptyList(),
        )
    }
}
