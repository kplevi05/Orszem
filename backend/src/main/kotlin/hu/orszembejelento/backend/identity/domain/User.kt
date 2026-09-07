package hu.orszembejelento.backend.identity.domain

import java.time.Instant
import java.util.UUID

enum class UserRole { SERVICE_USER, MODERATOR, SUPER_ADMIN }

enum class UserStatus { ACTIVE, DEACTIVATED }

/**
 * A service user.
 *
 * Holds no personal data by design — see the V001 migration. Identity is the pseudonymous
 * [serviceId]; everything else is authentication state.
 */
data class User(
    val id: UUID,
    val serviceId: ServiceId,
    val role: UserRole,
    val status: UserStatus,
    val passwordHash: String,
    val mustChangePassword: Boolean,
    val passwordChangedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val isActive: Boolean get() = status == UserStatus.ACTIVE

    /**
     * Whether this account may perform ordinary authenticated work.
     *
     * An account that still owes a password change is authenticated but not operational:
     * only the password-completion flow may proceed.
     */
    val canPerformProtectedOperations: Boolean get() = isActive && !mustChangePassword
}
