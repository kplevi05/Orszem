package hu.orszembejelento.backend.analytics

import hu.orszembejelento.backend.analytics.support.AnalyticsTestSupport
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The consistent-snapshot proof brief §13/§58 asks for: [AnalyticsQueryUseCase.summary][hu.orszembejelento.backend.analytics.application.AnalyticsQueryUseCase.summary]
 * runs its four aggregate queries inside one short read-only REPEATABLE READ transaction
 * specifically so a concurrent workflow mutation can never be reflected in some of them and
 * not others. This does not assert which side of any one race "wins" (brief §58) — it
 * repeatedly reads the summary while a second thread continuously mutates report workflow
 * state in the background, and asserts the three internal-coherence invariants hold on
 * *every single response*: status counts sum to total, the trend sums to total, and
 * `totalReports` itself never drifts from the five fixture reports regardless of how their
 * individual workflow state is churning underneath.
 */
class AnalyticsConsistencyIT : AnalyticsTestSupport() {

    private val pool = Executors.newSingleThreadExecutor()

    @AfterEach
    fun shutdownPool() {
        pool.shutdownNow()
    }

    @Test
    fun `concurrent workflow mutations never produce an internally inconsistent summary snapshot`() {
        val area = givenRoutedArea()
        val reports = (1..5).map { givenRoutedReport(area) }
        val worker = givenServiceUser()
        grantArea(worker.id, area.areaId)
        val workerBearer = bearerFor(worker)
        val readerBearer = bearerFor(givenSuperAdmin())

        val stop = AtomicBoolean(false)
        val mutatorFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val mutator = pool.submit {
            try {
                var i = 0
                while (!stop.get()) {
                    val report = reports[i % reports.size]
                    val current = reportRow(report.publicId)
                    when (current.status) {
                        "NEW" -> claim(workerBearer, report.publicId, current.workflowVersion)
                        "IN_PROGRESS" -> returnToNew(workerBearer, report.publicId, current.workflowVersion)
                    }
                    i++
                }
            } catch (t: Throwable) {
                mutatorFailure.set(t)
            }
        }

        try {
            repeat(40) {
                val body = json(summary(readerBearer, areaId = area.areaId))
                val total = body.get("totalReports").asInt()
                val statusSum = body.get("statusCounts").let { it.get("new").asInt() + it.get("inProgress").asInt() + it.get("archived").asInt() }
                val trendSum = body.get("trend").asList().sumOf { it.get("count").asInt() }

                assertEquals(5, total, "workflow-state churn must never change totalReports")
                assertEquals(total, statusSum, "status counts must sum to total even mid-race")
                assertEquals(total, trendSum, "trend buckets must sum to total even mid-race")
            }
        } finally {
            stop.set(true)
            mutator.get(10, TimeUnit.SECONDS)
        }
        assertEquals(null, mutatorFailure.get(), "the background mutator thread must not throw")
    }
}
