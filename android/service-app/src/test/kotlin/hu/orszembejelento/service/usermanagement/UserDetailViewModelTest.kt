package hu.orszembejelento.service.usermanagement

import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.usermanagement.data.AssignableAreaResponse
import hu.orszembejelento.service.usermanagement.data.ManagedUserResponse
import hu.orszembejelento.service.usermanagement.data.PasswordResetResponse
import hu.orszembejelento.service.usermanagement.data.UserManagementRepository
import hu.orszembejelento.service.usermanagement.ui.UserDetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * brief §60-61 (the active-assignment cross-phase guard) and §56-59/§99 (the one-time
 * credential's lifecycle - set only by a create/reset response, cleared explicitly, never
 * surviving a fresh ViewModel instance the way a genuine process restart would produce one).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserDetailViewModelTest {

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun user(status: String = "ACTIVE", role: String = "SERVICE_USER") = ManagedUserResponse(
        serviceId = "SZ-1042", role = role, status = status,
        mustChangePassword = false, globalAreaAccess = false, areas = emptyList(), canManage = true,
    )

    private class FakeRepository(
        var detailResponses: MutableList<ApiResult<ManagedUserResponse>>,
        var deactivateResult: ApiResult<ManagedUserResponse> = ApiResult.SessionEnded,
        var resetResult: ApiResult<PasswordResetResponse> = ApiResult.SessionEnded,
    ) : UserManagementRepository {
        var deactivateCalls = 0
        var detailCalls = 0

        override suspend fun list(page: Int, size: Int, role: String?, status: String?, query: String?, areaId: String?) = error("unused")

        override suspend fun detail(serviceId: String): ApiResult<ManagedUserResponse> {
            detailCalls++
            return if (detailResponses.size > 1) detailResponses.removeAt(0) else detailResponses.first()
        }

        override suspend fun assignableAreas(): ApiResult<List<AssignableAreaResponse>> = error("unused")
        override suspend fun create(role: String, areaIds: List<String>, globalAreaAccess: Boolean) = error("unused")

        override suspend fun resetPassword(serviceId: String): ApiResult<PasswordResetResponse> = resetResult

        override suspend fun deactivate(serviceId: String): ApiResult<ManagedUserResponse> {
            deactivateCalls++
            return deactivateResult
        }

        override suspend fun reactivate(serviceId: String) = error("unused")
        override suspend fun changeRole(serviceId: String, role: String) = error("unused")
        override suspend fun grantGlobalAccess(serviceId: String) = error("unused")
        override suspend fun revokeGlobalAccess(serviceId: String) = error("unused")
        override suspend fun grantArea(serviceId: String, areaId: String) = error("unused")
        override suspend fun revokeArea(serviceId: String, areaId: String) = error("unused")
    }

    @Test
    fun `USER_HAS_ACTIVE_REPORT_ASSIGNMENTS surfaces its own code, and the user is left unchanged after the refresh`() = runTest {
        val fake = FakeRepository(
            detailResponses = mutableListOf(
                ApiResult.Success(user(status = "ACTIVE")),
                ApiResult.Success(user(status = "ACTIVE")), // unchanged - the rejection never applied
            ),
            deactivateResult = ApiResult.Failure(code = "USER_HAS_ACTIVE_REPORT_ASSIGNMENTS", httpStatus = 409),
        )
        val vm = UserDetailViewModel("SZ-1042", fake, onSessionEnded = {})

        vm.deactivate()

        assertEquals(1, fake.deactivateCalls) // never retried
        assertEquals("ACTIVE", vm.state.value.user?.status)
        assertEquals(
            "USER_HAS_ACTIVE_REPORT_ASSIGNMENTS",
            (vm.state.value.mutationError as ApiResult.Failure).code,
        )
    }

    @Test
    fun `a successful deactivate replaces local state with the committed response`() = runTest {
        val fake = FakeRepository(
            detailResponses = mutableListOf(ApiResult.Success(user(status = "ACTIVE"))),
            deactivateResult = ApiResult.Success(user(status = "DEACTIVATED")),
        )
        val vm = UserDetailViewModel("SZ-1042", fake, onSessionEnded = {})

        vm.deactivate()

        assertEquals("DEACTIVATED", vm.state.value.user?.status)
        assertNull(vm.state.value.mutationError)
    }

    @Test
    fun `a fresh ViewModel instance never starts with a credential already visible`() = runTest {
        val fake = FakeRepository(detailResponses = mutableListOf(ApiResult.Success(user())))
        val vm = UserDetailViewModel("SZ-1042", fake, onSessionEnded = {})

        // Simulates "process death" for this piece of state: nothing but the two plain
        // constructor arguments (the target service id and the repository) carries over -
        // there is no SavedStateHandle/navigation-argument/persistence path a credential
        // could have leaked through (brief §57/§99).
        assertNull(vm.state.value.newCredential)
    }

    @Test
    fun `resetPassword shows the new credential exactly once, and consumeCredential clears it`() = runTest {
        val fake = FakeRepository(
            detailResponses = mutableListOf(ApiResult.Success(user())),
            resetResult = ApiResult.Success(PasswordResetResponse("SZ-1042", "TEMP-CREDENTIAL-123")),
        )
        val vm = UserDetailViewModel("SZ-1042", fake, onSessionEnded = {})

        vm.resetPassword()
        assertEquals("TEMP-CREDENTIAL-123", vm.state.value.newCredential?.temporaryCredential)

        vm.consumeCredential()
        assertNull("the credential must not remain in state once the dialog is dismissed", vm.state.value.newCredential)
    }
}
