package hu.orszembejelento.service.servicearea

import hu.orszembejelento.service.R
import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.common.ui.errorMessageRes
import hu.orszembejelento.service.servicearea.data.RailwayLineAdminListItemResponse
import hu.orszembejelento.service.servicearea.data.RailwayLineAdminListPageResponse
import hu.orszembejelento.service.servicearea.data.RailwayLineSettlementAssignmentResponse
import hu.orszembejelento.service.servicearea.data.RailwayLineSettlementMappingsResponse
import hu.orszembejelento.service.servicearea.domain.LineRoutingMode
import hu.orszembejelento.service.servicearea.domain.LineRowStatus
import hu.orszembejelento.service.servicearea.domain.canOfferWholeLineAssign
import hu.orszembejelento.service.servicearea.domain.lineRowStatus
import hu.orszembejelento.service.servicearea.domain.routingMode
import hu.orszembejelento.service.servicearea.ui.RailwayLinePickerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ADR 0011 in the ServiceArea admin client: a line configured settlement by settlement must read
 * as exactly that - never as "Nincs szolgálati területhez rendelve" - must never offer the legacy
 * whole-line assign, and its verified settlements are shown (on demand, read-only) with the area
 * each routes to. Whole-line and free lines behave exactly as before.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairLevelRailwayLineTest {

    @Before fun setUpMain() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDownMain() { Dispatchers.resetMain() }

    private fun pairLine(id: String = "line-1", count: Int = 3) =
        RailwayLineAdminListItemResponse(id, "1", "1 – Budapest–Győr–Hegyeshalom", true, null, null, "PER_SETTLEMENT", count)
    private fun wholeLine(id: String, areaId: String) =
        RailwayLineAdminListItemResponse(id, "9", "9 – Teszt", true, areaId, "Terület", "WHOLE_LINE", 0)
    private fun freeLine(id: String) =
        RailwayLineAdminListItemResponse(id, "8", "8 – Szabad", true, null, null, "UNASSIGNED", 0)

    private fun settlement(name: String, area: String?, areaActive: Boolean? = area?.let { true }, active: Boolean = true, county: String? = null) =
        RailwayLineSettlementAssignmentResponse("id-$name", "00001", name, county, active, area?.let { "area-$it" }, area, areaActive)

    private fun mappings(items: List<RailwayLineSettlementAssignmentResponse>, truncated: Boolean = false, total: Int = items.size) =
        RailwayLineSettlementMappingsResponse("line-1", "1", "1 – Budapest–Győr–Hegyeshalom", true, "PER_SETTLEMENT", total, items, truncated)

    // ------------------------------------------------------------------ status / list state

    @Test
    fun `a pair configured line is PER_SETTLEMENT and never reads as unassigned`() {
        val line = pairLine()
        assertEquals(LineRoutingMode.PER_SETTLEMENT, line.routingMode())
        assertEquals(LineRowStatus.PER_SETTLEMENT, lineRowStatus(line, "any-area"))
        assertNotEquals(LineRowStatus.UNASSIGNED, lineRowStatus(line, "any-area"))
    }

    @Test
    fun `the three states stay distinct and whole line and free lines behave as before`() {
        assertEquals(LineRowStatus.UNASSIGNED, lineRowStatus(freeLine("f"), "target"))
        assertEquals(LineRowStatus.IN_OTHER_AREA, lineRowStatus(wholeLine("w", "other"), "target"))
        assertEquals(LineRowStatus.ALREADY_IN_THIS_AREA, lineRowStatus(wholeLine("w", "target"), "target"))
        assertEquals(LineRowStatus.PER_SETTLEMENT, lineRowStatus(pairLine(), "target"))
        assertEquals(LineRowStatus.INACTIVE, lineRowStatus(freeLine("f").copy(active = false), "target"))
    }

    @Test
    fun `a backend older than assignmentMode still renders - falls back to the legacy whole-line inference`() {
        val legacyAssigned = RailwayLineAdminListItemResponse("x", "9", "n", true, "area-1", "Terület")
        val legacyFree = RailwayLineAdminListItemResponse("y", "8", "n", true, null, null)
        assertEquals(LineRoutingMode.WHOLE_LINE, legacyAssigned.routingMode())
        assertEquals(LineRoutingMode.UNASSIGNED, legacyFree.routingMode())
    }

    @Test
    fun `the new response fields decode, and an unknown future mode value cannot crash the client`() {
        val json = Json { ignoreUnknownKeys = true }
        val item = json.decodeFromString<RailwayLineAdminListItemResponse>(
            """{"id":"a","lineCode":"1","displayName":"n","active":true,"currentServiceAreaId":null,"currentServiceAreaName":null,"assignmentMode":"PER_SETTLEMENT","settlementMappingCount":146,"somethingNew":1}""",
        )
        assertEquals(LineRoutingMode.PER_SETTLEMENT, item.routingMode()); assertEquals(146, item.settlementMappingCount)
        val future = json.decodeFromString<RailwayLineAdminListItemResponse>(
            """{"id":"a","lineCode":"1","displayName":"n","active":true,"assignmentMode":"SOMETHING_FUTURE"}""",
        )
        assertEquals(LineRoutingMode.UNASSIGNED, future.routingMode())
    }

    // ------------------------------------------------ no clickable whole-line assign for a pair line

    @Test
    fun `the whole line assign is offered only where it can succeed`() {
        assertFalse(canOfferWholeLineAssign(pairLine(), "target"))
        assertFalse(canOfferWholeLineAssign(freeLine("f").copy(active = false), "target"))
        assertFalse(canOfferWholeLineAssign(wholeLine("w", "target"), "target"))
        assertTrue(canOfferWholeLineAssign(freeLine("f"), "target"))
        assertTrue(canOfferWholeLineAssign(wholeLine("w", "other"), "target"))
    }

    @Test
    fun `confirming an assign for a pair configured line never reaches the network`() = runTest {
        val fake = FakeAreaAdminRepository()
        val vm = RailwayLinePickerViewModel("target-area", fake, onSessionEnded = {})
        vm.confirmAssign(pairLine())
        assertEquals(0, fake.assignCalls)
        assertFalse(vm.state.value.assigning); assertFalse(vm.state.value.assigned)
    }

    // ------------------------------------------------------------- localized MIXED_ROUTING_MODES

    @Test
    fun `SETTLEMENT_LINE_MIXED_ROUTING_MODES has its own message, not the generic unexpected-error one`() {
        assertEquals(R.string.error_settlement_line_mixed_routing_modes, errorMessageRes("SETTLEMENT_LINE_MIXED_ROUTING_MODES"))
        assertNotEquals(R.string.error_unexpected, errorMessageRes("SETTLEMENT_LINE_MIXED_ROUTING_MODES"))
    }

    @Test
    fun `an old client that still hits the whole-line endpoint surfaces the mixed-mode failure instead of a generic one`() = runTest {
        val fake = FakeAreaAdminRepository(assignRailwayLineResult = ApiResult.Failure("SETTLEMENT_LINE_MIXED_ROUTING_MODES", 409))
        val vm = RailwayLinePickerViewModel("target-area", fake, onSessionEnded = {})
        vm.confirmAssign(freeLine("stale-view-of-a-line-that-became-pair-level"))
        val error = vm.state.value.assignError as ApiResult.Failure
        assertEquals("SETTLEMENT_LINE_MIXED_ROUTING_MODES", error.code)
        assertEquals(R.string.error_settlement_line_mixed_routing_modes, errorMessageRes(error.code))
        assertFalse(vm.state.value.assigned)
    }

    // ------------------------------------------------------------------- settlement detail

    @Test
    fun `opening a line fetches its settlements once and never selects or assigns it`() = runTest {
        val fake = FakeAreaAdminRepository(
            settlementMappingsResult = ApiResult.Success(mappings(listOf(settlement("Tata", "Székesfehérvár"), settlement("Vác", "Budapest")))),
            listRailwayLinesResult = ApiResult.Success(RailwayLineAdminListPageResponse(listOf(pairLine()), 0, 50, 1, 1)),
        )
        val vm = RailwayLinePickerViewModel("target-area", fake, onSessionEnded = {})
        vm.toggleDetails("line-1")
        val loaded = vm.state.value.details["line-1"] as RailwayLinePickerViewModel.LineDetailState.Loaded
        assertEquals(listOf("Tata", "Vác"), loaded.mappings.items.map { it.settlementName })
        assertEquals("Székesfehérvár", loaded.mappings.items[0].serviceAreaName)
        assertTrue("line-1" in vm.state.value.expandedLineIds)
        assertEquals(0, fake.assignCalls); assertFalse(vm.state.value.assigned); assertFalse(vm.state.value.assigning)
        // Closing and reopening reuses the loaded detail: still exactly one request.
        vm.toggleDetails("line-1"); vm.toggleDetails("line-1")
        assertEquals(1, fake.settlementMappingsCalls)
    }

    @Test
    fun `the list itself triggers no per-line detail request - no N+1`() = runTest {
        val lines = (1..50).map { pairLine("line-$it") }
        val fake = FakeAreaAdminRepository(listRailwayLinesResult = ApiResult.Success(RailwayLineAdminListPageResponse(lines, 0, 50, 50, 1)))
        val vm = RailwayLinePickerViewModel("target-area", fake, onSessionEnded = {})
        assertEquals(50, vm.state.value.items.size)
        assertEquals(0, fake.settlementMappingsCalls)
    }

    @Test
    fun `an empty settlement list is a normal loaded state`() = runTest {
        val fake = FakeAreaAdminRepository(settlementMappingsResult = ApiResult.Success(mappings(emptyList())))
        val vm = RailwayLinePickerViewModel("target-area", fake, onSessionEnded = {})
        vm.toggleDetails("line-1")
        val loaded = vm.state.value.details["line-1"] as RailwayLinePickerViewModel.LineDetailState.Loaded
        assertTrue(loaded.mappings.items.isEmpty())
    }

    @Test
    fun `a long list is kept in full and a truncated one carries its honest total`() = runTest {
        val long = (1..120).map { settlement("Város %03d".format(it), "Székesfehérvár") }
        val fake = FakeAreaAdminRepository(settlementMappingsResult = ApiResult.Success(mappings(long, truncated = true, total = 600)))
        val vm = RailwayLinePickerViewModel("target-area", fake, onSessionEnded = {})
        vm.toggleDetails("line-1")
        val loaded = vm.state.value.details["line-1"] as RailwayLinePickerViewModel.LineDetailState.Loaded
        assertEquals(120, loaded.mappings.items.size); assertTrue(loaded.mappings.truncated); assertEquals(600, loaded.mappings.settlementCount)
    }

    @Test
    fun `a failed detail load is recoverable with a retry and does not touch the rest of the screen`() = runTest {
        val fake = FakeAreaAdminRepository(settlementMappingsResult = ApiResult.NetworkError)
        val vm = RailwayLinePickerViewModel("target-area", fake, onSessionEnded = {})
        vm.toggleDetails("line-1")
        assertTrue(vm.state.value.details["line-1"] is RailwayLinePickerViewModel.LineDetailState.Failed)
        assertEquals(null, vm.state.value.loadError)
        fake.settlementMappingsResult = ApiResult.Success(mappings(listOf(settlement("Tata", "Székesfehérvár"))))
        vm.retryDetails("line-1")
        assertTrue(vm.state.value.details["line-1"] is RailwayLinePickerViewModel.LineDetailState.Loaded)
    }

    @Test
    fun `an ended session during a detail load signs out like every other call`() = runTest {
        var ended = 0
        val fake = FakeAreaAdminRepository(settlementMappingsResult = ApiResult.SessionEnded)
        val vm = RailwayLinePickerViewModel("target-area", fake, onSessionEnded = { ended++ })
        vm.toggleDetails("line-1")
        assertEquals(1, ended)
    }

    // ---------------------------------------------- what the settlement rows can and cannot say

    @Test
    fun `a settlement can be unmapped, retired, or routed to an inactive area - each stays representable`() {
        val unmapped = settlement("Alap", null)
        val retired = settlement("Régi", "Pécs", active = false)
        val inactiveArea = settlement("Sóly", "Zárt", areaActive = false)
        assertEquals(null, unmapped.serviceAreaName)
        assertFalse(retired.settlementActive)
        assertEquals(false, inactiveArea.serviceAreaActive)
    }

    @Test
    fun `the detail DTO has no routing reason, evidence, revision or reference field to leak into the UI`() {
        val fields = RailwayLineSettlementAssignmentResponse::class.java.declaredFields.map { it.name }.toSet() +
            RailwayLineSettlementMappingsResponse::class.java.declaredFields.map { it.name }.toSet()
        for (forbidden in listOf("routingReason", "evidence", "adminVersion", "referenceVersion", "datasetVersion", "verification", "revision")) {
            assertFalse("the client DTOs must not carry '$forbidden'", fields.any { it.equals(forbidden, ignoreCase = true) })
        }
    }

    @Test
    fun `the user-facing copy is the owner-approved wording and leaks no internal terminology`() {
        val xml = java.io.File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        fun stringValue(name: String): String =
            Regex("<string name=\"$name\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
                ?: error("string resource $name not found")
        assertEquals("Jelenleg elérhető települések ezen a vonalon", stringValue("line_settlements_title"))
        assertEquals("A lista az ellenőrzött referenciaadatok bővítésével frissül.", stringValue("line_settlements_partial_note"))
        assertEquals("Településenként hozzárendelve", stringValue("railway_line_per_settlement"))
        val userFacing = listOf(
            "line_settlements_title", "line_settlements_partial_note", "railway_line_per_settlement", "railway_line_per_settlement_count",
            "railway_line_per_settlement_hint", "line_details_open", "line_details_close", "line_settlements_loading", "line_settlements_empty",
            "line_settlements_truncated", "line_settlement_no_area", "line_settlement_area", "error_settlement_line_mixed_routing_modes",
            "filter_assignment_per_settlement",
        ).map { stringValue(it) }
        for (text in userFacing) {
            for (internal in listOf("PARTIAL", "VERIFIED", "OSM", "evidence", "reason", "revision", "UNCLASSIFIED", "MIXED_ROUTING")) {
                assertFalse("user-facing copy '$text' must not contain '$internal'", text.contains(internal, ignoreCase = true))
            }
        }
        // The mixed-mode copy tells the admin WHY: the line is configured per settlement.
        assertTrue(stringValue("error_settlement_line_mixed_routing_modes").contains("településenként"))
    }
}
