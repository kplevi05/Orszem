package hu.orszembejelento.backend.areaadmin

import hu.orszembejelento.backend.areaadmin.support.AreaAdminTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import
import java.util.UUID

/** Phase 10 brief §17-24/§69 "Railway lines" matrix: list/search/paging, assign/move/unassign, and every conflict. */
@Import(AbstractAuthIntegrationTest.Containers::class)
class RailwayLineAdminIT : AreaAdminTestSupport() {

    @Test
    fun `assigning an unassigned active line to an active area succeeds and bumps the target's version`() {
        val admin = adminBearer()
        val area = givenRoutedArea()
        val freeLine = insertLine("L${(100000..999999).random()}")

        val response = assignLine(admin, freeLine, area.areaId, null)
        assertEquals(204, response.statusCode())
        assertEquals(area.areaId, currentAreaOfLine(freeLine))
        assertEquals(1L, areaAdminVersion(area.areaId))
    }

    @Test
    fun `assigning to an inactive target area is rejected`() {
        val admin = adminBearer()
        val area = givenRoutedArea()
        unassignLine(admin, area.lineId, area.areaId)
        assertEquals(200, deactivateArea(admin, area.areaId, 1).statusCode())
        val freeLine = insertLine("L${(100000..999999).random()}")

        val response = assignLine(admin, freeLine, area.areaId, null)
        assertEquals(409, response.statusCode())
        assertEquals("TARGET_SERVICE_AREA_INACTIVE", errorCode(response))
        assertNull(currentAreaOfLine(freeLine))
    }

    @Test
    fun `an inactive railway line reference row cannot receive a NEW assignment`() {
        val admin = adminBearer()
        val area = givenRoutedArea()
        val inactiveLine = insertLine("L${(100000..999999).random()}", active = false)

        val response = assignLine(admin, inactiveLine, area.areaId, null)
        assertEquals(409, response.statusCode())
        assertEquals("RAILWAY_LINE_INACTIVE", errorCode(response))
    }

    @Test
    fun `an existing legacy mapping of an inactive line may still be unassigned for cleanup`() {
        val admin = adminBearer()
        val area = givenRoutedArea()
        // area.lineId is active and mapped by givenRoutedArea; deactivate its reference row
        // directly (a real dataset-import deactivation, not something Phase 10 ever does).
        jdbc.sql("UPDATE railway_lines SET active = FALSE WHERE id = :id").param("id", area.lineId).update()

        val response = unassignLine(admin, area.lineId, area.areaId)
        assertEquals(204, response.statusCode())
        assertNull(currentAreaOfLine(area.lineId))
    }

    @Test
    fun `move re-points a line from area A to area B atomically and bumps both versions`() {
        val admin = adminBearer()
        val a = givenRoutedArea()
        val b = givenRoutedArea()

        val response = assignLine(admin, a.lineId, b.areaId, a.areaId)
        assertEquals(204, response.statusCode())
        assertEquals(b.areaId, currentAreaOfLine(a.lineId))
        assertEquals(1, mappingCount(a.lineId)) { "never a moment with two committed mappings for the same line" }
        assertEquals(1L, areaAdminVersion(a.areaId))
        assertEquals(1L, areaAdminVersion(b.areaId))
    }

    @Test
    fun `assigning a line to the area it is already in is rejected as a no-op`() {
        val admin = adminBearer()
        val area = givenRoutedArea()
        val response = assignLine(admin, area.lineId, area.areaId, area.areaId)
        assertEquals(409, response.statusCode())
        assertEquals("RAILWAY_LINE_ALREADY_ASSIGNED_TO_AREA", errorCode(response))
    }

    @Test
    fun `a stale expectedCurrentServiceAreaId is rejected on both assign and unassign`() {
        val admin = adminBearer()
        val a = givenRoutedArea()
        val b = givenRoutedArea()

        // The line is actually in `a`, but the caller believes it is unassigned.
        val staleAssign = assignLine(admin, a.lineId, b.areaId, null)
        assertEquals(409, staleAssign.statusCode())
        assertEquals("RAILWAY_LINE_ASSIGNMENT_CHANGED", errorCode(staleAssign))

        // The line is actually in `a`, but the caller believes it is in `b`.
        val staleUnassign = unassignLine(admin, a.lineId, b.areaId)
        assertEquals(409, staleUnassign.statusCode())
        assertEquals("RAILWAY_LINE_ASSIGNMENT_CHANGED", errorCode(staleUnassign))

        assertEquals(a.areaId, currentAreaOfLine(a.lineId)) { "neither rejected call may have changed anything" }
    }

    @Test
    fun `unassign clears the mapping and future submissions on that line resolve the unchanged Phase 3 RAILWAY_LINE_UNASSIGNED outcome`() {
        val admin = adminBearer()
        val area = givenRoutedArea()
        assertEquals(204, unassignLine(admin, area.lineId, area.areaId).statusCode())
        assertNull(currentAreaOfLine(area.lineId))

        val report = givenCreatedReport(settlementId = area.settlementId, railwayLineId = area.lineId)
        assertNull(routingSnapshotArea(report.publicId))
        val reportId = internalReportId(report.publicId)
        val routingReason = jdbc.sql("SELECT routing_reason FROM report_routing_snapshots WHERE report_id = :id")
            .param("id", reportId).query(String::class.java).single()
        assertEquals("RAILWAY_LINE_UNASSIGNED", routingReason)
    }

    @Test
    fun `the database's own unique index keeps at most one current area per line even if assign were somehow called twice`() {
        val admin = adminBearer()
        val a = givenRoutedArea()
        val freeLine = insertLine("L${(100000..999999).random()}")
        assertEquals(204, assignLine(admin, freeLine, a.areaId, null).statusCode())
        assertEquals(1, mappingCount(freeLine))
    }

    @Test
    fun `the railway line list supports search, active filter and assignment-state filter`() {
        val admin = adminBearer()
        val area = givenRoutedArea()
        val unassignedCode = "LX${(1000..9999).random()}"
        val unassignedLine = insertLine(unassignedCode)

        val assignedOnly = json(listRailwayLines(admin, serviceAreaId = area.areaId, assignment = "ASSIGNED"))
        val assignedIds = assignedOnly.get("items").asList().map { it.get("id").asText() }
        assertTrue(assignedIds.contains(area.lineId.toString()))

        val unassignedOnly = json(listRailwayLines(admin, query = unassignedCode, assignment = "UNASSIGNED"))
        val unassignedIds = unassignedOnly.get("items").asList().map { it.get("id").asText() }
        assertTrue(unassignedIds.contains(unassignedLine.toString()))
    }

    @Test
    fun `a nonexistent railway line returns 404 RAILWAY_LINE_NOT_FOUND`() {
        val admin = adminBearer()
        val area = givenRoutedArea()
        val missing = UUID.randomUUID()
        assertEquals("RAILWAY_LINE_NOT_FOUND", errorCode(assignLine(admin, missing, area.areaId, null)))
        assertEquals("RAILWAY_LINE_NOT_FOUND", errorCode(unassignLine(admin, missing, area.areaId)))
    }
}
