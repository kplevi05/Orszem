package hu.orszembejelento.service.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import hu.orszembejelento.service.audit.data.AuditEventDetailResponse
import hu.orszembejelento.service.audit.data.AuditFilter
import hu.orszembejelento.service.audit.data.AuditListPageResponse
import hu.orszembejelento.service.audit.data.AuditOptionsResponse
import hu.orszembejelento.service.audit.data.AuditRepository
import hu.orszembejelento.service.audit.ui.AuditListScreen
import hu.orszembejelento.service.audit.ui.AuditListViewModel
import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.moderation.ui.DeletedReportsListViewModel
import hu.orszembejelento.service.moderation.ui.DeletedReportsListScreen
import hu.orszembejelento.service.moderation.data.DeletedReportPageResponse
import hu.orszembejelento.service.reports.ui.AreaChoice
import hu.orszembejelento.service.servicearea.data.AreaAdminRepository
import hu.orszembejelento.service.servicearea.data.RailwayLineAdminListFilter
import hu.orszembejelento.service.servicearea.data.RailwayLineAdminListPageResponse
import hu.orszembejelento.service.servicearea.data.ServiceAreaAdminDetailResponse
import hu.orszembejelento.service.servicearea.data.ServiceAreaAdminListFilter
import hu.orszembejelento.service.servicearea.data.ServiceAreaAdminListPageResponse
import hu.orszembejelento.service.servicearea.data.ServiceAreaAdminResponse
import hu.orszembejelento.service.servicearea.ui.ServiceAreaAdminListScreen
import hu.orszembejelento.service.servicearea.ui.ServiceAreaAdminListViewModel
import hu.orszembejelento.service.usermanagement.data.ManagedUserPageResponse
import hu.orszembejelento.service.usermanagement.ui.UsersListViewModel
import hu.orszembejelento.service.usermanagement.ui.UsersScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Field-test fix (§5): every nested Moderator/SUPER_ADMIN screen that used to rely only on the
 * Android system Back action now also carries an explicit in-app back arrow. In
 * [hu.orszembejelento.service.nav.ServiceNavHost] every one of these screens is wired with
 * `onBack = { navController.popBackStack() }` - the exact same call the system Back gesture
 * ultimately reaches, never a second, parallel navigation path. What each screen itself must
 * guarantee, and what these tests check, is that tapping its back arrow calls that single
 * `onBack` exactly once and pushes no other destination (no `onOpen*`/`onCreate*` callback
 * fires) - i.e. it can never *duplicate* a destination the way a wrongly-wired `navigate()`
 * call in place of `popBackStack()` would.
 */
class BackArrowComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun clickBackAndAssertOnce(onBackCalls: () -> Int) {
        compose.onNodeWithContentDescription("Vissza").assertExists()
        compose.onAllNodesWithContentDescription("Vissza").assertCountEquals(1) // never two back affordances
        compose.onNodeWithContentDescription("Vissza").performClick()
        assertEquals(1, onBackCalls())
    }

    @Test
    fun users_screen_back_arrow_calls_onBack_exactly_once_and_never_opens_a_user_or_create_screen() {
        var backCalls = 0
        var openUserCalls = 0
        var createUserCalls = 0
        val vm = UsersListViewModel(
            fetchPage = { page, _, _ -> ApiResult.Success(ManagedUserPageResponse(items = emptyList(), page = page, size = 30, totalCount = 0)) },
            onSessionEnded = {},
        )
        compose.setContent {
            UsersScreen(
                viewModel = vm,
                onOpenUser = { openUserCalls++ },
                onCreateUser = { createUserCalls++ },
                onBack = { backCalls++ },
            )
        }
        compose.waitForIdle()

        clickBackAndAssertOnce { backCalls }
        assertEquals(0, openUserCalls)
        assertEquals(0, createUserCalls)
    }

    @Test
    fun deleted_reports_list_screen_back_arrow_calls_onBack_exactly_once_and_never_opens_a_report() {
        var backCalls = 0
        var openReportCalls = 0
        val vm = DeletedReportsListViewModel(
            fetchPage = { page, _, _ -> ApiResult.Success(DeletedReportPageResponse(items = emptyList(), page = page, size = 50, totalElements = 0, totalPages = 1)) },
            onSessionEnded = {},
        )
        compose.setContent {
            DeletedReportsListScreen(
                viewModel = vm,
                onOpenReport = { openReportCalls++ },
                areaChoices = emptyList<AreaChoice>(),
                onBack = { backCalls++ },
            )
        }
        compose.waitForIdle()

        clickBackAndAssertOnce { backCalls }
        assertEquals(0, openReportCalls)
    }

    @Test
    fun service_area_admin_list_screen_back_arrow_calls_onBack_exactly_once_and_never_opens_or_creates_an_area() {
        var backCalls = 0
        var openAreaCalls = 0
        var createAreaCalls = 0
        val repo = object : AreaAdminRepository {
            override suspend fun listAreas(page: Int, size: Int, filter: ServiceAreaAdminListFilter): ApiResult<ServiceAreaAdminListPageResponse> =
                ApiResult.Success(ServiceAreaAdminListPageResponse(emptyList(), 0, 50, 0, 0))
            override suspend fun areaDetail(areaId: String): ApiResult<ServiceAreaAdminDetailResponse> = error("not used")
            override suspend fun createArea(name: String): ApiResult<ServiceAreaAdminResponse> = error("not used")
            override suspend fun renameArea(areaId: String, expectedVersion: Long, name: String): ApiResult<ServiceAreaAdminResponse> = error("not used")
            override suspend fun activateArea(areaId: String, expectedVersion: Long): ApiResult<ServiceAreaAdminResponse> = error("not used")
            override suspend fun deactivateArea(areaId: String, expectedVersion: Long): ApiResult<ServiceAreaAdminResponse> = error("not used")
            override suspend fun listRailwayLines(page: Int, size: Int, filter: RailwayLineAdminListFilter): ApiResult<RailwayLineAdminListPageResponse> = error("not used")
            override suspend fun assignRailwayLine(railwayLineId: String, targetServiceAreaId: String, expectedCurrentServiceAreaId: String?): ApiResult<Unit> = error("not used")
            override suspend fun unassignRailwayLine(railwayLineId: String, expectedCurrentServiceAreaId: String): ApiResult<Unit> = error("not used")
        }
        val vm = ServiceAreaAdminListViewModel(repo, onSessionEnded = {})
        compose.setContent {
            ServiceAreaAdminListScreen(
                viewModel = vm,
                onOpenArea = { openAreaCalls++ },
                onCreateArea = { createAreaCalls++ },
                onBack = { backCalls++ },
            )
        }
        compose.waitForIdle()

        clickBackAndAssertOnce { backCalls }
        assertEquals(0, openAreaCalls)
        assertEquals(0, createAreaCalls)
    }

    @Test
    fun audit_list_screen_back_arrow_calls_onBack_exactly_once_and_never_opens_an_event() {
        var backCalls = 0
        var openEventCalls = 0
        val repo = object : AuditRepository {
            override suspend fun events(filter: AuditFilter, page: Int, size: Int): ApiResult<AuditListPageResponse> =
                ApiResult.Success(AuditListPageResponse(items = emptyList(), page = 0, size = 50, totalElements = 0, totalPages = 1))
            override suspend fun detail(auditEventId: String): ApiResult<AuditEventDetailResponse> = error("not used")
            override suspend fun options(): ApiResult<AuditOptionsResponse> = ApiResult.Success(AuditOptionsResponse(emptyList(), emptyList()))
        }
        val vm = AuditListViewModel(repo, onSessionEnded = {})
        compose.setContent {
            AuditListScreen(
                viewModel = vm,
                onOpenEvent = { openEventCalls++ },
                onBack = { backCalls++ },
            )
        }
        compose.waitForIdle()

        clickBackAndAssertOnce { backCalls }
        assertEquals(0, openEventCalls)
    }
}
