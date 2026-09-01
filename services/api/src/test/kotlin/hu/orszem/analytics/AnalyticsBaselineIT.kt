package hu.orszem.analytics

import hu.orszem.analytics.api.AnalyticsSummaryResponse
import hu.orszem.analytics.api.CategoryStatisticsResponse
import hu.orszem.analytics.api.EventTypeStatisticsResponse
import hu.orszem.analytics.api.SettlementStatisticsResponse
import hu.orszem.analytics.api.TrainStatisticsResponse
import hu.orszem.support.AbstractDemoIntegrationTest
import hu.orszem.support.ServiceApiClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate

class AnalyticsBaselineIT @Autowired constructor(
    private val rest: TestRestTemplate,
) : AbstractDemoIntegrationTest() {

    private lateinit var api: ServiceApiClient

    @BeforeEach
    fun setUp() {
        api = ServiceApiClient(rest)
    }

    @Test
    fun `AT-030 baseline KPI values`() {
        val s = api.get("/api/v1/service/analytics/summary", AnalyticsSummaryResponse::class.java).body!!
        assertThat(s.totalReports).isEqualTo(120)
        assertThat(s.todayReports).isEqualTo(16)
        assertThat(s.activeReports).isEqualTo(14)
        assertThat(s.archivedReports).isEqualTo(106)
        assertThat(s.generatedAt).isNotNull()
    }

    @Test
    fun `AT-031 baseline top-list anchor values`() {
        val eventTypes = api.get("/api/v1/service/analytics/event-types", EventTypeStatisticsResponse::class.java).body!!
        assertThat(eventTypes.items.single { it.eventTypeCode == "LOUD_BEHAVIOR" }.count).isEqualTo(18)

        val settlements = api.get("/api/v1/service/analytics/settlements", SettlementStatisticsResponse::class.java).body!!
        assertThat(settlements.items.single { it.settlement == "Budapest" }.count).isEqualTo(28)

        val trains = api.get("/api/v1/service/analytics/trains", TrainStatisticsResponse::class.java).body!!
        assertThat(trains.items.single { it.trainIdentifier == "IC 123" }.count).isEqualTo(20)
    }

    @Test
    fun `event type stats are ordered by count desc then label, and percentages are consistent`() {
        val items = api.get("/api/v1/service/analytics/event-types", EventTypeStatisticsResponse::class.java).body!!.items
        assertThat(items).isSortedAccordingTo(
            compareByDescending<hu.orszem.analytics.api.EventTypeStatResponse> { it.count }.thenBy { it.label },
        )
        assertThat(items.sumOf { it.count }).isEqualTo(120)
        items.forEach { assertThat(it.percentage).isBetween(0.0, 100.0) }
    }

    @Test
    fun `category stats cover the seven categories and sum to the total`() {
        val items = api.get("/api/v1/service/analytics/categories", CategoryStatisticsResponse::class.java).body!!.items
        assertThat(items).hasSize(7)
        assertThat(items.sumOf { it.count }).isEqualTo(120)
        assertThat(items).isSortedAccordingTo(
            compareByDescending<hu.orszem.analytics.api.CategoryStatResponse> { it.count }.thenBy { it.categoryLabel },
        )
    }
}
