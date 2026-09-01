package hu.orszem.identity.domain

import java.util.UUID

enum class UserRole { SERVICE_USER }

enum class UserStatus { ACTIVE, DISABLED }

/** Minimal Demo v1 service user. */
data class ServiceUser(
    val id: UUID,
    val username: String,
    val displayName: String,
    val passwordHash: String,
    val role: UserRole,
    val status: UserStatus,
) {
    val isActive: Boolean get() = status == UserStatus.ACTIVE
}

interface ServiceUserRepository {
    fun findByUsername(username: String): ServiceUser?
    fun findById(id: UUID): ServiceUser?
}
