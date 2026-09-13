package hu.orszembejelento.backend.audit.application

import hu.orszembejelento.backend.audit.domain.AuditForbiddenException
import hu.orszembejelento.backend.auth.application.AuthenticatedActor
import hu.orszembejelento.backend.identity.domain.UserRole
import org.springframework.stereotype.Component

/**
 * Every audit-query use case authorises through this single gate first (Phase 12 brief §2):
 * SUPER_ADMIN only, full stop - a MODERATOR is rejected regardless of global area access, and
 * a SERVICE_USER is rejected identically. No territorial audit visibility exists; the audit
 * trail spans cross-domain security and administrative history, not one actor's own area.
 * Backend authority is absolute; Android role visibility is never trusted for this decision -
 * mirrors [hu.orszembejelento.backend.areaadmin.application.AreaAdminActorLoader] exactly.
 */
@Component
class AuditActorLoader {

    fun load(actor: AuthenticatedActor) {
        if (actor.role != UserRole.SUPER_ADMIN) throw AuditForbiddenException()
    }
}
