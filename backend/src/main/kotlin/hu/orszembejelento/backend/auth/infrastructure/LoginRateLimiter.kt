package hu.orszembejelento.backend.auth.infrastructure

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Ticker
import hu.orszembejelento.backend.common.config.AuthProperties
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import org.springframework.stereotype.Component

/**
 * Brute-force throttling for authentication attempts.
 *
 * Deliberately **not** account lockout. A permanent lock turns a guessing attempt into a
 * denial-of-service against the real user: anyone who knows a service ID could keep that
 * person permanently signed out. Attempts decay instead, so a genuine user is delayed at
 * worst, never locked out.
 *
 * Two independent buckets, counted separately rather than as one `(ip, serviceId)` pair:
 *
 *  * **per service ID** — stops one account being hammered from many addresses. A combined
 *    key would give an attacker a fresh budget for every source address they control.
 *  * **per source IP** — stops one address spraying one password across many accounts. A
 *    combined key would give a fresh budget for every service ID tried.
 *
 * Backed by Caffeine with a bounded maximum size and time-based expiry, so the limiter
 * cannot grow without limit while under attack — the failure mode of the obvious
 * `ConcurrentHashMap` implementation.
 *
 * **Accepted limitation:** the counters live in memory, so a backend restart clears them.
 * That is acceptable for a single-server pilot and avoids introducing Redis for this
 * alone. It must be revisited before running more than one backend instance, since each
 * would then count independently.
 */
@Component
class LoginRateLimiter(
    properties: AuthProperties,
    /**
     * Time source for expiry. Defaults to the system ticker; tests supply a fake one so
     * the cooldown can be exercised by advancing time rather than by sleeping for the
     * whole window.
     */
    private val ticker: Ticker = Ticker.systemTicker(),
) {

    private val config = properties.rateLimit

    private val serviceIdAttempts: Cache<String, AtomicInteger> = buildCache(config.window, config.maximumTrackedKeys)
    private val ipAttempts: Cache<String, AtomicInteger> = buildCache(config.window, config.maximumTrackedKeys)

    /**
     * Checks both buckets before an attempt is processed.
     *
     * The service ID is checked even when no such account exists, so an attacker cannot
     * escape throttling simply by guessing identifiers that are not registered.
     */
    fun checkAllowed(serviceId: String?, sourceIp: String?) {
        if (!config.enabled) return

        val serviceIdCount = serviceId?.let { serviceIdAttempts.getIfPresent(it)?.get() } ?: 0
        val ipCount = sourceIp?.let { ipAttempts.getIfPresent(it)?.get() } ?: 0

        if (serviceIdCount >= config.perServiceIdAttempts || ipCount >= config.perIpAttempts) {
            throw hu.orszembejelento.backend.auth.application.RateLimitedException(config.retryAfter.seconds)
        }
    }

    /** Records a failed attempt. Successful authentications deliberately cost nothing. */
    fun recordFailure(serviceId: String?, sourceIp: String?) {
        if (!config.enabled) return
        serviceId?.let { serviceIdAttempts.get(it) { AtomicInteger(0) }.incrementAndGet() }
        sourceIp?.let { ipAttempts.get(it) { AtomicInteger(0) }.incrementAndGet() }
    }

    /**
     * Clears the service-ID bucket after a successful authentication, so a user who
     * mistyped their password several times is not still throttled once they get it right.
     * The IP bucket is intentionally left alone: a shared address that has produced many
     * failures stays throttled even if one account on it succeeds.
     */
    fun recordSuccess(serviceId: String?) {
        if (!config.enabled) return
        serviceId?.let { serviceIdAttempts.invalidate(it) }
    }

    private fun buildCache(window: Duration, maximumSize: Long): Cache<String, AtomicInteger> =
        Caffeine.newBuilder()
            .expireAfterWrite(window)
            .maximumSize(maximumSize)
            .ticker(ticker)
            .build()
}
