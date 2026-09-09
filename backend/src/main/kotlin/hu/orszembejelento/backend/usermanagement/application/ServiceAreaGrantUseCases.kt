package hu.orszembejelento.backend.usermanagement.application

import hu.orszembejelento.backend.audit.domain.AuditActorType
import hu.orszembejelento.backend.audit.domain.AuditEventType
import hu.orszembejelento.backend.audit.domain.AuditTargetType
import hu.orszembejelento.backend.audit.infrastructure.JdbcAuditRepository
import hu.orszembejelento.backend.identity.infrastructure.JdbcUserRepository
import hu.orszembejelento.backend.reportworkflow.domain.AssignmentEligibilityGuard
import hu.orszembejelento.backend.reportworkflow.infrastructure.JdbcReportAssignmentRepository
import hu.orszembejelento.backend.scope.domain.AreaActor
import hu.orszembejelento.backend.scope.infrastructure.JdbcServiceAreaRepository
import hu.orszembejelento.backend.usermanagement.domain.AreaNotAssignableException
import hu.orszembejelento.backend.usermanagement.domain.AreaNotFoundException
import hu.orszembejelento.backend.usermanagement.domain.AssignedArea
import hu.orszembejelento.backend.usermanagement.domain.ManagedUser
import hu.orszembejelento.backend.usermanagement.domain.ManagementActor
import hu.orszembejelento.backend.usermanagement.domain.UserHasActiveReportAssignmentsException
import hu.orszembejelento.backend.usermanagement.domain.UserManagementPolicy
import hu.orszembejelento.backend.usermanagement.domain.UserNotFoundException
import hu.orszembejelento.backend.usermanagement.domain.UserRequiresServiceAreaException
import hu.orszembejelento.backend.usermanagement.infrastructure.JdbcUserManagementRepository
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Grants one service area to one user (§8, §25).
 *
 * Explicit grant/revoke per area, never a whole-list replacement — replacing the whole list
 * is exactly the shape that loses a concurrent grant of a different area (§35). The
 * `user_service_areas` primary key is the authority on "already granted": a duplicate grant
 * is a no-op, not an error, and is never separately audited (§8/§38).
 */
@Service
class ServiceAreaGrantUseCase(
    private val users: JdbcUserRepository,
    private val managedUsers: JdbcUserManagementRepository,
    private val serviceAreas: JdbcServiceAreaRepository,
    private val policy: UserManagementPolicy,
    private val audit: JdbcAuditRepository,
) {

    @Transactional
    fun grant(actor: ManagementActor, rawServiceId: String?, areaId: UUID): ManagedUser {
        val (locked, target) = lockAndAuthorize(users, managedUsers, policy, actor, rawServiceId)

        // Canonical lock order, step 2 — must be ACTIVE and inside the actor's own authority.
        val area = serviceAreas.lockById(areaId) ?: throw AreaNotFoundException()
        val snapshot = AssignedArea(area.id, area.name, area.status)
        if (!policy.canAssignArea(actor, snapshot)) throw AreaNotAssignableException()

        val inserted = serviceAreas.grantAreaIfAbsent(locked.id, areaId)
        if (inserted) {
            audit.record(
                operationId = UUID.randomUUID(),
                actorType = AuditActorType.USER,
                actorUserId = actor.userId,
                eventType = AuditEventType.USER_AREA_GRANTED,
                targetType = AuditTargetType.USER,
                targetId = locked.id,
                metadata = mapOf("serviceId" to locked.serviceId.value, "areaId" to areaId.toString()),
            )
        }

        return managedUsers.findManagedUser(locked.id) ?: target
    }
}

/**
 * Revokes one service area from one user (§9, §25).
 *
 * An area does not need to be ACTIVE to be revoked — an existing assignment to a
 * meanwhile-INACTIVE area is exactly the kind of stale grant this exists to let an
 * authorised actor clean up (§30). What is enforced instead is the last-area rule: a
 * MODERATOR may never remove a non-global SERVICE_USER's only remaining area, because that
 * would create an account instantly outside every territorial moderator's reach.
 *
 * **Cross-phase invariant review addendum** (`docs/PHASE_7_ENGINEERING_REPORT.md` §R):
 * rejected if the resulting (post-revoke) scope would no longer authorise one or more of the
 * target's current open report assignments — checked against each assignment's *current
 * routing-snapshot service area*, never by re-running routing.
 */
@Service
class ServiceAreaRevokeUseCase(
    private val users: JdbcUserRepository,
    private val managedUsers: JdbcUserManagementRepository,
    private val serviceAreas: JdbcServiceAreaRepository,
    private val reportAssignments: JdbcReportAssignmentRepository,
    private val assignmentEligibility: AssignmentEligibilityGuard,
    private val policy: UserManagementPolicy,
    private val audit: JdbcAuditRepository,
) {

    @Transactional
    fun revoke(actor: ManagementActor, rawServiceId: String?, areaId: UUID): ManagedUser {
        val (locked, target) = lockAndAuthorize(users, managedUsers, policy, actor, rawServiceId)

        // Canonical lock order, step 2.
        serviceAreas.lockById(areaId) ?: throw AreaNotFoundException()

        val isCurrentlyAssigned = target.assignedAreas.any { it.id == areaId }
        if (isCurrentlyAssigned) {
            val remainingAfter = target.assignedAreas.size - 1
            if (policy.wouldViolateLastAreaRule(actor, target, remainingAfter)) {
                throw UserRequiresServiceAreaException()
            }

            // Cross-phase invariant review addendum (§R) — fresh read, run only now that the
            // target's own row lock is already held.
            val postMutationScope = AreaActor(
                userId = locked.id,
                role = target.role,
                globalAreaAccess = target.globalAreaAccess,
                assignedAreaIds = target.assignedAreas.map { it.id }.toSet() - areaId,
            )
            val openAssignments = reportAssignments.findOpenAssignmentAreas(locked.id)
            if (assignmentEligibility.anyAssignmentOutsideScope(openAssignments, postMutationScope)) {
                throw UserHasActiveReportAssignmentsException()
            }
        }

        val removed = serviceAreas.revokeArea(locked.id, areaId) > 0
        if (removed) {
            audit.record(
                operationId = UUID.randomUUID(),
                actorType = AuditActorType.USER,
                actorUserId = actor.userId,
                eventType = AuditEventType.USER_AREA_REVOKED,
                targetType = AuditTargetType.USER,
                targetId = locked.id,
                metadata = mapOf("serviceId" to locked.serviceId.value, "areaId" to areaId.toString()),
            )
        }

        return managedUsers.findManagedUser(locked.id) ?: target
    }
}
