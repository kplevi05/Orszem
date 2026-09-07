package hu.orszembejelento.backend.identity.application

import hu.orszembejelento.backend.audit.domain.AuditActorType
import hu.orszembejelento.backend.audit.domain.AuditEventType
import hu.orszembejelento.backend.audit.domain.AuditTargetType
import hu.orszembejelento.backend.audit.infrastructure.JdbcAuditRepository
import hu.orszembejelento.backend.auth.domain.RevocationReason
import hu.orszembejelento.backend.auth.infrastructure.JdbcSessionRepository
import hu.orszembejelento.backend.identity.domain.PasswordHasher
import hu.orszembejelento.backend.identity.domain.ServiceId
import hu.orszembejelento.backend.identity.domain.ServiceIdGenerator
import hu.orszembejelento.backend.identity.domain.TemporaryCredentialGenerator
import hu.orszembejelento.backend.identity.domain.User
import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.identity.domain.UserStatus
import hu.orszembejelento.backend.identity.infrastructure.JdbcUserRepository
import java.time.Clock
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** What a maintenance command prints once, straight to the console. Never logged, never stored. */
data class ProvisionedCredential(
    val serviceId: ServiceId,
    val temporaryCredential: String,
)

/**
 * Creates a SUPER_ADMIN.
 *
 * There is no seeded administrator and no default credential anywhere in this system. A
 * shipped `admin/admin` is the single most reliably exploited weakness in self-hosted
 * software, and a seeded account exists on every deployment whether or not anyone needs it.
 * Instead an administrator is created explicitly, once, by someone with shell access to the
 * server — which is a strictly higher bar than any password could be.
 *
 * The account is created with a random temporary credential and `mustChangePassword`, so
 * the credential printed to the operator's console is useless after first use.
 */
@Service
class CreateSuperAdminUseCase(
    private val users: JdbcUserRepository,
    private val hasher: PasswordHasher,
    private val audit: JdbcAuditRepository,
    private val serviceIdGenerator: ServiceIdGenerator,
    private val credentialGenerator: TemporaryCredentialGenerator,
    private val clock: Clock,
) {

    @Transactional
    fun create(): ProvisionedCredential {
        val temporaryCredential = credentialGenerator.generate()
        // Stored in the canonical form the login flow will compare against.
        val hash = hasher.hash(TemporaryCredentialGenerator.normalize(temporaryCredential))
        val now = clock.instant()

        // The unique index is the authority on collisions; retry on conflict rather than
        // checking first, which would still race.
        repeat(SERVICE_ID_ATTEMPTS) {
            val candidate = serviceIdGenerator.next()
            val user = User(
                id = UUID.randomUUID(),
                serviceId = candidate,
                role = UserRole.SUPER_ADMIN,
                status = UserStatus.ACTIVE,
                passwordHash = hash,
                mustChangePassword = true,
                passwordChangedAt = null,
                createdAt = now,
                updatedAt = now,
            )

            if (users.insertIfServiceIdFree(user)) {
                audit.record(
                    operationId = UUID.randomUUID(),
                    actorType = AuditActorType.SYSTEM,
                    actorUserId = null,
                    eventType = AuditEventType.SUPER_ADMIN_CREATED,
                    targetType = AuditTargetType.USER,
                    targetId = user.id,
                    // Never the credential or its hash.
                    metadata = mapOf("source" to "MAINTENANCE_CLI", "serviceId" to candidate.value),
                )
                return ProvisionedCredential(candidate, temporaryCredential)
            }
        }

        error("could not allocate a free service ID after $SERVICE_ID_ATTEMPTS attempts")
    }

    private companion object {
        const val SERVICE_ID_ATTEMPTS = 10
    }
}

/**
 * Resets a SUPER_ADMIN password — the recovery path for a locked-out administrator.
 *
 * Strictly limited: it refuses any account that is not SUPER_ADMIN, does not reactivate a
 * deactivated account, does not change roles, and accepts no master password. It is a way
 * to regain access to an existing administrator account, not a way to manufacture one or
 * to reach anyone else's.
 */
@Service
class ResetSuperAdminPasswordUseCase(
    private val users: JdbcUserRepository,
    private val sessions: JdbcSessionRepository,
    private val hasher: PasswordHasher,
    private val audit: JdbcAuditRepository,
    private val credentialGenerator: TemporaryCredentialGenerator,
    private val clock: Clock,
) {

    class NotASuperAdminException(message: String) : RuntimeException(message)

    @Transactional
    fun reset(rawServiceId: String): ProvisionedCredential {
        val serviceId = ServiceId.parseOrNull(rawServiceId)
            ?: throw NotASuperAdminException("not a valid service ID: $rawServiceId")

        // Canonical lock order, step 1.
        val user = users.lockByServiceId(serviceId)
            ?: throw NotASuperAdminException("no such user: ${serviceId.value}")

        if (user.role != UserRole.SUPER_ADMIN) {
            throw NotASuperAdminException("${serviceId.value} is not a SUPER_ADMIN; refusing to reset")
        }
        if (user.status != UserStatus.ACTIVE) {
            // Deliberately not reactivating: that is an administrative decision, not a
            // side effect of password recovery.
            throw NotASuperAdminException("${serviceId.value} is not ACTIVE; refusing to reset")
        }

        val temporaryCredential = credentialGenerator.generate()
        val now = clock.instant()
        val operationId = UUID.randomUUID()

        users.updatePassword(
            userId = user.id,
            passwordHash = hasher.hash(TemporaryCredentialGenerator.normalize(temporaryCredential)),
            mustChangePassword = true,
            now = now,
        )

        val revoked = sessions.revokeAllSessionsOfUser(user.id, RevocationReason.ADMIN_PASSWORD_RESET, now)

        audit.record(
            operationId = operationId,
            actorType = AuditActorType.SYSTEM,
            actorUserId = null,
            eventType = AuditEventType.SUPER_ADMIN_PASSWORD_RESET,
            targetType = AuditTargetType.USER,
            targetId = user.id,
            metadata = mapOf(
                "source" to "MAINTENANCE_CLI",
                "serviceId" to serviceId.value,
                "revokedSessions" to revoked.size.toString(),
            ),
        )

        return ProvisionedCredential(serviceId, temporaryCredential)
    }
}
