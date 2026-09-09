package hu.orszembejelento.backend.usermanagement.api

import hu.orszembejelento.backend.auth.application.AuthenticatedActor
import hu.orszembejelento.backend.common.web.ApiPaths
import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.identity.domain.UserStatus
import hu.orszembejelento.backend.usermanagement.application.AdminPasswordResetUseCase
import hu.orszembejelento.backend.usermanagement.application.ChangeGlobalAreaAccessUseCase
import hu.orszembejelento.backend.usermanagement.application.ChangeUserRoleUseCase
import hu.orszembejelento.backend.usermanagement.application.CreateUserUseCase
import hu.orszembejelento.backend.usermanagement.application.DeactivateUserUseCase
import hu.orszembejelento.backend.usermanagement.application.ManagementActorLoader
import hu.orszembejelento.backend.usermanagement.application.ReactivateUserUseCase
import hu.orszembejelento.backend.usermanagement.application.ServiceAreaGrantUseCase
import hu.orszembejelento.backend.usermanagement.application.ServiceAreaRevokeUseCase
import hu.orszembejelento.backend.usermanagement.application.UserListingUseCase
import hu.orszembejelento.backend.usermanagement.domain.ManagedUser
import hu.orszembejelento.backend.usermanagement.domain.ManagementActor
import hu.orszembejelento.backend.usermanagement.domain.UserManagementForbiddenException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Service-side user management (Phase 6 brief). Every endpoint requires a MODERATOR or
 * SUPER_ADMIN session; a SERVICE_USER is rejected by [ManagementActorLoader] before any use
 * case runs (§2, §42). Controllers here only map HTTP to use cases — every authorisation
 * decision happens in [hu.orszembejelento.backend.usermanagement.domain.UserManagementPolicy]
 * or the use case itself, never here.
 *
 * No generic CRUD: only the explicit business operations below exist (§43/§25) — there is
 * no whole-user PATCH/PUT and no arbitrary field update, which is what keeps mass
 * assignment and role injection impossible by construction.
 */
@RestController
@RequestMapping("${ApiPaths.V1}/service/user-management")
@Tag(name = "Service user management")
@SecurityRequirement(name = "bearerAuth")
class UserManagementController(
    private val actorLoader: ManagementActorLoader,
    private val listing: UserListingUseCase,
    private val createUser: CreateUserUseCase,
    private val passwordReset: AdminPasswordResetUseCase,
    private val deactivate: DeactivateUserUseCase,
    private val reactivate: ReactivateUserUseCase,
    private val changeRole: ChangeUserRoleUseCase,
    private val globalAccess: ChangeGlobalAreaAccessUseCase,
    private val grantArea: ServiceAreaGrantUseCase,
    private val revokeArea: ServiceAreaRevokeUseCase,
) {

    @GetMapping("/users")
    @Operation(
        summary = "List managed users",
        description = "Server-side paginated, service-ID-ascending. A territorial MODERATOR sees only " +
            "users whose scope overlaps their own; a SUPER_ADMIN or global MODERATOR sees every " +
            "non-SUPER_ADMIN user (SUPER_ADMIN additionally sees SUPER_ADMIN rows). Never reachable by SERVICE_USER.",
    )
    fun list(
        @AuthenticationPrincipal principal: AuthenticatedActor,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(required = false) size: Int?,
        @RequestParam(required = false) role: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) areaId: UUID?,
    ): ResponseEntity<ManagedUserPageResponse> {
        val actor = actorLoader.load(principal)
        val boundedSize = (size ?: UserListingUseCase.DEFAULT_PAGE_SIZE).coerceIn(1, UserListingUseCase.MAX_PAGE_SIZE)
        val boundedPage = page.coerceAtLeast(0)

        val result = listing.list(
            actor = actor,
            page = boundedPage,
            size = boundedSize,
            role = role?.let(::parseRoleOrNull),
            status = status?.let(::parseStatusOrNull),
            serviceIdQuery = query?.takeIf { it.isNotBlank() },
            areaId = areaId,
        )
        return noStore(ManagedUserPageResponse.from(result))
    }

    @GetMapping("/users/{serviceId}")
    @Operation(
        summary = "One managed user's detail",
        description = "Returns 404 USER_NOT_FOUND identically for a nonexistent service ID and one " +
            "outside the actor's visibility - a MODERATOR cannot distinguish the two by probing.",
    )
    fun detail(
        @AuthenticationPrincipal principal: AuthenticatedActor,
        @PathVariable serviceId: String,
    ): ResponseEntity<ManagedUserResponse> {
        val actor = actorLoader.load(principal)
        val view = listing.detail(actor, serviceId)
        return noStore(ManagedUserResponse.from(view))
    }

    @GetMapping("/areas")
    @Operation(
        summary = "Areas the actor may currently assign",
        description = "Read-only support list for the creation and grant flows - not ServiceArea " +
            "administration (§29/§61). Every area returned is ACTIVE.",
    )
    fun assignableAreas(@AuthenticationPrincipal principal: AuthenticatedActor): ResponseEntity<List<AssignableAreaResponse>> {
        val actor = actorLoader.load(principal)
        val areas = listing.assignableAreas(actor).map { AssignableAreaResponse(it.id.toString(), it.name) }
        return noStore(areas)
    }

    @PostMapping("/users")
    @Operation(
        summary = "Create a SERVICE_USER or MODERATOR",
        description = "serviceId, the temporary credential, status and mustChangePassword are all " +
            "server-generated - none may be supplied by the caller. The temporary credential is " +
            "returned exactly once, in this response, and is never retrievable again (§17/§40).",
    )
    fun create(
        @AuthenticationPrincipal principal: AuthenticatedActor,
        @Valid @RequestBody request: CreateUserRequest,
    ): ResponseEntity<CreateUserResponse> {
        val actor = actorLoader.load(principal)
        val role = parseRoleOrNull(request.role) ?: throw UserManagementForbiddenException()
        val provisioned = createUser.create(actor, role, request.areaIds, request.globalAreaAccess)
        return noStore(CreateUserResponse.from(provisioned))
    }

    @PostMapping("/users/{serviceId}/password-reset")
    @Operation(
        summary = "Issue a new temporary credential",
        description = "Works for an ACTIVE or DEACTIVATED target and never changes status either way. " +
            "Revokes every existing session of the target. The credential is returned exactly once (§18/§40).",
    )
    fun resetPassword(
        @AuthenticationPrincipal principal: AuthenticatedActor,
        @PathVariable serviceId: String,
    ): ResponseEntity<PasswordResetResponse> {
        val actor = actorLoader.load(principal)
        val issued = passwordReset.reset(actor, serviceId)
        return noStore(PasswordResetResponse.from(issued))
    }

    @PostMapping("/users/{serviceId}/deactivate")
    @Operation(summary = "Deactivate a user", description = "Idempotent. Revokes every existing session of the target.")
    fun deactivateUser(
        @AuthenticationPrincipal principal: AuthenticatedActor,
        @PathVariable serviceId: String,
    ): ResponseEntity<ManagedUserResponse> = managedResponse(actorLoader.load(principal)) { deactivate.deactivate(it, serviceId) }

    @PostMapping("/users/{serviceId}/reactivate")
    @Operation(
        summary = "Reactivate a user",
        description = "Idempotent. Never resets the password, never clears mustChangePassword, never creates a session.",
    )
    fun reactivateUser(
        @AuthenticationPrincipal principal: AuthenticatedActor,
        @PathVariable serviceId: String,
    ): ResponseEntity<ManagedUserResponse> = managedResponse(actorLoader.load(principal)) { reactivate.reactivate(it, serviceId) }

    @PostMapping("/users/{serviceId}/role")
    @Operation(
        summary = "Change SERVICE_USER <-> MODERATOR",
        description = "SUPER_ADMIN only. Preserves area assignments, global access, password and status. " +
            "Takes effect on the target's next request - no session is revoked (§23).",
    )
    fun changeRole(
        @AuthenticationPrincipal principal: AuthenticatedActor,
        @PathVariable serviceId: String,
        @Valid @RequestBody request: ChangeRoleRequest,
    ): ResponseEntity<ManagedUserResponse> {
        val actor = actorLoader.load(principal)
        val requestedRole = parseRoleOrNull(request.role) ?: throw UserManagementForbiddenException()
        return noStore(ManagedUserResponse.from(changeRole.changeRole(actor, serviceId, requestedRole), canManage = true))
    }

    @PostMapping("/users/{serviceId}/global-access/grant")
    @Operation(summary = "Grant global area access", description = "SUPER_ADMIN only. Idempotent. Never touches ordinary area assignments.")
    fun grantGlobalAccess(
        @AuthenticationPrincipal principal: AuthenticatedActor,
        @PathVariable serviceId: String,
    ): ResponseEntity<ManagedUserResponse> = managedResponse(actorLoader.load(principal)) { globalAccess.grant(it, serviceId) }

    @PostMapping("/users/{serviceId}/global-access/revoke")
    @Operation(summary = "Revoke global area access", description = "SUPER_ADMIN only. Idempotent. Ordinary area assignments become effective again.")
    fun revokeGlobalAccess(
        @AuthenticationPrincipal principal: AuthenticatedActor,
        @PathVariable serviceId: String,
    ): ResponseEntity<ManagedUserResponse> = managedResponse(actorLoader.load(principal)) { globalAccess.revoke(it, serviceId) }

    @PostMapping("/users/{serviceId}/areas/{areaId}/grant")
    @Operation(summary = "Grant one service area", description = "The area must be ACTIVE and inside the actor's own authority. Idempotent.")
    fun grantArea(
        @AuthenticationPrincipal principal: AuthenticatedActor,
        @PathVariable serviceId: String,
        @PathVariable areaId: UUID,
    ): ResponseEntity<ManagedUserResponse> = managedResponse(actorLoader.load(principal)) { grantArea.grant(it, serviceId, areaId) }

    @PostMapping("/users/{serviceId}/areas/{areaId}/revoke")
    @Operation(
        summary = "Revoke one service area",
        description = "A MODERATOR may never remove a non-global SERVICE_USER's last remaining area (409 USER_REQUIRES_SERVICE_AREA).",
    )
    fun revokeArea(
        @AuthenticationPrincipal principal: AuthenticatedActor,
        @PathVariable serviceId: String,
        @PathVariable areaId: UUID,
    ): ResponseEntity<ManagedUserResponse> = managedResponse(actorLoader.load(principal)) { revokeArea.revoke(it, serviceId, areaId) }

    // ------------------------------------------------------------------------------ helpers

    /**
     * `canManage = true` here is not assumed, it is a property every one of these use cases
     * already guarantees: none of them return successfully unless
     * [hu.orszembejelento.backend.usermanagement.domain.UserManagementPolicy.canManageTarget]
     * held for the *same* actor immediately before the mutation, and no mutation this
     * controller exposes can revoke the acting SUPER_ADMIN/MODERATOR's own authority over
     * the target it just changed (a grant only adds an area already inside the actor's own
     * scope; a revoke only removes one from a set that was already fully in scope; role and
     * global-access changes are SUPER_ADMIN-only, and SUPER_ADMIN manages every non-
     * SUPER_ADMIN target regardless of role or global flag).
     */
    private fun managedResponse(
        actor: ManagementActor,
        operation: (ManagementActor) -> ManagedUser,
    ): ResponseEntity<ManagedUserResponse> = noStore(ManagedUserResponse.from(operation(actor), canManage = true))

    /** Every response here reflects per-session administrative state and must never be cached. */
    private fun <T : Any> noStore(body: T): ResponseEntity<T> =
        ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body)

    private fun parseRoleOrNull(raw: String): UserRole? = UserRole.entries.find { it.name == raw }

    private fun parseStatusOrNull(raw: String): UserStatus? = UserStatus.entries.find { it.name == raw }
}
