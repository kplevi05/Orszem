package hu.orszembejelento.backend.reports

import hu.orszembejelento.backend.reports.support.PublicReportTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource

/**
 * Decision B6 end to end: the anonymous Public report-creation limiter, through the real HTTP
 * stack against PostgreSQL, at the **approved production values** (burst 10, one token per 20 s).
 *
 * Time comes from the suite's controllable clock, so real refill is proven by moving it rather
 * than sleeping. Every test uses its own source address (sent as `X-Forwarded-For` from the
 * loopback peer, which is the trusted proxy in this suite - exactly Caddy's position in
 * production), so tests cannot poison each other's buckets.
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
@TestPropertySource(
    properties = [
        "orszem.public-submission-rate-limit.enabled=true",
        "orszem.public-submission-rate-limit.burst=10",
        "orszem.public-submission-rate-limit.refill-period=20s",
        "orszem.public-submission-rate-limit.maximum-tracked-keys=100000",
    ],
)
class PublicSubmissionRateLimitIT : PublicReportTestSupport() {

    /**
     * A benchmarking-range IPv4 address (RFC 2544) unique across the whole JVM. The counter is
     * static because JUnit creates a new test instance per test; an instance field would restart
     * at 1 every time and let tests share, and poison, one another's buckets.
     */
    private fun newSource(): String {
        val n = nextSource.getAndIncrement()
        return "198.18.${n / 250}.${n % 250 + 1}"
    }

    private fun reportCount(): Int = jdbc.sql("SELECT COUNT(*) FROM reports").query(Int::class.java).single()

    private fun submitFrom(
        body: String,
        credential: String? = randomCredential(),
        forwardedFor: String? = null,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/v1/public/reports"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        credential?.let { builder.header(REPORT_ACCESS_HEADER, it) }
        forwardedFor?.let { builder.header("X-Forwarded-For", it) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun newReport(settlement: UUID, source: String, eventType: String = "FIGHT") =
        submitFrom(submitReportBody(settlementId = settlement, eventTypeCode = eventType), forwardedFor = source)

    private fun fixture(): UUID {
        setCurrentReferenceState()
        return insertSettlement("00001")
    }

    // ------------------------------------------------------------- the limit and its shape

    @Test
    fun `ten reports from one source are accepted and the eleventh is a 429 that created nothing`() {
        val settlement = fixture()
        val source = newSource()

        repeat(10) { assertEquals(201, newReport(settlement, source).statusCode(), "report ${it + 1}") }
        val throttled = newReport(settlement, source)

        assertEquals(429, throttled.statusCode())
        assertEquals("RATE_LIMITED", errorCode(throttled))
        assertEquals(10, reportCount(), "the throttled request must not have created a report")
    }

    @Test
    fun `a 429 carries a valid Retry-After, the stable safe body and no-store`() {
        val settlement = fixture()
        val source = newSource()
        repeat(10) { newReport(settlement, source) }

        val throttled = newReport(settlement, source)

        assertEquals(429, throttled.statusCode())
        val retryAfter = throttled.headers().firstValue("Retry-After").orElse("")
        assertTrue(retryAfter.matches(Regex("""\d+""")), "Retry-After must be whole seconds, got '$retryAfter'")
        assertEquals(20L, retryAfter.toLong(), "an empty bucket needs one full 20 s token period")
        assertEquals("no-store", throttled.headers().firstValue("Cache-Control").orElse(""))
        assertEquals("application/json", throttled.headers().firstValue("Content-Type").orElse("").substringBefore(';'))
        assertEquals(setOf("code", "message", "correlationId"), json(throttled).propertyNames().toSet())

        val text = throttled.body()
        assertFalse(text.contains(source), "the body must not echo the caller's address")
        assertFalse(text.contains("Exception"), text)
        assertFalse(text.contains("pr_"), "nor any credential")
    }

    @Test
    fun `real refill - after twenty seconds the same source may create one more, and only one`() {
        val settlement = fixture()
        val source = newSource()
        repeat(10) { newReport(settlement, source) }
        assertEquals(429, newReport(settlement, source).statusCode())

        mutableClock.advance(Duration.ofSeconds(19))
        val early = newReport(settlement, source)
        assertEquals(429, early.statusCode())
        assertEquals(1L, early.headers().firstValue("Retry-After").get().toLong(), "one second left")

        mutableClock.advance(Duration.ofSeconds(1))
        assertEquals(201, newReport(settlement, source).statusCode(), "the token has arrived")
        assertEquals(429, newReport(settlement, source).statusCode(), "and that was the only one")
        assertEquals(11, reportCount())
    }

    @Test
    fun `a throttled request leaves no trace, so retrying the SAME clientSubmissionId later creates exactly one report`() {
        val settlement = fixture()
        val source = newSource()
        repeat(10) { newReport(settlement, source) }

        val id = UUID.randomUUID()
        val credential = randomCredential()
        val body = submitReportBody(clientSubmissionId = id, settlementId = settlement)

        assertEquals(429, submitFrom(body, credential, source).statusCode())
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM reports WHERE client_submission_id = :id").param("id", id).query(Int::class.java).single())

        mutableClock.advance(Duration.ofSeconds(20))
        val retried = submitFrom(body, credential, source)
        assertEquals(201, retried.statusCode(), "the manual retry with the same identity and credential is accepted")
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM reports WHERE client_submission_id = :id").param("id", id).query(Int::class.java).single())
    }

    @Test
    fun `sources are independent of one another`() {
        val settlement = fixture()
        val a = newSource()
        val b = newSource()
        repeat(10) { newReport(settlement, a) }

        assertEquals(429, newReport(settlement, a).statusCode())
        assertEquals(201, newReport(settlement, b).statusCode(), "another source is unaffected")
    }

    // ---------------------------------------------------------------- replay is never throttled

    @Test
    fun `an accepted submission can always be replayed, even by a source that is completely exhausted`() {
        val settlement = fixture()
        val source = newSource()
        val id = UUID.randomUUID()
        val credential = randomCredential()
        val body = submitReportBody(clientSubmissionId = id, settlementId = settlement)

        val created = submitFrom(body, credential, source)
        assertEquals(201, created.statusCode())
        val reportId = json(created).get("reportId").asText()

        repeat(9) { newReport(settlement, source) }
        assertEquals(429, newReport(settlement, source).statusCode(), "the source is now exhausted")

        val replay = submitFrom(body, credential, source)
        assertEquals(200, replay.statusCode(), "the identical replay is answered, not throttled")
        assertEquals(reportId, json(replay).get("reportId").asText(), "with the same report")
        assertEquals(10, reportCount())
    }

    @Test
    fun `a mismatching reuse of an accepted id is a 409 for an exhausted source, never a 429`() {
        val settlement = fixture()
        val source = newSource()
        val id = UUID.randomUUID()
        val credential = randomCredential()
        assertEquals(201, submitFrom(submitReportBody(clientSubmissionId = id, settlementId = settlement), credential, source).statusCode())
        repeat(9) { newReport(settlement, source) }
        assertEquals(429, newReport(settlement, source).statusCode())

        val differentPayload = submitFrom(
            submitReportBody(clientSubmissionId = id, settlementId = settlement, eventTypeCode = "THEFT"), credential, source,
        )
        assertEquals(409, differentPayload.statusCode())
        assertEquals("IDEMPOTENCY_KEY_REUSED", errorCode(differentPayload))

        val differentCredential = submitFrom(submitReportBody(clientSubmissionId = id, settlementId = settlement), randomCredential(), source)
        assertEquals(409, differentCredential.statusCode())
    }

    @Test
    fun `replays do not spend tokens - a hundred replays leave the full burst available`() {
        val settlement = fixture()
        val source = newSource()
        val id = UUID.randomUUID()
        val credential = randomCredential()
        val body = submitReportBody(clientSubmissionId = id, settlementId = settlement)
        assertEquals(201, submitFrom(body, credential, source).statusCode())

        repeat(100) { assertEquals(200, submitFrom(body, credential, source).statusCode()) }

        // One token went on the original; nine remain regardless of the hundred replays.
        repeat(9) { assertEquals(201, newReport(settlement, source).statusCode(), "creation ${it + 2}") }
        assertEquals(429, newReport(settlement, source).statusCode())
    }

    @Test
    fun `looking a report up is never throttled, and a throttled source can still read its own report`() {
        val settlement = fixture()
        val source = newSource()
        val credential = randomCredential()
        val created = submitFrom(submitReportBody(settlementId = settlement), credential, source)
        val reportId = UUID.fromString(json(created).get("reportId").asText())
        repeat(9) { newReport(settlement, source) }
        assertEquals(429, newReport(settlement, source).statusCode())

        assertEquals(200, getPublicReport(reportId, credential).statusCode())
    }

    // ------------------------------------------------------------- spoofed forwarding headers

    @Test
    fun `prepending forged addresses cannot earn a fresh bucket - only the proxy-written last entry counts`() {
        val settlement = fixture()
        val real = newSource()

        repeat(10) { i ->
            val forged = "192.0.2.$i, 10.$i.0.1, $real" // what an attacker sends; the proxy appends the real peer
            assertEquals(201, newReport(settlement, forged).statusCode(), "report ${i + 1}")
        }
        assertEquals(429, newReport(settlement, "192.0.2.200, 10.99.0.1, $real").statusCode())
        assertEquals(10, reportCount())
    }

    @Test
    fun `an attacker cannot hide behind garbage forwarded values - they all collapse to the peer's own bucket`() {
        val settlement = fixture()
        // Loopback is the peer here, and it is shared by nothing else in this class's tests,
        // because every other test names an explicit source. Garbage must not open new buckets.
        repeat(10) { i -> assertEquals(201, newReport(settlement, "not-an-ip-$i").statusCode()) }
        assertEquals(429, newReport(settlement, "still-garbage").statusCode())
    }

    @Test
    fun `a forged address cannot be used to throttle somebody else`() {
        val settlement = fixture()
        val victim = newSource()
        val attacker = newSource()

        // The attacker exhausts THEIR bucket while claiming, in the leftmost position, to be the victim.
        repeat(10) { newReport(settlement, "$victim, $attacker") }
        assertEquals(429, newReport(settlement, "$victim, $attacker").statusCode())

        assertEquals(201, newReport(settlement, victim).statusCode(), "the named victim is untouched")
    }

    // ------------------------------------------------------------------------------ IPv4 / IPv6

    @Test
    fun `IPv6 sources are limited per slash-64, so rotating the low 64 bits gains nothing`() {
        val settlement = fixture()
        val prefix = "2001:db8:${nextSource.getAndIncrement()}:${nextSource.getAndIncrement()}"

        (1..10).forEach { i -> assertEquals(201, newReport(settlement, "$prefix::$i").statusCode(), "host $i") }
        assertEquals(429, newReport(settlement, "$prefix:aaaa:bbbb:cccc:dddd").statusCode(), "another host, same /64")
        assertEquals(429, newReport(settlement, "$prefix::ffff").statusCode())

        val otherPrefix = "2001:db8:${nextSource.getAndIncrement()}:${nextSource.getAndIncrement()}"
        assertEquals(201, newReport(settlement, "$otherPrefix::1").statusCode(), "a different /64 is a different source")
    }

    @Test
    fun `an IPv4-mapped IPv6 spelling shares the IPv4 bucket`() {
        val settlement = fixture()
        val v4 = newSource()
        repeat(10) { newReport(settlement, v4) }

        assertEquals(429, newReport(settlement, "::ffff:$v4").statusCode())
    }

    // ------------------------------------------------------------------- races on PostgreSQL

    @Test
    fun `forty parallel new reports from one source create exactly ten`() {
        val settlement = fixture()
        val source = newSource()
        val statuses = runParallel(40) { newReport(settlement, source).statusCode() }

        assertEquals(10, statuses.count { it == 201 }, "statuses: $statuses")
        assertEquals(30, statuses.count { it == 429 }, "statuses: $statuses")
        assertEquals(10, reportCount(), "the database agrees: never more than the burst")
    }

    @Test
    fun `parallel replays of an accepted report are never throttled, whatever the source's state`() {
        val settlement = fixture()
        val source = newSource()
        val id = UUID.randomUUID()
        val credential = randomCredential()
        val body = submitReportBody(clientSubmissionId = id, settlementId = settlement)
        assertEquals(201, submitFrom(body, credential, source).statusCode())
        repeat(9) { newReport(settlement, source) }
        assertEquals(429, newReport(settlement, source).statusCode())

        val statuses = runParallel(40) { submitFrom(body, credential, source).statusCode() }

        assertTrue(statuses.all { it == 200 }, "every replay must be answered: $statuses")
    }

    @Test
    fun `identical concurrent submissions converge on one report and net exactly one token`() {
        val settlement = fixture()
        val source = newSource()
        val id = UUID.randomUUID()
        val credential = randomCredential()
        val body = submitReportBody(clientSubmissionId = id, settlementId = settlement)

        val statuses = runParallel(8) { submitFrom(body, credential, source).statusCode() }
        assertEquals(1, statuses.count { it == 201 }, "exactly one creation: $statuses")
        assertTrue(statuses.all { it == 201 || it == 200 }, "the other seven are replays, never throttled: $statuses")
        assertEquals(1, reportCount())

        // Losers of the insert race took a token and must have handed it back: only ONE was
        // really spent, so nine more creations fit and the tenth-after is throttled.
        repeat(9) { assertEquals(201, newReport(settlement, source).statusCode(), "creation ${it + 2}") }
        assertEquals(429, newReport(settlement, source).statusCode())
        assertEquals(10, reportCount())
    }

    @Test
    fun `a throttled source does not slow or block a well-behaved one running at the same time`() {
        val settlement = fixture()
        val noisy = newSource()
        val quiet = newSource()
        repeat(10) { newReport(settlement, noisy) }

        // 28 parallel requests: 7 from the quiet source (within its burst of 10), 21 from the exhausted one.
        val results = runParallel(28) { i ->
            if (i % 4 == 0) newReport(settlement, quiet).statusCode() else newReport(settlement, noisy).statusCode()
        }
        val quietResults = results.filterIndexed { i, _ -> i % 4 == 0 }
        val noisyResults = results.filterIndexed { i, _ -> i % 4 != 0 }
        assertTrue(noisyResults.all { it == 429 }, "$noisyResults")
        assertTrue(quietResults.all { it == 201 }, "$quietResults")
    }

    // ------------------------------------------------------------------ contract and neighbours

    @Test
    fun `the OpenAPI contract documents the 429 on report creation`() {
        val spec = http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/v3/api-docs")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val responses = objectMapper.readTree(spec.body()).path("paths").path("/api/v1/public/reports").path("post").path("responses")
        assertNotNull(responses.get("429"), "POST /public/reports must document 429")
        assertTrue(responses.get("429").path("description").asText().contains("Retry-After"))
    }

    @Test
    fun `other Public endpoints are not affected by an exhausted creation bucket`() {
        val settlement = fixture()
        val source = newSource()
        repeat(10) { newReport(settlement, source) }
        assertEquals(429, newReport(settlement, source).statusCode())

        val catalog = http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/v1/public/report-catalog")).header("X-Forwarded-For", source).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, catalog.statusCode())
    }

    // --------------------------------------------------------------------------------- helpers

    private companion object {
        val nextSource = AtomicInteger(1)
    }

    /** Runs [count] tasks released at the same instant and returns their results in task order. */
    private fun <T> runParallel(count: Int, task: (Int) -> T): List<T> {
        val pool = Executors.newFixedThreadPool(count)
        val ready = CountDownLatch(count)
        val go = CountDownLatch(1)
        try {
            val futures = (0 until count).map { i ->
                pool.submit<T> {
                    ready.countDown()
                    go.await(10, TimeUnit.SECONDS)
                    task(i)
                }
            }
            ready.await(10, TimeUnit.SECONDS)
            go.countDown()
            return futures.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }
}
