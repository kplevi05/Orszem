package hu.orszembejelento.backend.auth.api

import hu.orszembejelento.backend.auth.application.AuthenticatedActor
import hu.orszembejelento.backend.auth.application.ChangeOwnPasswordUseCase
import hu.orszembejelento.backend.common.web.ApiPaths
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** The authenticated user's own account. No administration of other users exists in Phase 2. */
@RestController
@RequestMapping("${ApiPaths.V1}/service/account")
@Tag(name = "Service account")
@SecurityRequirement(name = "bearerAuth")
class ServiceAccountController(
    private val changeOwnPassword: ChangeOwnPasswordUseCase,
) {

    @GetMapping("/me")
    @Operation(
        summary = "The current user's identity",
        description = "Service ID and role, read from current server-side state.",
    )
    fun me(@AuthenticationPrincipal actor: AuthenticatedActor): ResponseEntity<MeResponse> =
        ResponseEntity.ok()
            // Identity is per-session state; caching it would be wrong on a shared device.
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(MeResponse(serviceId = actor.serviceId.value, role = actor.role.name))

    @PostMapping("/change-password")
    @Operation(
        summary = "Change your own password",
        description = "Revokes every existing session and returns one fresh session for this client.",
    )
    fun changePassword(
        @AuthenticationPrincipal actor: AuthenticatedActor,
        @Valid @RequestBody request: ChangePasswordRequest,
    ): ResponseEntity<TokenResponse> {
        val issued = changeOwnPassword.changePassword(
            userId = actor.userId,
            rawCurrentPassword = request.currentPassword,
            rawNewPassword = request.newPassword,
        )
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(HttpHeaders.PRAGMA, "no-cache")
            .body(TokenResponse.from(issued))
    }
}
