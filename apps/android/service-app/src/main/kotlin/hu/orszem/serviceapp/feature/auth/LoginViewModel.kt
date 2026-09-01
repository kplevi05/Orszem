package hu.orszem.serviceapp.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hu.orszem.core.common.ApiErrorCode
import hu.orszem.core.common.Outcome
import hu.orszem.serviceapp.data.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LoginError { INVALID_CREDENTIALS, NETWORK }

data class LoginState(
    val username: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: LoginError? = null,
) {
    val canSubmit: Boolean get() = !loading && username.isNotBlank() && password.isNotBlank()
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginState())
    val state: StateFlow<LoginState> = _state.asStateFlow()

    fun onUsernameChanged(value: String) = _state.update { it.copy(username = value, error = null) }
    fun onPasswordChanged(value: String) = _state.update { it.copy(password = value, error = null) }

    fun onSubmit() {
        val current = _state.value
        if (!current.canSubmit) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val outcome = authRepository.login(current.username, current.password)) {
                is Outcome.Success -> _state.update { it.copy(loading = false) }
                is Outcome.Failure -> _state.update {
                    it.copy(
                        loading = false,
                        error = when (outcome.error.code) {
                            ApiErrorCode.INVALID_CREDENTIALS, ApiErrorCode.UNAUTHORIZED -> LoginError.INVALID_CREDENTIALS
                            else -> LoginError.NETWORK
                        },
                    )
                }
            }
        }
    }
}
