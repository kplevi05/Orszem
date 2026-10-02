package hu.orszembejelento.backend.areaadmin

import hu.orszembejelento.backend.areaadmin.support.AreaAdminTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/**
 * The RailwayLine admin surface once pair-level (settlement + line) mappings exist (ADR 0011):
 * a pair-configured line must read as "configured per settlement" - never as unassigned - must
 * stay out of the UNASSIGNED filter, must reject the legacy whole-line operations, and exposes a
 * read-only, SUPER_ADMIN-only detail of its verified settlements and where each routes.
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class RailwayLinePairLevelAdminIT : AreaAdminTestSupport() {

    private val mappings = "/api/v1/service/service-area-admin/settlement-line-mappings"
    private fun detailPath(lineId: UUID) = "/api/v1/service/service-area-admin/railway-lines/$lineId/settlement-mappings"

    private data class Fixture(val first: UUID, val second: UUID, val line: UUID, val a: UUID, val b: UUID)

    /** One line with two verified settlements, mapped pair by pair to two different areas. */
    private fun pairConfiguredLine(admin: String): Fixture {
        setCurrentReferenceState("pair-v1")
        val first = insertSettlement("00001", "Fixture A")
        val second = insertSettlement("00002", "Fixture B")
        val line = insertLine("PAIR-LINE")
        insertRelation(first, line)
        insertRelation(second, line)
        val f = Fixture(first, second, line, insertArea("Area A"), insertArea("Area B"))
        val body = """{"expectedReferenceVersion":"pair-v1","changes":[
            {"kshCode":"00001","lineCode":"PAIR-LINE","targetServiceAreaId":"${f.a}","expectedCurrentServiceAreaId":null},
            {"kshCode":"00002","lineCode":"PAIR-LINE","targetServiceAreaId":"${f.b}","expectedCurrentServiceAreaId":null}]}"""
        assertEquals(200, post("$mappings/apply", body, admin).statusCode())
        return f
    }

    private fun listItem(admin: String, lineId: UUID, assignment: String? = null): tools.jackson.databind.JsonNode? {
        val response = listRailwayLines(admin, size = 100, assignment = assignment)
        assertEquals(200, response.statusCode())
        return json(response).get("items").asList().firstOrNull { it.get("id").asText() == lineId.toString() }
    }

    // ------------------------------------------------------------------------- list status

    @Test
    fun `a pair configured line is listed as per settlement, never as unassigned`() {
        val admin = adminBearer(); val f = pairConfiguredLine(admin)
        val item = listItem(admin, f.line)!!
        assertEquals("PER_SETTLEMENT", item.get("assignmentMode").asText())
        assertEquals(2, item.get("settlementMappingCount").asInt())
        assertTrue(item.get("currentServiceAreaId").isNull)
        assertTrue(item.get("currentServiceAreaName").isNull)
    }

    @Test
    fun `the unassigned filter excludes a pair configured line while the per settlement filter includes it`() {
        val admin = adminBearer(); val f = pairConfiguredLine(admin)
        val trulyFree = insertLine("FREE-LINE")
        assertNull(listItem(admin, f.line, "UNASSIGNED"), "a pair configured line must not be 'unassigned'")
        assertEquals("UNASSIGNED", listItem(admin, trulyFree, "UNASSIGNED")!!.get("assignmentMode").asText())
        assertNull(listItem(admin, trulyFree, "PER_SETTLEMENT"))
        assertEquals("PER_SETTLEMENT", listItem(admin, f.line, "PER_SETTLEMENT")!!.get("assignmentMode").asText())
        assertNull(listItem(admin, f.line, "ASSIGNED"), "ASSIGNED keeps its legacy whole-line meaning")
        val total = json(listRailwayLines(admin, size = 100)).get("totalElements").asInt()
        val parts = listOf("UNASSIGNED", "PER_SETTLEMENT", "ASSIGNED")
            .sumOf { json(listRailwayLines(admin, size = 100, assignment = it)).get("totalElements").asInt() }
        assertEquals(total, parts, "every line is in exactly one of the three modes")
    }

    @Test
    fun `a whole line mapped fixture keeps its behaviour and a free line stays unassigned`() {
        val admin = adminBearer(); val area = givenRoutedArea()
        val whole = listItem(admin, area.lineId)!!
        assertEquals("WHOLE_LINE", whole.get("assignmentMode").asText())
        assertEquals(0, whole.get("settlementMappingCount").asInt())
        assertEquals(area.areaId.toString(), whole.get("currentServiceAreaId").asText())
        assertEquals(area.lineId.toString(), listItem(admin, area.lineId, "ASSIGNED")!!.get("id").asText())
        val free = insertLine("L${(100000..999999).random()}")
        val freeItem = listItem(admin, free)!!
        assertEquals("UNASSIGNED", freeItem.get("assignmentMode").asText()); assertEquals(0, freeItem.get("settlementMappingCount").asInt())
    }

    // ----------------------------------------------- legacy whole-line operations stay refused

    @Test
    fun `the legacy whole line assign still rejects a pair configured line with 409 and changes nothing`() {
        val admin = adminBearer(); val f = pairConfiguredLine(admin)
        val before = jdbc.sql("SELECT COUNT(*) FROM service_area_settlement_lines").query(Int::class.java).single()
        val versionBefore = areaAdminVersion(f.a)
        val response = assignLine(admin, f.line, f.a, null)
        assertEquals(409, response.statusCode())
        assertEquals("SETTLEMENT_LINE_MIXED_ROUTING_MODES", errorCode(response))
        assertNull(currentAreaOfLine(f.line))
        assertEquals(before, jdbc.sql("SELECT COUNT(*) FROM service_area_settlement_lines").query(Int::class.java).single())
        assertEquals(versionBefore, areaAdminVersion(f.a))
        // A "move" attempt is refused the same way.
        assertEquals("SETTLEMENT_LINE_MIXED_ROUTING_MODES", errorCode(assignLine(admin, f.line, f.b, f.a)))
    }

    @Test
    fun `the legacy whole line unassign is also refused for a pair configured line`() {
        val admin = adminBearer(); val f = pairConfiguredLine(admin)
        val response = unassignLine(admin, f.line, f.a)
        assertEquals(409, response.statusCode())
        assertEquals(2, jdbc.sql("SELECT COUNT(*) FROM service_area_settlement_lines").query(Int::class.java).single())
    }

    @Test
    fun `existing reports keep their routing snapshot and new ones keep resolving per pair`() {
        val admin = adminBearer(); val f = pairConfiguredLine(admin)
        val first = givenCreatedReport(f.first, f.line)
        assertEquals(f.a, routingSnapshotArea(first.publicId))
        assertEquals(409, assignLine(admin, f.line, f.b, null).statusCode())
        assertEquals(f.a, routingSnapshotArea(first.publicId))
        assertEquals(f.b, routingSnapshotArea(givenCreatedReport(f.second, f.line).publicId))
    }

    // ------------------------------------------------------------------- pair-level detail

    @Test
    fun `detail lists every verified settlement with its effective area and exposes no internal field`() {
        val admin = adminBearer(); val f = pairConfiguredLine(admin)
        val unmapped = insertSettlement("00003", "Fixture C"); insertRelation(unmapped, f.line) // verified but not mapped
        val response = get(detailPath(f.line), admin)
        assertEquals(200, response.statusCode(), response.body())
        val body = json(response)
        assertEquals("PER_SETTLEMENT", body.get("assignmentMode").asText())
        assertEquals(3, body.get("settlementCount").asInt()); assertFalse(body.get("truncated").asBoolean())
        val byName = body.get("items").asList().associateBy { it.get("settlementName").asText() }
        assertEquals(f.a.toString(), byName.getValue("Fixture A").get("serviceAreaId").asText())
        assertEquals("Area A", byName.getValue("Fixture A").get("serviceAreaName").asText())
        assertEquals(f.b.toString(), byName.getValue("Fixture B").get("serviceAreaId").asText())
        assertTrue(byName.getValue("Fixture C").get("serviceAreaId").isNull, "a verified pair without a mapping resolves to no area")
        val raw = response.body()
        for (forbidden in listOf("routingReason", "evidence", "adminVersion", "referenceVersion", "datasetVersion", "verification")) {
            assertFalse(raw.contains(forbidden), "the detail must not expose '$forbidden'")
        }
    }

    @Test
    fun `detail is ordered in Hungarian collation, not by database or binary order`() {
        val admin = adminBearer(); setCurrentReferenceState("pair-v1")
        val line = insertLine("SORT-LINE")
        // Binary order would be: Alap, Balatonfüred, Csopak, Ábrahámhegy.
        for ((i, name) in listOf("Csopak", "Ábrahámhegy", "Balatonfüred", "Alap").withIndex()) {
            insertRelation(insertSettlement("1000$i", name), line)
        }
        val names = json(get(detailPath(line), admin)).get("items").asList().map { it.get("settlementName").asText() }
        assertEquals(listOf("Ábrahámhegy", "Alap", "Balatonfüred", "Csopak"), names)
    }

    @Test
    fun `detail of a whole line mapped line reports that area for every settlement`() {
        val admin = adminBearer(); val area = givenRoutedArea()
        val extra = insertSettlement("20001", "Extra Town"); insertRelation(extra, area.lineId)
        val body = json(get(detailPath(area.lineId), admin))
        assertEquals("WHOLE_LINE", body.get("assignmentMode").asText())
        assertTrue(body.get("items").size() >= 1)
        assertTrue(body.get("items").asList().all { it.get("serviceAreaId").asText() == area.areaId.toString() })
    }

    @Test
    fun `detail of a line with no relations is an empty list, never an error`() {
        val admin = adminBearer(); val line = insertLine("EMPTY-LINE")
        val response = get(detailPath(line), admin)
        assertEquals(200, response.statusCode())
        val body = json(response)
        assertEquals(0, body.get("settlementCount").asInt()); assertEquals(0, body.get("items").size())
        assertEquals("UNASSIGNED", body.get("assignmentMode").asText()); assertFalse(body.get("truncated").asBoolean())
    }

    @Test
    fun `a long relation list is returned in full below the cap and honestly truncated above it`() {
        val admin = adminBearer(); setCurrentReferenceState("pair-v1")
        val line = insertLine("LONG-LINE")
        for (i in 0 until 505) insertRelation(insertSettlement("%05d".format(30000 + i), "Town %04d".format(i)), line)
        val body = json(get(detailPath(line), admin))
        assertEquals(505, body.get("settlementCount").asInt())
        assertEquals(500, body.get("items").size())
        assertTrue(body.get("truncated").asBoolean())
    }

    @Test
    fun `an inactive settlement is still listed but flagged`() {
        val admin = adminBearer(); setCurrentReferenceState("pair-v1")
        val line = insertLine("RETIRED-LINE")
        insertRelation(insertSettlement("40001", "Old Town", active = false), line)
        val item = json(get(detailPath(line), admin)).get("items").single()
        assertFalse(item.get("settlementActive").asBoolean())
    }

    @Test
    fun `detail of an unknown line is a 404 with a stable code`() {
        val response = get(detailPath(UUID.randomUUID()), adminBearer())
        assertEquals(404, response.statusCode())
        assertEquals("RAILWAY_LINE_NOT_FOUND", errorCode(response))
    }

    @Test
    fun `only a SUPER_ADMIN may read the pair detail or the new filter values`() {
        val admin = adminBearer(); val f = pairConfiguredLine(admin)
        for (user in listOf(givenServiceUser(), givenGlobalServiceUser(), givenTerritorialModerator(), givenGlobalModerator())) {
            val bearer = bearerFor(user)
            assertEquals(403, get(detailPath(f.line), bearer).statusCode())
            assertEquals(403, listRailwayLines(bearer, assignment = "PER_SETTLEMENT").statusCode())
        }
        assertEquals(401, get(detailPath(f.line)).statusCode())
    }

    @Test
    fun `reading the detail changes nothing`() {
        val admin = adminBearer(); val f = pairConfiguredLine(admin)
        val audits = auditEventCount("SETTLEMENT_LINE_SERVICE_AREA_CHANGED")
        repeat(3) { assertEquals(200, get(detailPath(f.line), admin).statusCode()) }
        assertEquals(audits, auditEventCount("SETTLEMENT_LINE_SERVICE_AREA_CHANGED"))
        assertEquals(2, jdbc.sql("SELECT COUNT(*) FROM service_area_settlement_lines").query(Int::class.java).single())
    }
}
