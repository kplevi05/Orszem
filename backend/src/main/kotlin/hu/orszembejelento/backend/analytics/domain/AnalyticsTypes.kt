package hu.orszembejelento.backend.analytics.domain

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The four fixed reporting windows Phase 11 supports (brief §3). Deliberately closed: no
 * custom date range and no ALL_TIME, both explicit brief decisions (§3/§15).
 */
enum class AnalyticsPeriod { TODAY, LAST_7_DAYS, LAST_30_DAYS, LAST_90_DAYS }

/**
 * One resolved period, in `reports.submitted_at` terms (brief §2: submission time, never
 * `occurred_at`) — the inclusive window `[from, to]`. [from] is a local-calendar midnight
 * boundary in [zoneId] (brief §3, never device-local time and never a hardcoded UTC offset);
 * [to] is literally "backend now" (brief §3's own "-> backend now"), so a report submitted at
 * the exact instant a request is made is still included, not excluded by an off-by-nothing
 * exclusive boundary.
 */
data class AnalyticsPeriodRange(
    val code: AnalyticsPeriod,
    val from: Instant,
    val to: Instant,
    val zoneId: String = "Europe/Budapest",
    /** Every local calendar date the period covers, ascending — the trend's zero-fill list (brief §18). */
    val localDates: List<LocalDate>,
)

/**
 * The optional summary/area-list filter (brief §15/§21). [areaId] and [unclassifiedOnly]
 * are mutually exclusive (brief §15) — enforced by the use case, never silently resolved
 * here.
 */
data class AnalyticsFilter(
    val areaId: UUID? = null,
    val unclassifiedOnly: Boolean = false,
    val categoryCode: String? = null,
)

/** Current workflow-state breakdown of the reports included by the period/filter (brief §6/§16). */
data class AnalyticsStatusCounts(val new: Int, val inProgress: Int, val archived: Int) {
    val total: Int get() = new + inProgress + archived
}

/** One daily local-calendar bucket (brief §18) — always present in the response even when [count] is 0. */
data class AnalyticsTrendPoint(val localDate: LocalDate, val count: Int)

/** One category's report count for the period/filter (brief §19) — only categories with count > 0. */
data class AnalyticsCategoryCount(val code: String, val displayName: String, val count: Int)

/** One of the top 5 event types for the period/filter (brief §20). */
data class AnalyticsEventTypeCount(val code: String, val displayName: String, val categoryCode: String, val count: Int)

/**
 * The full analytics summary response (brief §16) — one coherent snapshot: [statusCounts]
 * sums to [totalReports] (brief §17), [trend] sums to [totalReports] (brief §18), and an
 * unfiltered [categories] sums to [totalReports] too (brief §59).
 */
data class AnalyticsSummary(
    val period: AnalyticsPeriodRange,
    val generatedAt: Instant,
    val totalReports: Int,
    val statusCounts: AnalyticsStatusCounts,
    val trend: List<AnalyticsTrendPoint>,
    val categories: List<AnalyticsCategoryCount>,
    val topEventTypes: List<AnalyticsEventTypeCount>,
)

/** One legal analytics-area choice for the current actor (brief §21) — never a fake `Besorolatlan`/`Minden terület` row. */
data class AnalyticsAreaOption(val id: UUID, val name: String, val active: Boolean)

/** The full role-scoped filter-option set (brief §21) — `Besorolatlan` is offered as [canViewUnclassified], never a synthetic area. */
data class AnalyticsAreaOptions(val areas: List<AnalyticsAreaOption>, val canViewUnclassified: Boolean)
