package hu.orszembejelento.backend.areaadmin

import hu.orszembejelento.backend.areaadmin.support.AreaAdminTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/** Phase 10 brief §41: one immutable audit row per admin-visible mutation, in the same transaction. */
@Import(AbstractAuthIntegrationTest.Containers::class)
class AreaAdminAuditIT : AreaAdminTestSupport() {

    @Test
    fun `create writes SERVICE_AREA_CREATED with the name`() {
        val admin = adminBearer()
        val name = "Audit teszt terulet ${System.nanoTime()}"
        val before = auditEventCount("SERVICE_AREA_CREATED")
        val areaId = java.util.UUID.fromString(json(createArea(admin, name)).get("id").asText())
        assertEquals(before + 1, auditEventCount("SERVICE_AREA_CREATED"))
        assertEquals(name, latestAuditMetadata("SERVICE_AREA_CREATED", areaId)?.get("name"))
    }

    @Test
    fun `rename writes SERVICE_AREA_RENAMED with the old and new name`() {
        val admin = adminBearer()
        val area = givenRoutedArea()
        val newName = "Uj nev audit teszt ${System.nanoTime()}"
        val before = auditEventCount("SERVICE_AREA_RENAMED")
        renameArea(admin, area.areaId, 0, newName)
        assertEquals(before + 1, auditEventCount("SERVICE_AREA_RENAMED"))
        val metadata = latestAuditMetadata("SERVICE_AREA_RENAMED", area.areaId)
        assertEquals(newName, metadata?.get("newName"))
        assertTrue((metadata?.get("oldName") as? String)?.isNotBlank() == true)
    }

    @Test
    fun `activate and deactivate write their own distinct audit events`() {
        val admin = adminBearer()
        val area = givenRoutedArea()
        unassignLine(admin, area.lineId, area.areaId)

        val deactivateBefore = auditEventCount("SERVICE_AREA_DEACTIVATED")
        deactivateArea(admin, area.areaId, 1)
        assertEquals(deactivateBefore + 1, auditEventCount("SERVICE_AREA_DEACTIVATED"))

        val activateBefore = auditEventCount("SERVICE_AREA_ACTIVATED")
        activateArea(admin, area.areaId, 2)
        assertEquals(activateBefore + 1, auditEventCount("SERVICE_AREA_ACTIVATED"))
    }

    @Test
    fun `assign writes RAILWAY_LINE_SERVICE_AREA_ASSIGNED, move writes RAILWAY_LINE_SERVICE_AREA_MOVED, unassign writes RAILWAY_LINE_SERVICE_AREA_UNASSIGNED`() {
        val admin = adminBearer()
        val a = givenRoutedArea()
        val b = givenRoutedArea()
        val freeLine = insertLine("L${(100000..999999).random()}")

        val assignBefore = auditEventCount("RAILWAY_LINE_SERVICE_AREA_ASSIGNED")
        assignLine(admin, freeLine, a.areaId, null)
        assertEquals(assignBefore + 1, auditEventCount("RAILWAY_LINE_SERVICE_AREA_ASSIGNED"))

        val moveBefore = auditEventCount("RAILWAY_LINE_SERVICE_AREA_MOVED")
        assignLine(admin, freeLine, b.areaId, a.areaId)
        assertEquals(moveBefore + 1, auditEventCount("RAILWAY_LINE_SERVICE_AREA_MOVED"))
        val moveMetadata = latestAuditMetadata("RAILWAY_LINE_SERVICE_AREA_MOVED", freeLine)
        assertEquals(a.areaId.toString(), moveMetadata?.get("fromAreaId"))
        assertEquals(b.areaId.toString(), moveMetadata?.get("toAreaId"))

        val unassignBefore = auditEventCount("RAILWAY_LINE_SERVICE_AREA_UNASSIGNED")
        unassignLine(admin, freeLine, b.areaId)
        assertEquals(unassignBefore + 1, auditEventCount("RAILWAY_LINE_SERVICE_AREA_UNASSIGNED"))
    }

    @Test
    fun `a rejected mutation never writes an audit row`() {
        val admin = adminBearer()
        val area = givenRoutedArea()
        val before = auditEventCount("SERVICE_AREA_DEACTIVATED")
        val response = deactivateArea(admin, area.areaId, 0) // blocked: still has a mapped line
        assertEquals(409, response.statusCode())
        assertEquals(before, auditEventCount("SERVICE_AREA_DEACTIVATED"))
    }
}
