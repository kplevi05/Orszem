package hu.orszem

import hu.orszem.analytics.api.AnalyticsSummaryResponse
import hu.orszem.analytics.api.EventTypeStatisticsResponse
import hu.orszem.analytics.api.SettlementStatisticsResponse
import hu.orszem.analytics.api.TrainStatisticsResponse
import hu.orszem.reporting.api.PublicReportCreateResponse
import hu.orszem.servicecase.api.ReportListResponse
import hu.orszem.servicecase.api.ServiceReportDetailResponse
import hu.orszem.support.AbstractDemoIntegrationTest
import hu.orszem.support.ServiceApiClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import java.time.Instant
import java.util.UUID

/**
 * AT-050 — the end-to-end presentation smoke test, executed from a clean demo reset.
 */
class PresentationFlowIT @Autowired constructor(
    private val rest: TestRestTemplate,
) : AbstractDemoIntegrationTest() {

    private fun api() = ServiceApiClient(rest)

    private fun kpi(api: ServiceApiClient) =
        api.get("/api/v1/service/analytics/summary", AnalyticsSummaryResponse::class.java).body!!

    private fun count(api: ServiceApiClient, path: String, key: String): Int = when (path) {
        "event-types" -> api.get("/api/v1/service/analytics/event-types", EventTypeStatisticsResponse::class.java)
            .body!!.items.single { it.eventTypeCode == key }.count
        "settlements" -> api.get("/api/v1/service/analytics/settlements", SettlementStatisticsResponse::class.java)
            .body!!.items.single { it.settlement == key }.count
        "trains" -> api.get("/api/v1/service/analytics/trains", TrainStatisticsResponse::class.java)
            .body!!.items.single { it.trainIdentifier == key }.count
        else -> error(path)
    }

    @Test
    fun `AT-050 full five minute flow`() {
        val api = api()

        // 1-2. baseline after reset
        kpi(api).let {
            assertThat(it.totalReports).isEqualTo(120)
            assertThat(it.todayReports).isEqualTo(16)
            assertThat(it.activeReports).isEqualTo(14)
            assertThat(it.archivedReports).isEqualTo(106)
        }

        // 3-4. anonymous public report: Késelés / IC 123 / Budapest / now
        val reportId = UUID.randomUUID()
        val created = rest.postForEntity(
            "/api/v1/public/reports",
            mapOf(
                "id" to reportId,
                "eventTypeCode" to "KNIFE_ATTACK",
                "trainIdentifier" to "IC 123",
                "settlement" to "Budapest",
                "occurredAt" to Instant.now().toString(),
            ),
            PublicReportCreateResponse::class.java,
        )
        assertThat(created.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(created.body!!.status).isEqualTo("NEW")

        // analytics reflect the new report
        kpi(api).let {
            assertThat(it.totalReports).isEqualTo(121)
            assertThat(it.todayReports).isEqualTo(17)
            assertThat(it.activeReports).isEqualTo(15)
            assertThat(it.archivedReports).isEqualTo(106)
        }
        assertThat(count(api, "event-types", "KNIFE_ATTACK")).isEqualTo(4)
        assertThat(count(api, "settlements", "Budapest")).isEqualTo(29)
        assertThat(count(api, "trains", "IC 123")).isEqualTo(21)

        // 5-6. service sees the new report and opens the detail
        val listItem = api.get("/api/v1/service/reports", ReportListResponse::class.java)
            .body!!.items.single { it.id == reportId.toString() }
        assertThat(listItem.status).isEqualTo("NEW")
        val detail = api.get("/api/v1/service/reports/$reportId", ServiceReportDetailResponse::class.java).body!!
        assertThat(detail.eventType.code).isEqualTo("KNIFE_ATTACK")

        // 7. accept — total/today unchanged, active still 15
        val accepted = api.post("/api/v1/service/reports/$reportId/accept", ServiceReportDetailResponse::class.java)
        assertThat(accepted.body!!.status).isEqualTo("IN_PROGRESS")
        kpi(api).let {
            assertThat(it.totalReports).isEqualTo(121)
            assertThat(it.todayReports).isEqualTo(17)
            assertThat(it.activeReports).isEqualTo(15)
        }

        // 8-9. archive — active drops to 14, archived rises to 107
        val archived = api.post("/api/v1/service/reports/$reportId/archive", ServiceReportDetailResponse::class.java)
        assertThat(archived.body!!.status).isEqualTo("ARCHIVED")
        kpi(api).let {
            assertThat(it.totalReports).isEqualTo(121)
            assertThat(it.todayReports).isEqualTo(17)
            assertThat(it.activeReports).isEqualTo(14)
            assertThat(it.archivedReports).isEqualTo(107)
        }
        val inArchive = api.get("/api/v1/service/archive", ReportListResponse::class.java)
            .body!!.items.any { it.id == reportId.toString() }
        assertThat(inArchive).isTrue()
    }
}
