package hu.orszem.serviceapp

import com.google.common.truth.Truth.assertThat
import hu.orszem.core.common.ApiError
import hu.orszem.core.common.ApiErrorCode
import hu.orszem.core.common.Outcome
import hu.orszem.core.testing.MainDispatcherRule
import hu.orszem.serviceapp.data.AuthRepository
import hu.orszem.serviceapp.feature.auth.LoginError
import hu.orszem.serviceapp.feature.auth.LoginViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val authRepository = mockk<AuthRepository>()
    private fun vm() = LoginViewModel(authRepository)

    @Test
    fun `AT-002 successful login clears loading and error`() = runTest {
        coEvery { authRepository.login("demo.service", "OrszemDemo!2026") } returns Outcome.Success(Unit)
        val vm = vm()
        vm.onUsernameChanged("demo.service")
        vm.onPasswordChanged("OrszemDemo!2026")
        vm.onSubmit()
        advanceUntilIdle()
        assertThat(vm.state.value.loading).isFalse()
        assertThat(vm.state.value.error).isNull()
        coVerify { authRepository.login("demo.service", "OrszemDemo!2026") }
    }

    @Test
    fun `AT-003 invalid credentials surface an inline error`() = runTest {
        coEvery { authRepository.login(any(), any()) } returns
            Outcome.Failure(ApiError(ApiErrorCode.INVALID_CREDENTIALS, "nope"))
        val vm = vm()
        vm.onUsernameChanged("demo.service")
        vm.onPasswordChanged("wrong")
        vm.onSubmit()
        advanceUntilIdle()
        assertThat(vm.state.value.error).isEqualTo(LoginError.INVALID_CREDENTIALS)
    }

    @Test
    fun `a network failure is reported separately from bad credentials`() = runTest {
        coEvery { authRepository.login(any(), any()) } returns Outcome.Failure(ApiError(ApiErrorCode.NETWORK, "io"))
        val vm = vm()
        vm.onUsernameChanged("demo.service")
        vm.onPasswordChanged("x")
        vm.onSubmit()
        advanceUntilIdle()
        assertThat(vm.state.value.error).isEqualTo(LoginError.NETWORK)
    }

    @Test
    fun `submit is disabled until both fields are filled`() {
        val vm = vm()
        assertThat(vm.state.value.canSubmit).isFalse()
        vm.onUsernameChanged("demo.service")
        assertThat(vm.state.value.canSubmit).isFalse()
        vm.onPasswordChanged("pw")
        assertThat(vm.state.value.canSubmit).isTrue()
    }
}
