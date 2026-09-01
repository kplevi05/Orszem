package hu.orszem.demo

import hu.orszem.support.AbstractIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate

class DemoResetIT @Autowired constructor(
    private val rest: TestRestTemplate,
    private val jdbc: JdbcTemplate,
    private val demoDataService: DemoDataService,
) : AbstractIntegrationTest() {

    private fun count(sql: String) = jdbc.queryForObject(sql) { rs, _ -> rs.getInt(1) }

    private fun assertBaseline() {
        assertThat(count("SELECT count(*) FROM reports")).isEqualTo(120)
        assertThat(count("SELECT count(*) FROM reports WHERE status = 'NEW'")).isEqualTo(8)
        assertThat(count("SELECT count(*) FROM reports WHERE status = 'IN_PROGRESS'")).isEqualTo(6)
        assertThat(count("SELECT count(*) FROM reports WHERE status = 'ARCHIVED'")).isEqualTo(106)
        assertThat(count("SELECT count(*) FROM users WHERE username = 'demo.service' AND status = 'ACTIVE'")).isEqualTo(1)
        assertThat(
            count(
                "SELECT count(*) FROM reports WHERE (occurred_at AT TIME ZONE 'Europe/Budapest')::date " +
                    "= (now() AT TIME ZONE 'Europe/Budapest')::date",
            ),
        ).isEqualTo(16)
    }

    @Test
    fun `AT-001 reset restores the documented baseline`() {
        demoDataService.reset()
        // mutate
        jdbc.update("UPDATE reports SET status = 'NEW', accepted_at = NULL, accepted_by_user_id = NULL WHERE status = 'IN_PROGRESS'")
        jdbc.update("DELETE FROM reports WHERE status = 'ARCHIVED'")

        demoDataService.reset()
        assertBaseline()
    }

    @Test
    fun `the reset endpoint requires the shared secret`() {
        demoDataService.reset()
        val missing = rest.postForEntity("/api/v1/admin/demo/reset", null, String::class.java)
        assertThat(missing.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)

        val wrong = rest.exchange(
            "/api/v1/admin/demo/reset",
            HttpMethod.POST,
            HttpEntity<Void>(HttpHeaders().apply { set("X-Demo-Reset-Token", "nope") }),
            String::class.java,
        )
        assertThat(wrong.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)

        val ok = rest.exchange(
            "/api/v1/admin/demo/reset",
            HttpMethod.POST,
            HttpEntity<Void>(HttpHeaders().apply { set("X-Demo-Reset-Token", "test-demo-reset-token") }),
            String::class.java,
        )
        assertThat(ok.statusCode).isEqualTo(HttpStatus.OK)
        assertBaseline()
    }
}
