package hu.orszembejelento.backend.reports

import hu.orszembejelento.backend.common.ReferenceStateLock
import hu.orszembejelento.backend.reports.support.PublicReportTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.sql.DataSource
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import

/**
 * The three concurrency guarantees §45 requires, proved against real PostgreSQL - never a
 * JVM `synchronized`, an in-memory mutex, or Redis (ADR 0008 Decision 2 and 3).
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class PublicReportConcurrencyIT : PublicReportTestSupport() {

    @Autowired
    private lateinit var dataSource: DataSource

    private fun reportCount(): Int = jdbc.sql("SELECT COUNT(*) FROM reports").query(Int::class.java).single()

    // ---------------------------------------------------------- identical-submission race

    @Test
    fun `N concurrent identical submissions converge on exactly one report`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        val credential = randomCredential()
        val clientSubmissionId = UUID.randomUUID()
        val body = submitReportBody(clientSubmissionId = clientSubmissionId, settlementId = settlement)

        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        try {
            val futures = (1..threads).map {
                pool.submit<HttpResponse<String>> {
                    ready.countDown()
                    go.await(10, TimeUnit.SECONDS)
                    submitReport(body, credential)
                }
            }
            check(ready.await(10, TimeUnit.SECONDS))
            go.countDown()
            val responses = futures.map { it.get(30, TimeUnit.SECONDS) }

            val statuses = responses.map { it.statusCode() }
            check(statuses.all { it == 200 || it == 201 }) { "unexpected statuses: $statuses" }
            check(statuses.count { it == 201 } == 1) { "exactly one attempt must create the report, got: $statuses" }
            val publicIds = responses.map { json(it).get("reportId").asText() }.toSet()
            check(publicIds.size == 1) { "every response must name the same report" }
        } finally {
            pool.shutdownNow()
        }

        check(reportCount() == 1) { "the database must hold exactly one row" }
    }

    // --------------------------------------------------------- conflicting-payload race

    @Test
    fun `N concurrent submissions sharing an id but disagreeing on payload leave exactly one winner`() {
        setCurrentReferenceState()
        val settlements = (1..8).map { insertSettlement("%05d".format(it), "S$it") }
        val credential = randomCredential()
        val clientSubmissionId = UUID.randomUUID()

        val threads = settlements.size
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        try {
            val futures = settlements.map { settlementId ->
                pool.submit<HttpResponse<String>> {
                    ready.countDown()
                    go.await(10, TimeUnit.SECONDS)
                    submitReport(
                        submitReportBody(clientSubmissionId = clientSubmissionId, settlementId = settlementId),
                        credential,
                    )
                }
            }
            check(ready.await(10, TimeUnit.SECONDS))
            go.countDown()
            val responses = futures.map { it.get(30, TimeUnit.SECONDS) }

            val statuses = responses.map { it.statusCode() }
            check(statuses.count { it == 201 } == 1) { "exactly one payload may win, got: $statuses" }
            check(statuses.count { it == 409 } == threads - 1) { "every loser must see 409, got: $statuses" }
            responses.filter { it.statusCode() == 409 }.forEach { check(errorCode(it) == "IDEMPOTENCY_KEY_REUSED") }
        } finally {
            pool.shutdownNow()
        }

        check(reportCount() == 1) { "the database must hold exactly one row" }
    }

    // ------------------------------------------------------- reference-import-vs-submission race

    @Test
    fun `a submission blocks until an in-flight reference import finishes, then sees its finished state`() {
        // Deliberately establishes a reference state that would resolve differently before
        // and after the simulated import, so a submission that read a half-applied or
        // pre-import state would be provably wrong, not just accidentally consistent.
        setCurrentReferenceState(version = "before-import", relationsCoverage = "COMPLETE")
        val settlement = insertSettlement("00001")
        // No relations yet: routed against "before-import" alone this would resolve to
        // NO_VERIFIED_RAILWAY_LINE_REFERENCE.

        val exclusiveLockHeld = CountDownLatch(1)
        val proceedWithImport = CountDownLatch(1)
        val importDone = CountDownLatch(1)
        var newLineId: UUID? = null

        val importThread = Thread {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                connection.prepareStatement("SELECT pg_advisory_xact_lock(?)").use { statement ->
                    statement.setLong(1, ReferenceStateLock.KEY)
                    statement.execute()
                }
                // Holds the EXCLUSIVE lock from here on, exactly like a real reference
                // import's transaction (JdbcReferenceRepository.acquireImportLock) - a
                // concurrent submission's SHARED lock request must now block.
                exclusiveLockHeld.countDown()
                proceedWithImport.await(10, TimeUnit.SECONDS)

                val lineId = UUID.randomUUID()
                val areaId = UUID.randomUUID()
                connection.prepareStatement(
                    "INSERT INTO railway_lines (id, line_code, display_name, active, created_at, updated_at) " +
                        "VALUES (?, '1', 'Line', TRUE, now(), now())",
                ).use { it.setObject(1, lineId); it.execute() }
                connection.prepareStatement(
                    "INSERT INTO service_areas (id, name, status, created_at, updated_at) " +
                        "VALUES (?, 'Import Area', 'ACTIVE', now(), now())",
                ).use { it.setObject(1, areaId); it.execute() }
                connection.prepareStatement("INSERT INTO service_area_railway_lines VALUES (?, ?)").use {
                    it.setObject(1, areaId)
                    it.setObject(2, lineId)
                    it.execute()
                }
                connection.prepareStatement("INSERT INTO settlement_railway_lines VALUES (?, ?)").use {
                    it.setObject(1, settlement)
                    it.setObject(2, lineId)
                    it.execute()
                }
                connection.prepareStatement("UPDATE reference_dataset_imports SET is_current = FALSE WHERE is_current")
                    .execute()
                connection.prepareStatement(
                    "INSERT INTO reference_dataset_imports (id, dataset_version, manifest_sha256, imported_at, " +
                        "settlement_count, railway_line_count, mapping_count, settlements_coverage, " +
                        "railway_lines_coverage, settlement_railway_lines_coverage, is_current) " +
                        "VALUES (gen_random_uuid(), 'after-import', ?, now(), 0, 0, 0, 'COMPLETE', 'COMPLETE', 'COMPLETE', TRUE)",
                ).use {
                    it.setBytes(1, ByteArray(32))
                    it.execute()
                }

                newLineId = lineId
                connection.commit() // releases the exclusive lock
            }
            importDone.countDown()
        }
        importThread.isDaemon = true
        importThread.start()

        check(exclusiveLockHeld.await(10, TimeUnit.SECONDS)) { "the simulated import never acquired its lock" }

        val submissionPool = Executors.newSingleThreadExecutor()
        try {
            val submissionFuture = submissionPool.submit<HttpResponse<String>> {
                submitReport(submitReportBody(settlementId = settlement))
            }

            // Genuinely blocked, not merely slow: it must not complete before the import does.
            check(!completesWithin(submissionFuture, 800)) {
                "the submission completed while the import still held the exclusive lock - " +
                    "the shared/exclusive pair is not actually serialising them"
            }

            proceedWithImport.countDown()
            check(importDone.await(10, TimeUnit.SECONDS)) { "the simulated import never finished" }

            val response = submissionFuture.get(10, TimeUnit.SECONDS)
            check(response.statusCode() == 201) { "the submission must succeed once the import releases the lock: ${response.body()}" }

            val publicId = UUID.fromString(json(response).get("reportId").asText())
            val internalId = jdbc.sql("SELECT id FROM reports WHERE public_id = :id")
                .param("id", publicId).query(UUID::class.java).single()
            val snapshot = jdbc.sql(
                "SELECT routing_status, routing_reason, resolved_railway_line_id " +
                    "FROM report_routing_snapshots WHERE report_id = :id",
            ).param("id", internalId).query { rs, _ ->
                Triple(
                    rs.getString("routing_status"),
                    rs.getString("routing_reason"),
                    rs.getObject("resolved_railway_line_id", UUID::class.java),
                )
            }.single()

            check(snapshot.first == "ROUTED") {
                "the submission must see the import's FINISHED state (ROUTED via the new line), " +
                    "not the half-applied or pre-import state - got ${snapshot.first}/${snapshot.second}"
            }
            check(snapshot.third == newLineId) { "expected the newly-imported line to have resolved the report" }
        } finally {
            submissionPool.shutdownNow()
        }
    }

    /** Waits up to [timeoutMillis] for [future] to complete; returns whether it did. */
    private fun completesWithin(future: Future<*>, timeoutMillis: Long): Boolean =
        try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS)
            true
        } catch (_: TimeoutException) {
            false
        }
}
