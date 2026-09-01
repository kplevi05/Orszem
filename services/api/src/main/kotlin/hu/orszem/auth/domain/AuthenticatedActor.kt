package hu.orszem.auth.domain

import java.util.UUID

/** The authenticated service user for the current request. */
data class AuthenticatedActor(
    val userId: UUID,
    val username: String,
    val displayName: String,
    val role: String,
    val capabilities: Set<Capability>,
)
