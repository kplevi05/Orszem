package hu.orszembejelento.service.usermanagement

import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.usermanagement.data.AssignableAreaResponse
import hu.orszembejelento.service.usermanagement.data.CreateUserResponse
import hu.orszembejelento.service.usermanagement.data.ManagedUserPageResponse
import hu.orszembejelento.service.usermanagement.data.ManagedUserResponse
import hu.orszembejelento.service.usermanagement.data.PasswordResetResponse
import hu.orszembejelento.service.usermanagement.data.UserManagementRepository
import hu.orszembejelento.service.usermanagement.ui.CreateUserViewModel
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

/** brief §54-58/§99 - the create-user credential's one-time lifecycle. */
@OptIn(ExperimentalCoroutinesApi::class)
class CreateUserViewModelTest {

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }

    private class FakeRepository(
        val areas: List<AssignableAreaResponse> = emptyList(),
        val createResult: ApiResult<CreateUserResponse> = ApiResult.SessionEnded,
    ) : UserManagementRepository {
        var createCalls = 0
        var lastRole: String? = null
        var lastAreaIds: List<String>? = null
        var lastGlobal: Boolean? = null

        override suspend fun list(page: Int, size: Int, role: String?, status: String?, query: String?, areaId: String?) = error("unused")
        override suspend fun detail(serviceId: String) = error("unused")
        override suspend fun assignableAreas(): ApiResult<List<AssignableAreaResponse>> = ApiResult.Success(areas)

        override suspend fun create(role: String, areaIds: List<String>, globalAreaAccess: Boolean): ApiResult<CreateUserResponse> {
            createCalls++
            lastRole = role; lastAreaIds = areaIds; lastGlobal = globalAreaAccess
            return createResult
        }

        override suspend fun resetPassword(serviceId: String) = error("unused")
        override suspend fun deactivate(serviceId: String) = error("unused")
        override suspend fun reactivate(serviceId: String) = error("unused")
        override suspend fun changeRole(serviceId: String, role: String) = error("unused")
        override suspend fun grantGlobalAccess(serviceId: String) = error("unused")
        override suspend fun revokeGlobalAccess(serviceId: String) = error("unused")
        override suspend fun grantArea(serviceId: String, areaId: String) = error("unused")
        override suspend fun revokeArea(serviceId: String, areaId: String) = error("unused")
    }

    @Test
    fun `a MODERATOR cannot select MODERATOR as the new role - only SUPER_ADMIN may`() = runTest {
        val vm = CreateUserViewModel(canAssignModerator = false, repository = FakeRepository(), onSessionEnded = {})
        assertEquals("SERVICE_USER", vm.state.value.role) // the only option a MODERATOR ever submits
    }

    @Test
    fun `submit sends exactly the selected role, areas and global flag - nothing server-generated is supplied by the client`() = runTest {
        val fake = FakeRepository(createResult = ApiResult.Success(CreateUserResponse("SZ-1188", "SERVICE_USER", "TEMP-1", true)))
        val vm = CreateUserViewModel(canAssignModerator = true, repository = fake, onSessionEnded = {})
        vm.toggleArea("area-1")
        vm.setGlobalAccess(false)

        vm.submit()

        assertEquals(1, fake.createCalls)
        assertEquals("SERVICE_USER", fake.lastRole)
        assertEquals(listOf("area-1"), fake.lastAreaIds)
        assertEquals(false, fake.lastGlobal)
    }

    @Test
    fun `the created credential is shown once and consumeCreated clears it - never re-derivable afterward`() = runTest {
        val fake = FakeRepository(createResult = ApiResult.Success(CreateUserResponse("SZ-1188", "SERVICE_USER", "TEMP-1", true)))
        val vm = CreateUserViewModel(canAssignModerator = true, repository = fake, onSessionEnded = {})

        vm.submit()
        assertEquals("TEMP-1", vm.state.value.created?.temporaryCredential)

        vm.consumeCreated()
        assertNull(vm.state.value.created)
    }

    @Test
    fun `a fresh ViewModel instance never starts with a credential visible`() = runTest {
        val vm = CreateUserViewModel(canAssignModerator = true, repository = FakeRepository(), onSessionEnded = {})
        assertNull(vm.state.value.created)
    }
}
