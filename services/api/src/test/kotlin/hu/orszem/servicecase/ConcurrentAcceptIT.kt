package hu.orszem.servicecase

import hu.orszem.servicecase.api.ReportListResponse
import hu.orszem.support.AbstractDemoIntegrationTest
import hu.orszem.support.ServiceApiClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import java.util.concurrent.Executors
import java.util.concurrent.Future

class ConcurrentAcceptIT @Autowired constructor(
    private val rest: TestRestTemplate,
    private val jdbc: JdbcTemplate,
) : AbstractDemoIntegrationTest() {

    @Test
    fun `AT-025 two concurrent accepts, exactly one succeeds`() {
        val api = ServiceApiClient(rest)
        val target = api.get("/api/v1/service/reports", ReportListResponse::class.java)
            .body!!.items.first { it.status == "NEW" }

        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val results: List<Future<HttpStatus>> = (1..threads).map {
                pool.submit<HttpStatus> {
                    api.post("/api/v1/service/reports/${target.id}/accept", String::class.java).statusCode as HttpStatus
                }
            }
            val statuses = results.map { it.get() }
            assertThat(statuses.count { it == HttpStatus.OK }).isEqualTo(1)
            assertThat(statuses.count { it == HttpStatus.CONFLICT }).isEqualTo(threads - 1)
        } finally {
            pool.shutdownNow()
        }

        val row = jdbc.queryForMap("SELECT status, accepted_by_user_id FROM reports WHERE id = ?::uuid", target.id)
        assertThat(row["status"]).isEqualTo("IN_PROGRESS")
        assertThat(row["accepted_by_user_id"]).isNotNull()

        val audits = jdbc.queryForObject(
            "SELECT count(*) FROM audit_events WHERE action = 'REPORT_ACCEPTED' AND target_id = ?::uuid",
            Int::class.java, target.id,
        )
        assertThat(audits).isEqualTo(1)
    }
}
