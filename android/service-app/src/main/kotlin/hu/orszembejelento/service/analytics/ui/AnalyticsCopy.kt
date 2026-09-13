package hu.orszembejelento.service.analytics.ui

import androidx.annotation.StringRes
import hu.orszembejelento.service.R
import hu.orszembejelento.service.analytics.data.AnalyticsPeriod

/**
 * The frozen Hungarian label for one [AnalyticsPeriod] (brief §36: "Android labels may be
 * locally mapped because this is frozen product vocabulary"). Shared between
 * [AnalyticsFilterSheet] (the period chip choices) and [AnalyticsScreen] (the always-visible
 * current-scope summary) so the two never drift to different wording for the same period.
 */
@StringRes
internal fun periodLabelRes(period: AnalyticsPeriod): Int = when (period) {
    AnalyticsPeriod.TODAY -> R.string.analytics_period_today
    AnalyticsPeriod.LAST_7_DAYS -> R.string.analytics_period_last_7_days
    AnalyticsPeriod.LAST_30_DAYS -> R.string.analytics_period_last_30_days
    AnalyticsPeriod.LAST_90_DAYS -> R.string.analytics_period_last_90_days
}

internal fun periodChoices(): List<Pair<AnalyticsPeriod, Int>> = listOf(
    AnalyticsPeriod.TODAY,
    AnalyticsPeriod.LAST_7_DAYS,
    AnalyticsPeriod.LAST_30_DAYS,
    AnalyticsPeriod.LAST_90_DAYS,
).map { it to periodLabelRes(it) }
