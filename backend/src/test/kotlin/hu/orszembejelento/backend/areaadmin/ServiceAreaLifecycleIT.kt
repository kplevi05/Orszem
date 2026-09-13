package hu.orszembejelento.backend.areaadmin

import hu.orszembejelento.backend.areaadmin.support.AreaAdminTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/** Phase 10 brief §10-16/§69 "Areas" matrix: create/rename/activate/deactivate and every deactivation blocker. */
@Import(AbstractAuthIntegrationTest.Containers::class)
class ServiceAreaLifecycleIT : AreaAdminTestSupport() {

    @Test
    fun `create starts ACTIVE, unmapped, adminVersion 0`() {
        val admin = adminBearer()
        val response = createArea(admin, "Uj terulet ${System.nanoTime()}")
        assertEquals(200, response.statusCode())
        val body = json(response)
        assertTrue(body.get("active").asBoolean())
        assertEquals(0L, body.get("adminVersion").asLong())

        val areaId = java.util.UUID.fromString(body.get("id").asText())
        val detail = json(areaDetail(admin, areaId))
        assertEquals(0, detail.get("mappedRailwayLineCount").asInt())
        assertEquals(0, detail.get("openOperationalReportCount").asInt())
    }

    @Test
    fun `a blank name is rejected and never silently truncated`() {
        val admin = adminBearer()
        assertEquals(400, createArea(admin, "   ").statusCode())
    }

    @Test
    fun `a name already in use by another area is rejected`() {
        val admin = adminBearer()
        val name = "Ismetlodo nev ${System.nanoTime()}"
        assertEquals(200, createArea(admin, name).statusCode())
        val second = createArea(admin, name)
        assertEquals(409, second.statusCode())
        assertEquals("SERVICE_AREA_NAME_ALREADY_IN_USE", errorCode(second))
    }

    @Test
    fun `rename changes only the name - never reports, assignments or line mapping`() {
        val admin = adminBearer()
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)

        val response = renameArea(admin, area.areaId, 0, "Atnevezve ${System.nanoTime()}")
        assertEquals(200, response.statusCode())
        assertEquals(1L, json(response).get("adminVersion").asLong())

        assertEquals(area.areaId, routingSnapshotArea(report.publicId))
        assertTrue(storedAssignmentExists(user.id, area.areaId))
        assertEquals(area.areaId, currentAreaOfLine(area.lineId))
    }

    @Test
    fun `rename with a stale expectedVersion is rejected`() {
        val admin = adminBearer()
        val area = givenRoutedArea()
        assertEquals(200, renameArea(admin, area.areaId, 0, "Elso atnevezes").statusCode())
        val stale = renameArea(admin, area.areaId, 0, "Masodik atnevezes")
        assertEquals(409, stale.statusCode())
        assertEquals("SERVICE_AREA_STATE_CHANGED", errorCode(stale))
    }

    @Test
    fun `activate is rejected when already active, and succeeds on a genuinely inactive area`() {
        val admin = adminBearer()
        val area = givenRoutedArea()
        val alreadyActive = activateArea(admin, area.areaId, 0)
        assertEquals(409, alreadyActive.statusCode())
        assertEquals("SERVICE_AREA_ALREADY_ACTIVE", errorCode(alreadyActive))

        // Clear both blockers, then deactivate, then reactivate.
        assertEquals(204, unassignLine(admin, area.lineId, area.areaId).statusCode())
        assertEquals(200, deactivateArea(admin, area.areaId, 1).statusCode())
        val reactivated = activateArea(admin, area.areaId, 2)
        assertEquals(200, reactivated.statusCode())
        assertTrue(json(reactivated).get("active").asBoolean())
    }

    @Test
    fun `deactivate is rejected when already inactive`() {
        val admin = adminBearer()
        val area = givenRoutedArea()
        unassignLine(admin, area.lineId, area.areaId)
        assertEquals(200, deactivateArea(admin, area.areaId, 1).statusCode())
        val again = deactivateArea(admin, area.areaId, 2)
        assertEquals(409, again.statusCode())
        assertEquals("SERVICE_AREA_ALREADY_INACTIVE", errorCode(again))
    }

    @Test
    fun `deactivate is blocked while a railway line is still mapped`() {
        val admin = adminBearer()
        val area = givenRoutedArea()
        val response = deactivateArea(admin, area.areaId, 0)
        assertEquals(409, response.statusCode())
        assertEquals("SERVICE_AREA_HAS_RAILWAY_LINES", errorCode(response))
        assertEquals("ACTIVE", areaStatus(area.areaId))
    }

    @Test
    fun `deactivate is blocked by a NEW report and again by an IN_PROGRESS report`() {
        val admin = adminBearer()
        val area = givenRoutedArea()
        // The report is submitted *while the line is still mapped* so it genuinely routes
        // into `area`; only afterward is the line unassigned, leaving the report as the
        // area's one remaining blocker.
        val newReport = givenRoutedReport(area)
        assertEquals(204, unassignLine(admin, area.lineId, area.areaId).statusCode())

        val blockedByNew = deactivateArea(admin, area.areaId, 1)
        assertEquals(409, blockedByNew.statusCode())
        assertEquals("SERVICE_AREA_HAS_OPEN_REPORTS", errorCode(blockedByNew))

        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        check(claim(bearerFor(user), newReport.publicId, 0).statusCode() == 200)

        val blockedByInProgress = deactivateArea(admin, area.areaId, 1)
        assertEquals(409, blockedByInProgress.statusCode())
        assertEquals("SERVICE_AREA_HAS_OPEN_REPORTS", errorCode(blockedByInProgress))
    }

    @Test
    fun `ARCHIVED reports never block deactivation`() {
        val admin = adminBearer()
        val area = givenRoutedArea()
        val report = givenRoutedReport(area)
        assertEquals(204, unassignLine(admin, area.lineId, area.areaId).statusCode())
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val userBearer = bearerFor(user)
        check(claim(userBearer, report.publicId, 0).statusCode() == 200)
        check(close(userBearer, report.publicId, 1).statusCode() == 200)

        val response = deactivateArea(admin, area.areaId, 1)
        assertEquals(200, response.statusCode())
        assertFalse(json(response).get("active").asBoolean())
    }

    @Test
    fun `a moderation-deleted report never blocks deactivation`() {
        val admin = adminBearer()
        val area = givenRoutedArea()
        val report = givenRoutedReport(area)
        assertEquals(204, unassignLine(admin, area.lineId, area.areaId).statusCode())

        check(delete(admin, report.publicId, 0).statusCode() == 204)

        val response = deactivateArea(admin, area.areaId, 1)
        assertEquals(200, response.statusCode())
    }

    @Test
    fun `stored user assignments survive deactivate and reactivate untouched`() {
        val admin = adminBearer()
        val area = givenRoutedArea()
        unassignLine(admin, area.lineId, area.areaId)
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        assertTrue(storedAssignmentExists(user.id, area.areaId))

        assertEquals(200, deactivateArea(admin, area.areaId, 1).statusCode())
        assertTrue(storedAssignmentExists(user.id, area.areaId)) { "deactivation must never delete a stored assignment" }

        assertEquals(200, activateArea(admin, area.areaId, 2).statusCode())
        assertTrue(storedAssignmentExists(user.id, area.areaId))
    }

    @Test
    fun `the area list is server-side paginated and filterable by active state and name`() {
        val admin = adminBearer()
        // No spaces - `listAreas`'s query param is passed straight into a URI, and the HTTP
        // helper does not URL-encode it (matches how every other list-filter test in this
        // codebase avoids the same landmine).
        val marker = "SzuresTeszt${System.nanoTime()}"
        val activeId = java.util.UUID.fromString(json(createArea(admin, "Aktiv-$marker")).get("id").asText())
        val inactiveId = java.util.UUID.fromString(json(createArea(admin, "Inaktiv-$marker")).get("id").asText())
        assertEquals(200, deactivateArea(admin, inactiveId, 0).statusCode())

        val onlyActive = json(listAreas(admin, query = marker, active = true))
        val activeIds = onlyActive.get("items").asList().map { it.get("id").asText() }
        assertTrue(activeIds.contains(activeId.toString()))
        assertFalse(activeIds.contains(inactiveId.toString()))

        val onlyInactive = json(listAreas(admin, query = marker, active = false))
        val inactiveIds = onlyInactive.get("items").asList().map { it.get("id").asText() }
        assertTrue(inactiveIds.contains(inactiveId.toString()))
        assertFalse(inactiveIds.contains(activeId.toString()))
    }

    @Test
    fun `a nonexistent area returns 404 SERVICE_AREA_NOT_FOUND, never leaks internal state`() {
        val admin = adminBearer()
        val missing = java.util.UUID.randomUUID()
        assertEquals("SERVICE_AREA_NOT_FOUND", errorCode(areaDetail(admin, missing)))
        assertEquals("SERVICE_AREA_NOT_FOUND", errorCode(renameArea(admin, missing, 0, "X")))
        assertEquals("SERVICE_AREA_NOT_FOUND", errorCode(activateArea(admin, missing, 0)))
        assertEquals("SERVICE_AREA_NOT_FOUND", errorCode(deactivateArea(admin, missing, 0)))
    }
}
