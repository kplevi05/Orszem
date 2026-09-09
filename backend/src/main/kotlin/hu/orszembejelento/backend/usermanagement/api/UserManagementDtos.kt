package hu.orszembejelento.backend.usermanagement.api

import hu.orszembejelento.backend.usermanagement.application.AdminIssuedCredential
import hu.orszembejelento.backend.usermanagement.application.ManagedUserPage
import hu.orszembejelento.backend.usermanagement.application.ManagedUserView
import hu.orszembejelento.backend.usermanagement.application.ProvisionedUser
import hu.orszembejelento.backend.usermanagement.domain.ManagedUser
import jakarta.validation.constraints.Pattern
import java.util.UUID

/**
 * `role` fields accept exactly the three role names — including `SUPER_ADMIN`, which is
 * syntactically well-formed but always semantically rejected by
 * [hu.orszembejelento.backend.usermanagement.domain.UserManagementPolicy] with a 403, never
 * a validation error. Distinguishing "not a role at all" (400) from "a role nobody may
 * request here" (403) is deliberate — see `ApiExceptionHandler`.
 */
private const val ROLE_PATTERN = "SERVICE_USER|MODERATOR|SUPER_ADMIN"

data class CreateUserRequest(
    @field:Pattern(regexp = ROLE_PATTERN) val role: String = "",
    val areaIds: List<UUID> = emptyList(),
    val globalAreaAccess: Boolean = false,
)

data class CreateUserResponse(
    val serviceId: String,
    val role: String,
    val temporaryCredential: String,
    val mustChangePassword: Boolean,
) {
    companion object {
        fun from(provisioned: ProvisionedUser) = CreateUserResponse(
            serviceId = provisioned.serviceId.value,
            role = provisioned.role.name,
            temporaryCredential = provisioned.temporaryCredential,
            mustChangePassword = provisioned.mustChangePassword,
        )
    }
}

data class PasswordResetResponse(val serviceId: String, val temporaryCredential: String) {
    companion object {
        fun from(issued: AdminIssuedCredential) = PasswordResetResponse(issued.serviceId.value, issued.temporaryCredential)
    }
}

data class ChangeRoleRequest(@field:Pattern(regexp = ROLE_PATTERN) val role: String = "")

/** One service area as it currently applies to a managed user — id, name and its own status (§27, §30). */
data class ManagedUserAreaResponse(val id: String, val name: String, val status: String)

/**
 * One row of the user-management surface. Deliberately exposes only what §27 lists:
 * no password hash, no `passwordChangedAt`, no session or token material, no audit rows.
 * [canManage] is a UI hint only — every mutation independently re-authorises server-side.
 */
data class ManagedUserResponse(
    val serviceId: String,
    val role: String,
    val status: String,
    val mustChangePassword: Boolean,
    val globalAreaAccess: Boolean,
    val areas: List<ManagedUserAreaResponse>,
    val canManage: Boolean,
) {
    companion object {
        fun from(view: ManagedUserView) = from(view.user, view.canManage)

        fun from(user: ManagedUser, canManage: Boolean) = ManagedUserResponse(
            serviceId = user.serviceId.value,
            role = user.role.name,
            status = user.status.name,
            mustChangePassword = user.mustChangePassword,
            globalAreaAccess = user.globalAreaAccess,
            areas = user.assignedAreas.map { ManagedUserAreaResponse(it.id.toString(), it.name, it.status.name) },
            canManage = canManage,
        )
    }
}

data class ManagedUserPageResponse(
    val items: List<ManagedUserResponse>,
    val page: Int,
    val size: Int,
    val totalCount: Int,
) {
    companion object {
        fun from(page: ManagedUserPage) = ManagedUserPageResponse(
            items = page.items.map(ManagedUserResponse::from),
            page = page.page,
            size = page.size,
            totalCount = page.totalCount,
        )
    }
}

/** One assignable area (§29) — read-only support data, not ServiceArea administration. */
data class AssignableAreaResponse(val id: String, val name: String)
