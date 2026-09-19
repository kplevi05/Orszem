package hu.orszembejelento.backend.reports

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import hu.orszembejelento.backend.auth.infrastructure.ClientIpResolver
import hu.orszembejelento.backend.common.config.AuthProperties
import hu.orszembejelento.backend.common.config.PublicSubmissionRateLimitProperties
import hu.orszembejelento.backend.reports.domain.SubmissionRateLimitedException
import hu.orszembejelento.backend.reports.infrastructure.PublicSubmissionRateLimiter
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockHttpServletRequest

/**
 * The Public report-creation limiter (decision B6): burst 10, one token per 20 s.
 *
 * Pure unit tests against a fake clock, so refill, `Retry-After` and expiry are exact and
 * instant rather than sleeps. The end-to-end behaviour (replay bypass, HTTP shape, spoofed
 * headers through the real filter chain, races against PostgreSQL) is in
 * `PublicSubmissionRateLimitIT`.
 */
class PublicSubmissionRateLimiterTest {

    /** A clock the test moves by hand. */
    private class TestClock(start: Instant = Instant.parse("2026-09-20T10:00:00Z")) : Clock() {
        private val millis = AtomicLong(start.toEpochMilli())
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): Instant = Instant.ofEpochMilli(millis.get())
        override fun millis(): Long = millis.get()
        fun advance(by: Duration) { millis.addAndGet(by.toMillis()) }
    }

    private val clock = TestClock()

    private fun limiter(
        burst: Int = 10,
        refill: Duration = Duration.ofSeconds(20),
        maxKeys: Long = 100_000,
        enabled: Boolean = true,
    ) = PublicSubmissionRateLimiter(PublicSubmissionRateLimitProperties(enabled, burst, refill, maxKeys), clock)

    private fun PublicSubmissionRateLimiter.tryOnce(source: String?): Long? =
        try { acquire(source); null } catch (e: SubmissionRateLimitedException) { e.retryAfterSeconds }

    // ------------------------------------------------------------------- burst and refill

    @Test
    fun `a source may create exactly ten reports in a burst and the eleventh is throttled`() {
        val limiter = limiter()
        repeat(10) { assertEquals(null, limiter.tryOnce("203.0.113.5"), "creation ${it + 1} must be allowed") }
        assertEquals(20L, limiter.tryOnce("203.0.113.5"), "the eleventh must wait one full token period")
    }

    @Test
    fun `one token is added every twenty seconds, and Retry-After counts down honestly`() {
        val limiter = limiter()
        repeat(10) { limiter.acquire("203.0.113.5") }

        clock.advance(Duration.ofSeconds(5))
        assertEquals(15L, limiter.tryOnce("203.0.113.5"))

        clock.advance(Duration.ofSeconds(14))
        assertEquals(1L, limiter.tryOnce("203.0.113.5"), "1 second left")

        clock.advance(Duration.ofSeconds(1)) // 20 s since exhaustion
        assertEquals(null, limiter.tryOnce("203.0.113.5"), "one token has arrived")
        assertEquals(20L, limiter.tryOnce("203.0.113.5"), "and it was the only one")
    }

    @Test
    fun `Retry-After is whole seconds, rounded up, and never zero`() {
        val limiter = limiter()
        repeat(10) { limiter.acquire("203.0.113.5") }

        clock.advance(Duration.ofMillis(19_500))
        assertEquals(1L, limiter.tryOnce("203.0.113.5"), "0.5 s left rounds up to 1, never 0")
    }

    @Test
    fun `sustained use is capped at one report per twenty seconds after the burst`() {
        val limiter = limiter()
        repeat(10) { limiter.acquire("203.0.113.5") }

        var allowed = 0
        repeat(30) { // ten simulated minutes, trying every 20 s
            clock.advance(Duration.ofSeconds(20))
            if (limiter.tryOnce("203.0.113.5") == null) allowed++
        }
        assertEquals(30, allowed, "a caller staying at the sustained rate is never throttled")

        var hammered = 0
        repeat(600) { // ten minutes, hammering once per second
            clock.advance(Duration.ofSeconds(1))
            if (limiter.tryOnce("203.0.113.5") == null) hammered++
        }
        assertEquals(30, hammered, "hammering yields exactly the refill: 600 s / 20 s")
    }

    @Test
    fun `an idle source never accumulates more than a full bucket`() {
        val limiter = limiter()
        limiter.acquire("203.0.113.5")
        clock.advance(Duration.ofHours(6))

        var allowed = 0
        repeat(25) { if (limiter.tryOnce("203.0.113.5") == null) allowed++ }
        assertEquals(10, allowed, "a long idle period buys a full burst, not more")
    }

    @Test
    fun `different sources have independent buckets`() {
        val limiter = limiter()
        repeat(10) { limiter.acquire("203.0.113.5") }
        assertTrue(limiter.tryOnce("203.0.113.5") != null)
        assertEquals(null, limiter.tryOnce("203.0.113.6"))
        assertEquals(null, limiter.tryOnce("198.51.100.5"))
    }

    // ------------------------------------------------------------------------ IPv4 / IPv6

    @Test
    fun `IPv6 addresses in one slash-64 share one bucket, another slash-64 has its own`() {
        val limiter = limiter()
        // Ten different hosts inside 2001:db8:1:2::/64 (an attacker rotating the low 64 bits).
        (1..10).forEach { limiter.acquire("2001:db8:1:2::$it") }

        assertTrue(limiter.tryOnce("2001:db8:1:2:aaaa:bbbb:cccc:dddd") != null, "same /64, new low bits: throttled")
        assertTrue(limiter.tryOnce("2001:db8:1:2::ffff") != null)
        assertEquals(null, limiter.tryOnce("2001:db8:1:3::1"), "the next /64 is a different source")
    }

    @Test
    fun `IPv4 is keyed by its exact address, not a range`() {
        val limiter = limiter()
        repeat(10) { limiter.acquire("203.0.113.5") }
        assertEquals(null, limiter.tryOnce("203.0.113.6"), "a neighbouring IPv4 address is a different source")
    }

    @Test
    fun `an IPv4-mapped IPv6 address is the same source as the IPv4 address`() {
        assertEquals(
            PublicSubmissionRateLimiter.keyFor("203.0.113.9"),
            PublicSubmissionRateLimiter.keyFor("::ffff:203.0.113.9"),
        )
        val limiter = limiter()
        repeat(10) { limiter.acquire("203.0.113.9") }
        assertTrue(limiter.tryOnce("::ffff:203.0.113.9") != null)
    }

    @Test
    fun `equivalent spellings of one IPv6 address are one key`() {
        assertEquals(
            PublicSubmissionRateLimiter.keyFor("2001:db8:0:0:0:0:0:1"),
            PublicSubmissionRateLimiter.keyFor("2001:DB8::1"),
        )
        assertNotEquals(
            PublicSubmissionRateLimiter.keyFor("2001:db8:1::1"),
            PublicSubmissionRateLimiter.keyFor("2001:db8:2::1"),
        )
    }

    @Test
    fun `something that is not an IP literal is never turned into a key, so it cannot be used to pool or flood`() {
        assertEquals(null, PublicSubmissionRateLimiter.keyFor(null))
        assertEquals(null, PublicSubmissionRateLimiter.keyFor("not-an-ip"))
        assertEquals(null, PublicSubmissionRateLimiter.keyFor("evil.example.com"))
        assertEquals(null, PublicSubmissionRateLimiter.keyFor("999.1.1.1"))
        assertEquals(null, PublicSubmissionRateLimiter.keyFor(""))
        assertEquals(null, limiter().tryOnce(null), "an unknown source is not throttled (fail open, never a shared bucket)")
    }

    // -------------------------------------------------- resolver + limiter: spoofed headers

    private val resolver = ClientIpResolver(AuthProperties()) // default: loopback is the only trusted proxy

    private fun request(peer: String, forwardedFor: String?): MockHttpServletRequest =
        MockHttpServletRequest().apply {
            remoteAddr = peer
            forwardedFor?.let { addHeader("X-Forwarded-For", it) }
        }

    @Test
    fun `a forged X-Forwarded-For from an untrusted peer is ignored - the peer is the source`() {
        val limiter = limiter()
        // A client connecting directly (not via Caddy) and inventing a new "client" every time.
        repeat(10) { i ->
            val source = resolver.resolve(request("198.51.100.9", "10.0.0.$i"))
            assertEquals("198.51.100.9", source)
            limiter.acquire(source)
        }
        val eleventh = resolver.resolve(request("198.51.100.9", "10.9.9.9"))
        assertTrue(limiter.tryOnce(eleventh) != null, "inventing a new forwarded address must not earn a new bucket")
    }

    @Test
    fun `behind the trusted proxy only the last forwarded entry counts, so prepending forged entries changes nothing`() {
        val limiter = limiter()
        repeat(10) { i ->
            val source = resolver.resolve(request("127.0.0.1", "192.0.2.$i, 10.$i.0.1, 203.0.113.77"))
            assertEquals("203.0.113.77", source, "the rightmost entry is the one the proxy wrote")
            limiter.acquire(source)
        }
        val eleventh = resolver.resolve(request("127.0.0.1", "1.2.3.4, 203.0.113.77"))
        assertTrue(limiter.tryOnce(eleventh) != null)
    }

    @Test
    fun `an unparseable forwarded value falls back to the peer instead of opening a fresh bucket`() {
        val limiter = limiter()
        repeat(10) { i ->
            val source = resolver.resolve(request("127.0.0.1", "garbage-$i"))
            assertEquals("127.0.0.1", source)
            limiter.acquire(source)
        }
        assertTrue(limiter.tryOnce(resolver.resolve(request("127.0.0.1", "garbage-again"))) != null)
    }

    // ----------------------------------------------------------------------------- refund

    @Test
    fun `a refunded token is usable again`() {
        val limiter = limiter()
        val permits = (1..10).map { limiter.acquire("203.0.113.5") }
        assertTrue(limiter.tryOnce("203.0.113.5") != null)

        permits.first().refund()
        assertEquals(null, limiter.tryOnce("203.0.113.5"), "the refunded token can be spent")
        assertTrue(limiter.tryOnce("203.0.113.5") != null, "and only that one")
    }

    @Test
    fun `a refund can never grant more than a full bucket`() {
        val limiter = limiter()
        val permit = limiter.acquire("203.0.113.5")
        permit.refund()
        permit.refund()
        permit.refund()

        var allowed = 0
        repeat(25) { if (limiter.tryOnce("203.0.113.5") == null) allowed++ }
        assertEquals(10, allowed, "refunds are corrections, not extra allowance")
    }

    // -------------------------------------------------------------------- off switch, config

    @Test
    fun `when disabled nothing is ever throttled`() {
        val limiter = limiter(enabled = false)
        repeat(1000) { assertEquals(null, limiter.tryOnce("203.0.113.5")) }
        assertEquals(0L, limiter.trackedKeys(), "and nothing is tracked")
    }

    @Test
    fun `nonsensical configuration fails fast instead of silently disabling the limit`() {
        assertThrows(IllegalArgumentException::class.java) { PublicSubmissionRateLimitProperties(burst = 0) }
        assertThrows(IllegalArgumentException::class.java) { PublicSubmissionRateLimitProperties(refillPeriod = Duration.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { PublicSubmissionRateLimitProperties(refillPeriod = Duration.ofSeconds(-1)) }
        assertThrows(IllegalArgumentException::class.java) { PublicSubmissionRateLimitProperties(maximumTrackedKeys = 0) }
    }

    @Test
    fun `the approved production defaults are burst 10 and one token per 20 seconds`() {
        val defaults = PublicSubmissionRateLimitProperties()
        assertTrue(defaults.enabled)
        assertEquals(10, defaults.burst)
        assertEquals(Duration.ofSeconds(20), defaults.refillPeriod)
        assertEquals(100_000L, defaults.maximumTrackedKeys)
    }

    // -------------------------------------------------------------- bounded memory, eviction

    @Test
    fun `memory is bounded by the key cap however many distinct sources arrive`() {
        val limiter = limiter(maxKeys = 100)
        (0 until 20_000).forEach { i -> limiter.acquire("10.${(i shr 16) and 255}.${(i shr 8) and 255}.${i and 255}") }
        assertTrue(limiter.trackedKeys() <= 100, "tracked ${limiter.trackedKeys()} keys, cap is 100")
    }

    @Test
    fun `an evicted source simply starts over with a full bucket - eviction never errors or blocks`() {
        val limiter = limiter(maxKeys = 50)
        repeat(10) { limiter.acquire("203.0.113.5") }
        assertTrue(limiter.tryOnce("203.0.113.5") != null)

        // A flood of other sources pushes it out (or not); either way no exception, bounded size.
        (0 until 5_000).forEach { i -> limiter.acquire("172.16.${i shr 8}.${i and 255}") }
        val result = limiter.tryOnce("203.0.113.5")
        assertTrue(result == null || result in 1..20, "either evicted (allowed) or still throttled (sane Retry-After)")
        assertTrue(limiter.trackedKeys() <= 50)
    }

    @Test
    fun `idle entries expire once their bucket would be full again, so the map empties by itself`() {
        val limiter = limiter()
        (0 until 500).forEach { i -> limiter.acquire("10.0.${i shr 8}.${i and 255}") }
        assertTrue(limiter.trackedKeys() > 0)

        clock.advance(Duration.ofSeconds(200 + 5)) // burst * period, plus the margin
        assertEquals(0L, limiter.trackedKeys(), "nothing is remembered past the point it stops mattering")
    }

    @Test
    fun `expiry does not shortchange a throttled source - it is not forgotten early`() {
        val limiter = limiter()
        repeat(10) { limiter.acquire("203.0.113.5") }
        clock.advance(Duration.ofSeconds(100)) // half the window: it has earned 5 tokens back, not 10
        var allowed = 0
        repeat(20) { if (limiter.tryOnce("203.0.113.5") == null) allowed++ }
        assertEquals(5, allowed, "the entry must still remember it was throttled")
    }

    // ------------------------------------------------------------------------- concurrency

    @Test
    fun `many threads hammering one source can never overspend the bucket`() {
        val limiter = limiter()
        val threads = 64
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val allowed = AtomicInteger()
        val throttled = AtomicInteger()
        try {
            val futures = (1..threads).map {
                pool.submit {
                    ready.countDown()
                    go.await(10, TimeUnit.SECONDS)
                    repeat(20) {
                        if (limiter.tryOnce("203.0.113.5") == null) allowed.incrementAndGet() else throttled.incrementAndGet()
                    }
                }
            }
            ready.await(10, TimeUnit.SECONDS)
            go.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(10, allowed.get(), "exactly the burst, however the threads interleave")
        assertEquals(threads * 20 - 10, throttled.get())
    }

    @Test
    fun `concurrent refunds and acquisitions never credit a source above a full bucket`() {
        val limiter = limiter()
        val pool = Executors.newFixedThreadPool(16)
        try {
            (1..16).map {
                pool.submit {
                    repeat(200) {
                        val permit = try { limiter.acquire("203.0.113.5") } catch (e: SubmissionRateLimitedException) { null }
                        permit?.refund()
                    }
                }
            }.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        // Every spend was refunded, so the bucket is full: exactly ten more, then throttled.
        var allowed = 0
        repeat(25) { if (limiter.tryOnce("203.0.113.5") == null) allowed++ }
        assertEquals(10, allowed, "every spend was refunded, so the bucket is exactly full: never more, never fewer than the burst")
    }

    // -------------------------------------------------------------------------- log hygiene

    @Test
    fun `throttling is logged as a count at most once a minute and never names a source`() {
        val logger = LoggerFactory.getLogger(PublicSubmissionRateLimiter::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            val limiter = limiter()
            repeat(10) { limiter.acquire("203.0.113.5") }
            repeat(50) { limiter.tryOnce("203.0.113.5") }

            val first = appender.list.filter { it.level == Level.WARN }
            assertEquals(1, first.size, "fifty throttled requests in one instant produce one notice")

            clock.advance(Duration.ofSeconds(30))
            repeat(5) { limiter.tryOnce("203.0.113.5") }
            assertEquals(1, appender.list.count { it.level == Level.WARN }, "still inside the minute")

            clock.advance(Duration.ofSeconds(31))
            repeat(5) { limiter.tryOnce("203.0.113.5") }
            assertEquals(2, appender.list.count { it.level == Level.WARN }, "a new minute, a new notice")

            appender.list.forEach {
                val text = it.formattedMessage
                assertFalse(text.contains("203.0.113"), "a log line must never contain a source address: $text")
                assertFalse(text.contains("2001:"), text)
            }
        } finally {
            logger.detachAppender(appender)
        }
    }
}
