package hu.orszem.servicecase

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

class ServiceReportWorkflowIT @Autowired constructor(
    private val rest: TestRestTemplate,
    private val jdbc: JdbcTemplate,
) : AbstractDemoIntegrationTest() {

    private lateinit var api: ServiceApiClient

    @BeforeEach
    fun setUp() {
        api = ServiceApiClient(rest)
    }

    private fun activeList() = api.get("/api/v1/service/reports", ReportListResponse::class.java).body!!
    private fun archiveList() = api.get("/api/v1/service/archive", ReportListResponse::class.java).body!!

    @Test
    fun `AT-020 active list contains only NEW and IN_PROGRESS`() {
        val statuses = activeList().items.map { it.status }.toSet()
        assertThat(statuses).isSubsetOf("NEW", "IN_PROGRESS")
        assertThat(statuses).doesNotContain("ARCHIVED")
        assertThat(activeList().items).hasSize(14)
    }

    @Test
    fun `AT-021 NEW reports precede IN_PROGRESS, newest receivedAt first within a group`() {
        val items = activeList().items
        val firstInProgress = items.indexOfFirst { it.status == "IN_PROGRESS" }
        val lastNew = items.indexOfLast { it.status == "NEW" }
        assertThat(lastNew).isLessThan(firstInProgress)

        val news = items.filter { it.status == "NEW" }.map { it.receivedAt }
        assertThat(news).isSortedAccordingTo(reverseOrder())
    }

    @Test
    fun `AT-022 detail exposes every required field`() {
        val target = activeList().items.first { it.status == "NEW" }
        val detail = api.get("/api/v1/service/reports/${target.id}", ServiceReportDetailResponse::class.java).body!!
        assertThat(detail.eventType.code).isNotBlank()
        assertThat(detail.eventType.categoryLabel).isNotBlank()
        assertThat(detail.trainIdentifier).isNotBlank()
        assertThat(detail.settlement).isNotBlank()
        assertThat(detail.occurredAt).isNotNull()
        assertThat(detail.receivedAt).isNotNull()
        assertThat(detail.status).isEqualTo("NEW")
        assertThat(detail.acceptedBy).isNull()
    }

    @Test
    fun `AT-023 accepting a NEW report moves it to IN_PROGRESS with actor and timestamp`() {
        val target = activeList().items.first { it.status == "NEW" }
        val accepted = api.post("/api/v1/service/reports/${target.id}/accept", ServiceReportDetailResponse::class.java)

        assertThat(accepted.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(accepted.body!!.status).isEqualTo("IN_PROGRESS")
        assertThat(accepted.body!!.acceptedAt).isNotNull()
        assertThat(accepted.body!!.acceptedBy!!.displayName).isEqualTo("Demo Szolgálat")

        val audits = jdbc.queryForObject(
            "SELECT count(*) FROM audit_events WHERE action = 'REPORT_ACCEPTED' AND target_id = ?::uuid",
            Int::class.java, target.id,
        )
        assertThat(audits).isEqualTo(1)
    }

    @Test
    fun `AT-024 accepting an already IN_PROGRESS report returns 409 REPORT_NOT_ACCEPTABLE`() {
        val target = activeList().items.first { it.status == "IN_PROGRESS" }
        val response = api.post("/api/v1/service/reports/${target.id}/accept", ProblemResponse::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response.body!!.code).isEqualTo("REPORT_NOT_ACCEPTABLE")
    }

    @Test
    fun `AT-026 archiving an IN_PROGRESS report moves it to ARCHIVED and drops it from the active list`() {
        val target = activeList().items.first { it.status == "IN_PROGRESS" }
        val archived = api.post("/api/v1/service/reports/${target.id}/archive", ServiceReportDetailResponse::class.java)

        assertThat(archived.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(archived.body!!.status).isEqualTo("ARCHIVED")
        assertThat(archived.body!!.archivedBy!!.displayName).isEqualTo("Demo Szolgálat")
        assertThat(activeList().items.map { it.id }).doesNotContain(target.id)
        assertThat(archiveList().items.map { it.id }).contains(target.id)
    }

    @Test
    fun `AT-027 archiving a NEW report is rejected with 409 REPORT_NOT_ARCHIVABLE`() {
        val target = activeList().items.first { it.status == "NEW" }
        val response = api.post("/api/v1/service/reports/${target.id}/archive", ProblemResponse::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response.body!!.code).isEqualTo("REPORT_NOT_ARCHIVABLE")
    }

    @Test
    fun `AT-028 an archived report is visible in the archive with full detail`() {
        val target = activeList().items.first { it.status == "IN_PROGRESS" }
        api.post("/api/v1/service/reports/${target.id}/archive", ServiceReportDetailResponse::class.java)

        val fromArchive = archiveList().items.first { it.id == target.id }
        assertThat(fromArchive.status).isEqualTo("ARCHIVED")
        val detail = api.get("/api/v1/service/reports/${target.id}", ServiceReportDetailResponse::class.java).body!!
        assertThat(detail.acceptedAt).isNotNull()
        assertThat(detail.archivedAt).isNotNull()
        assertThat(detail.acceptedBy).isNotNull()
        assertThat(detail.archivedBy).isNotNull()
    }

    @Test
    fun `accepting an unknown report returns 404`() {
        val response = api.post(
            "/api/v1/service/reports/00000000-0000-0000-0000-000000000000/accept", ProblemResponse::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body!!.code).isEqualTo("REPORT_NOT_FOUND")
    }

    @Test
    fun `archive list is paginated and the cursor walks the whole set`() {
        val firstPage = api.get("/api/v1/service/archive?limit=40", ReportListResponse::class.java).body!!
        assertThat(firstPage.items).hasSize(40)
        assertThat(firstPage.nextCursor).isNotNull()

        val seen = firstPage.items.map { it.id }.toMutableSet()
        var cursor = firstPage.nextCursor
        var pages = 1
        while (cursor != null && pages < 10) {
            val page = api.get("/api/v1/service/archive?limit=40&cursor=$cursor", ReportListResponse::class.java).body!!
            page.items.forEach { seen.add(it.id) }
            cursor = page.nextCursor
            pages++
        }
        assertThat(seen).hasSize(106)
    }
}
