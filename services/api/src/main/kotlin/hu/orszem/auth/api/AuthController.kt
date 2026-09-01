package hu.orszem.auth.api

import hu.orszem.auth.application.LoginCommand
import hu.orszem.auth.application.LoginUseCase
import hu.orszem.auth.web.currentActor
import hu.orszem.shared.error.ErrorCode
import hu.orszem.shared.error.ApiException
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/service")
class AuthController(
    private val loginUseCase: LoginUseCase,
) {

    @PostMapping("/auth/login")
    fun login(@Valid @RequestBody request: ServiceLoginRequest): ServiceLoginResponse {
        val issued = loginUseCase.execute(LoginCommand(request.username!!, request.password!!))
        return ServiceLoginResponse(
            accessToken = issued.token,
            tokenType = "Bearer",
            expiresAt = issued.expiresAt,
        )
    }

    @GetMapping("/me")
    fun me(): ServiceUserProfileResponse {
        val actor = currentActor() ?: throw ApiException(ErrorCode.UNAUTHORIZED, "Érvényes szolgálati bejelentkezés szükséges.")
        return ServiceUserProfileResponse(
            id = actor.userId.toString(),
            username = actor.username,
            displayName = actor.displayName,
            role = actor.role,
            capabilities = actor.capabilities.map { it.name }.sorted(),
        )
    }
}
