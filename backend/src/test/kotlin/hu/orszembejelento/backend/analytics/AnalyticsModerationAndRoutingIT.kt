package hu.orszembejelento.backend.analytics

import hu.orszembejelento.backend.analytics.support.AnalyticsTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The two cross-phase proofs the Phase 11 brief calls out as mandatory (§27/§28): a
 * moderation delete/restore cycle never double-counts or under-counts, and moving a
 * RailwayLine (Phase 10) never rewrites which area a *past* report counts under — analytics
 * area grouping is [hu.orszembejelento.backend.analytics.infrastructure.JdbcAnalyticsRepository]'s
 * join to `report_routing_snapshots`, never to current `service_area_railway_lines`.
 */
class AnalyticsModerationAndRoutingIT : AnalyticsTestSupport() {

    /** Brief §27, exact 6-step sequence. */
    @Test
    fun `moderation delete then restore returns the count to its original value with no duplicate counting`() {
        val area = givenRoutedArea()
        val admin = givenSuperAdmin()
        val adminBearer = bearerFor(admin)

        givenRoutedReport(area)
        val target = givenRoutedReport(area)
        givenRoutedReport(area)

        // 1-2: N = 3.
        assertEquals(3, json(summary(adminBearer, areaId = area.areaId)).get("totalReports").asInt())

        // 3-4: delete -> N-1.
        val deleteResponse = delete(adminBearer, target.publicId, reportRow(target.publicId).workflowVersion, reason = "OTHER")
        check(deleteResponse.statusCode() == 204) { "fixture delete failed: ${deleteResponse.statusCode()} ${deleteResponse.body()}" }
        assertEquals(2, json(summary(adminBearer, areaId = area.areaId)).get("totalReports").asInt())

        // 5-6: SUPER_ADMIN restores -> N again, no duplicate.
        val restoreResponse = restore(adminBearer, target.publicId, reportRow(target.publicId).workflowVersion)
        check(restoreResponse.statusCode() == 204) { "fixture restore failed: ${restoreResponse.statusCode()} ${restoreResponse.body()}" }
        assertEquals(3, json(summary(adminBearer, areaId = area.areaId)).get("totalReports").asInt())
    }

    /** Brief §28, all 8 steps, plus the moderation delete/restore and status-transition steps from brief §63. */
    @Test
    fun `moving a railway line never regroups a report already routed to the old area`() {
        val areaA = givenRoutedArea()
        val areaB = givenRoutedArea()
        val admin = givenSuperAdmin()
        val adminBearer = bearerFor(admin)

        // 1-3: R1 routes into Area A.
        val r1 = givenRoutedReport(areaA)
        assertEquals(1, json(summary(adminBearer, areaId = areaA.areaId)).get("totalReports").asInt())

        // 4: move the line from A to B via the real Phase 10 endpoint.
        val moveResponse = assignLine(adminBearer, areaA.lineId, areaB.areaId, areaA.areaId)
        check(moveResponse.statusCode() == 204) { "fixture line move failed: ${moveResponse.statusCode()} ${moveResponse.body()}" }

        // 5: Area A still counts R1 - the move must not regroup a historical report.
        assertEquals(1, json(summary(adminBearer, areaId = areaA.areaId)).get("totalReports").asInt())

        // 6-7: R2, submitted after the move, routes into Area B. The settlement<->line
        // reference relation is unaffected by the Phase 10 area move (that relation and
        // ServiceArea assignment are two different things) - areaA.settlementId is the one
        // relation `insertRelation` actually established for areaA.lineId, so it must be used
        // here too, with the moved line still resolving to its new area (B) via the current
        // `service_area_railway_lines` mapping, not the settlement.
        val r2 = givenCreatedReport(settlementId = areaA.settlementId, railwayLineId = areaA.lineId)
        assertEquals(1, json(summary(adminBearer, areaId = areaB.areaId)).get("totalReports").asInt())

        // 8: Area A still counts only R1, never R2.
        assertEquals(1, json(summary(adminBearer, areaId = areaA.areaId)).get("totalReports").asInt())

        // brief §63 continuation: moderation-delete/restore R2, then transition its workflow state,
        // proving status breakdown changes while total stays constant throughout.
        val deleteResponse = delete(adminBearer, r2.publicId, reportRow(r2.publicId).workflowVersion, reason = "OTHER")
        check(deleteResponse.statusCode() == 204)
        assertEquals(0, json(summary(adminBearer, areaId = areaB.areaId)).get("totalReports").asInt())

        val restoreResponse = restore(adminBearer, r2.publicId, reportRow(r2.publicId).workflowVersion)
        check(restoreResponse.statusCode() == 204)
        var body = json(summary(adminBearer, areaId = areaB.areaId))
        assertEquals(1, body.get("totalReports").asInt())
        assertEquals(1, body.get("statusCounts").get("new").asInt())

        // claim is SERVICE_USER-only (ClaimReportUseCase's own policy) - SUPER_ADMIN cannot
        // self-claim, so a fixture SERVICE_USER with Area B access does it instead.
        val worker = givenServiceUser()
        grantArea(worker.id, areaB.areaId)
        val claimResponse = claim(bearerFor(worker), r2.publicId, reportRow(r2.publicId).workflowVersion)
        check(claimResponse.statusCode() == 200) { "fixture claim failed: ${claimResponse.statusCode()} ${claimResponse.body()}" }
        body = json(summary(adminBearer, areaId = areaB.areaId))
        assertEquals(1, body.get("totalReports").asInt())
        assertEquals(0, body.get("statusCounts").get("new").asInt())
        assertEquals(1, body.get("statusCounts").get("inProgress").asInt())

        val closeResponse = close(adminBearer, r2.publicId, reportRow(r2.publicId).workflowVersion)
        check(closeResponse.statusCode() == 200) { "fixture close failed: ${closeResponse.statusCode()} ${closeResponse.body()}" }
        body = json(summary(adminBearer, areaId = areaB.areaId))
        assertEquals(1, body.get("totalReports").asInt())
        assertEquals(0, body.get("statusCounts").get("inProgress").asInt())
        assertEquals(1, body.get("statusCounts").get("archived").asInt())

        check(r1.publicId != r2.publicId)
    }
}
