package hu.orszembejelento.backend.reportworkflow.application

import hu.orszembejelento.backend.auth.application.AuthenticatedActor
import hu.orszembejelento.backend.identity.infrastructure.JdbcUserRepository
import hu.orszembejelento.backend.reportworkflow.domain.ReportWorkflowActor
import hu.orszembejelento.backend.scope.infrastructure.JdbcServiceAreaRepository
import org.springframework.stereotype.Component

/**
 * Builds the [ReportWorkflowActor] every report-workflow use case authorises against, from
 * current database state only (brief §8) — role and scope always come from the session's
 * user row and `user_service_areas`, never from anything the caller claims.
 *
 * Unlike [hu.orszembejelento.backend.usermanagement.application.ManagementActorLoader],
 * this never rejects by role: SERVICE_USER is a first-class report-workflow actor (it is
 * user-*management* SERVICE_USER has no authority over, not the report workflow itself).
 */
@Component
class ReportWorkflowActorLoader(
    private val users: JdbcUserRepository,
    private val serviceAreas: JdbcServiceAreaRepository,
) {

    fun load(actor: AuthenticatedActor): ReportWorkflowActor {
        val loaded = serviceAreas.loadAreaActor(actor.userId)
            ?: error("authenticated user ${actor.userId} has no corresponding users row")
        val user = users.findById(actor.userId)
            ?: error("authenticated user ${actor.userId} has no corresponding users row")

        return ReportWorkflowActor(
            userId = loaded.userId,
            serviceId = user.serviceId,
            role = loaded.role,
            globalAreaAccess = loaded.globalAreaAccess,
            ownActiveAreaIds = serviceAreas.activeAssignedAreaIds(loaded.userId),
        )
    }
}
