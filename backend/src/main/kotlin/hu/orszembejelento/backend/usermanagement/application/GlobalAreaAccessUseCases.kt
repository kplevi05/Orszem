package hu.orszembejelento.backend.usermanagement.application

import hu.orszembejelento.backend.audit.domain.AuditActorType
import hu.orszembejelento.backend.audit.domain.AuditEventType
import hu.orszembejelento.backend.audit.domain.AuditTargetType
import hu.orszembejelento.backend.audit.infrastructure.JdbcAuditRepository
import hu.orszembejelento.backend.identity.domain.ServiceId
import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.identity.infrastructure.JdbcUserRepository
import hu.orszembejelento.backend.reportworkflow.domain.AssignmentEligibilityGuard
import hu.orszembejelento.backend.reportworkflow.infrastructure.JdbcReportAssignmentRepository
import hu.orszembejelento.backend.scope.domain.AreaActor
import hu.orszembejelento.backend.scope.infrastructure.JdbcServiceAreaRepository
import hu.orszembejelento.backend.usermanagement.domain.GlobalAccessNotAllowedException
import hu.orszembejelento.backend.usermanagement.domain.ManagedUser
import hu.orszembejelento.backend.usermanagement.domain.ManagementActor
import hu.orszembejelento.backend.usermanagement.domain.UserHasActiveReportAssignmentsException
import hu.orszembejelento.backend.usermanagement.domain.UserManagementPolicy
import hu.orszembejelento.backend.usermanagement.domain.UserNotFoundException
import hu.orszembejelento.backend.usermanagement.infrastructure.JdbcUserManagementRepository
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Grants or revokes `global_area_access`, SUPER_ADMIN-only (§10, §24).
 *
 * A SUPER_ADMIN target is rejected outright: the flag is not even meaningful for that role
 * ([hu.orszembejelento.backend.scope.domain.AreaScopePolicy] already treats SUPER_ADMIN as
 * global by virtue of the role, never by consulting this column). Existing ordinary area
 * assignments are never touched by either direction — granting does not clear them, and
 * revoking makes them effective again exactly as they were (§10).
 *
 * Both operations are idempotent: repeating the same grant or revoke changes nothing and
 * writes no further audit row, so retrying a call — or two admins acting at once — cannot
 * pad the trail with no-op events (§24).
 *
 * **Cross-phase invariant review addendum** (`docs/PHASE_7_ENGINEERING_REPORT.md` §R): a
 * revoke is rejected if the resulting (explicit-grants-only) scope would no longer authorise
 * one or more of the target's current open report assignments. Grant is never blocked — it
 * only ever widens scope.
 */
@Service
class ChangeGlobalAreaAccessUseCase(
    private val users: JdbcUserRepository,
    private val managedUsers: JdbcUserManagementRepository,
    private val serviceAreas: JdbcServiceAreaRepository,
    private val reportAssignments: JdbcReportAssignmentRepository,
    private val assignmentEligibility: AssignmentEligibilityGuard,
    private val policy: UserManagementPolicy,
    private val audit: JdbcAuditRepository,
) {

    @Transactional
    fun grant(actor: ManagementActor, rawServiceId: String?): ManagedUser = apply(actor, rawServiceId, value = true)

    @Transactional
    fun revoke(actor: ManagementActor, rawServiceId: String?): ManagedUser = apply(actor, rawServiceId, value = false)

    private fun apply(actor: ManagementActor, rawServiceId: String?, value: Boolean): ManagedUser {
        if (!policy.canChangeGlobalAccess(actor)) throw GlobalAccessNotAllowedException()

        val serviceId = ServiceId.parseOrNull(rawServiceId) ?: throw UserNotFoundException()
        // Canonical lock order, step 1.
        val locked = users.lockByServiceId(serviceId) ?: throw UserNotFoundException()
        val target = managedUsers.findManagedUser(locked.id) ?: throw UserNotFoundException()

        if (target.role == UserRole.SUPER_ADMIN) throw GlobalAccessNotAllowedException()
        if (target.globalAreaAccess == value) return target

        // Cross-phase invariant review addendum (§R) — only the revoke direction can ever
        // narrow scope, so only it is checked; grant only ever widens it.
        if (!value) {
            val postMutationScope = AreaActor(
                userId = locked.id,
                role = target.role,
                globalAreaAccess = false,
                assignedAreaIds = target.assignedAreas.map { it.id }.toSet(),
            )
            val openAssignments = reportAssignments.findOpenAssignmentAreas(locked.id)
            if (assignmentEligibility.anyAssignmentOutsideScope(openAssignments, postMutationScope)) {
                throw UserHasActiveReportAssignmentsException()
            }
        }

        serviceAreas.setGlobalAreaAccess(locked.id, value)

        audit.record(
            operationId = UUID.randomUUID(),
            actorType = AuditActorType.USER,
            actorUserId = actor.userId,
            eventType = if (value) AuditEventType.USER_GLOBAL_ACCESS_GRANTED else AuditEventType.USER_GLOBAL_ACCESS_REVOKED,
            targetType = AuditTargetType.USER,
            targetId = locked.id,
            metadata = mapOf("serviceId" to serviceId.value),
        )

        return target.copy(globalAreaAccess = value)
    }
}
