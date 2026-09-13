package hu.orszembejelento.backend.areaadmin

import hu.orszembejelento.backend.areaadmin.support.AreaAdminTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/**
 * Phase 10 brief §4/§69: ServiceArea administration is SUPER_ADMIN only, full stop. A
 * MODERATOR is rejected identically regardless of global area access - there is no
 * territorial nuance here at all, unlike every other Service surface in this codebase.
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class AreaAdminAuthorizationIT : AreaAdminTestSupport() {

    @Test
    fun `a SERVICE_USER is forbidden from every area-admin endpoint`() {
        val user = bearerFor(givenServiceUser())
        val area = givenRoutedArea()

        assertEquals(403, listAreas(user).statusCode())
        assertEquals(403, areaDetail(user, area.areaId).statusCode())
        assertEquals(403, createArea(user, "Uj terulet").statusCode())
        assertEquals(403, renameArea(user, area.areaId, 0, "Atnevezve").statusCode())
        assertEquals(403, activateArea(user, area.areaId, 0).statusCode())
        assertEquals(403, deactivateArea(user, area.areaId, 0).statusCode())
        assertEquals(403, listRailwayLines(user).statusCode())
        assertEquals(403, assignLine(user, area.lineId, area.areaId, null).statusCode())
        assertEquals(403, unassignLine(user, area.lineId, area.areaId).statusCode())
        every403IsServiceAreaAdminForbidden(user, area.areaId, area.lineId)
    }

    @Test
    fun `a global MODERATOR is forbidden from every area-admin endpoint - no territorial exception exists here`() {
        val mod = bearerFor(givenGlobalModerator())
        val area = givenRoutedArea()

        assertEquals(403, listAreas(mod).statusCode())
        assertEquals(403, createArea(mod, "Uj terulet").statusCode())
        assertEquals(403, deactivateArea(mod, area.areaId, 0).statusCode())
        assertEquals(403, assignLine(mod, area.lineId, area.areaId, area.areaId).statusCode())
    }

    @Test
    fun `a SUPER_ADMIN may use every area-admin endpoint`() {
        val admin = adminBearer()

        val created = createArea(admin, "SUPER_ADMIN teszt terulet ${System.nanoTime()}")
        assertEquals(200, created.statusCode())
        val areaId = java.util.UUID.fromString(json(created).get("id").asText())

        assertEquals(200, listAreas(admin).statusCode())
        assertEquals(200, areaDetail(admin, areaId).statusCode())
        assertEquals(200, listRailwayLines(admin).statusCode())
    }

    private fun every403IsServiceAreaAdminForbidden(bearer: String, areaId: java.util.UUID, lineId: java.util.UUID) {
        assertEquals("SERVICE_AREA_ADMIN_FORBIDDEN", errorCode(listAreas(bearer)))
        assertEquals("SERVICE_AREA_ADMIN_FORBIDDEN", errorCode(createArea(bearer, "X")))
        assertEquals("SERVICE_AREA_ADMIN_FORBIDDEN", errorCode(deactivateArea(bearer, areaId, 0)))
        assertEquals("SERVICE_AREA_ADMIN_FORBIDDEN", errorCode(assignLine(bearer, lineId, areaId, null)))
    }
}
