package hu.orszembejelento.service.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import hu.orszembejelento.service.audit.data.AuditDetailItemResponse
import hu.orszembejelento.service.audit.data.AuditEventDetailResponse
import hu.orszembejelento.service.audit.data.AuditFilter
import hu.orszembejelento.service.audit.data.AuditListItemResponse
import hu.orszembejelento.service.audit.data.AuditListPageResponse
import hu.orszembejelento.service.audit.data.AuditOptionsResponse
import hu.orszembejelento.service.audit.data.AuditRepository
import hu.orszembejelento.service.audit.ui.AuditDetailScreen
import hu.orszembejelento.service.audit.ui.AuditDetailViewModel
import hu.orszembejelento.service.audit.ui.AuditListScreen
import hu.orszembejelento.service.audit.ui.AuditListViewModel
import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.hub.ui.AdminHubScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Phase 12 brief §71: the `Változási előzmények` list/filter/search/detail Compose surface,
 * plus the live Adminisztráció hub entry. Every fake implements the real [AuditRepository]
 * interface directly, mirroring `AnalyticsComposeTest`'s own reasoning - a call-count assertion
 * genuinely proves "retry is one explicit user action, never a poll loop". Every ViewModel is
 * constructed *before* `compose.setContent { }`, exactly like `AnalyticsComposeTest`/
 * `ServiceAreaAdminComposeTest` - constructing one lexically inside the composable lambda trips
 * `ViewModelConstructorInComposable`.
 */
class AuditComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private class FakeAuditRepo(
        var eventsResult: ApiResult<AuditListPageResponse> = ApiResult.Success(defaultPage()),
        var optionsResult: ApiResult<AuditOptionsResponse> = ApiResult.Success(
            AuditOptionsResponse(eventTypes = listOf("USER_ROLE_CHANGED", "SERVICE_AREA_RENAMED"), targetTypes = listOf("USER", "SERVICE_AREA")),
        ),
        var detailResult: ApiResult<AuditEventDetailResponse> = ApiResult.Success(defaultDetail()),
    ) : AuditRepository {
        var eventsCalls = 0
        var detailCalls = 0
        val seenFilters = mutableListOf<AuditFilter>()

        override suspend fun events(filter: AuditFilter, page: Int, size: Int): ApiResult<AuditListPageResponse> {
            eventsCalls++
            seenFilters += filter
            return eventsResult
        }
        override suspend fun detail(auditEventId: String): ApiResult<AuditEventDetailResponse> {
            detailCalls++
            return detailResult
        }
        override suspend fun options(): ApiResult<AuditOptionsResponse> = optionsResult
    }

    private companion object {
        fun defaultPage(items: List<AuditListItemResponse> = listOf(roleChangeItem())) =
            AuditListPageResponse(items, page = 0, size = 50, totalElements = items.size, totalPages = 1)

        fun roleChangeItem() = AuditListItemResponse(
            auditEventId = "evt-role-change",
            occurredAt = "2026-09-13T16:24:37Z",
            eventType = "USER_ROLE_CHANGED",
            actorServiceId = "SZ-654321",
            targetType = "USER",
            targetDisplayLabel = "SZ-123456",
            summary = listOf(AuditDetailItemResponse("OLD_ROLE", "SERVICE_USER"), AuditDetailItemResponse("NEW_ROLE", "MODERATOR")),
        )

        fun areaRenameItem() = AuditListItemResponse(
            auditEventId = "evt-area-rename",
            occurredAt = "2026-09-13T15:00:00Z",
            eventType = "SERVICE_AREA_RENAMED",
            actorServiceId = "SZ-654321",
            targetType = "SERVICE_AREA",
            targetDisplayLabel = "Dunantuli Teruleti Kozpont",
            summary = listOf(AuditDetailItemResponse("OLD_NAME", "Nyugati Teruleti Kozpont"), AuditDetailItemResponse("NEW_NAME", "Dunantuli Teruleti Kozpont")),
        )

        fun defaultDetail() = AuditEventDetailResponse(
            auditEventId = "evt-role-change",
            occurredAt = "2026-09-13T16:24:37Z",
            eventType = "USER_ROLE_CHANGED",
            actorServiceId = "SZ-654321",
            targetType = "USER",
            targetDisplayLabel = "SZ-123456",
            details = listOf(AuditDetailItemResponse("OLD_ROLE", "SERVICE_USER"), AuditDetailItemResponse("NEW_ROLE", "MODERATOR")),
        )

        fun moderationDeleteDetail() = AuditEventDetailResponse(
            auditEventId = "evt-mod-delete",
            occurredAt = "2026-09-13T14:10:00Z",
            eventType = "REPORT_MODERATION_DELETED",
            actorServiceId = "SZ-654321",
            targetType = "REPORT",
            targetDisplayLabel = "#AB12CD34",
            details = listOf(AuditDetailItemResponse("REASON", "DUPLICATE")),
        )
    }

    private fun setListContentWith(repo: FakeAuditRepo, onOpenEvent: (String) -> Unit = {}): AuditListViewModel {
        val vm = AuditListViewModel(repo, onSessionEnded = {})
        compose.setContent { AuditListScreen(viewModel = vm, onOpenEvent = onOpenEvent) }
        return vm
    }

    private fun setDetailContentWith(repo: FakeAuditRepo, auditEventId: String = "evt-role-change"): AuditDetailViewModel {
        val vm = AuditDetailViewModel(auditEventId, repo, onSessionEnded = {})
        compose.setContent { AuditDetailScreen(viewModel = vm, onBack = {}) }
        return vm
    }

    // ----------------------------------------------------------------------------- admin hub

    @Test
    fun the_admin_hub_shows_the_live_Valtozasi_elozmenyek_entry() {
        var opened = false
        compose.setContent {
            AdminHubScreen(onOpenUsers = {}, onOpenDeletedReports = {}, onOpenServiceAreas = {}, onOpenAudit = { opened = true }, onOpenAccount = {})
        }
        compose.onNodeWithText("Változási előzmények").assertExists()
        compose.onNodeWithText("Változási előzmények").performClick()
        assertEquals(true, opened)
    }

    // ----------------------------------------------------------------------------- list basics

    @Test
    fun the_list_loads_and_shows_a_natural_event_title_never_a_raw_code() {
        setListContentWith(FakeAuditRepo())
        compose.onNodeWithText("Szerepkör módosítva").assertExists()
        compose.onAllNodesWithText("USER_ROLE_CHANGED").assertCountEquals(0)
    }

    @Test
    fun the_card_shows_the_actor_Service_ID_and_the_safe_target_never_a_UUID() {
        setListContentWith(FakeAuditRepo())
        compose.onNodeWithText("Végrehajtotta: SZ-654321").assertExists()
        compose.onNodeWithText("Felhasználó: SZ-123456").assertExists()
    }

    @Test
    fun the_summary_line_shows_localized_role_values_never_raw_enum_names() {
        setListContentWith(FakeAuditRepo())
        compose.onNodeWithText("Szolgálati munkatárs → Moderátor").assertExists()
        compose.onAllNodesWithText("SERVICE_USER").assertCountEquals(0)
        compose.onAllNodesWithText("MODERATOR").assertCountEquals(0)
    }

    @Test
    fun a_moderation_reason_in_the_detail_is_localized_never_the_raw_code() {
        val repo = FakeAuditRepo(detailResult = ApiResult.Success(moderationDeleteDetail()))
        setDetailContentWith(repo, "evt-mod-delete")
        compose.onNodeWithText("Duplikált bejelentés").assertExists()
        compose.onAllNodesWithText("DUPLICATE").assertCountEquals(0)
    }

    @Test
    fun an_unknown_future_event_type_renders_the_safe_generic_title_never_a_raw_code() {
        val unknown = roleChangeItem().copy(eventType = null, summary = emptyList())
        setListContentWith(FakeAuditRepo(eventsResult = ApiResult.Success(defaultPage(listOf(unknown)))))
        compose.onNodeWithText("Rendszeresemény").assertExists()
    }

    @Test
    fun the_empty_state_shows_the_natural_copy() {
        setListContentWith(FakeAuditRepo(eventsResult = ApiResult.Success(defaultPage(emptyList()))))
        compose.onNodeWithText("Nincs megjeleníthető esemény a kiválasztott feltételekkel.").assertExists()
    }

    // ------------------------------------------------------------------------------- search

    @Test
    fun typing_in_the_search_field_updates_the_filter_query_backend_side() {
        val repo = FakeAuditRepo()
        setListContentWith(repo)
        compose.onNodeWithText("Felhasználó, bejelentés, terület vagy vasútvonal…").performTextInput("SZ-123456")
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertEquals("SZ-123456", repo.seenFilters.last().query)
    }

    // ------------------------------------------------------------------------------- filters

    @Test
    fun the_filter_sheet_opens_and_shows_all_three_groups() {
        setListContentWith(FakeAuditRepo())
        compose.onNodeWithContentDescriptionCompat("Szűrők").performClick()
        compose.onNodeWithText("Időszak").assertExists()
        compose.onNodeWithText("Eseménytípus").assertExists()
        compose.onNodeWithText("Érintett elem").assertExists()
        compose.onNodeWithText("Ma").assertExists()
        compose.onNodeWithText("Teljes előzmény").assertExists()
    }

    @Test
    fun applying_a_period_filter_reloads_with_it() {
        val repo = FakeAuditRepo()
        setListContentWith(repo)
        compose.onNodeWithContentDescriptionCompat("Szűrők").performClick()
        compose.onNodeWithText("Ma").performClick()
        compose.onNodeWithText("Alkalmaz").performClick()
        assertEquals(hu.orszembejelento.service.audit.data.AuditPeriod.TODAY, repo.seenFilters.last().period)
    }

    @Test
    fun applying_an_event_type_filter_reloads_with_it_using_the_natural_label() {
        val repo = FakeAuditRepo()
        setListContentWith(repo)
        compose.onNodeWithContentDescriptionCompat("Szűrők").performClick()
        compose.onNodeWithText("Szolgálati terület átnevezve").performClick()
        compose.onNodeWithText("Alkalmaz").performClick()
        assertEquals("SERVICE_AREA_RENAMED", repo.seenFilters.last().eventType)
    }

    @Test
    fun applying_a_target_type_filter_reloads_with_it() {
        val repo = FakeAuditRepo()
        setListContentWith(repo)
        compose.onNodeWithContentDescriptionCompat("Szűrők").performClick()
        compose.onNodeWithText("Szolgálati terület").performClick()
        compose.onNodeWithText("Alkalmaz").performClick()
        assertEquals("SERVICE_AREA", repo.seenFilters.last().targetType)
    }

    @Test
    fun clearing_filters_resets_to_the_default_period_and_no_event_or_target_type() {
        val repo = FakeAuditRepo()
        setListContentWith(repo)
        compose.onNodeWithContentDescriptionCompat("Szűrők").performClick()
        compose.onNodeWithText("Ma").performClick()
        compose.onNodeWithText("Szűrők törlése").performClick()
        compose.onNodeWithText("Alkalmaz").performClick()
        val applied = repo.seenFilters.last()
        assertEquals(hu.orszembejelento.service.audit.data.AuditPeriod.LAST_30_DAYS, applied.period)
        assertEquals(null, applied.eventType)
        assertEquals(null, applied.targetType)
    }

    // ------------------------------------------------------------------------------- retry

    @Test
    fun a_first_load_network_failure_is_retryable_via_Ujraprobalas_exactly_once_per_tap() {
        val repo = FakeAuditRepo(eventsResult = ApiResult.NetworkError)
        setListContentWith(repo)
        compose.onNodeWithText("A változási előzmények most nem tölthetők be.").assertExists()

        val callsBeforeRetry = repo.eventsCalls
        repo.eventsResult = ApiResult.Success(defaultPage())
        compose.onNodeWithText("Újrapróbálás").performClick()
        assertEquals(callsBeforeRetry + 1, repo.eventsCalls)
        compose.onNodeWithText("Nincs megjeleníthető esemény a kiválasztott feltételekkel.").assertDoesNotExist()
    }

    @Test
    fun a_refresh_failure_after_a_successful_load_keeps_stale_items_and_offers_Ujraprobalas() {
        val repo = FakeAuditRepo(eventsResult = ApiResult.Success(defaultPage()))
        val vm = setListContentWith(repo)
        compose.onNodeWithText("Szerepkör módosítva").assertExists()

        repo.eventsResult = ApiResult.NetworkError
        vm.refresh()
        compose.onNodeWithText("Szerepkör módosítva").assertExists()
        compose.onNodeWithText("Újrapróbálás").assertExists()
    }

    // -------------------------------------------------------------------------- pagination

    @Test
    fun loadMore_appends_the_next_page_without_replacing_the_first() {
        val page0 = AuditListPageResponse(listOf(roleChangeItem()), page = 0, size = 1, totalElements = 2, totalPages = 2)
        val repo = FakeAuditRepo(eventsResult = ApiResult.Success(page0))
        val vm = setListContentWith(repo)
        compose.onNodeWithText("Szerepkör módosítva").assertExists()

        repo.eventsResult = ApiResult.Success(AuditListPageResponse(listOf(areaRenameItem()), page = 1, size = 1, totalElements = 2, totalPages = 2))
        vm.loadMore()
        compose.waitForIdle()
        compose.onNodeWithText("Szerepkör módosítva").assertExists()
        compose.onNodeWithText("Szolgálati terület átnevezve").assertExists()
    }

    // ----------------------------------------------------------------------------- detail

    @Test
    fun the_detail_screen_shows_before_and_after_values_from_the_stored_event() {
        setDetailContentWith(FakeAuditRepo())
        compose.onNodeWithText("Esemény részletei").assertExists()
        compose.onNodeWithText("Korábbi szerepkör").assertExists()
        compose.onNodeWithText("Szolgálati munkatárs").assertExists()
        compose.onNodeWithText("Új szerepkör").assertExists()
        compose.onNodeWithText("Moderátor").assertExists()
    }

    @Test
    fun the_detail_screen_never_shows_a_raw_detail_code() {
        setDetailContentWith(FakeAuditRepo())
        compose.onAllNodesWithText("OLD_ROLE").assertCountEquals(0)
        compose.onAllNodesWithText("NEW_ROLE").assertCountEquals(0)
    }

    @Test
    fun no_audit_mutation_control_exists_anywhere_on_the_detail_screen() {
        setDetailContentWith(FakeAuditRepo())
        listOf("Visszaállítás", "Visszavonás", "Újra végrehajtás", "Törlés", "Szerkesztés").forEach {
            compose.onAllNodesWithText(it).assertCountEquals(0)
        }
    }

    // A tiny local helper - content-description lookup for icon-only buttons.
    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onNodeWithContentDescriptionCompat(label: String) =
        this.onNode(androidx.compose.ui.test.hasContentDescription(label))
}
