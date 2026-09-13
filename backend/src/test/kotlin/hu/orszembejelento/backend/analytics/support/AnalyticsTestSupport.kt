package hu.orszembejelento.backend.analytics.support

import hu.orszembejelento.backend.areaadmin.support.AreaAdminTestSupport
import java.net.http.HttpResponse
import java.util.UUID

/**
 * Shared HTTP helpers for the Phase 11 analytics test battery. Extends
 * [AreaAdminTestSupport] (not just `ReportWorkflowTestSupport`/`ModerationTestSupport`
 * directly) so this module has all three fixture families a full cross-phase analytics
 * proof needs in one inheritance chain: Phase 7 routed-report fixtures and role users,
 * Phase 9 moderation delete/restore, and Phase 10 RailwayLine assign/move/unassign plus area
 * activate/deactivate — brief §28/§63 exercise all three in a single test.
 */
abstract class AnalyticsTestSupport : AreaAdminTestSupport() {

    protected fun summary(
        bearer: String,
        period: String? = null,
        areaId: UUID? = null,
        unclassifiedOnly: Boolean? = null,
        categoryCode: String? = null,
    ): HttpResponse<String> {
        val params = buildList {
            period?.let { add("period=$it") }
            areaId?.let { add("areaId=$it") }
            unclassifiedOnly?.let { add("unclassifiedOnly=$it") }
            categoryCode?.let { add("categoryCode=$it") }
        }
        val qs = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        return get("/api/v1/service/analytics/summary$qs", bearer)
    }

    protected fun analyticsAreas(bearer: String): HttpResponse<String> = get("/api/v1/service/analytics/areas", bearer)
}
