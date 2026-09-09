package hu.orszembejelento.backend.usermanagement.application

import hu.orszembejelento.backend.identity.domain.ServiceId
import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.identity.domain.UserStatus
import hu.orszembejelento.backend.reference.domain.ServiceArea
import hu.orszembejelento.backend.scope.infrastructure.JdbcServiceAreaRepository
import hu.orszembejelento.backend.usermanagement.domain.ManagedUser
import hu.orszembejelento.backend.usermanagement.domain.ManagementActor
import hu.orszembejelento.backend.usermanagement.domain.UserManagementPolicy
import hu.orszembejelento.backend.usermanagement.domain.UserNotFoundException
import hu.orszembejelento.backend.usermanagement.infrastructure.JdbcUserManagementRepository
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** One page of [ManagedUser] rows, plus enough to render pagination controls. */
data class ManagedUserPage(
    val items: List<ManagedUserView>,
    val page: Int,
    val size: Int,
    val totalCount: Int,
)

/** A [ManagedUser] paired with the actor-relative `canManage` UI hint (§4 — a hint only, never trusted back). */
data class ManagedUserView(val user: ManagedUser, val canManage: Boolean)

/** Read-only user-management queries: the list, one detail, and the assignable-area support list. */
@Service
class UserListingUseCase(
    private val users: JdbcUserManagementRepository,
    private val serviceAreas: JdbcServiceAreaRepository,
    private val policy: UserManagementPolicy,
) {

    @Transactional(readOnly = true)
    fun list(
        actor: ManagementActor,
        page: Int,
        size: Int,
        role: UserRole?,
        status: UserStatus?,
        serviceIdQuery: String?,
        areaId: UUID?,
    ): ManagedUserPage {
        val filter = JdbcUserManagementRepository.Filter(
            role = role,
            status = status,
            serviceIdQuery = serviceIdQuery,
            areaId = areaId,
            excludeSuperAdmin = actor.role == UserRole.MODERATOR,
            territorialActorAreaIds = territorialScopeOrNull(actor),
        )

        val totalCount = users.count(filter)
        val items = users.findPage(filter, limit = size, offset = page * size)
            .map { ManagedUserView(it, policy.canManageTarget(actor, it)) }

        return ManagedUserPage(items = items, page = page, size = size, totalCount = totalCount)
    }

    /** @throws UserNotFoundException for both a nonexistent target and one outside the actor's view (§28, §41). */
    @Transactional(readOnly = true)
    fun detail(actor: ManagementActor, rawServiceId: String?): ManagedUserView {
        val serviceId = ServiceId.parseOrNull(rawServiceId) ?: throw UserNotFoundException()
        val target = users.findManagedUserByServiceId(serviceId) ?: throw UserNotFoundException()
        if (!policy.canViewTarget(actor, target)) throw UserNotFoundException()
        return ManagedUserView(target, policy.canManageTarget(actor, target))
    }

    /** The areas [actor] may currently assign to anyone — §29, not ServiceArea administration. */
    @Transactional(readOnly = true)
    fun assignableAreas(actor: ManagementActor): List<ServiceArea> {
        val active = serviceAreas.findAllActive()
        return if (actor.role == UserRole.SUPER_ADMIN || actor.globalAreaAccess) {
            active
        } else {
            active.filter { it.id in actor.ownActiveAreaIds }
        }
    }

    /** `null` = no scope restriction (SUPER_ADMIN, or a global MODERATOR); a set = territorial MODERATOR overlap rule. */
    private fun territorialScopeOrNull(actor: ManagementActor): Set<UUID>? =
        if (actor.role == UserRole.MODERATOR && !actor.globalAreaAccess) actor.ownActiveAreaIds else null

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
        const val MAX_PAGE_SIZE = 100
    }
}
