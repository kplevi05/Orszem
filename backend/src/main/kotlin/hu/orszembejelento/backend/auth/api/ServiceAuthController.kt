package hu.orszembejelento.backend.auth.api

import hu.orszembejelento.backend.auth.application.AuthenticatedActor
import hu.orszembejelento.backend.auth.application.ChangeOwnPasswordUseCase
import hu.orszembejelento.backend.auth.application.CompleteInitialPasswordChangeUseCase
import hu.orszembejelento.backend.auth.application.IssuedCredentials
import hu.orszembejelento.backend.auth.application.LoginUseCase
import hu.orszembejelento.backend.auth.application.LogoutAllUseCase
import hu.orszembejelento.backend.auth.application.LogoutUseCase
import hu.orszembejelento.backend.auth.application.RefreshUseCase
import hu.orszembejelento.backend.common.web.ApiPaths
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Unauthenticated and session-management endpoints for the Service client.
 *
 * Controllers here only map HTTP to use cases: no verification, no branching on account
 * state, no persistence. All of that belongs to the application layer, where it is
 * transactional and testable without HTTP.
 */
@RestController
@RequestMapping("${ApiPaths.V1}/service/auth")
@Tag(name = "Service authentication")
class ServiceAuthController(
    private val loginUseCase: LoginUseCase,
    private val completeInitialPasswordChange: CompleteInitialPasswordChangeUseCase,
    private val refreshUseCase: RefreshUseCase,
    private val logoutUseCase: LogoutUseCase,
    private val logoutAllUseCase: LogoutAllUseCase,
    private val changeOwnPassword: ChangeOwnPasswordUseCase,
) {

    @PostMapping("/login")
    @Operation(
        summary = "Sign in with a service ID and password",
        description = "Returns 401 INVALID_CREDENTIALS for an unknown service ID, a wrong " +
            "password or a deactivated account alike. Returns 403 PASSWORD_CHANGE_REQUIRED " +
            "when the account must complete its initial password change; no session is created in that case.",
    )
    fun login(
        @Valid @RequestBody request: LoginRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<TokenResponse> =
        credentials(loginUseCase.login(request.serviceId, request.password, httpRequest.remoteAddr))

    @PostMapping("/complete-password-change")
    @Operation(
        summary = "Complete the forced initial password change",
        description = "Verifies the temporary credential, sets the new password and returns a " +
            "normal session. The temporary credential stays valid until this call succeeds.",
    )
    fun completePasswordChange(
        @Valid @RequestBody request: CompletePasswordChangeRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<TokenResponse> = credentials(
        completeInitialPasswordChange.complete(
            rawServiceId = request.serviceId,
            rawTemporaryPassword = request.temporaryPassword,
            rawNewPassword = request.newPassword,
            sourceIp = httpRequest.remoteAddr,
        ),
    )

    @PostMapping("/refresh")
    @Operation(
        summary = "Rotate the session credentials",
        description = "Consumes the presented refresh token and issues a new access and refresh " +
            "token. Presenting an already-consumed token revokes the whole session.",
    )
    fun refresh(@Valid @RequestBody request: RefreshRequest): ResponseEntity<TokenResponse> =
        credentials(refreshUseCase.refresh(request.refreshToken))

    @PostMapping("/logout")
    @Operation(summary = "Revoke the current session")
    @SecurityRequirement(name = "bearerAuth")
    fun logout(@AuthenticationPrincipal actor: AuthenticatedActor): ResponseEntity<Void> {
        logoutUseCase.logout(actor)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/logout-all")
    @Operation(summary = "Revoke every session of the current user, including this one")
    @SecurityRequirement(name = "bearerAuth")
    fun logoutAll(@AuthenticationPrincipal actor: AuthenticatedActor): ResponseEntity<Void> {
        logoutAllUseCase.logoutAll(actor)
        return ResponseEntity.noContent().build()
    }

    /**
     * Wraps credentials with `Cache-Control: no-store`, so no intermediary or client cache
     * retains a bearer token. Tokens never appear in a URL, only in a request body or the
     * `Authorization` header.
     */
    private fun credentials(issued: IssuedCredentials): ResponseEntity<TokenResponse> =
        ResponseEntity.status(HttpStatus.OK)
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(HttpHeaders.PRAGMA, "no-cache")
            .body(TokenResponse.from(issued))
}
