package hu.orszembejelento.backend.reports.infrastructure

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import hu.orszembejelento.backend.auth.infrastructure.IpLiterals
import hu.orszembejelento.backend.common.config.PublicSubmissionRateLimitProperties
import hu.orszembejelento.backend.reports.domain.SubmissionRateLimitedException
import java.net.Inet6Address
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Per-source token bucket for anonymous Public report creation (decision B6, ADR 0010).
 *
 * ## Algorithm
 * A virtual-scheduling token bucket (GCRA). A source's whole state is one number, the
 * *theoretical arrival time* `tat`: the instant at which its bucket would be completely
 * empty if no further requests came. With bucket size `burst` and one token per `period`:
 *
 * ```
 * newTat   = max(tat, now) + period          // this request would use one token
 * allowAt  = newTat - burst * period         // earliest instant that is still within the burst
 * allowed  = now >= allowAt
 * ```
 *
 * A source that has been idle has `tat <= now`, which is a full bucket. One number per source
 * keeps memory tiny, and updating it inside Caffeine's per-key `compute` makes every decision
 * atomic, so concurrent requests from one source cannot overspend the bucket.
 *
 * ## Who a "source" is
 * The caller passes the address `ClientIpResolver` resolved. That resolver already believes
 * `X-Forwarded-For` only when the TCP peer is a configured trusted proxy (Caddy, on loopback)
 * and then only its last entry, so a client cannot forge its way to a different bucket. This
 * class then reduces the address to a key:
 *
 * * IPv4: the exact address.
 * * IPv6: the first 64 bits (the "slash 64" prefix). A single subscriber is normally delegated a whole /64 and can
 *   rotate through its 2^64 addresses; limiting per full address would give them a fresh
 *   bucket every request.
 * * IPv4-mapped IPv6 (`::ffff:a.b.c.d`) is the same host as `a.b.c.d`, so it maps to IPv4.
 *
 * ## Memory
 * Bounded twice. Entries expire once the bucket would be full again (an entry older than that
 * carries no information), and the cache is capped at `maximumTrackedKeys`; beyond the cap
 * Caffeine evicts, and an evicted source starts again with a full bucket. Memory therefore
 * cannot grow with traffic. Eviction is run on the calling thread, so it is deterministic.
 *
 * ## What is deliberately not here
 * No source address is ever logged or stored anywhere but this in-memory map, and nothing
 * survives a restart. That is correct for the single-instance deployment this project runs.
 * Running more than one backend instance would give each its own counters (an effective limit
 * of N times the configured one) and needs a shared design first - see ADR 0010.
 */
@Component
class PublicSubmissionRateLimiter(
    private val properties: PublicSubmissionRateLimitProperties,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val periodNanos: Long = properties.refillPeriod.toNanos()
    private val burstWindowNanos: Long = Math.multiplyExact(periodNanos, properties.burst.toLong())

    /** Nanoseconds on this limiter's clock; the same source drives both the maths and expiry. */
    private fun nowNanos(): Long = Math.multiplyExact(clock.millis(), 1_000_000L)

    private val buckets: Cache<String, AtomicLong> = Caffeine.newBuilder()
        .ticker(Ticker { nowNanos() })
        // Once the bucket would be full again the entry is worthless; keep it that long, no more.
        .expireAfterWrite(Duration.ofNanos(burstWindowNanos).plusSeconds(1))
        .maximumSize(properties.maximumTrackedKeys)
        .executor(Runnable::run)
        .build()

    private val throttledSinceLastLog = AtomicLong(0)
    private val lastLogNanos = AtomicLong(Long.MIN_VALUE)

    /**
     * Spends one token for [sourceAddress], or throws [SubmissionRateLimitedException].
     *
     * A null address (which a real socket never produces) is not limited rather than pooled
     * into one shared bucket, which would let one caller throttle everyone.
     */
    fun acquire(sourceAddress: String?): Permit {
        if (!properties.enabled) return Permit.NONE
        val key = keyFor(sourceAddress) ?: return Permit.NONE

        val now = nowNanos()
        var retryAfterNanos = 0L
        buckets.asMap().compute(key) { _, current ->
            val tat = current?.get() ?: now
            val newTat = maxOf(tat, now) + periodNanos
            val allowAt = newTat - burstWindowNanos
            if (now >= allowAt) {
                AtomicLong(newTat)
            } else {
                retryAfterNanos = allowAt - now
                current ?: AtomicLong(tat)
            }
        }

        if (retryAfterNanos > 0) {
            noteThrottled(now)
            throw SubmissionRateLimitedException(retryAfterSeconds(retryAfterNanos))
        }
        return Permit { refund(key) }
    }

    /** Gives back a token taken for a request that turned out not to create anything. */
    private fun refund(key: String) {
        buckets.asMap().computeIfPresent(key) { _, current ->
            // Never credit beyond a full bucket: a refund is a correction, not extra allowance.
            AtomicLong(maxOf(current.get() - periodNanos, nowNanos()))
        }
    }

    /** `Retry-After` is whole seconds, rounded up so the caller never retries too early. */
    private fun retryAfterSeconds(nanos: Long): Long =
        maxOf(1L, TimeUnit.NANOSECONDS.toSeconds(nanos + TimeUnit.SECONDS.toNanos(1) - 1))

    /**
     * At most one log line a minute, carrying only a count. No address, no key, nothing about
     * any caller: the log must be able to say "throttling is happening" without becoming a
     * record of who.
     */
    private fun noteThrottled(now: Long) {
        throttledSinceLastLog.incrementAndGet()
        val last = lastLogNanos.get()
        if ((last == Long.MIN_VALUE || now - last >= LOG_INTERVAL_NANOS) && lastLogNanos.compareAndSet(last, now)) {
            log.warn(
                "public report submissions are being throttled ({} requests since the last notice)",
                throttledSinceLastLog.getAndSet(0),
            )
        }
    }

    /** Number of sources currently tracked; exposed for tests of the memory bound. */
    fun trackedKeys(): Long {
        buckets.cleanUp()
        return buckets.estimatedSize()
    }

    /** A token that can be handed back when the request did not, after all, create a report. */
    fun interface Permit {
        fun refund()

        companion object {
            val NONE = Permit { }
        }
    }

    internal companion object {
        private val LOG_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1)

        /** The bucket key for a resolved address, or null when it is not an IP literal. */
        fun keyFor(address: String?): String? {
            val parsed = address?.let(IpLiterals::parse) ?: return null
            val bytes = parsed.address
            return if (parsed is Inet6Address) {
                "6:" + bytes.copyOfRange(0, 8).joinToString("") { "%02x".format(it) }
            } else {
                "4:" + bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
            }
        }
    }
}
