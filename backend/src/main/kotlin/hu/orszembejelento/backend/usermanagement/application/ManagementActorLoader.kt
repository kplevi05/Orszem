package hu.orszembejelento.backend.usermanagement.application

import hu.orszembejelento.backend.auth.application.AuthenticatedActor
import hu.orszembejelento.backend.scope.infrastructure.JdbcServiceAreaRepository
import hu.orszembejelento.backend.usermanagement.domain.ManagementActor
import hu.orszembejelento.backend.usermanagement.domain.UserManagementForbiddenException
import org.springframework.stereotype.Component

/**
 * Builds the [ManagementActor] every user-management use case authorises against, from
 * current database state only (Phase 6 brief §33) — role and scope always come from the
 * session's user row and `user_service_areas`, never from anything the caller claims.
 */
@Component
class ManagementActorLoader(private val serviceAreas: JdbcServiceAreaRepository) {

    /** @throws UserManagementForbiddenException if [actor] is a SERVICE_USER (§2: no user-management authority at all). */
    fun load(actor: AuthenticatedActor): ManagementActor {
        val loaded = serviceAreas.loadAreaActor(actor.userId)
            ?: throw UserManagementForbiddenException()

        if (loaded.role == hu.orszembejelento.backend.identity.domain.UserRole.SERVICE_USER) {
            throw UserManagementForbiddenException()
        }

        return ManagementActor(
            userId = loaded.userId,
            role = loaded.role,
            globalAreaAccess = loaded.globalAreaAccess,
            ownActiveAreaIds = serviceAreas.activeAssignedAreaIds(loaded.userId),
        )
    }
}
