package hu.orszembejelento.service.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import hu.orszembejelento.service.analytics.data.AnalyticsAreaOptionResponse
import hu.orszembejelento.service.analytics.data.AnalyticsAreaOptionsResponse
import hu.orszembejelento.service.analytics.data.AnalyticsCategoryCountResponse
import hu.orszembejelento.service.analytics.data.AnalyticsEventTypeCountResponse
import hu.orszembejelento.service.analytics.data.AnalyticsFilter
import hu.orszembejelento.service.analytics.data.AnalyticsPeriod
import hu.orszembejelento.service.analytics.data.AnalyticsPeriodResponse
import hu.orszembejelento.service.analytics.data.AnalyticsRepository
import hu.orszembejelento.service.analytics.data.AnalyticsStatusCountsResponse
import hu.orszembejelento.service.analytics.data.AnalyticsSummaryResponse
import hu.orszembejelento.service.analytics.data.AnalyticsTrendPointResponse
import hu.orszembejelento.service.analytics.ui.AnalyticsScreen
import hu.orszembejelento.service.analytics.ui.AnalyticsViewModel
import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.reports.data.CatalogCategoryResponse
import hu.orszembejelento.service.reports.data.CatalogRepository
import hu.orszembejelento.service.reports.data.ReportCatalogResponse
import hu.orszembejelento.service.reports.data.SettlementSearchResultResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/**
 * Phase 11 brief §61: the `Statisztika` Compose surface. Every fake here implements the real
 * [AnalyticsRepository]/[CatalogRepository] interfaces directly, mirroring
 * [ServiceAreaAdminComposeTest]'s own reasoning - a call-count assertion genuinely proves
 * "retry is one explicit user action, never a poll loop" (brief §49/§61). Every ViewModel is
 * constructed *before* `compose.setContent { }`, exactly like [ServiceAreaAdminComposeTest] -
 * constructing one lexically inside the composable lambda trips the `ViewModelConstructorInComposable`
 * lint check even though nothing here is a real composition-scoped `viewModel()` call.
 */
class AnalyticsComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private class FakeAnalyticsRepo(
        var summaryResult: ApiResult<AnalyticsSummaryResponse> = ApiResult.Success(summaryWith()),
        var areaOptionsResult: ApiResult<AnalyticsAreaOptionsResponse> = ApiResult.Success(AnalyticsAreaOptionsResponse(emptyList(), false)),
    ) : AnalyticsRepository {
        var summaryCalls = 0
        val seenFilters = mutableListOf<AnalyticsFilter>()
        override suspend fun summary(filter: AnalyticsFilter): ApiResult<AnalyticsSummaryResponse> {
            summaryCalls++
            seenFilters += filter
            return summaryResult
        }
        override suspend fun areaOptions(): ApiResult<AnalyticsAreaOptionsResponse> = areaOptionsResult
    }

    private class FakeCatalogRepo(
        var catalogResult: ApiResult<ReportCatalogResponse> = ApiResult.Success(
            ReportCatalogResponse(listOf(CatalogCategoryResponse("VIOLENCE_DANGER", "Erőszak és közvetlen veszély"))),
        ),
    ) : CatalogRepository {
        override suspend fun catalog(): ApiResult<ReportCatalogResponse> = catalogResult
        override suspend fun searchSettlements(query: String): ApiResult<List<SettlementSearchResultResponse>> = ApiResult.Success(emptyList())
    }

    private companion object {
        fun summaryWith(
            total: Int = 12,
            new: Int = 3,
            inProgress: Int = 4,
            archived: Int = 5,
            trend: List<AnalyticsTrendPointResponse> = listOf(
                AnalyticsTrendPointResponse("2026-09-10", 0),
                AnalyticsTrendPointResponse("2026-09-11", 7),
            ),
            categories: List<AnalyticsCategoryCountResponse> = listOf(
                AnalyticsCategoryCountResponse("VIOLENCE_DANGER", "Erőszak és közvetlen veszély", 8),
                AnalyticsCategoryCountResponse("THEFT_PROPERTY", "Lopás és vagyon elleni esemény", 4),
            ),
            topEventTypes: List<AnalyticsEventTypeCountResponse> = listOf(
                AnalyticsEventTypeCountResponse("FIGHT", "Verekedés", "VIOLENCE_DANGER", 6),
            ),
        ) = AnalyticsSummaryResponse(
            period = AnalyticsPeriodResponse("LAST_30_DAYS", "2026-08-12T00:00:00Z", "2026-09-11T10:00:00Z", "Europe/Budapest"),
            generatedAt = "2026-09-11T10:00:00Z",
            totalReports = total,
            statusCounts = AnalyticsStatusCountsResponse(new, inProgress, archived),
            trend = trend,
            categories = categories,
            topEventTypes = topEventTypes,
        )
    }

    private fun setContentWith(repo: FakeAnalyticsRepo, catalog: FakeCatalogRepo = FakeCatalogRepo()): AnalyticsViewModel {
        val vm = AnalyticsViewModel(repo, onSessionEnded = {})
        compose.setContent { AnalyticsScreen(viewModel = vm, catalogRepository = catalog) }
        return vm
    }

    // ----------------------------------------------------------------------------- basic shape

    @Test
    fun statisztika_is_live_not_a_placeholder() {
        setContentWith(FakeAnalyticsRepo())
        compose.onNodeWithText("Nincs megjeleníthető adat a kiválasztott időszakban.").assertDoesNotExist()
        compose.onNodeWithText("A statisztikai összesítések ebben a verzióban még nem érhetők el.").assertDoesNotExist()
    }

    @Test
    fun summary_KPI_cards_show_the_backend_counts() {
        // categories/topEventTypes cleared so their own (unrelated) counts can never coincide
        // with a KPI digit being asserted on below - this test is only about the KPI grid.
        setContentWith(
            FakeAnalyticsRepo(
                summaryResult = ApiResult.Success(
                    summaryWith(total = 12, new = 3, inProgress = 4, archived = 5, categories = emptyList(), topEventTypes = emptyList()),
                ),
            ),
        )
        compose.onNodeWithText("12").assertExists()
        compose.onNodeWithText("3").assertExists()
        compose.onNodeWithText("4").assertExists()
        compose.onNodeWithText("5").assertExists()
        compose.onNodeWithText("Összes bejelentés").assertExists()
        compose.onNodeWithText("Új").assertExists()
        compose.onNodeWithText("Folyamatban").assertExists()
        compose.onNodeWithText("Lezárt").assertExists()
    }

    @Test
    fun category_breakdown_renders_display_names_and_counts_never_a_raw_code() {
        setContentWith(FakeAnalyticsRepo())
        compose.onNodeWithText("Erőszak és közvetlen veszély").assertExists()
        compose.onNodeWithText("Lopás és vagyon elleni esemény").assertExists()
        compose.onAllNodesWithText("VIOLENCE_DANGER").assertCountEquals(0)
        compose.onAllNodesWithText("THEFT_PROPERTY").assertCountEquals(0)
    }

    @Test
    fun top_event_types_render_display_names_never_a_raw_code() {
        setContentWith(FakeAnalyticsRepo())
        compose.onNodeWithText("Verekedés").assertExists()
        compose.onAllNodesWithText("FIGHT").assertCountEquals(0)
    }

    @Test
    fun no_adminVersion_or_workflowVersion_or_backend_word_or_raw_period_code_is_ever_shown() {
        setContentWith(FakeAnalyticsRepo())
        listOf("adminVersion", "workflowVersion", "LAST_30_DAYS", "NEW", "IN_PROGRESS", "ARCHIVED", "UNCLASSIFIED").forEach {
            compose.onAllNodesWithText(it).assertCountEquals(0)
        }
    }

    @Test
    fun empty_filtered_state_shows_the_natural_copy_and_no_broken_chart_sections() {
        setContentWith(
            FakeAnalyticsRepo(
                summaryResult = ApiResult.Success(
                    summaryWith(total = 0, new = 0, inProgress = 0, archived = 0, trend = emptyList(), categories = emptyList(), topEventTypes = emptyList()),
                ),
            ),
        )
        compose.onNodeWithText("Nincs megjeleníthető adat a kiválasztott időszakban.").assertExists()
        compose.onNodeWithText("Beérkezett bejelentések").assertDoesNotExist()
    }

    @Test
    fun a_network_failure_is_retryable_and_retry_calls_the_backend_exactly_once_per_tap() {
        val repo = FakeAnalyticsRepo(summaryResult = ApiResult.NetworkError)
        setContentWith(repo)
        compose.onNodeWithText("A statisztika most nem tölthető be.").assertExists()

        val callsBeforeRetry = repo.summaryCalls
        repo.summaryResult = ApiResult.Success(summaryWith())
        compose.onNodeWithText("Újrapróbálkozás").performClick()
        assertEquals(callsBeforeRetry + 1, repo.summaryCalls)
        compose.onNodeWithText("Nincs megjeleníthető adat a kiválasztott időszakban.").assertDoesNotExist()
    }

    // -------------------------------------------------------------------------------- filters

    @Test
    fun the_filter_sheet_opens_and_shows_period_area_and_category_groups() {
        val repo = FakeAnalyticsRepo(
            areaOptionsResult = ApiResult.Success(
                AnalyticsAreaOptionsResponse(listOf(AnalyticsAreaOptionResponse("a1", "Kelenföldi Területi Központ", true)), canViewUnclassified = false),
            ),
        )
        setContentWith(repo)
        compose.onAllNodesWithText("Szűrők").assertCountEquals(0)
        compose.onNodeWithText("Statisztika").assertExists() // sanity: screen rendered before opening the sheet
        // Open via the filter icon button (content description "Szűrők").
        compose.onNodeWithContentDescriptionCompat("Szűrők").performClick()
        compose.onNodeWithText("Időszak").assertExists()
        compose.onNodeWithText("Szolgálati terület").assertExists()
        compose.onNodeWithText("Kategória").assertExists()
        compose.onNodeWithText("Kelenföldi Területi Központ").assertExists()
        compose.onNodeWithText("Minden jogosult terület").assertExists()
        compose.onNodeWithText("Ma").assertExists()
        compose.onNodeWithText("Utolsó 7 nap").assertExists()
        compose.onNodeWithText("Utolsó 30 nap").assertExists()
        compose.onNodeWithText("Utolsó 90 nap").assertExists()
    }

    @Test
    fun applying_a_period_filter_reloads_with_that_period_and_never_says_Minden_terulet() {
        val repo = FakeAnalyticsRepo()
        setContentWith(repo)
        compose.onNodeWithContentDescriptionCompat("Szűrők").performClick()
        compose.onNodeWithText("Ma").performClick()
        compose.onNodeWithText("Alkalmaz").performClick()
        assertEquals(AnalyticsPeriod.TODAY, repo.seenFilters.last().period)
        // brief §24: never "Minden terület" (could imply access beyond the actor's authorization).
        compose.onAllNodesWithText("Minden terület", substring = false).assertCountEquals(0)
    }

    @Test
    fun a_SERVICE_USER_level_actor_never_sees_a_Besorolatlan_option() {
        val repo = FakeAnalyticsRepo(areaOptionsResult = ApiResult.Success(AnalyticsAreaOptionsResponse(emptyList(), canViewUnclassified = false)))
        setContentWith(repo)
        compose.onNodeWithContentDescriptionCompat("Szűrők").performClick()
        compose.onNodeWithText("Besorolatlan").assertDoesNotExist()
    }

    @Test
    fun an_eligible_global_role_sees_the_Besorolatlan_option() {
        val repo = FakeAnalyticsRepo(areaOptionsResult = ApiResult.Success(AnalyticsAreaOptionsResponse(emptyList(), canViewUnclassified = true)))
        setContentWith(repo)
        compose.onNodeWithContentDescriptionCompat("Szűrők").performClick()
        compose.onNodeWithText("Besorolatlan").assertExists()
    }

    @Test
    fun a_SUPER_ADMIN_level_actor_sees_an_inactive_area_indicator() {
        val repo = FakeAnalyticsRepo(
            areaOptionsResult = ApiResult.Success(
                AnalyticsAreaOptionsResponse(
                    listOf(
                        AnalyticsAreaOptionResponse("a1", "Aktív Kísérleti Terület", true),
                        AnalyticsAreaOptionResponse("a2", "Megszűnt Kísérleti Terület", false),
                    ),
                    canViewUnclassified = true,
                ),
            ),
        )
        setContentWith(repo)
        compose.onNodeWithContentDescriptionCompat("Szűrők").performClick()
        compose.onNodeWithText("Megszűnt Kísérleti Terület (Inaktív)").assertExists()
        compose.onNodeWithText("Aktív Kísérleti Terület").assertExists()
    }

    @Test
    fun selecting_an_area_clears_unclassifiedOnly_and_vice_versa() {
        val repo = FakeAnalyticsRepo(
            areaOptionsResult = ApiResult.Success(
                AnalyticsAreaOptionsResponse(listOf(AnalyticsAreaOptionResponse("a1", "Nyugati Területi Központ", true)), canViewUnclassified = true),
            ),
        )
        setContentWith(repo)
        compose.onNodeWithContentDescriptionCompat("Szűrők").performClick()
        compose.onNodeWithText("Besorolatlan").performClick()
        compose.onNodeWithText("Nyugati Területi Központ").performClick()
        compose.onNodeWithText("Alkalmaz").performClick()
        val applied = repo.seenFilters.last()
        assertFalse(applied.unclassifiedOnly)
        assertEquals("a1", applied.areaId)
    }

    @Test
    fun clearing_filters_resets_to_the_default_period_and_no_area_or_category() {
        val repo = FakeAnalyticsRepo(
            areaOptionsResult = ApiResult.Success(
                AnalyticsAreaOptionsResponse(listOf(AnalyticsAreaOptionResponse("a1", "Deli Teruleti Kozpont", true)), canViewUnclassified = false),
            ),
        )
        setContentWith(repo)
        compose.onNodeWithContentDescriptionCompat("Szűrők").performClick()
        compose.onNodeWithText("Deli Teruleti Kozpont").performClick()
        compose.onNodeWithText("Szűrők törlése").performClick()
        compose.onNodeWithText("Alkalmaz").performClick()
        val applied = repo.seenFilters.last()
        assertEquals(AnalyticsPeriod.LAST_30_DAYS, applied.period)
        assertEquals(null, applied.areaId)
        assertFalse(applied.unclassifiedOnly)
        assertEquals(null, applied.categoryCode)
    }

    // -------------------------------------------------------------------------- session isolation

    // `createComposeRule()` throws "has already set content" on a second `setContent { }`
    // within one @Test (the same constraint the Phase 10 correction pass hit) - so this is
    // two separate test methods, each getting its own fresh Activity/rule from JUnit, rather
    // than two `setContentWith` calls in one method. Together they prove the same thing a
    // single before/after test would: a brand-new [AnalyticsViewModel] instance backed by a
    // brand-new fake never shows a total any *other* instance's fake ever configured - exactly
    // what a real logout/login's fresh `sessionOwner.viewModelStore` guarantees in production.

    @Test
    fun a_freshly_constructed_viewmodel_shows_only_its_own_repositorys_total() {
        // 99 is deliberately distinct from new/inProgress/archived (all 0) and from the
        // default categories/topEventTypes/trend digits, so exactly one node ever shows "99".
        val repo = FakeAnalyticsRepo(summaryResult = ApiResult.Success(summaryWith(total = 99, new = 0, inProgress = 0, archived = 0)))
        setContentWith(repo)
        compose.onNodeWithText("99").assertExists()
    }

    @Test
    fun a_second_freshly_constructed_viewmodel_never_shows_a_total_only_a_different_instance_configured() {
        val repo = FakeAnalyticsRepo(summaryResult = ApiResult.Success(summaryWith(total = 2, new = 1, inProgress = 1, archived = 0)))
        setContentWith(repo)
        compose.onNodeWithText("2").assertExists()
        // The value the sibling test above configures on a completely different fake/ViewModel
        // instance must never leak in here - there is no shared mutable state to leak through.
        compose.onNodeWithText("99").assertDoesNotExist()
    }

    // A tiny local helper - the app's own tests elsewhere use onNodeWithText almost
    // exclusively; content-description lookup for icon-only buttons needs its own finder.
    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onNodeWithContentDescriptionCompat(label: String) =
        this.onNode(androidx.compose.ui.test.hasContentDescription(label))
}
