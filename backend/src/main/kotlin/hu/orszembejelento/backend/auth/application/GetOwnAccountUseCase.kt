package hu.orszembejelento.backend.auth.application

import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.reference.domain.ServiceArea
import hu.orszembejelento.backend.scope.infrastructure.JdbcServiceAreaRepository
import java.util.UUID
import org.springframework.stereotype.Service

/**
 * Reads the current user's **own** account scope from live database state.
 *
 * Exists so `/account/me` can tell the Service app which service areas the signed-in user
 * covers (to drive the local "active work view" selector) without exposing any
 * user-management endpoint to a SERVICE_USER. Strictly self-account: role, the global flag
 * and the user's own assigned areas — nothing about any other user.
 */
@Service
class GetOwnAccountUseCase(private val serviceAreas: JdbcServiceAreaRepository) {

    data class OwnAccountScope(
        val role: UserRole,
        val globalAreaAccess: Boolean,
        val areas: List<ServiceArea>,
    )

    fun scopeOf(userId: UUID): OwnAccountScope {
        val actor = serviceAreas.loadAreaActor(userId)
            ?: return OwnAccountScope(role = UserRole.SERVICE_USER, globalAreaAccess = false, areas = emptyList())
        return OwnAccountScope(
            role = actor.role,
            globalAreaAccess = actor.globalAreaAccess,
            areas = serviceAreas.assignedAreas(userId),
        )
    }
}
