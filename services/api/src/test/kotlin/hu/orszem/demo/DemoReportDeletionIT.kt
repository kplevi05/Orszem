package hu.orszem.demo

import hu.orszem.analytics.api.AnalyticsSummaryResponse
import hu.orszem.analytics.api.SettlementStatisticsResponse
import hu.orszem.analytics.api.TrainStatisticsResponse
import hu.orszem.servicecase.api.ReportListResponse
import hu.orszem.servicecase.api.ServiceReportDetailResponse
import hu.orszem.shared.error.ProblemResponse
import hu.orszem.support.AbstractDemoIntegrationTest
import hu.orszem.support.ServiceApiClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/**
 * Demo v1.1 §E/§G — authenticated, demo-gated hard deletion.
 *
 * The `local` profile used by the integration tests sets
 * `orszem.demo.deletion-enabled=true`, so the route exists here. The
 * "switch off" behaviour is covered by [DemoReportDeletionDisabledIT].
 */
class DemoReportDeletionIT @Autowired constructor(
    private val rest: TestRestTemplate,
    private val jdbc: JdbcTemplate,
) : AbstractDemoIntegrationTest() {

    private lateinit var api: ServiceApiClient

    @BeforeEach
    fun setUp() {
        api = ServiceApiClient(rest)
    }

    private fun activeItems() = api.get("/api/v1/service/reports", ReportListResponse::class.java).body!!.items
    private fun archiveItems() = api.get("/api/v1/service/archive", ReportListResponse::class.java).body!!.items
    private fun summary() = api.get("/api/v1/service/analytics/summary", AnalyticsSummaryResponse::class.java).body!!
    private fun rowCount(id: String) =
        jdbc.queryForObject("SELECT count(*) FROM reports WHERE id = ?::uuid", Int::class.java, id)!!

    private fun firstWithStatus(status: String): String =
        if (status == "ARCHIVED") archiveItems().first().id else activeItems().first { it.status == status }.id

    @Test
    fun `an authenticated demo user deletes a NEW report and the row is really gone`() {
        val id = firstWithStatus("NEW")
        assertThat(rowCount(id)).isEqualTo(1)

        val response = api.delete("/api/v1/service/reports/$id", Void::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(rowCount(id)).isZero()
        assertThat(activeItems().map { it.id }).doesNotContain(id)
        // A deleted report is gone, not hidden: fetching it now behaves like any unknown id.
        val detail = api.get("/api/v1/service/reports/$id", ProblemResponse::class.java)
        assertThat(detail.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `an IN_PROGRESS report can be deleted`() {
        val id = firstWithStatus("IN_PROGRESS")
        assertThat(api.delete("/api/v1/service/reports/$id", Void::class.java).statusCode)
            .isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(rowCount(id)).isZero()
    }

    @Test
    fun `an ARCHIVED report can be deleted`() {
        val id = firstWithStatus("ARCHIVED")
        assertThat(api.delete("/api/v1/service/reports/$id", Void::class.java).statusCode)
            .isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(rowCount(id)).isZero()
        assertThat(archiveItems().map { it.id }).doesNotContain(id)
    }

    @Test
    fun `an unauthenticated caller cannot delete - the Public App can never reach this`() {
        val id = firstWithStatus("NEW")

        val anonymous = rest.exchange(
            "/api/v1/service/reports/$id",
            org.springframework.http.HttpMethod.DELETE,
            null,
            ProblemResponse::class.java,
        )

        assertThat(anonymous.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(anonymous.body!!.code).isEqualTo("UNAUTHORIZED")
        // Nothing was removed.
        assertThat(rowCount(id)).isEqualTo(1)
    }

    @Test
    fun `a malformed bearer token cannot delete`() {
        val id = firstWithStatus("NEW")
        val headers = org.springframework.http.HttpHeaders().apply { setBearerAuth("not-a-real-token") }
        val response = rest.exchange(
            "/api/v1/service/reports/$id",
            org.springframework.http.HttpMethod.DELETE,
            org.springframework.http.HttpEntity<Void>(headers),
            ProblemResponse::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(rowCount(id)).isEqualTo(1)
    }

    @Test
    fun `deleting an unknown report returns 404`() {
        val response = api.delete("/api/v1/service/reports/${UUID.randomUUID()}", ProblemResponse::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body!!.code).isEqualTo("REPORT_NOT_FOUND")
    }

    @Test
    fun `deletion leaves no orphaned audit rows and records the deletion itself`() {
        val id = firstWithStatus("NEW")
        // Give the report a workflow audit trail first.
        api.post("/api/v1/service/reports/$id/accept", ServiceReportDetailResponse::class.java)
        val beforeTargeted = jdbc.queryForObject(
            "SELECT count(*) FROM audit_events WHERE target_type = 'REPORT' AND target_id = ?::uuid",
            Int::class.java, id,
        )!!
        assertThat(beforeTargeted).isGreaterThanOrEqualTo(1)

        api.delete("/api/v1/service/reports/$id", Void::class.java)

        // No audit row still points at a report that no longer exists.
        val orphaned = jdbc.queryForObject(
            """
            SELECT count(*) FROM audit_events a
            WHERE a.target_type = 'REPORT' AND a.target_id IS NOT NULL
              AND NOT EXISTS (SELECT 1 FROM reports r WHERE r.id = a.target_id)
            """.trimIndent(),
            Int::class.java,
        )!!
        assertThat(orphaned).isZero()

        // The deletion is still auditable, via metadata rather than a dangling id.
        val deletions = jdbc.queryForObject(
            "SELECT count(*) FROM audit_events WHERE action = 'REPORT_DELETED' AND metadata_json ->> 'reportId' = ?",
            Int::class.java, id,
        )!!
        assertThat(deletions).isEqualTo(1)
    }

    @Test
    fun `AT-G analytics follow the remaining rows after deletion, with no deleted bucket`() {
        val before = summary()
        assertThat(before.totalReports).isEqualTo(120)
        assertThat(before.activeReports).isEqualTo(14)

        val target = activeItems().first { it.status == "NEW" }
        val settlement = target.settlement
        val train = target.trainIdentifier
        val settlementBefore = api.get("/api/v1/service/analytics/settlements", SettlementStatisticsResponse::class.java)
            .body!!.items.single { it.settlement == settlement }.count
        val trainBefore = api.get("/api/v1/service/analytics/trains", TrainStatisticsResponse::class.java)
            .body!!.items.single { it.trainIdentifier == train }.count

        api.delete("/api/v1/service/reports/${target.id}", Void::class.java)

        val after = summary()
        assertThat(after.totalReports).isEqualTo(119)
        assertThat(after.activeReports).isEqualTo(13)
        assertThat(after.archivedReports).isEqualTo(106)

        val settlementAfter = api.get("/api/v1/service/analytics/settlements", SettlementStatisticsResponse::class.java)
            .body!!.items.singleOrNull { it.settlement == settlement }?.count ?: 0
        val trainAfter = api.get("/api/v1/service/analytics/trains", TrainStatisticsResponse::class.java)
            .body!!.items.singleOrNull { it.trainIdentifier == train }?.count ?: 0
        assertThat(settlementAfter).isEqualTo(settlementBefore - 1)
        assertThat(trainAfter).isEqualTo(trainBefore - 1)
    }

    @Test
    fun `demo reset restores the documented baseline after deletions`() {
        repeat(3) {
            val id = activeItems().first().id
            api.delete("/api/v1/service/reports/$id", Void::class.java)
        }
        assertThat(summary().totalReports).isEqualTo(117)

        resetDemoData()

        val restored = summary()
        assertThat(restored.totalReports).isEqualTo(120)
        assertThat(restored.todayReports).isEqualTo(16)
        assertThat(restored.activeReports).isEqualTo(14)
        assertThat(restored.archivedReports).isEqualTo(106)
    }
}
