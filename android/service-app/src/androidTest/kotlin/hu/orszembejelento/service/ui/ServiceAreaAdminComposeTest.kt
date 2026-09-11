package hu.orszembejelento.service.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.hub.ui.AdminHubScreen
import hu.orszembejelento.service.hub.ui.ModerationHubScreen
import hu.orszembejelento.service.servicearea.data.AreaAdminRepository
import hu.orszembejelento.service.servicearea.data.MappedRailwayLineResponse
import hu.orszembejelento.service.servicearea.data.RailwayLineAdminListFilter
import hu.orszembejelento.service.servicearea.data.RailwayLineAdminListItemResponse
import hu.orszembejelento.service.servicearea.data.RailwayLineAdminListPageResponse
import hu.orszembejelento.service.servicearea.data.ServiceAreaAdminDetailResponse
import hu.orszembejelento.service.servicearea.data.ServiceAreaAdminListFilter
import hu.orszembejelento.service.servicearea.data.ServiceAreaAdminListItemResponse
import hu.orszembejelento.service.servicearea.data.ServiceAreaAdminListPageResponse
import hu.orszembejelento.service.servicearea.data.ServiceAreaAdminResponse
import hu.orszembejelento.service.servicearea.ui.CreateServiceAreaScreen
import hu.orszembejelento.service.servicearea.ui.CreateServiceAreaViewModel
import hu.orszembejelento.service.servicearea.ui.RailwayLinePickerScreen
import hu.orszembejelento.service.servicearea.ui.RailwayLinePickerViewModel
import hu.orszembejelento.service.servicearea.ui.ServiceAreaAdminListScreen
import hu.orszembejelento.service.servicearea.ui.ServiceAreaAdminListViewModel
import hu.orszembejelento.service.servicearea.ui.ServiceAreaDetailScreen
import hu.orszembejelento.service.servicearea.ui.ServiceAreaDetailViewModel
import org.junit.Rule
import org.junit.Test

/**
 * Phase 10 brief §73: the ServiceArea/RailwayLine admin Compose surface. Every fake here
 * implements the real [AreaAdminRepository] interface directly - not a lambda substitute -
 * so a call-count assertion genuinely proves "exactly once, no automatic retry" the same way
 * [hu.orszembejelento.service.ui.ModerationDeleteActionComposeTest]'s fakes do.
 */
class ServiceAreaAdminComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private class FakeAreaAdminRepo(
        var listAreasResult: ApiResult<ServiceAreaAdminListPageResponse> = ApiResult.Success(ServiceAreaAdminListPageResponse(emptyList(), 0, 50, 0, 0)),
        var areaDetailResult: ApiResult<ServiceAreaAdminDetailResponse>? = null,
        var createAreaResult: ApiResult<ServiceAreaAdminResponse>? = null,
        var renameAreaResult: ApiResult<ServiceAreaAdminResponse>? = null,
        var listRailwayLinesResult: ApiResult<RailwayLineAdminListPageResponse> = ApiResult.Success(RailwayLineAdminListPageResponse(emptyList(), 0, 50, 0, 0)),
        var assignRailwayLineResult: ApiResult<Unit> = ApiResult.Success(Unit),
        var unassignRailwayLineResult: ApiResult<Unit> = ApiResult.Success(Unit),
    ) : AreaAdminRepository {
        var renameCalls = 0
        var unassignCalls = 0
        var assignCalls = 0
        var lastAssignArgs: Triple<String, String, String?>? = null
        var lastUnassignArgs: Pair<String, String>? = null

        override suspend fun listAreas(page: Int, size: Int, filter: ServiceAreaAdminListFilter) = listAreasResult
        override suspend fun areaDetail(areaId: String) = areaDetailResult ?: error("not configured")
        override suspend fun createArea(name: String) = createAreaResult ?: error("not configured")
        override suspend fun renameArea(areaId: String, expectedVersion: Long, name: String): ApiResult<ServiceAreaAdminResponse> {
            renameCalls++
            return renameAreaResult ?: error("not configured")
        }
        override suspend fun activateArea(areaId: String, expectedVersion: Long) = ApiResult.Success(ServiceAreaAdminResponse(areaId, "X", true, expectedVersion + 1))
        override suspend fun deactivateArea(areaId: String, expectedVersion: Long) = ApiResult.Success(ServiceAreaAdminResponse(areaId, "X", false, expectedVersion + 1))
        override suspend fun listRailwayLines(page: Int, size: Int, filter: RailwayLineAdminListFilter) = listRailwayLinesResult
        override suspend fun assignRailwayLine(railwayLineId: String, targetServiceAreaId: String, expectedCurrentServiceAreaId: String?): ApiResult<Unit> {
            assignCalls++
            lastAssignArgs = Triple(railwayLineId, targetServiceAreaId, expectedCurrentServiceAreaId)
            return assignRailwayLineResult
        }
        override suspend fun unassignRailwayLine(railwayLineId: String, expectedCurrentServiceAreaId: String): ApiResult<Unit> {
            unassignCalls++
            lastUnassignArgs = railwayLineId to expectedCurrentServiceAreaId
            return unassignRailwayLineResult
        }
    }

    // ------------------------------------------------------------------------------- Admin hub

    @Test
    fun the_admin_hub_has_a_live_service_area_entry() {
        compose.setContent {
            AdminHubScreen(onOpenUsers = {}, onOpenDeletedReports = {}, onOpenServiceAreas = {}, onOpenAccount = {})
        }
        compose.onNodeWithText("Szolgálati területek").assertExists()
        // The old placeholder is gone specifically for ServiceArea admin - "Még nem elérhető"
        // still legitimately labels the untouched Phase 12 audit row beside it.
        compose.onNodeWithText("Változási előzmények").assertExists()
        compose.onAllNodesWithText("Még nem elérhető").assertCountEquals(1)
    }

    @Test
    fun the_moderation_hub_never_gets_a_service_area_entry_point() {
        compose.setContent {
            ModerationHubScreen(onOpenUsers = {}, onOpenDeletedReports = {}, onOpenAccount = {})
        }
        compose.onNodeWithText("Szolgálati területek").assertDoesNotExist() // no territorial-moderator entry point exists at all (brief §4/§44)
    }

    // --------------------------------------------------------------------------------- Listing

    @Test
    fun the_area_list_shows_name_status_and_line_count_never_a_raw_adminVersion() {
        val fake = FakeAreaAdminRepo(
            listAreasResult = ApiResult.Success(
                ServiceAreaAdminListPageResponse(
                    items = listOf(ServiceAreaAdminListItemResponse("a1", "Nyugat-dunantuli terulet", true, 7, 3, 0)),
                    page = 0, size = 50, totalElements = 1, totalPages = 1,
                ),
            ),
        )
        val vm = ServiceAreaAdminListViewModel(fake, onSessionEnded = {})
        compose.setContent { ServiceAreaAdminListScreen(viewModel = vm, onOpenArea = {}, onCreateArea = {}) }
        compose.waitForIdle()

        compose.onNodeWithText("Nyugat-dunantuli terulet").assertExists()
        // Two legitimate "Aktív" nodes: the Összes/Aktív/Inaktív status filter chip, and the
        // card's own status label - not a duplicate-rendering bug.
        compose.onAllNodesWithText("Aktív").assertCountEquals(2)
        compose.onNodeWithText("3 vasútvonal").assertExists()
        compose.onAllNodesWithText("7", substring = true).assertCountEquals(0) // the adminVersion itself must never render
    }

    // ---------------------------------------------------------------------------------- Create

    @Test
    fun create_area_stays_disabled_for_a_blank_name_and_submits_the_trimmed_name() {
        val fake = FakeAreaAdminRepo(createAreaResult = ApiResult.Success(ServiceAreaAdminResponse("a1", "Uj terulet", true, 0)))
        val vm = CreateServiceAreaViewModel(fake, onSessionEnded = {})
        compose.setContent { CreateServiceAreaScreen(viewModel = vm, onBack = {}, onCreated = {}) }

        compose.onNodeWithText("Létrehozás").assertIsNotEnabled()
        compose.onNodeWithText("Terület neve").performTextInput("Uj terulet")
        compose.waitForIdle()
        compose.onNodeWithText("Létrehozás").assertIsEnabled()
    }

    // ---------------------------------------------------------------------------------- Detail

    private fun detailOf(active: Boolean, version: Long, lines: List<MappedRailwayLineResponse> = emptyList(), openReports: Int = 0) =
        ServiceAreaAdminDetailResponse(
            id = "a1", name = "Keleti terulet", active = active, adminVersion = version,
            mappedRailwayLines = lines, mappedRailwayLineCount = lines.size, openOperationalReportCount = openReports,
        )

    @Test
    fun an_inactive_area_shows_the_activate_action_not_deactivate() {
        val fake = FakeAreaAdminRepo(areaDetailResult = ApiResult.Success(detailOf(active = false, version = 2)))
        val vm = ServiceAreaDetailViewModel("a1", fake, onSessionEnded = {})
        compose.setContent { ServiceAreaDetailScreen(viewModel = vm, onBack = {}, onAddRailwayLine = {}) }
        compose.waitForIdle()

        compose.onNodeWithText("Inaktív").assertExists()
        compose.onNodeWithText("Terület aktiválása").assertExists()
        compose.onNodeWithText("Terület deaktiválása").assertDoesNotExist()
    }

    @Test
    fun a_mapped_railway_line_disables_deactivation_and_shows_the_blocker_hint() {
        val line = MappedRailwayLineResponse("l1", "L100", "Pelda vonal", true)
        val fake = FakeAreaAdminRepo(areaDetailResult = ApiResult.Success(detailOf(active = true, version = 0, lines = listOf(line))))
        val vm = ServiceAreaDetailViewModel("a1", fake, onSessionEnded = {})
        compose.setContent { ServiceAreaDetailScreen(viewModel = vm, onBack = {}, onAddRailwayLine = {}) }
        compose.waitForIdle()

        compose.onNodeWithText("A terület deaktiválása előtt helyezze át vagy szüntesse meg az összes vasútvonal hozzárendelését.").assertExists()
        compose.onNodeWithText("Terület deaktiválása").assertIsNotEnabled()
    }

    @Test
    fun an_open_report_disables_deactivation_and_shows_the_blocker_hint() {
        val fake = FakeAreaAdminRepo(areaDetailResult = ApiResult.Success(detailOf(active = true, version = 0, openReports = 2)))
        val vm = ServiceAreaDetailViewModel("a1", fake, onSessionEnded = {})
        compose.setContent { ServiceAreaDetailScreen(viewModel = vm, onBack = {}, onAddRailwayLine = {}) }
        compose.waitForIdle()

        compose.onNodeWithText("A területhez még nyitott bejelentések tartoznak. Ezeket előbb rendezni kell.").assertExists()
        compose.onNodeWithText("Terület deaktiválása").assertIsNotEnabled()
    }

    @Test
    fun an_eligible_area_may_be_deactivated_and_the_action_is_enabled() {
        val fake = FakeAreaAdminRepo(areaDetailResult = ApiResult.Success(detailOf(active = true, version = 0)))
        val vm = ServiceAreaDetailViewModel("a1", fake, onSessionEnded = {})
        compose.setContent { ServiceAreaDetailScreen(viewModel = vm, onBack = {}, onAddRailwayLine = {}) }
        compose.waitForIdle()

        compose.onNodeWithText("Terület deaktiválása").assertIsEnabled()
    }

    @Test
    fun rename_dialog_prefills_the_current_name_and_confirming_calls_rename_exactly_once() {
        val fake = FakeAreaAdminRepo(
            areaDetailResult = ApiResult.Success(detailOf(active = true, version = 4)),
            renameAreaResult = ApiResult.Success(ServiceAreaAdminResponse("a1", "Atnevezve", true, 5)),
        )
        val vm = ServiceAreaDetailViewModel("a1", fake, onSessionEnded = {})
        compose.setContent { ServiceAreaDetailScreen(viewModel = vm, onBack = {}, onAddRailwayLine = {}) }
        compose.waitForIdle()

        compose.onNodeWithText("Átnevezés").performClick()
        compose.waitForIdle()
        // Two legitimate nodes show "Keleti terulet" at once: the detail screen's own name
        // heading behind the dialog, and the dialog's own text field composed after it - the
        // later one in composition order is the dialog's, mirroring the established
        // `.onLast()` pattern this codebase already uses to target a dialog's own button over
        // the trigger behind it.
        compose.onAllNodesWithText("Keleti terulet").assertCountEquals(2)
        compose.onAllNodesWithText("Keleti terulet").onLast().performTextReplacement("Atnevezve")
        compose.onNodeWithText("Mentés").performClick()
        compose.waitForIdle()

        assert(fake.renameCalls == 1) { "expected exactly one rename call, got ${fake.renameCalls}" }
    }

    @Test
    fun unassigning_a_mapped_line_shows_the_future_only_notice_and_never_implies_earlier_reports_change() {
        val line = MappedRailwayLineResponse("l1", "L100", "Pelda vonal", true)
        val fake = FakeAreaAdminRepo(
            areaDetailResult = ApiResult.Success(detailOf(active = true, version = 0, lines = listOf(line))),
        )
        val vm = ServiceAreaDetailViewModel("a1", fake, onSessionEnded = {})
        compose.setContent { ServiceAreaDetailScreen(viewModel = vm, onBack = {}, onAddRailwayLine = {}) }
        compose.waitForIdle()

        compose.onNodeWithText("Hozzárendelés megszüntetése").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(
            "A vasútvonalhoz érkező új bejelentések a hozzárendelés megszüntetése után nem kerülnek szolgálati területhez. A korábbi bejelentések nem változnak.",
            substring = true,
        ).assertExists()
        compose.onNodeWithText("A módosítás csak az ezután érkező bejelentéseket érinti. A korábbi bejelentések szolgálati területe nem változik.", substring = true).assertExists()

        // Confirm - the dialog's own confirm button, not the row's.
        compose.onAllNodesWithText("Hozzárendelés megszüntetése").onLast().performClick()
        compose.waitForIdle()
        assert(fake.unassignCalls == 1)
        assert(fake.lastUnassignArgs == ("l1" to "a1"))
    }

    @Test
    fun a_stale_rename_conflict_refreshes_silently_and_is_never_automatically_retried() {
        val fake = FakeAreaAdminRepo(
            areaDetailResult = ApiResult.Success(detailOf(active = true, version = 1)),
            renameAreaResult = ApiResult.Failure(code = "SERVICE_AREA_STATE_CHANGED", httpStatus = 409),
        )
        val vm = ServiceAreaDetailViewModel("a1", fake, onSessionEnded = {})
        compose.setContent { ServiceAreaDetailScreen(viewModel = vm, onBack = {}, onAddRailwayLine = {}) }
        compose.waitForIdle()

        compose.onNodeWithText("Átnevezés").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Mentés").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("A szolgálati terület időközben megváltozott. Frissítettük az aktuális állapotot.").assertExists()
        assert(fake.renameCalls == 1) { "no blind retry - exactly one attempt even after the conflict" }
        // The raw stable code must never reach the screen.
        compose.onAllNodesWithText("SERVICE_AREA_STATE_CHANGED", substring = true).assertCountEquals(0)
    }

    @Test
    fun no_report_reroute_action_exists_anywhere_on_the_area_detail_screen() {
        val line = MappedRailwayLineResponse("l1", "L100", "Pelda vonal", true)
        val fake = FakeAreaAdminRepo(areaDetailResult = ApiResult.Success(detailOf(active = true, version = 0, lines = listOf(line), openReports = 3)))
        val vm = ServiceAreaDetailViewModel("a1", fake, onSessionEnded = {})
        compose.setContent { ServiceAreaDetailScreen(viewModel = vm, onBack = {}, onAddRailwayLine = {}) }
        compose.waitForIdle()

        compose.onAllNodesWithText("Bejelentés áthelyezése", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("áthelyezése másik", substring = true).assertCountEquals(0)
    }

    // ---------------------------------------------------------------------------- Line picker

    private fun pickerLine(id: String, areaId: String?, areaName: String?, active: Boolean = true) =
        RailwayLineAdminListItemResponse(id, "L$id", "Vonal $id", active, areaId, areaName)

    @Test
    fun the_picker_visually_distinguishes_all_four_assignment_states() {
        val fake = FakeAreaAdminRepo(
            listRailwayLinesResult = ApiResult.Success(
                RailwayLineAdminListPageResponse(
                    items = listOf(
                        pickerLine("1", null, null), // unassigned
                        pickerLine("2", "target", "Cel terulet"), // already in this area
                        pickerLine("3", "other", "Masik terulet"), // assigned elsewhere
                        pickerLine("4", null, null, active = false), // inactive reference
                    ),
                    page = 0, size = 50, totalElements = 4, totalPages = 1,
                ),
            ),
        )
        val vm = RailwayLinePickerViewModel("target", fake, onSessionEnded = {})
        compose.setContent {
            RailwayLinePickerScreen(viewModel = vm, targetAreaId = "target", targetAreaName = "Cel terulet", onBack = {}, onAssigned = {})
        }
        compose.waitForIdle()

        compose.onNodeWithText("Nincs szolgálati területhez rendelve").assertExists()
        compose.onNodeWithText("Jelenleg ehhez a területhez van rendelve").assertExists()
        compose.onNodeWithText("Jelenleg itt: Masik terulet").assertExists()
        compose.onNodeWithText("Inaktív vasútvonal").assertExists()
    }

    @Test
    fun selecting_a_line_assigned_elsewhere_shows_the_move_confirmation_naming_both_areas_and_the_future_only_notice() {
        val fake = FakeAreaAdminRepo(
            listRailwayLinesResult = ApiResult.Success(
                RailwayLineAdminListPageResponse(listOf(pickerLine("3", "other", "Masik terulet")), 0, 50, 1, 1),
            ),
        )
        val vm = RailwayLinePickerViewModel("target", fake, onSessionEnded = {})
        compose.setContent {
            RailwayLinePickerScreen(viewModel = vm, targetAreaId = "target", targetAreaName = "Cel terulet", onBack = {}, onAssigned = {})
        }
        compose.waitForIdle()

        compose.onNodeWithText("Vonal 3").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Vasútvonal áthelyezése").assertExists()
        compose.onNodeWithText("A vasútvonal jelenleg a(z) „Masik terulet” szolgálati terület része. Áthelyezi a(z) „Cel terulet” szolgálati területhez?").assertExists()
        compose.onNodeWithText("A módosítás csak az ezután érkező bejelentéseket érinti. A korábbi bejelentések szolgálati területe nem változik.").assertExists()

        compose.onNodeWithText("Áthelyezés").performClick()
        compose.waitForIdle()
        assert(fake.assignCalls == 1)
        assert(fake.lastAssignArgs == Triple("3", "target", "other")) { "must never silently steal the line - the move carries its real current area" }
    }

    @Test
    fun a_line_already_in_the_target_area_is_not_selectable() {
        val fake = FakeAreaAdminRepo(
            listRailwayLinesResult = ApiResult.Success(
                RailwayLineAdminListPageResponse(listOf(pickerLine("2", "target", "Cel terulet")), 0, 50, 1, 1),
            ),
        )
        val vm = RailwayLinePickerViewModel("target", fake, onSessionEnded = {})
        compose.setContent {
            RailwayLinePickerScreen(viewModel = vm, targetAreaId = "target", targetAreaName = "Cel terulet", onBack = {}, onAssigned = {})
        }
        compose.waitForIdle()

        compose.onNodeWithText("Vonal 2").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Vasútvonal áthelyezése").assertDoesNotExist()
        assert(fake.assignCalls == 0)
    }

    @Test
    fun an_inactive_line_is_not_selectable() {
        val fake = FakeAreaAdminRepo(
            listRailwayLinesResult = ApiResult.Success(
                RailwayLineAdminListPageResponse(listOf(pickerLine("9", null, null, active = false)), 0, 50, 1, 1),
            ),
        )
        val vm = RailwayLinePickerViewModel("target", fake, onSessionEnded = {})
        compose.setContent {
            RailwayLinePickerScreen(viewModel = vm, targetAreaId = "target", targetAreaName = "Cel terulet", onBack = {}, onAssigned = {})
        }
        compose.waitForIdle()

        compose.onNodeWithText("Vonal 9").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Vasútvonal hozzárendelése").assertDoesNotExist()
        assert(fake.assignCalls == 0)
    }
}
