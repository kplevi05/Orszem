package hu.orszembejelento.backend.areaadmin.application

import hu.orszembejelento.backend.areaadmin.domain.AreaAdminActor
import hu.orszembejelento.backend.areaadmin.domain.ServiceAreaAdminForbiddenException
import hu.orszembejelento.backend.auth.application.AuthenticatedActor
import hu.orszembejelento.backend.identity.domain.UserRole
import org.springframework.stereotype.Component

/**
 * Every ServiceArea-administration use case authorises through this single gate first
 * (Phase 10 brief §4): SUPER_ADMIN only, full stop - a MODERATOR is rejected regardless of
 * global area access, and a SERVICE_USER is rejected identically. Backend authority is
 * absolute; Android role visibility (brief §43 "do not use Android role visibility as
 * security") is never trusted for this decision.
 */
@Component
class AreaAdminActorLoader {

    fun load(actor: AuthenticatedActor): AreaAdminActor {
        if (actor.role != UserRole.SUPER_ADMIN) throw ServiceAreaAdminForbiddenException()
        return AreaAdminActor(userId = actor.userId, role = actor.role)
    }
}
