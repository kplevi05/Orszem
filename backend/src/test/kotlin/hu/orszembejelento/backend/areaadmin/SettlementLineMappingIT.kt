package hu.orszembejelento.backend.areaadmin

import hu.orszembejelento.backend.areaadmin.support.AreaAdminTestSupport
import hu.orszembejelento.backend.reference.application.ReferenceImportUseCase
import hu.orszembejelento.backend.reference.domain.ReferenceLineInUseException
import hu.orszembejelento.backend.reference.domain.ReferenceMappingInUseException
import hu.orszembejelento.backend.reference.support.ReferenceDatasetFixture
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException

@Import(AbstractAuthIntegrationTest.Containers::class)
class SettlementLineMappingIT : AreaAdminTestSupport() {
    @Autowired private lateinit var referenceImport: ReferenceImportUseCase
    private val endpoint = "/api/v1/service/service-area-admin/settlement-line-mappings"

    private data class Fixture(val first: UUID, val second: UUID, val line: UUID, val a: UUID, val b: UUID)

    private fun fixture(): Fixture {
        setCurrentReferenceState("pair-v1")
        val first = insertSettlement("00001", "Fixture A")
        val second = insertSettlement("00002", "Fixture B")
        val line = insertLine("TEST-LINE")
        insertRelation(first, line)
        insertRelation(second, line)
        return Fixture(first, second, line, insertArea("Area A"), insertArea("Area B"))
    }

    private fun change(ksh: String, target: UUID?, expected: UUID? = null, line: String = "TEST-LINE") =
        """{"kshCode":"$ksh","lineCode":"$line","targetServiceAreaId":${uuid(target)},"expectedCurrentServiceAreaId":${uuid(expected)}}"""

    private fun uuid(id: UUID?) = id?.let { "\"$it\"" } ?: "null"
    private fun body(vararg changes: String, version: String = "pair-v1") =
        """{"expectedReferenceVersion":"$version","changes":[${changes.joinToString(",") }]}"""
    private fun apply(bearer: String, vararg changes: String) = post("$endpoint/apply", body(*changes), bearer)
    private fun pairCount() = jdbc.sql("SELECT COUNT(*) FROM service_area_settlement_lines").query(Int::class.java).single()

    @Test
    fun `preview is read only and atomic apply splits one line between two areas`() {
        val f = fixture(); val admin = adminBearer()
        val request = body(change("00001", f.a), change("00002", f.b))
        val before = auditEventCount("SETTLEMENT_LINE_SERVICE_AREA_CHANGED")
        val preview = post("$endpoint/preview", request, admin)
        assertEquals(200, preview.statusCode(), preview.body())
        assertEquals(2, json(preview).get("changedCount").asInt())
        assertEquals(0, pairCount())
        assertEquals(before, auditEventCount("SETTLEMENT_LINE_SERVICE_AREA_CHANGED"))
        val applied = post("$endpoint/apply", request, admin)
        assertEquals(200, applied.statusCode(), applied.body())
        assertEquals(2, pairCount())
        assertEquals(before + 2, auditEventCount("SETTLEMENT_LINE_SERVICE_AREA_CHANGED"))
        assertEquals(1L, areaAdminVersion(f.a)); assertEquals(1L, areaAdminVersion(f.b))
        assertEquals(f.a, routingSnapshotArea(givenCreatedReport(f.first, f.line).publicId))
        assertEquals(f.b, routingSnapshotArea(givenCreatedReport(f.second, f.line).publicId))
        // Complete single-candidate inference still works without explicit line selection.
        assertEquals(f.a, routingSnapshotArea(givenCreatedReport(f.first).publicId))
        assertEquals(2, json(get(endpoint, admin)).size())
        assertEquals(1, json(get("$endpoint?serviceAreaId=${f.a}", admin)).size())
        assertEquals(1, json(areaDetail(admin, f.a)).get("mappedSettlementLineCount").asInt())
    }

    @Test
    fun `move and removal only affect future reports and leave public history readable`() {
        val f = fixture(); val admin = adminBearer()
        assertEquals(200, apply(admin, change("00001", f.a)).statusCode())
        val original = givenCreatedReport(f.first, f.line)
        assertEquals(200, apply(admin, change("00001", f.b, f.a)).statusCode())
        assertEquals(f.a, routingSnapshotArea(original.publicId))
        assertEquals(f.b, routingSnapshotArea(givenCreatedReport(f.first, f.line).publicId))
        assertEquals(200, getPublicReport(original.publicId, original.credential).statusCode())
        assertEquals(200, apply(admin, change("00001", null, f.b)).statusCode())
        assertNull(routingSnapshotArea(givenCreatedReport(f.first, f.line).publicId))
        assertEquals(f.a, routingSnapshotArea(original.publicId))
    }

    @Test
    fun `missing pair never borrows another settlement area and zero relation still accepts`() {
        val f = fixture(); val admin = adminBearer()
        assertEquals(200, apply(admin, change("00001", f.a)).statusCode())
        val report = givenCreatedReport(f.second, f.line)
        assertEquals("RAILWAY_LINE_UNASSIGNED", routingSnapshot(report.publicId).routingReason)
        val noLine = insertSettlement("00003", "Fixture C")
        assertEquals("NO_VERIFIED_RAILWAY_LINE_REFERENCE", routingSnapshot(givenCreatedReport(noLine).publicId).routingReason)
    }

    @Test
    fun `legacy whole line mappings remain usable and mixing modes is rejected both ways`() {
        val f = fixture(); val admin = adminBearer()
        assertEquals(204, assignLine(admin, f.line, f.a, null).statusCode())
        assertEquals(f.a, routingSnapshotArea(givenCreatedReport(f.second, f.line).publicId))
        assertEquals("SETTLEMENT_LINE_MIXED_ROUTING_MODES", errorCode(apply(admin, change("00001", f.b))))
        assertEquals(204, unassignLine(admin, f.line, f.a).statusCode())
        assertEquals(200, apply(admin, change("00001", f.b)).statusCode())
        assertEquals("SETTLEMENT_LINE_MIXED_ROUTING_MODES", errorCode(assignLine(admin, f.line, f.a, null)))
        assertNull(currentAreaOfLine(f.line))
    }

    @Test
    fun `invalid later row rejects the entire batch without area versions or audits`() {
        val f = fixture(); val admin = adminBearer()
        val before = auditEventCount("SETTLEMENT_LINE_SERVICE_AREA_CHANGED")
        val response = apply(admin, change("00001", f.a), change("99999", f.b))
        assertEquals(409, response.statusCode())
        assertEquals(0, pairCount()); assertEquals(0L, areaAdminVersion(f.a))
        assertEquals(before, auditEventCount("SETTLEMENT_LINE_SERVICE_AREA_CHANGED"))
    }

    @Test
    fun `audit failure rolls back mappings and versions already changed in the transaction`() {
        val f = fixture(); val admin = adminBearer()
        jdbc.sql("ALTER TABLE audit_events ADD CONSTRAINT pair_test_reject CHECK (event_type <> 'SETTLEMENT_LINE_SERVICE_AREA_CHANGED') NOT VALID").update()
        try {
            assertEquals(500, apply(admin, change("00001", f.a), change("00002", f.b)).statusCode())
            assertEquals(0, pairCount()); assertEquals(0L, areaAdminVersion(f.a)); assertEquals(0L, areaAdminVersion(f.b))
        } finally {
            jdbc.sql("ALTER TABLE audit_events DROP CONSTRAINT pair_test_reject").update()
        }
    }

    @Test
    fun `stale preview duplicate rows wrong reference and inactive targets cannot mutate`() {
        val f = fixture(); val admin = adminBearer()
        val request = body(change("00001", f.a))
        assertEquals(200, post("$endpoint/preview", request, admin).statusCode())
        assertEquals(200, apply(admin, change("00001", f.b)).statusCode())
        assertEquals("SETTLEMENT_LINE_ASSIGNMENT_CHANGED", errorCode(post("$endpoint/apply", request, admin)))
        assertEquals(400, apply(admin, change("00002", f.a), change("00002", f.b)).statusCode())
        assertEquals("SETTLEMENT_LINE_REFERENCE_CHANGED", errorCode(post("$endpoint/apply", body(change("00002", f.a), version = "stale"), admin)))
        assertEquals(200, deactivateArea(admin, f.a, 0).statusCode())
        assertEquals("TARGET_SERVICE_AREA_INACTIVE", errorCode(apply(admin, change("00002", f.a))))
        assertEquals("SERVICE_AREA_HAS_RAILWAY_LINES", errorCode(deactivateArea(admin, f.b, 1)))
    }

    @Test
    fun `unchanged request is a no op when current expectation is correct`() {
        val f = fixture(); val admin = adminBearer()
        apply(admin, change("00001", f.a))
        val before = auditEventCount("SETTLEMENT_LINE_SERVICE_AREA_CHANGED")
        val response = apply(admin, change("00001", f.a, f.a))
        assertEquals(200, response.statusCode()); assertEquals(0, json(response).get("changedCount").asInt())
        assertEquals(before, auditEventCount("SETTLEMENT_LINE_SERVICE_AREA_CHANGED")); assertEquals(1L, areaAdminVersion(f.a))
    }

    @Test
    fun `only active authenticated super admins may inspect preview or apply`() {
        val f = fixture(); val admin = adminBearer()
        for (user in listOf(givenServiceUser(), givenGlobalServiceUser(), givenTerritorialModerator(), givenGlobalModerator())) {
            val bearer = bearerFor(user)
            assertEquals(403, get(endpoint, bearer).statusCode())
            assertEquals(403, post("$endpoint/preview", body(change("00001", f.a)), bearer).statusCode())
            assertEquals(403, apply(bearer, change("00001", f.a)).statusCode())
        }
        val deactivated = givenSuperAdmin(); val token = bearerFor(deactivated)
        assertEquals(200, httpDeactivate(admin, deactivated).statusCode())
        assertEquals(401, apply(token, change("00001", f.a)).statusCode())
        assertEquals(0, pairCount())
    }

    @Test
    fun `territorial users see only their paired area and cannot claim across the boundary`() {
        val f = fixture(); val admin = adminBearer()
        apply(admin, change("00001", f.a), change("00002", f.b))
        val a = givenCreatedReport(f.first, f.line); val b = givenCreatedReport(f.second, f.line)
        val user = givenServiceUser(); grantArea(user.id, f.a); val token = bearerFor(user)
        assertEquals(200, detail(token, a.publicId).statusCode())
        assertEquals(404, detail(token, b.publicId).statusCode())
        assertEquals(404, claim(token, b.publicId, 0).statusCode())
        assertEquals(200, claim(token, a.publicId, 0).statusCode())
    }

    @Test
    fun `concurrent assignments have exactly one winner and one audit record`() {
        val f = fixture(); val admin = adminBearer()
        val before = auditEventCount("SETTLEMENT_LINE_SERVICE_AREA_CHANGED")
        val results = runConcurrently(2) { i ->
            rawPost("$endpoint/apply", body(change("00001", if (i == 0) f.a else f.b)), admin)
        }.map { it.getOrThrow() }
        assertEquals(listOf(200, 409), results.map { it.statusCode() }.sorted())
        assertEquals(1, pairCount()); assertEquals(before + 1, auditEventCount("SETTLEMENT_LINE_SERVICE_AREA_CHANGED"))
    }

    @Test
    fun `assignment racing deactivation cannot leave inactive target with new pair`() {
        val f = fixture(); val admin = adminBearer()
        val results = runConcurrently(2) { i ->
            if (i == 0) rawPost("$endpoint/apply", body(change("00001", f.a)), admin)
            else rawDeactivateArea(admin, f.a, 0)
        }.map { it.getOrThrow() }
        assertEquals(listOf(200, 409), results.map { it.statusCode() }.sorted())
        assertTrue(pairCount() == 0 || areaStatus(f.a) == "ACTIVE")
    }

    @Test
    fun `pair move racing submission yields either complete snapshot and never rewrites it`() {
        val f = fixture(); val admin = adminBearer()
        apply(admin, change("00001", f.a))
        val results = runConcurrently(2) { i ->
            if (i == 0) rawPost("$endpoint/apply", body(change("00001", f.b, f.a)), admin)
            else submitReport(submitReportBody(settlementId = f.first, railwayLineId = f.line))
        }.map { it.getOrThrow() }
        assertEquals(200, results[0].statusCode()); assertEquals(201, results[1].statusCode())
        val id = UUID.fromString(json(results[1]).get("reportId").asText())
        val snapshot = routingSnapshot(id)
        assertEquals("ROUTED", snapshot.routingStatus)
        assertTrue(snapshot.serviceAreaId in setOf(f.a, f.b))
        assertEquals(f.line, snapshot.resolvedRailwayLineId); assertEquals(1, routingSnapshotCount(id))
    }

    @Test
    fun `inactive reference cannot receive a mapping but old configuration can be cleaned up`() {
        val f = fixture(); val admin = adminBearer()
        apply(admin, change("00001", f.a))
        jdbc.sql("UPDATE railway_lines SET active = FALSE WHERE id = :id").param("id", f.line).update()
        assertEquals("SETTLEMENT_LINE_REFERENCE_NOT_AVAILABLE", errorCode(apply(admin, change("00002", f.b))))
        assertEquals(200, apply(admin, change("00001", null, f.a)).statusCode())
        assertEquals(0, pairCount())
    }

    @Test
    fun `database rejects invented relations duplicate pairs and deleting a configured reference`() {
        val f = fixture(); val admin = adminBearer()
        apply(admin, change("00001", f.a))
        assertThrows<DataIntegrityViolationException> {
            jdbc.sql("INSERT INTO service_area_settlement_lines VALUES (:s, :l, :a)")
                .param("s", UUID.randomUUID()).param("l", f.line).param("a", f.a).update()
        }
        assertThrows<DataIntegrityViolationException> {
            jdbc.sql("INSERT INTO service_area_settlement_lines VALUES (:s, :l, :a)")
                .param("s", f.first).param("l", f.line).param("a", f.b).update()
        }
        assertThrows<DataIntegrityViolationException> {
            jdbc.sql("DELETE FROM settlement_railway_lines WHERE settlement_id = :s").param("s", f.first).update()
        }
        assertEquals(1, pairCount())
    }

    @Test
    fun `reference import refuses to remove configured relation settlement or line atomically`(@TempDir tmp: Path) {
        referenceImport.import(ReferenceDatasetFixture.write(tmp.resolve("initial"), "initial"))
        val admin = adminBearer(); val area = insertArea("Test territory")
        val assigned = post("$endpoint/apply", body(change("00001", area, line = "1"), version = "initial"), admin)
        assertEquals(200, assigned.statusCode(), assigned.body())
        val relations = ReferenceDatasetFixture.defaultRelations.filter { it.first != "00001" }
        assertThrows<ReferenceMappingInUseException> {
            referenceImport.import(ReferenceDatasetFixture.write(tmp.resolve("remove-relation"), "remove-relation", relations = relations))
        }
        assertThrows<ReferenceMappingInUseException> {
            referenceImport.import(ReferenceDatasetFixture.write(tmp.resolve("remove-settlement"), "remove-settlement",
                settlements = ReferenceDatasetFixture.defaultSettlements.filter { it.first != "00001" }, relations = relations))
        }
        assertThrows<ReferenceLineInUseException> {
            referenceImport.import(ReferenceDatasetFixture.write(tmp.resolve("remove-line"), "remove-line",
                lines = ReferenceDatasetFixture.defaultLines.filter { it.first != "1" },
                relations = ReferenceDatasetFixture.defaultRelations.filter { it.second != "1" }))
        }
        assertEquals(1, pairCount())
        assertEquals("initial", jdbc.sql("SELECT dataset_version FROM reference_dataset_imports WHERE is_current").query(String::class.java).single())
    }
}
