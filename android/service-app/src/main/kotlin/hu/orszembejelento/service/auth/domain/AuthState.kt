package hu.orszembejelento.service.auth.domain

/**
 * The single source of truth for whether the user is signed in.
 *
 * Deliberately one sealed state rather than a scattering of `isLoggedIn`, `needsPassword`
 * and `isLoading` booleans. Those combinations multiply — "loading and logged in and needs
 * password" is representable but meaningless — and every screen ends up re-deriving the
 * answer slightly differently. With a sealed hierarchy the impossible states cannot be
 * expressed at all, and there is exactly one place that decides.
 */
sealed interface AuthState {

    /** Startup: an encrypted refresh token exists and is being exchanged for a session. */
    data object RestoringSession : AuthState

    /** No usable credentials. The login screen is shown. */
    data object Unauthenticated : AuthState

    /**
     * The credentials were correct but the account still owes its initial password change.
     * No session exists yet, and the service ID is carried so the completion screen can
     * submit it without asking the user to type it again.
     */
    data class PasswordChangeRequired(val serviceId: String) : AuthState

    /** A live session. [role] comes from the server, never from anything stored locally. */
    data class Authenticated(val serviceId: String, val role: String) : AuthState

    /** A failure the user must see, such as an unreachable server. */
    data class AuthError(val kind: AuthErrorKind) : AuthState
}

/**
 * Why an attempt failed, in terms the UI can translate.
 *
 * Mapped from the server's stable error codes rather than from message text.
 */
enum class AuthErrorKind {
    INVALID_CREDENTIALS,
    PASSWORD_POLICY,
    RATE_LIMITED,
    NETWORK,
    UNEXPECTED,
}
