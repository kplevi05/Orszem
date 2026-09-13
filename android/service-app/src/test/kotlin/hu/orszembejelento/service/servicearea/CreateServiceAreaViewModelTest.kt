package hu.orszembejelento.service.servicearea

import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.servicearea.data.ServiceAreaAdminResponse
import hu.orszembejelento.service.servicearea.ui.CreateServiceAreaViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/** Phase 10 brief §10/§47 - create is name-only; blank names never reach the repository. */
@OptIn(ExperimentalCoroutinesApi::class)
class CreateServiceAreaViewModelTest {

    @Before
    fun setUpMainDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }

    @After
    fun tearDownMainDispatcher() { Dispatchers.resetMain() }

    @Test
    fun `a blank or whitespace-only name never reaches the repository`() = runTest {
        val fake = FakeAreaAdminRepository()
        val vm = CreateServiceAreaViewModel(fake, onSessionEnded = {})

        vm.setName("   ")
        vm.submit()

        assertEquals(0, fake.createAreaCalls)
    }

    @Test
    fun `a successful create trims the name and surfaces the created area`() = runTest {
        val fake = FakeAreaAdminRepository()
        fake.createAreaResult = ApiResult.Success(ServiceAreaAdminResponse("area-1", "Uj terulet", true, 0))
        val vm = CreateServiceAreaViewModel(fake, onSessionEnded = {})

        vm.setName("  Uj terulet  ")
        vm.submit()

        assertEquals("Uj terulet", fake.lastCreateName)
        assertNotNull(vm.state.value.created)
    }

    @Test
    fun `a name-conflict failure surfaces as an error, never a raw backend code`() = runTest {
        val fake = FakeAreaAdminRepository()
        fake.createAreaResult = ApiResult.Failure(code = "SERVICE_AREA_NAME_ALREADY_IN_USE", httpStatus = 409)
        val vm = CreateServiceAreaViewModel(fake, onSessionEnded = {})

        vm.setName("Foglalt nev")
        vm.submit()

        assertEquals(true, vm.state.value.error is ApiResult.Failure)
    }
}
