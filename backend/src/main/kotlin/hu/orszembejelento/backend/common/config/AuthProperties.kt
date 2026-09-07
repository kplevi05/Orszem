package hu.orszembejelento.backend.common.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Authentication tuning. Configuration rather than constants scattered through use cases,
 * so lifetimes and thresholds can be adjusted per environment without a code change.
 *
 * The rate-limit numbers are provisional pilot values. They are deliberately conservative
 * and are expected to be tuned once there is real traffic to measure.
 */
@ConfigurationProperties(prefix = "orszem.auth")
data class AuthProperties(
    /** How long a single access token stays valid. Short, because revocation is cheap here. */
    val accessTokenLifetime: Duration = Duration.ofMinutes(15),

    /**
     * Absolute session lifetime. Refreshing issues new tokens but can never move this
     * boundary, so a session cannot be kept alive indefinitely by refreshing.
     */
    val sessionLifetime: Duration = Duration.ofDays(30),

    val rateLimit: RateLimitProperties = RateLimitProperties(),
) {
    data class RateLimitProperties(
        val enabled: Boolean = true,

        /** Failed attempts allowed per service ID within [window] before throttling. */
        val perServiceIdAttempts: Int = 10,

        /**
         * Failed attempts allowed per source IP within [window].
         *
         * Higher than the per-account limit on purpose: several legitimate users can share
         * one NAT address, and an IP limit set as tightly as the account limit would lock
         * out a whole site because of one person's typo.
         */
        val perIpAttempts: Int = 40,

        val window: Duration = Duration.ofMinutes(15),

        /** Value advertised in `Retry-After` when a caller is throttled. */
        val retryAfter: Duration = Duration.ofMinutes(1),

        /** Upper bound on tracked keys, so the limiter cannot grow without limit. */
        val maximumTrackedKeys: Long = 100_000,
    )
}
