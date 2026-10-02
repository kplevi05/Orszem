package hu.orszembejelento.service.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.servicearea.data.AreaAdminRepository
import hu.orszembejelento.service.servicearea.data.RailwayLineAdminListFilter
import hu.orszembejelento.service.servicearea.data.RailwayLineAdminListItemResponse
import hu.orszembejelento.service.servicearea.data.RailwayLineAdminListPageResponse
import hu.orszembejelento.service.servicearea.data.RailwayLineSettlementAssignmentResponse
import hu.orszembejelento.service.servicearea.data.RailwayLineSettlementMappingsResponse
import hu.orszembejelento.service.servicearea.data.ServiceAreaAdminDetailResponse
import hu.orszembejelento.service.servicearea.data.ServiceAreaAdminListFilter
import hu.orszembejelento.service.servicearea.data.ServiceAreaAdminListPageResponse
import hu.orszembejelento.service.servicearea.data.ServiceAreaAdminResponse
import hu.orszembejelento.service.servicearea.ui.RailwayLinePickerScreen
import hu.orszembejelento.service.servicearea.ui.RailwayLinePickerViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * ADR 0011 in the picker: a pair-configured line says "Településenként hozzárendelve", offers no
 * clickable whole-line assign, and its settlement list opens in the same card without selecting
 * anything - with the owner-approved wording, a spelled-out state, and accessibility semantics.
 */
class RailwayLinePickerPairLevelComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private class Repo(private val lines: List<RailwayLineAdminListItemResponse>, private val detail: RailwayLineSettlementMappingsResponse) : AreaAdminRepository {
        var assignCalls = 0
        var detailCalls = 0
        override suspend fun listAreas(page: Int, size: Int, filter: ServiceAreaAdminListFilter): ApiResult<ServiceAreaAdminListPageResponse> = error("not used")
        override suspend fun areaDetail(areaId: String): ApiResult<ServiceAreaAdminDetailResponse> = error("not used")
        override suspend fun createArea(name: String): ApiResult<ServiceAreaAdminResponse> = error("not used")
        override suspend fun renameArea(areaId: String, expectedVersion: Long, name: String): ApiResult<ServiceAreaAdminResponse> = error("not used")
        override suspend fun activateArea(areaId: String, expectedVersion: Long): ApiResult<ServiceAreaAdminResponse> = error("not used")
        override suspend fun deactivateArea(areaId: String, expectedVersion: Long): ApiResult<ServiceAreaAdminResponse> = error("not used")
        override suspend fun listRailwayLines(page: Int, size: Int, filter: RailwayLineAdminListFilter): ApiResult<RailwayLineAdminListPageResponse> =
            ApiResult.Success(RailwayLineAdminListPageResponse(lines, 0, 50, lines.size, 1))
        override suspend fun railwayLineSettlementMappings(railwayLineId: String): ApiResult<RailwayLineSettlementMappingsResponse> {
            detailCalls++; return ApiResult.Success(detail)
        }
        override suspend fun assignRailwayLine(railwayLineId: String, targetServiceAreaId: String, expectedCurrentServiceAreaId: String?): ApiResult<Unit> {
            assignCalls++; return ApiResult.Success(Unit)
        }
        override suspend fun unassignRailwayLine(railwayLineId: String, expectedCurrentServiceAreaId: String): ApiResult<Unit> = error("not used")
    }

    private val pairLine = RailwayLineAdminListItemResponse("line-1", "1", "1 – Budapest–Győr–Hegyeshalom", true, null, null, "PER_SETTLEMENT", 2)
    private val freeLine = RailwayLineAdminListItemResponse("line-2", "8", "8 – Szabad vonal", true, null, null, "UNASSIGNED", 0)
    private fun detail(items: List<RailwayLineSettlementAssignmentResponse>) =
        RailwayLineSettlementMappingsResponse("line-1", "1", "1 – Budapest–Győr–Hegyeshalom", true, "PER_SETTLEMENT", items.size, items)

    /** The picker is a LazyColumn: a card below the viewport is not composed until scrolled to - scroll first, like a user would. */
    private fun scrollTo(text: String) {
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text))
    }
/** On a small phone the list viewport is shorter than one expanded card - a user simply keeps scrolling the list. */    private fun scrollListDown() {        compose.onAllNodes(hasScrollAction()).onFirst().performTouchInput { swipeUp() }    }
    private fun show(repo: Repo) {
        val vm = RailwayLinePickerViewModel("target-area", repo, onSessionEnded = {})
        compose.setContent {
            RailwayLinePickerScreen(vm, targetAreaId = "target-area", targetAreaName = "Cél terület", onBack = {}, onAssigned = {})
        }
    }

    @Test
    fun pairLineIsLabelledPerSettlementNeverUnassignedAndOffersNoWholeLineAssign() {
        val repo = Repo(listOf(pairLine, freeLine), detail(emptyList()))
        show(repo)
        compose.onNodeWithText("Településenként hozzárendelve").assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithText("Beállított települések: 2").assertIsDisplayed()
        scrollTo("Nincs szolgálati területhez rendelve")
        // Exactly one row (the genuinely free one) says "unassigned" - the pair line never does.
        compose.onAllNodesWithText("Nincs szolgálati területhez rendelve").assertCountEquals(1)
        compose.onNodeWithText("Nincs szolgálati területhez rendelve").assertHasClickAction()
    }

    @Test
    fun openingTheSettlementListShowsTheApprovedWordingAndNeverSelectsTheLine() {
        val repo = Repo(
            listOf(pairLine),
            detail(listOf(RailwayLineSettlementAssignmentResponse("s1", "13601", "Tata", "Komárom-Esztergom", true, "a1", "Székesfehérvár", true))),
        )
        show(repo)
        compose.onNodeWithText("Települések megtekintése").performClick()
        scrollTo("Jelenleg elérhető települések ezen a vonalon")
        compose.onNodeWithText("Jelenleg elérhető települések ezen a vonalon").assertIsDisplayed()
        scrollTo("A lista az ellenőrzött referenciaadatok bővítésével frissül.")
        compose.onNodeWithText("A lista az ellenőrzött referenciaadatok bővítésével frissül.").assertIsDisplayed()
        scrollListDown()
        compose.onNodeWithText("Tata (Komárom-Esztergom)").assertIsDisplayed()
        compose.onNodeWithText("Szolgálati terület: Székesfehérvár").assertIsDisplayed()
        assertEquals("opening the list must never assign anything", 0, repo.assignCalls)
        compose.onNodeWithText("Települések elrejtése").assertIsDisplayed()
    }

    // A legacy backend sends no assignmentMode on any row (and has no settlement-mappings endpoint).
    private val legacyAssigned = RailwayLineAdminListItemResponse("w", "9", "9 – Teljes vonal", true, "area-1", "Régi terület")
    private val legacyFree = RailwayLineAdminListItemResponse("f", "8", "8 – Szabad vonal", true, null, null)

    @Test
    fun againstALegacyBackendThereIsNoPairLevelFilterAndNoDetailButton() {
        val repo = Repo(listOf(legacyAssigned, legacyFree), detail(emptyList()))
        show(repo)
        // The legacy admin controls are all there and usable ...
        compose.onNodeWithText("Összes").assertIsDisplayed()
        compose.onNodeWithText("Hozzárendelés nélküli").assertIsDisplayed()
        compose.onNodeWithText("Teljes vonalhoz rendelt").assertIsDisplayed()
        // ... but the new pair-level filter and the detail control (which would call an endpoint that
        // does not exist there) are not drawn at all - nothing can run into an error.
        compose.onNodeWithText("Településenként").assertDoesNotExist()
        compose.onAllNodesWithText("Települések megtekintése").assertCountEquals(0)
        compose.onNodeWithText("Nincs szolgálati területhez rendelve").assertHasClickAction()
        assertEquals(0, repo.detailCalls)
    }

    @Test
    fun againstAPairAwareBackendTheFilterAndTheDetailButtonAreThere() {
        show(Repo(listOf(pairLine, freeLine), detail(emptyList())))
        compose.onNodeWithText("Településenként").assertIsDisplayed()
        compose.onNodeWithText("Teljes vonalhoz rendelt").assertIsDisplayed()
        compose.onAllNodesWithText("Települések megtekintése").assertCountEquals(2)
    }

    @Test
    fun theToggleAnnouncesTheLineTheCountAndItsOpenOrClosedState() {
        show(Repo(listOf(pairLine), detail(emptyList())))
        compose.onNodeWithContentDescription("1 – Budapest–Győr–Hegyeshalom: 2 beállított település, zárva").assertIsDisplayed()
        compose.onNodeWithText("Települések megtekintése").performClick()
        compose.onNodeWithContentDescription("1 – Budapest–Győr–Hegyeshalom: 2 beállított település, nyitva").assertIsDisplayed()
    }

    @Test
    fun anEmptyListSaysSoInsteadOfClaimingCoverage() {
        show(Repo(listOf(pairLine), detail(emptyList())))
        compose.onNodeWithText("Települések megtekintése").performClick()
        scrollTo("Ehhez a vonalhoz jelenleg nincs ellenőrzött település.")
        compose.onNodeWithText("Ehhez a vonalhoz jelenleg nincs ellenőrzött település.").assertIsDisplayed()
    }

}
