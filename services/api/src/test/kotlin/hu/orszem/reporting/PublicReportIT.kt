package hu.orszem.reporting

import com.fasterxml.jackson.databind.JsonNode
import hu.orszem.reporting.api.PublicReportCreateResponse
import hu.orszem.shared.error.ProblemResponse
import hu.orszem.support.AbstractIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class PublicReportIT @Autowired constructor(
    private val rest: TestRestTemplate,
    private val jdbc: JdbcTemplate,
) : AbstractIntegrationTest() {

    private fun body(
        id: UUID = UUID.randomUUID(),
        eventTypeCode: String = "KNIFE_ATTACK",
        trainIdentifier: String = "IC 123",
        settlement: String = "Budapest",
        occurredAt: Instant = Instant.now().minusSeconds(60),
    ) = mapOf(
        "id" to id,
        "eventTypeCode" to eventTypeCode,
        "trainIdentifier" to trainIdentifier,
        "settlement" to settlement,
        "occurredAt" to occurredAt.toString(),
    )

    @Test
    fun `AT-012 creates a NEW report and returns 201`() {
        val id = UUID.randomUUID()
        val response = rest.postForEntity("/api/v1/public/reports", body(id = id), PublicReportCreateResponse::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(response.body!!.id).isEqualTo(id)
        assertThat(response.body!!.status).isEqualTo("NEW")
        assertThat(response.body!!.receivedAt).isNotNull()

        val status = jdbc.queryForObject("SELECT status FROM reports WHERE id = ?", String::class.java, id)
        assertThat(status).isEqualTo("NEW")
    }

    @Test
    fun `AT-011 rejects a report with a missing field`() {
        val incomplete = body().toMutableMap().also { it.remove("settlement") }
        val response = rest.postForEntity("/api/v1/public/reports", incomplete, ProblemResponse::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body!!.code).isEqualTo("VALIDATION_ERROR")
        assertThat(response.body!!.fieldErrors!!.map { it.field }).contains("settlement")
    }

    @Test
    fun `AT-013 same id and identical body is idempotent`() {
        val payload = body()
        val first = rest.postForEntity("/api/v1/public/reports", payload, PublicReportCreateResponse::class.java)
        val second = rest.postForEntity("/api/v1/public/reports", payload, PublicReportCreateResponse::class.java)

        assertThat(first.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(second.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(second.body!!.receivedAt).isEqualTo(first.body!!.receivedAt)

        val count = jdbc.queryForObject(
            "SELECT count(*) FROM reports WHERE id = ?", Int::class.java, payload["id"],
        )
        assertThat(count).isEqualTo(1)
    }

    @Test
    fun `AT-014 same id different body returns 409 REPORT_ID_CONFLICT`() {
        val id = UUID.randomUUID()
        rest.postForEntity("/api/v1/public/reports", body(id = id, settlement = "Budapest"), PublicReportCreateResponse::class.java)
        val conflict = rest.postForEntity(
            "/api/v1/public/reports", body(id = id, settlement = "Vác"), ProblemResponse::class.java,
        )

        assertThat(conflict.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(conflict.body!!.code).isEqualTo("REPORT_ID_CONFLICT")
    }

    @Test
    fun `unknown event type returns 400 EVENT_TYPE_INVALID`() {
        val response = rest.postForEntity(
            "/api/v1/public/reports", body(eventTypeCode = "NOT_A_REAL_CODE"), ProblemResponse::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body!!.code).isEqualTo("EVENT_TYPE_INVALID")
    }

    @Test
    fun `occurredAt beyond the 5 minute skew returns 400 OCCURRED_AT_IN_FUTURE`() {
        val response = rest.postForEntity(
            "/api/v1/public/reports",
            body(occurredAt = Instant.now().plus(30, ChronoUnit.MINUTES)),
            ProblemResponse::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body!!.code).isEqualTo("OCCURRED_AT_IN_FUTURE")
    }

    @Test
    fun `occurredAt within the skew tolerance is accepted`() {
        val response = rest.postForEntity(
            "/api/v1/public/reports",
            body(occurredAt = Instant.now().plus(3, ChronoUnit.MINUTES)),
            PublicReportCreateResponse::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
    }

    @Test
    fun `train and settlement whitespace is normalized`() {
        val id = UUID.randomUUID()
        rest.postForEntity(
            "/api/v1/public/reports",
            body(id = id, trainIdentifier = "  IC   123 ", settlement = " Buda  pest "),
            JsonNode::class.java,
        )
        val row = jdbc.queryForMap("SELECT train_identifier, settlement FROM reports WHERE id = ?", id)
        assertThat(row["train_identifier"]).isEqualTo("IC 123")
        assertThat(row["settlement"]).isEqualTo("Buda pest")
    }

    @Test
    fun `AT-040 public report requires no authentication`() {
        // No Authorization header is ever set on `rest`; a 2xx here proves anonymity.
        val response = rest.postForEntity("/api/v1/public/reports", body(), PublicReportCreateResponse::class.java)
        assertThat(response.statusCode.is2xxSuccessful).isTrue()
    }
}
