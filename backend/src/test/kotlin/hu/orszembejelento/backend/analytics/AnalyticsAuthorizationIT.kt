package hu.orszembejelento.backend.analytics

import hu.orszembejelento.backend.analytics.support.AnalyticsTestSupport
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Role/scope authorization for both analytics endpoints (Phase 11 brief §7/§8/§21-24/§57).
 * Mirrors the report-workflow queue's own role rules exactly, reusing the same actor
 * ([hu.orszembejelento.backend.reportworkflow.domain.ReportWorkflowActor]) — see
 * [hu.orszembejelento.backend.analytics.infrastructure.JdbcAnalyticsRepository]'s own KDoc
 * for the one deliberate difference (a global MODERATOR's analytics scope excludes inactive
 * areas, unlike the ordinary report-workflow queues).
 */
class AnalyticsAuthorizationIT : AnalyticsTestSupport() {

    @Test
    fun `a territorial SERVICE_USER only sees their own active area, never UNCLASSIFIED`() {
        val ownArea = givenRoutedArea()
        val otherArea = givenRoutedArea()
        givenRoutedReport(ownArea)
        givenRoutedReport(otherArea)
        givenUnclassifiedReport()

        val user = givenServiceUser()
        grantArea(user.id, ownArea.areaId)
        val bearer = bearerFor(user)

        val response = summary(bearer, period = "LAST_30_DAYS")
        assertEquals(200, response.statusCode())
        assertEquals(1, json(response).get("totalReports").asInt())
    }

    @Test
    fun `a territorial MODERATOR only sees their own active area, never UNCLASSIFIED`() {
        val ownArea = givenRoutedArea()
        val otherArea = givenRoutedArea()
        givenRoutedReport(ownArea)
        givenRoutedReport(otherArea)
        givenUnclassifiedReport()

        val mod = givenTerritorialModerator()
        grantArea(mod.id, ownArea.areaId)
        val bearer = bearerFor(mod)

        assertEquals(1, json(summary(bearer)).get("totalReports").asInt())
    }

    @Test
    fun `a global MODERATOR sees every active area plus UNCLASSIFIED, but not an inactive area`() {
        val activeArea = givenRoutedArea()
        val inactiveArea = givenRoutedArea()
        givenRoutedReport(activeArea)
        val inactiveAreaReport = givenRoutedReport(inactiveArea)
        givenUnclassifiedReport()
        deactivateAreaDirect(inactiveArea.areaId, reportsToArchiveFirst = listOf(inactiveAreaReport.publicId))

        val bearer = bearerFor(givenGlobalModerator())
        // 1 active-area report + 1 unclassified report; the inactive area's (now-archived) report is still excluded by area status.
        assertEquals(2, json(summary(bearer)).get("totalReports").asInt())
    }

    @Test
    fun `a global SERVICE_USER sees every active area but never UNCLASSIFIED`() {
        val area = givenRoutedArea()
        givenRoutedReport(area)
        givenUnclassifiedReport()

        val bearer = bearerFor(givenGlobalServiceUser())
        assertEquals(1, json(summary(bearer)).get("totalReports").asInt())
    }

    @Test
    fun `SUPER_ADMIN sees everything, including UNCLASSIFIED and a currently-inactive area`() {
        val activeArea = givenRoutedArea()
        val inactiveArea = givenRoutedArea()
        givenRoutedReport(activeArea)
        val inactiveAreaReport = givenRoutedReport(inactiveArea)
        givenUnclassifiedReport()
        deactivateAreaDirect(inactiveArea.areaId, reportsToArchiveFirst = listOf(inactiveAreaReport.publicId))

        val bearer = bearerFor(givenSuperAdmin())
        assertEquals(3, json(summary(bearer)).get("totalReports").asInt())
    }

    @Test
    fun `a territorial actor cannot filter to an area outside their own scope - 404, not a scope leak`() {
        val ownArea = givenRoutedArea()
        val otherArea = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, ownArea.areaId)

        val response = summary(bearerFor(user), areaId = otherArea.areaId)
        assertEquals(404, response.statusCode())
        assertEquals("ANALYTICS_AREA_NOT_AVAILABLE", errorCode(response))
    }

    @Test
    fun `a nonexistent areaId is the identical 404 - no enumeration signal`() {
        val user = givenServiceUser()
        val response = summary(bearerFor(user), areaId = UUID.randomUUID())
        assertEquals(404, response.statusCode())
        assertEquals("ANALYTICS_AREA_NOT_AVAILABLE", errorCode(response))
    }

    @Test
    fun `a territorial actor cannot request unclassifiedOnly`() {
        val user = givenServiceUser()
        val response = summary(bearerFor(user), unclassifiedOnly = true)
        assertEquals(403, response.statusCode())
        assertEquals("ANALYTICS_UNCLASSIFIED_FORBIDDEN", errorCode(response))
    }

    @Test
    fun `a territorial MODERATOR cannot request unclassifiedOnly either`() {
        val mod = givenTerritorialModerator()
        val response = summary(bearerFor(mod), unclassifiedOnly = true)
        assertEquals(403, response.statusCode())
        assertEquals("ANALYTICS_UNCLASSIFIED_FORBIDDEN", errorCode(response))
    }

    @Test
    fun `a global MODERATOR may request unclassifiedOnly`() {
        givenUnclassifiedReport()
        val response = summary(bearerFor(givenGlobalModerator()), unclassifiedOnly = true)
        assertEquals(200, response.statusCode())
        assertEquals(1, json(response).get("totalReports").asInt())
    }

    @Test
    fun `SUPER_ADMIN may filter to a currently-inactive area`() {
        val area = givenRoutedArea()
        val report = givenRoutedReport(area)
        deactivateAreaDirect(area.areaId, reportsToArchiveFirst = listOf(report.publicId))

        val response = summary(bearerFor(givenSuperAdmin()), areaId = area.areaId)
        assertEquals(200, response.statusCode())
        assertEquals(1, json(response).get("totalReports").asInt())
    }

    @Test
    fun `areaId and unclassifiedOnly together is rejected, not silently resolved`() {
        val area = givenRoutedArea()
        val response = summary(bearerFor(givenSuperAdmin()), areaId = area.areaId, unclassifiedOnly = true)
        assertEquals(400, response.statusCode())
        assertEquals("VALIDATION_ERROR", errorCode(response))
    }

    // --------------------------------------------------------------------------- /analytics/areas

    @Test
    fun `analytics areas - territorial SERVICE_USER sees only own active assigned areas, no Besorolatlan`() {
        val owned = givenRoutedArea()
        val other = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, owned.areaId)

        val body = json(analyticsAreas(bearerFor(user)))
        val ids = body.get("areas").asList().map { it.get("id").asText() }
        assertTrue(owned.areaId.toString() in ids)
        assertFalse(other.areaId.toString() in ids)
        assertFalse(body.get("canViewUnclassified").asBoolean())
    }

    @Test
    fun `analytics areas - global MODERATOR sees every active area and can view Besorolatlan, never inactive`() {
        val active = givenRoutedArea()
        val inactive = givenRoutedArea()
        deactivateAreaDirect(inactive.areaId)
        // (no reports created in `inactive`, so nothing to archive first)

        val body = json(analyticsAreas(bearerFor(givenGlobalModerator())))
        val ids = body.get("areas").asList().map { it.get("id").asText() }
        assertTrue(active.areaId.toString() in ids)
        assertFalse(inactive.areaId.toString() in ids)
        assertTrue(body.get("canViewUnclassified").asBoolean())
    }

    @Test
    fun `analytics areas - SUPER_ADMIN sees active and inactive areas, both flagged correctly`() {
        val active = givenRoutedArea()
        val inactive = givenRoutedArea()
        deactivateAreaDirect(inactive.areaId)

        val body = json(analyticsAreas(bearerFor(givenSuperAdmin())))
        val byId = body.get("areas").asList().associateBy { it.get("id").asText() }
        assertTrue(byId.getValue(active.areaId.toString()).get("active").asBoolean())
        assertFalse(byId.getValue(inactive.areaId.toString()).get("active").asBoolean())
    }

    // ------------------------------------------------------------------------------- helpers

    /**
     * Deactivates an area directly through the real Phase 10 endpoint, as a fresh SUPER_ADMIN:
     * archives every report named in [reportsToArchiveFirst] (Phase 10 deactivation blocks on
     * open NEW/IN_PROGRESS reports, brief §15 - this is not an analytics concern to work
     * around, it is the real precondition the real endpoint enforces), then unassigns the
     * area's mapped line, then deactivates.
     */
    private fun deactivateAreaDirect(areaId: UUID, reportsToArchiveFirst: List<UUID> = emptyList()) {
        val admin = bearerFor(givenSuperAdmin())
        reportsToArchiveFirst.forEach { publicId ->
            val version = reportRow(publicId).workflowVersion
            val response = close(admin, publicId, version)
            check(response.statusCode() == 200) { "fixture close failed: ${response.statusCode()} ${response.body()}" }
        }
        unassignAllLinesFrom(areaId, admin)
        val version = areaAdminVersion(areaId)
        val response = deactivateArea(admin, areaId, version)
        check(response.statusCode() == 200) { "fixture deactivate failed: ${response.statusCode()} ${response.body()}" }
    }

    private fun unassignAllLinesFrom(areaId: UUID, adminBearer: String) {
        val lineId = currentLineIdOf(areaId) ?: return
        unassignLine(adminBearer, lineId, areaId)
    }

    private fun currentLineIdOf(areaId: UUID): UUID? =
        jdbc.sql("SELECT railway_line_id FROM service_area_railway_lines WHERE service_area_id = :id")
            .param("id", areaId)
            .query(UUID::class.java)
            .optional()
            .orElse(null)
}
