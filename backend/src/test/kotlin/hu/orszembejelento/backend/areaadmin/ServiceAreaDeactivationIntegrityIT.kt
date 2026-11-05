package hu.orszembejelento.backend.areaadmin

import hu.orszembejelento.backend.areaadmin.support.AreaAdminTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import
import java.util.UUID

/**
 * JAV-01: Service Area Deactivation - Data Integrity Regression Suite.
 *
 * Enforces the BLOCK business rule:
 * - A service area with ANY open operational reports (NEW or IN_PROGRESS) MUST NOT be deactivated (409 Conflict).
 * - Under rejection, no ticket status, assignee, or routing snapshot is changed.
 * - Historical ARCHIVED reports MUST preserve their service_area_id and routing snapshot permanently.
 * - Concurrent claims and settlement assignments must never leave an INACTIVE area with active tickets.
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class ServiceAreaDeactivationIntegrityIT : AreaAdminTestSupport() {

    private var refStateSeeded = false

    private fun seedReferenceState() {
        if (!refStateSeeded) {
            runCatching { setCurrentReferenceState() }
            refStateSeeded = true
        }
    }

    private fun assignSettlementToArea(admin: String, settlementId: UUID, targetAreaId: UUID) =
        post(
            "/api/v1/service/service-area-admin/settlements/$settlementId/assign",
            """{"targetServiceAreaId":"$targetAreaId","expectedCurrentServiceAreaId":null}""",
            admin,
        )

    private fun givenSettlementAndArea(): Pair<UUID, UUID> {
        seedReferenceState()
        val admin = adminBearer()
        val settlementId = insertSettlement("%05d".format((10000..99999).random()))
        val createAreaResp = createArea(admin, "Area-${UUID.randomUUID()}")
        assertEquals(200, createAreaResp.statusCode())
        val areaId = UUID.fromString(json(createAreaResp).get("id").asText())
        val assignResp = assignSettlementToArea(admin, settlementId, areaId)
        assertEquals(204, assignResp.statusCode())
        return settlementId to areaId
    }

    private fun givenReportForSettlement(settlementId: UUID): UUID {
        val resp = submitReport(submitReportBody(settlementId = settlementId))
        assertEquals(201, resp.statusCode())
        return UUID.fromString(json(resp).get("reportId").asText())
    }

    private fun reportStatusInDb(publicReportId: UUID): String =
        jdbc.sql("SELECT status FROM reports WHERE public_id = :id")
            .param("id", publicReportId)
            .query(String::class.java)
            .single()

    private fun reportAssigneeInDb(publicReportId: UUID): UUID? =
        jdbc.sql("SELECT assigned_user_id FROM reports WHERE public_id = :id")
            .param("id", publicReportId)
            .query(UUID::class.java)
            .optional()
            .orElse(null)

    // -------------------------------------------------------------------------
    // 1. NEW ticket mellett az inaktiválás 409-et ad.
    // -------------------------------------------------------------------------
    @Test
    fun `case 1 - deactivation is rejected with 409 when a NEW report exists`() {
        val admin = adminBearer()
        val (settlementId, areaId) = givenSettlementAndArea()
        val reportId = givenReportForSettlement(settlementId)

        assertEquals("NEW", reportStatusInDb(reportId))
        assertEquals(areaId, routingSnapshot(reportId).serviceAreaId)

        val deactResp = deactivateArea(admin, areaId, 1)
        assertEquals(409, deactResp.statusCode())
        assertEquals("SERVICE_AREA_HAS_OPEN_REPORTS", errorCode(deactResp))
        assertEquals("ACTIVE", areaStatus(areaId))

        // State remains NEW and ROUTED to area
        assertEquals("NEW", reportStatusInDb(reportId))
        val snapshot = routingSnapshot(reportId)
        assertEquals("ROUTED", snapshot.routingStatus)
        assertEquals(areaId, snapshot.serviceAreaId)
        assertNull(snapshot.routingReason)
    }

    // -------------------------------------------------------------------------
    // 2. IN_PROGRESS ticket mellett az inaktiválás 409-et ad.
    // -------------------------------------------------------------------------
    @Test
    fun `case 2 - deactivation is rejected with 409 when an IN_PROGRESS report exists`() {
        val admin = adminBearer()
        val (settlementId, areaId) = givenSettlementAndArea()
        val reportId = givenReportForSettlement(settlementId)

        val user = givenServiceUser()
        grantArea(user.id, areaId)
        val claimResp = claim(bearerFor(user), reportId, 0)
        assertEquals(200, claimResp.statusCode())
        assertEquals("IN_PROGRESS", reportStatusInDb(reportId))

        val deactResp = deactivateArea(admin, areaId, 1)
        assertEquals(409, deactResp.statusCode())
        assertEquals("SERVICE_AREA_HAS_OPEN_REPORTS", errorCode(deactResp))
        assertEquals("ACTIVE", areaStatus(areaId))

        assertEquals("IN_PROGRESS", reportStatusInDb(reportId))
        assertEquals(user.id, reportAssigneeInDb(reportId))
        assertEquals(areaId, routingSnapshot(reportId).serviceAreaId)
    }

    // -------------------------------------------------------------------------
    // 3. NEW és IN_PROGRESS ticket együttes jelenléte esetén is 409.
    // -------------------------------------------------------------------------
    @Test
    fun `case 3 - deactivation is rejected with 409 when both NEW and IN_PROGRESS reports exist`() {
        val admin = adminBearer()
        val (settlementId, areaId) = givenSettlementAndArea()
        val newReportId = givenReportForSettlement(settlementId)
        val ipReportId = givenReportForSettlement(settlementId)

        val user = givenServiceUser()
        grantArea(user.id, areaId)
        assertEquals(200, claim(bearerFor(user), ipReportId, 0).statusCode())

        val deactResp = deactivateArea(admin, areaId, 1)
        assertEquals(409, deactResp.statusCode())
        assertEquals("SERVICE_AREA_HAS_OPEN_REPORTS", errorCode(deactResp))
        assertEquals("ACTIVE", areaStatus(areaId))

        assertEquals("NEW", reportStatusInDb(newReportId))
        assertEquals("IN_PROGRESS", reportStatusInDb(ipReportId))
        assertEquals(areaId, routingSnapshot(newReportId).serviceAreaId)
        assertEquals(areaId, routingSnapshot(ipReportId).serviceAreaId)
    }

    // -------------------------------------------------------------------------
    // 4. ARCHIVED ticket mellett az inaktiválás sikeres lehet.
    // -------------------------------------------------------------------------
    @Test
    fun `case 4 - deactivation succeeds when only ARCHIVED reports exist`() {
        val admin = adminBearer()
        val (settlementId, areaId) = givenSettlementAndArea()
        val reportId = givenReportForSettlement(settlementId)

        val user = givenServiceUser()
        grantArea(user.id, areaId)
        val bearer = bearerFor(user)
        assertEquals(200, claim(bearer, reportId, 0).statusCode())
        assertEquals(200, close(bearer, reportId, 1).statusCode())
        assertEquals("ARCHIVED", reportStatusInDb(reportId))

        val deactResp = deactivateArea(admin, areaId, 1)
        assertEquals(200, deactResp.statusCode())
        assertEquals("INACTIVE", areaStatus(areaId))
    }

    // -------------------------------------------------------------------------
    // 5. ARCHIVED ticket service_area_id értéke változatlan marad.
    // 6. ARCHIVED ticket assigned_user_id értéke változatlan marad.
    // 7. ARCHIVED ticket routing snapshotja változatlan marad.
    // -------------------------------------------------------------------------
    @Test
    fun `case 5, 6, 7 - ARCHIVED ticket preserves service_area_id and routing snapshot untouched`() {
        val admin = adminBearer()
        val (settlementId, areaId) = givenSettlementAndArea()
        val reportId = givenReportForSettlement(settlementId)

        val user = givenServiceUser()
        grantArea(user.id, areaId)
        val bearer = bearerFor(user)
        assertEquals(200, claim(bearer, reportId, 0).statusCode())
        assertEquals(200, close(bearer, reportId, 1).statusCode())

        val snapshotBefore = routingSnapshot(reportId)
        assertEquals("ROUTED", snapshotBefore.routingStatus)
        assertEquals(areaId, snapshotBefore.serviceAreaId)
        assertNull(snapshotBefore.routingReason)

        val deactResp = deactivateArea(admin, areaId, 1)
        assertEquals(200, deactResp.statusCode())

        // Historical snapshot is 100% preserved and never unclassified!
        val snapshotAfter = routingSnapshot(reportId)
        assertEquals(snapshotBefore.routingStatus, snapshotAfter.routingStatus)
        assertEquals(snapshotBefore.serviceAreaId, snapshotAfter.serviceAreaId)
        assertEquals(snapshotBefore.routingReason, snapshotAfter.routingReason)
        assertEquals(areaId, snapshotAfter.serviceAreaId)

        // Historical assignment in report_assignments table is preserved
        val assignmentCount = jdbc.sql("SELECT COUNT(*) FROM report_assignments WHERE report_id = :r AND assignee_user_id = :u")
            .param("r", internalReportId(reportId))
            .param("u", user.id)
            .query(Int::class.java)
            .single()
        assertEquals(1, assignmentCount)
    }

    // -------------------------------------------------------------------------
    // 8. Sikertelen inaktiválás után minden érintett adatbázisrekord változatlan.
    // -------------------------------------------------------------------------
    @Test
    fun `case 8 - failed deactivation leaves all DB records completely unchanged`() {
        val admin = adminBearer()
        val (settlementId, areaId) = givenSettlementAndArea()
        val rNew = givenReportForSettlement(settlementId)
        val rArchived = givenReportForSettlement(settlementId)

        val user = givenServiceUser()
        grantArea(user.id, areaId)
        val bearer = bearerFor(user)
        assertEquals(200, claim(bearer, rArchived, 0).statusCode())
        assertEquals(200, close(bearer, rArchived, 1).statusCode())

        val areaVersionBefore = areaDetail(admin, areaId).let { json(it).get("adminVersion").asLong() }
        val snapshotNewBefore = routingSnapshot(rNew)
        val snapshotArchivedBefore = routingSnapshot(rArchived)

        val deactResp = deactivateArea(admin, areaId, areaVersionBefore)
        assertEquals(409, deactResp.statusCode())

        assertEquals("ACTIVE", areaStatus(areaId))
        val areaVersionAfter = areaDetail(admin, areaId).let { json(it).get("adminVersion").asLong() }
        assertEquals(areaVersionBefore, areaVersionAfter)

        assertEquals(snapshotNewBefore, routingSnapshot(rNew))
        assertEquals(snapshotArchivedBefore, routingSnapshot(rArchived))
    }

    // -------------------------------------------------------------------------
    // 9. Másik szolgálati területhez tartozó jegyek nem módosulnak.
    // -------------------------------------------------------------------------
    @Test
    fun `case 9 - deactivating an area never touches reports of any other service area`() {
        val admin = adminBearer()
        val (settlement1, area1) = givenSettlementAndArea()
        val (settlement2, area2) = givenSettlementAndArea()

        val r1 = givenReportForSettlement(settlement1) // for area 1
        val r2 = givenReportForSettlement(settlement2) // for area 2

        // Archive r1 so area1 can be deactivated cleanly
        val user1 = givenServiceUser().also { grantArea(it.id, area1) }
        val b1 = bearerFor(user1)
        assertEquals(200, claim(b1, r1, 0).statusCode())
        assertEquals(200, close(b1, r1, 1).statusCode())

        val snapshot2Before = routingSnapshot(r2)
        assertEquals(area2, snapshot2Before.serviceAreaId)

        val deactResp = deactivateArea(admin, area1, 1)
        assertEquals(200, deactResp.statusCode())
        assertEquals("INACTIVE", areaStatus(area1))

        // Area 2 and its reports remain completely untouched
        assertEquals("ACTIVE", areaStatus(area2))
        assertEquals("NEW", reportStatusInDb(r2))
        val snapshot2After = routingSnapshot(r2)
        assertEquals(snapshot2Before, snapshot2After)
    }

    // -------------------------------------------------------------------------
    // 10. Sikeres inaktiválás után az area státusza INACTIVE.
    // -------------------------------------------------------------------------
    @Test
    fun `case 10 - after successful deactivation, area status is INACTIVE`() {
        val admin = adminBearer()
        val (settlementId, areaId) = givenSettlementAndArea()
        // No reports created, area is empty
        val deactResp = deactivateArea(admin, areaId, 1)
        assertEquals(200, deactResp.statusCode())
        assertEquals("INACTIVE", areaStatus(areaId))
        val detail = json(areaDetail(admin, areaId))
        assertFalse(detail.get("active").asBoolean())
    }

    // -------------------------------------------------------------------------
    // 11. Párhuzamos inaktiválás és ticket claim nem okoz inkonzisztens állapotot (20x).
    // -------------------------------------------------------------------------
    @Test
    fun `case 11 - concurrent deactivation and claim never violates invariant (20 iterations)`() {
        repeat(20) {
            val admin = adminBearer()
            val (settlementId, areaId) = givenSettlementAndArea()
            val reportId = givenReportForSettlement(settlementId)

            val user = givenServiceUser()
            grantArea(user.id, areaId)
            val userBearer = bearerFor(user)

            val results = runConcurrently(2) { index ->
                if (index == 0) {
                    rawDeactivateArea(admin, areaId, 1)
                } else {
                    rawPost("/api/v1/service/reports/$reportId/claim", """{"expectedVersion":0}""", userBearer)
                }
            }.map { it.getOrThrow() }

            val deactCode = results[0].statusCode()
            val claimCode = results[1].statusCode()
            val finalAreaStatus = areaStatus(areaId)
            val finalReportStatus = reportStatusInDb(reportId)

            // INVARIANT: An INACTIVE area must NEVER hold an active NEW or IN_PROGRESS ticket!
            if (finalAreaStatus == "INACTIVE") {
                assertTrue(
                    finalReportStatus == "ARCHIVED",
                    "An INACTIVE area cannot have an active ticket; report was $finalReportStatus",
                )
            }

            // Since report was NEW, deactivation must either be blocked (409) or claim won and deactivation was blocked (409).
            // A deactivation could never succeed while an open report exists!
            assertEquals(409, deactCode, "Deactivation must be blocked by open report (got $deactCode, claim=$claimCode)")
            assertEquals("ACTIVE", finalAreaStatus)
        }
    }

    // -------------------------------------------------------------------------
    // 12. Párhuzamos inaktiválás és település-hozzárendelés nem okoz inkonzisztens állapotot (20x).
    // -------------------------------------------------------------------------
    @Test
    fun `case 12 - concurrent deactivation and settlement assignment never violates invariant (20 iterations)`() {
        repeat(20) {
            val admin = adminBearer()
            val createAreaResp = createArea(admin, "Area-Race-${UUID.randomUUID()}")
            assertEquals(200, createAreaResp.statusCode())
            val areaId = UUID.fromString(json(createAreaResp).get("id").asText())
            val settlementId = insertSettlement("%05d".format((10000..99999).random()))

            val results = runConcurrently(2) { index ->
                if (index == 0) {
                    rawDeactivateArea(admin, areaId, 0)
                } else {
                    rawPost(
                        "/api/v1/service/service-area-admin/settlements/$settlementId/assign",
                        """{"targetServiceAreaId":"$areaId","expectedCurrentServiceAreaId":null}""",
                        admin,
                    )
                }
            }.map { it.getOrThrow() }

            val deactCode = results[0].statusCode()
            val assignCode = results[1].statusCode()
            val finalAreaStatus = areaStatus(areaId)

            // Exclusive lock ensures serialization:
            // Either deactivation commits first -> area becomes INACTIVE -> assign rejects (409 TargetServiceAreaInactiveException)
            // Or assign commits first -> area stays ACTIVE (or deactivation runs after assign)
            assertTrue(
                (deactCode == 200 && (assignCode == 409 || assignCode == 404)) ||
                (assignCode == 204 && (deactCode == 200 || deactCode == 409)),
                "Unexpected status pair: deact=$deactCode, assign=$assignCode, areaStatus=$finalAreaStatus",
            )

            // If area became INACTIVE and settlement was mapped, ensure zero open reports were stranded
            val openReports = jdbc.sql(
                "SELECT COUNT(*) FROM reports r JOIN report_routing_snapshots rs ON rs.report_id = r.id " +
                    "WHERE rs.service_area_id = :areaId AND r.status IN ('NEW', 'IN_PROGRESS')",
            )
                .param("areaId", areaId)
                .query(Int::class.java)
                .single()
            assertEquals(0, openReports, "INACTIVE area cannot have open reports")
        }
    }
}
