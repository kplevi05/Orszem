package hu.orszembejelento.service.analytics.data

import hu.orszembejelento.service.auth.data.AuthRepository
import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.common.data.apiCall

/** The four fixed reporting windows (brief §3) - frozen product vocabulary, never computed on Android (brief §36). */
enum class AnalyticsPeriod { TODAY, LAST_7_DAYS, LAST_30_DAYS, LAST_90_DAYS }

/**
 * The analytics summary/area-list filter (brief §36-38). [areaId] and [unclassifiedOnly] are
 * mutually exclusive - the UI itself never lets both be set at once (brief §15), matching the
 * backend's own validation.
 */
data class AnalyticsFilter(
    val period: AnalyticsPeriod = AnalyticsPeriod.LAST_30_DAYS,
    val areaId: String? = null,
    val unclassifiedOnly: Boolean = false,
    val categoryCode: String? = null,
) {
    val activeFacetCount: Int get() = listOfNotNull(areaId, categoryCode).size + (if (unclassifiedOnly) 1 else 0)
}

/**
 * The Phase 11 analytics surface every Statisztika screen/ViewModel depends on.
 *
 * An interface, not a class - mirrors [hu.orszembejelento.service.servicearea.data.AreaAdminRepository]'s
 * own reasoning: a test supplies a plain fake, production code only ever constructs
 * [DefaultAnalyticsRepository] via [hu.orszembejelento.service.auth.data.NetworkModule].
 */
interface AnalyticsRepository {
    suspend fun summary(filter: AnalyticsFilter): ApiResult<AnalyticsSummaryResponse>
    suspend fun areaOptions(): ApiResult<AnalyticsAreaOptionsResponse>
}

class DefaultAnalyticsRepository(
    private val api: AnalyticsApi,
    private val auth: AuthRepository,
) : AnalyticsRepository {

    override suspend fun summary(filter: AnalyticsFilter): ApiResult<AnalyticsSummaryResponse> =
        apiCall(auth) { bearer ->
            api.summary(
                bearer = bearer,
                period = filter.period.name,
                areaId = filter.areaId,
                unclassifiedOnly = filter.unclassifiedOnly.takeIf { it },
                categoryCode = filter.categoryCode,
            )
        }

    override suspend fun areaOptions(): ApiResult<AnalyticsAreaOptionsResponse> =
        apiCall(auth) { bearer -> api.areas(bearer) }
}
