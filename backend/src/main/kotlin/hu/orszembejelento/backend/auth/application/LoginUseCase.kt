package hu.orszembejelento.backend.auth.application

import hu.orszembejelento.backend.auth.infrastructure.LoginRateLimiter
import hu.orszembejelento.backend.identity.domain.PasswordHasher
import hu.orszembejelento.backend.identity.domain.PasswordNormalizer
import hu.orszembejelento.backend.identity.domain.ServiceId
import hu.orszembejelento.backend.identity.domain.TemporaryCredentialGenerator
import hu.orszembejelento.backend.identity.infrastructure.JdbcUserRepository
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Password login.
 *
 * Three externally identical failures — unknown service ID, wrong password, deactivated
 * account — all raise [InvalidCredentialsException]. Distinguishing them would let anyone
 * enumerate valid service IDs and learn which accounts are disabled.
 *
 * Timing is levelled too: when no user exists, the password is still verified against a
 * dummy Argon2 hash. Without that, an unknown account would return in microseconds while a
 * real one took the full Argon2 cost, and the difference alone would reveal which service
 * IDs exist.
 */
@Service
class LoginUseCase(
    private val users: JdbcUserRepository,
    private val hasher: PasswordHasher,
    private val sessionFactory: SessionFactory,
    private val rateLimiter: LoginRateLimiter,
    private val dummyHash: DummyPasswordHash,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @param rawServiceId as typed by the user; normalized here.
     * @param rawPassword never trimmed or altered beyond Unicode NFC normalization.
     */
    @Transactional
    fun login(rawServiceId: String?, rawPassword: String, sourceIp: String?): IssuedCredentials {
        val serviceId = ServiceId.parseOrNull(rawServiceId)

        // Throttle on the canonical service ID when it parses, and on the raw input when it
        // does not, so malformed guesses still consume budget.
        val throttleKey = serviceId?.value ?: rawServiceId?.trim()?.take(64)
        rateLimiter.checkAllowed(throttleKey, sourceIp)

        if (serviceId == null) {
            // Malformed input is ordinary validation, but it still costs an attempt so the
            // endpoint cannot be probed for free.
            rateLimiter.recordFailure(throttleKey, sourceIp)
            throw InvalidCredentialsException()
        }

        val password = PasswordNormalizer.normalize(rawPassword)

        // Canonical lock order, step 1: lock the user row before verifying anything, so a
        // concurrent password reset cannot commit between verification and session creation.
        val user = users.lockByServiceId(serviceId)

        if (user == null) {
            // Equivalent work to a real verification, so response time does not disclose
            // whether the account exists.
            hasher.matches(password, dummyHash.value)
            rateLimiter.recordFailure(throttleKey, sourceIp)
            log.info("authentication failed: no such service id")
            throw InvalidCredentialsException()
        }

        // An account still owing its initial change holds a temporary credential, which is
        // stored in canonical form. Accept it as printed too — with display hyphens and in
        // any case — otherwise the very first sign-in would fail for anyone who types the
        // credential exactly as the maintenance command showed it.
        //
        // This alternate form is tried *only* while must_change_password is set. Applying
        // it to ordinary passwords would strip hyphens and change case from passwords where
        // those characters are significant.
        val passwordMatches = hasher.matches(password, user.passwordHash) ||
            (
                user.mustChangePassword &&
                    hasher.matches(TemporaryCredentialGenerator.normalize(rawPassword), user.passwordHash)
                )

        // A deactivated account is verified first and rejected afterwards, so that it costs
        // the same as an active one and cannot be identified by timing either.
        if (!passwordMatches || !user.isActive) {
            rateLimiter.recordFailure(throttleKey, sourceIp)
            log.info("authentication failed for an existing account")
            throw InvalidCredentialsException()
        }

        rateLimiter.recordSuccess(throttleKey)

        // Correct credentials, but the account still owes its initial password change.
        // No session is created: the client must complete the change first.
        if (user.mustChangePassword) throw PasswordChangeRequiredException()

        return sessionFactory.createSession(user.id, UUID.randomUUID())
    }
}

/**
 * A real Argon2 hash of a random value, computed once at startup, used to spend the same
 * verification time on unknown accounts as on real ones.
 *
 * The plaintext is discarded immediately and deliberately never retained: nothing can ever
 * match this hash, so it cannot become a backdoor credential.
 */
@Service
class DummyPasswordHash(hasher: PasswordHasher) {
    val value: String = hasher.hash(UUID.randomUUID().toString() + UUID.randomUUID().toString())
}
