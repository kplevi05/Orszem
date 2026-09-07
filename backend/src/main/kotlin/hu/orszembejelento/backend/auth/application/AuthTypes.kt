package hu.orszembejelento.backend.auth.application

import hu.orszembejelento.backend.identity.domain.ServiceId
import hu.orszembejelento.backend.identity.domain.UserRole
import java.time.Instant
import java.util.UUID

/**
 * Credentials handed to a client after a successful authentication or rotation.
 *
 * The plaintext secrets exist only inside this object, on their way into the HTTP
 * response. Nothing here is persisted or logged.
 */
data class IssuedCredentials(
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    val refreshToken: String,
    val sessionExpiresAt: Instant,
    val sessionId: UUID,
)

/**
 * The authenticated caller, built from current database state on every request.
 *
 * Role is read from the `users` row rather than carried in the token, so a role change or
 * deactivation takes effect on the next request instead of when some token happens to
 * expire. A client's claim about its own role is never trusted.
 */
data class AuthenticatedActor(
    val userId: UUID,
    val serviceId: ServiceId,
    val role: UserRole,
    val sessionId: UUID,
)

/**
 * Every way authentication can fail, collapsed to one exception.
 *
 * Callers map this to a single generic response, so an unknown service ID, a wrong
 * password and a deactivated account are externally indistinguishable.
 */
class InvalidCredentialsException : RuntimeException("invalid credentials")

/** The account must complete its initial password change before a session can be issued. */
class PasswordChangeRequiredException : RuntimeException("password change required")

/** An access or refresh credential was malformed, unknown, expired, consumed or revoked. */
class SessionInvalidException : RuntimeException("session invalid")

/** Too many recent failed attempts for this service ID or source IP. */
class RateLimitedException(val retryAfterSeconds: Long) : RuntimeException("rate limited")
