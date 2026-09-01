package hu.orszem.demo

import hu.orszem.auth.api.ServiceUserProfileResponse
import hu.orszem.servicecase.api.ReportListResponse
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
import org.springframework.test.context.TestPropertySource

/**
 * Demo v1.1 §E — the configuration gate.
 *
 * With `orszem.demo.deletion-enabled=false` the controller bean is not created at
 * all, so the route does not exist and the capability is never granted. This is
 * what keeps the feature demo-scoped rather than a permanent product capability.
 */
@TestPropertySource(properties = ["orszem.demo.deletion-enabled=false"])
class DemoReportDeletionDisabledIT @Autowired constructor(
    private val rest: TestRestTemplate,
    private val jdbc: JdbcTemplate,
) : AbstractDemoIntegrationTest() {

    private lateinit var api: ServiceApiClient

    @BeforeEach
    fun setUp() {
        api = ServiceApiClient(rest)
    }

    @Test
    fun `the delete route does not exist when demo deletion is disabled`() {
        val id = api.get("/api/v1/service/reports", ReportListResponse::class.java).body!!.items.first().id

        // The path still exists for GET, so an absent DELETE handler is reported as
        // "method not allowed" rather than "not found".
        val response = api.delete("/api/v1/service/reports/$id", ProblemResponse::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)
        assertThat(response.body!!.code).isEqualTo("METHOD_NOT_ALLOWED")
        // The report is untouched.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reports WHERE id = ?::uuid", Int::class.java, id))
            .isEqualTo(1)
    }

    @Test
    fun `the delete capability is not advertised when demo deletion is disabled`() {
        val me = api.get("/api/v1/service/me", ServiceUserProfileResponse::class.java).body!!
        assertThat(me.capabilities).doesNotContain("REPORT_DELETE")
        assertThat(me.capabilities).containsExactlyInAnyOrder(
            "REPORT_READ_ACTIVE", "REPORT_ACCEPT", "REPORT_ARCHIVE", "ARCHIVE_READ", "ANALYTICS_READ",
        )
    }
}
