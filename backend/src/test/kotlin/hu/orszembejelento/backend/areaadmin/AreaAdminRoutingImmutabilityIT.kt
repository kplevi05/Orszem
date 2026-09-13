package hu.orszembejelento.backend.areaadmin

import hu.orszembejelento.backend.areaadmin.support.AreaAdminTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/**
 * Phase 10 brief §2/§3/§33: the single most important Phase 10 test. Moving a RailwayLine's
 * ServiceArea mapping must never rewrite an existing report's routing snapshot - only
 * submissions that route *after* the move commits may see the new area.
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class AreaAdminRoutingImmutabilityIT : AreaAdminTestSupport() {

    @Test
    fun `moving a line's area leaves an existing report's snapshot on the old area, and only a later submission sees the new one`() {
        val admin = adminBearer()
        val a = givenRoutedArea()
        val b = givenRoutedArea()

        // 1-3: line L mapped to area A, submit R1, assert R1 snapshot = A.
        val r1 = givenCreatedReport(settlementId = a.settlementId, railwayLineId = a.lineId)
        assertEquals(a.areaId, routingSnapshotArea(r1.publicId))

        // 4: move L -> area B.
        assertEquals(204, assignLine(admin, a.lineId, b.areaId, a.areaId).statusCode())

        // 5-6: fetch R1, assert its snapshot is STILL A - never rewritten by the move.
        assertEquals(a.areaId, routingSnapshotArea(r1.publicId)) { "an existing report's routing snapshot must never be rewritten by a later configuration change" }

        // 7-8: submit R2 on the same settlement/line, assert it now routes to B.
        val r2 = givenCreatedReport(settlementId = a.settlementId, railwayLineId = a.lineId)
        assertEquals(b.areaId, routingSnapshotArea(r2.publicId))

        // R1 is unaffected by R2 either.
        assertEquals(a.areaId, routingSnapshotArea(r1.publicId))
    }

    @Test
    fun `renaming or deactivating an area never rewrites any existing report's routing snapshot`() {
        val admin = adminBearer()
        val area = givenRoutedArea()
        val report = givenRoutedReport(area)
        assertEquals(area.areaId, routingSnapshotArea(report.publicId))

        assertEquals(200, renameArea(admin, area.areaId, 0, "Atnevezett terulet ${System.nanoTime()}").statusCode())
        assertEquals(area.areaId, routingSnapshotArea(report.publicId))

        // Clear both deactivation blockers: unassign the line, then archive the report
        // (claim + close) so it stops counting as open operational work - never reroute it,
        // never moderation-delete it just to make room; this is the ordinary workflow path.
        assertEquals(204, unassignLine(admin, area.lineId, area.areaId).statusCode())
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val userBearer = bearerFor(user)
        check(claim(userBearer, report.publicId, 0).statusCode() == 200)
        check(close(userBearer, report.publicId, 1).statusCode() == 200)

        assertEquals(200, deactivateArea(admin, area.areaId, 2).statusCode())
        assertEquals(area.areaId, routingSnapshotArea(report.publicId)) { "the report keeps its original area id even though that area is now inactive - reports are never rerouted" }
    }

    @Test
    fun `unassigning a line does not touch any settlement-railway-line reference relation`() {
        val admin = adminBearer()
        val area = givenRoutedArea()
        val relationBefore = jdbc.sql("SELECT COUNT(*) FROM settlement_railway_lines WHERE railway_line_id = :id")
            .param("id", area.lineId).query(Int::class.java).single()

        assertEquals(204, unassignLine(admin, area.lineId, area.areaId).statusCode())

        val relationAfter = jdbc.sql("SELECT COUNT(*) FROM settlement_railway_lines WHERE railway_line_id = :id")
            .param("id", area.lineId).query(Int::class.java).single()
        assertEquals(relationBefore, relationAfter) { "Phase 10 must never alter reference-data rows" }

        val lineActiveAfter = jdbc.sql("SELECT active FROM railway_lines WHERE id = :id").param("id", area.lineId).query(Boolean::class.java).single()
        assertEquals(true, lineActiveAfter) { "unassign must never touch the line's own reference active flag" }
    }
}
