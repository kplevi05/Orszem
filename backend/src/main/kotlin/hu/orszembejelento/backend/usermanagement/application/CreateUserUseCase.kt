package hu.orszembejelento.backend.usermanagement.application

import hu.orszembejelento.backend.audit.domain.AuditActorType
import hu.orszembejelento.backend.audit.domain.AuditEventType
import hu.orszembejelento.backend.audit.domain.AuditTargetType
import hu.orszembejelento.backend.audit.infrastructure.JdbcAuditRepository
import hu.orszembejelento.backend.identity.domain.PasswordHasher
import hu.orszembejelento.backend.identity.domain.ServiceId
import hu.orszembejelento.backend.identity.domain.ServiceIdGenerator
import hu.orszembejelento.backend.identity.domain.TemporaryCredentialGenerator
import hu.orszembejelento.backend.identity.domain.User
import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.identity.domain.UserStatus
import hu.orszembejelento.backend.identity.infrastructure.JdbcUserRepository
import hu.orszembejelento.backend.scope.infrastructure.JdbcServiceAreaRepository
import hu.orszembejelento.backend.usermanagement.domain.AreaNotAssignableException
import hu.orszembejelento.backend.usermanagement.domain.AreaNotFoundException
import hu.orszembejelento.backend.usermanagement.domain.AssignedArea
import hu.orszembejelento.backend.usermanagement.domain.ManagementActor
import hu.orszembejelento.backend.usermanagement.domain.UserManagementForbiddenException
import hu.orszembejelento.backend.usermanagement.domain.UserManagementPolicy
import hu.orszembejelento.backend.usermanagement.domain.UserRequiresServiceAreaException
import java.time.Clock
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** What is returned exactly once, straight in the HTTP response — never persisted or logged (§17, §40). */
data class ProvisionedUser(
    val serviceId: ServiceId,
    val role: UserRole,
    val temporaryCredential: String,
    val mustChangePassword: Boolean,
)

/**
 * Creates a SERVICE_USER or MODERATOR (§12-17).
 *
 * `serviceId`, `password`/credential, `status` and `mustChangePassword` are never accepted
 * from the caller — every one of them is server-generated, which is what keeps mass
 * assignment and role injection impossible by construction (§43).
 */
@Service
class CreateUserUseCase(
    private val users: JdbcUserRepository,
    private val serviceAreas: JdbcServiceAreaRepository,
    private val hasher: PasswordHasher,
    private val policy: UserManagementPolicy,
    private val audit: JdbcAuditRepository,
    private val serviceIdGenerator: ServiceIdGenerator,
    private val credentialGenerator: TemporaryCredentialGenerator,
    private val clock: Clock,
) {

    @Transactional
    fun create(
        actor: ManagementActor,
        requestedRole: UserRole,
        requestedAreaIds: List<UUID>,
        requestedGlobalAreaAccess: Boolean,
    ): ProvisionedUser {
        if (!policy.canCreateRole(actor, requestedRole)) throw UserManagementForbiddenException()

        // A MODERATOR's creation is additionally constrained beyond the role check itself
        // (§14): never global, and never an unassigned account - an unassigned SERVICE_USER
        // would fall outside every territorial moderator's authority the instant it exists.
        val distinctAreaIds = requestedAreaIds.distinct()
        if (actor.role == UserRole.MODERATOR) {
            if (requestedGlobalAreaAccess) throw UserManagementForbiddenException()
            if (distinctAreaIds.isEmpty()) throw UserRequiresServiceAreaException()
        }

        // Validate every requested area before touching the users table, so a bad area id
        // never leaves a half-created account behind.
        val areas = distinctAreaIds.map { areaId ->
            val area = serviceAreas.findById(areaId) ?: throw AreaNotFoundException()
            val snapshot = AssignedArea(area.id, area.name, area.status)
            if (!policy.canAssignArea(actor, snapshot)) throw AreaNotAssignableException()
            snapshot
        }

        val temporaryCredential = credentialGenerator.generate()
        val hash = hasher.hash(TemporaryCredentialGenerator.normalize(temporaryCredential))
        val now = clock.instant()

        repeat(SERVICE_ID_ATTEMPTS) {
            val candidate = serviceIdGenerator.next()
            val user = User(
                id = UUID.randomUUID(),
                serviceId = candidate,
                role = requestedRole,
                status = UserStatus.ACTIVE,
                passwordHash = hash,
                mustChangePassword = true,
                passwordChangedAt = null,
                createdAt = now,
                updatedAt = now,
            )

            if (users.insertIfServiceIdFree(user)) {
                areas.forEach { area -> serviceAreas.grantAreaIfAbsent(user.id, area.id) }
                if (requestedGlobalAreaAccess) serviceAreas.setGlobalAreaAccess(user.id, true)

                audit.record(
                    operationId = UUID.randomUUID(),
                    actorType = AuditActorType.USER,
                    actorUserId = actor.userId,
                    eventType = AuditEventType.USER_CREATED,
                    targetType = AuditTargetType.USER,
                    targetId = user.id,
                    metadata = mapOf(
                        "serviceId" to candidate.value,
                        "role" to requestedRole.name,
                        "areaIds" to areas.joinToString(",") { it.id.toString() },
                        "globalAreaAccess" to requestedGlobalAreaAccess.toString(),
                    ),
                )

                return ProvisionedUser(candidate, requestedRole, temporaryCredential, mustChangePassword = true)
            }
        }

        error("could not allocate a free service ID after $SERVICE_ID_ATTEMPTS attempts")
    }

    private companion object {
        const val SERVICE_ID_ATTEMPTS = 10
    }
}
