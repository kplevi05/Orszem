package hu.orszembejelento.backend.reports

import hu.orszembejelento.backend.reports.support.PublicReportTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import java.util.UUID
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/**
 * Public report lookup over real HTTP (ADR 0008 §25-26, §28): an unknown id, a missing
 * credential, a malformed credential and a wrong credential must all be indistinguishable,
 * so report existence is never disclosed by which of the four actually happened.
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class PublicReportLookupIT : PublicReportTestSupport() {

    @Test
    fun `the correct credential returns the report's detail`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001", "Alfaváros")
        val created = givenCreatedReport(settlement, eventTypeCode = "FIGHT")

        val response = getPublicReport(created.publicId, created.credential)
        check(response.statusCode() == 200) { response.body() }
        val body = json(response)
        check(body.get("reportId").asText() == created.publicId.toString())
        check(body.get("settlement").get("name").asText() == "Alfaváros")
        check(body.get("eventType").get("code").asText() == "FIGHT")
        check(body.get("status").asText() == "RECEIVED")
    }

    @Test
    fun `an unknown public id returns 404 REPORT_NOT_FOUND`() {
        val response = getPublicReport(UUID.randomUUID(), randomCredential())
        check(response.statusCode() == 404) { response.body() }
        check(errorCode(response) == "REPORT_NOT_FOUND")
    }

    @Test
    fun `a missing credential returns 404 REPORT_NOT_FOUND, not 400`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        val created = givenCreatedReport(settlement)

        val response = getPublicReport(created.publicId, credential = null)
        check(response.statusCode() == 404) { response.body() }
        check(errorCode(response) == "REPORT_NOT_FOUND")
    }

    @Test
    fun `a malformed credential returns 404 REPORT_NOT_FOUND`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        val created = givenCreatedReport(settlement)

        val response = getPublicReport(created.publicId, credential = "not-a-real-credential")
        check(response.statusCode() == 404) { response.body() }
        check(errorCode(response) == "REPORT_NOT_FOUND")
    }

    @Test
    fun `a well-formed but wrong credential returns 404 REPORT_NOT_FOUND`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        val created = givenCreatedReport(settlement)

        val response = getPublicReport(created.publicId, credential = randomCredential())
        check(response.statusCode() == 404) { response.body() }
        check(errorCode(response) == "REPORT_NOT_FOUND")
    }

    @Test
    fun `unknown id, missing, malformed and wrong credential all return byte-identical bodies`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        val created = givenCreatedReport(settlement)

        val unknownId = getPublicReport(UUID.randomUUID(), randomCredential())
        val missing = getPublicReport(created.publicId, null)
        val malformed = getPublicReport(created.publicId, "garbage")
        val wrong = getPublicReport(created.publicId, randomCredential())

        val bodies = listOf(unknownId, missing, malformed, wrong).map { response ->
            // correlationId legitimately differs per request - strip it before comparing.
            json(response).let { node -> "${node.get("code").asText()}|${node.get("message").asText()}" }
        }
        check(bodies.toSet().size == 1) { "all four failure reasons must be indistinguishable, got: $bodies" }
        listOf(unknownId, missing, malformed, wrong).forEach { response ->
            check(response.statusCode() == 404) { response.body() }
        }
    }

    @Test
    fun `a Service bearer token does not work as a report access credential`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        val created = givenCreatedReport(settlement)
        val user = givenUser()
        val credentials = loginSuccessfully(user.serviceId)

        // The service access token is a completely different token type (opaque, its own
        // shape) - it can never even parse as a pr_ credential, so this must fail exactly
        // like any other malformed value: 404, not 401/403 and not a lucky match.
        val response = getPublicReport(created.publicId, credentials.accessToken)
        check(response.statusCode() == 404) { response.body() }
        check(errorCode(response) == "REPORT_NOT_FOUND")
    }

    @Test
    fun `a report access credential does not authenticate a Service endpoint`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        val created = givenCreatedReport(settlement)

        // Presented as a Bearer token against an authenticated Service endpoint, the report
        // credential must not be mistaken for a session token.
        val response = post("/api/v1/service/auth/logout", "", bearer = created.credential)
        check(response.statusCode() == 401) {
            "a report access credential must never authenticate a Service session: ${response.statusCode()} ${response.body()}"
        }
    }

    @Test
    fun `every lookup response, success or failure, is marked no-store`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        val created = givenCreatedReport(settlement)

        val success = getPublicReport(created.publicId, created.credential)
        val failure = getPublicReport(UUID.randomUUID(), randomCredential())
        check(success.headers().firstValue("Cache-Control").orElse("") == "no-store")
        check(failure.headers().firstValue("Cache-Control").orElse("") == "no-store")
    }

    @Test
    fun `lookup does not require a current reference dataset - it must never call RoutingService`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        val created = givenCreatedReport(settlement)

        // No current reference state at all any more.
        jdbc.sql("UPDATE reference_dataset_imports SET is_current = FALSE WHERE is_current").update()

        val response = getPublicReport(created.publicId, created.credential)
        check(response.statusCode() == 200) { "lookup must not depend on a current reference state: ${response.body()}" }
    }

    @Test
    fun `a later-deactivated settlement, category or event type still resolves for an existing report`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001", "Alfaváros")
        val created = givenCreatedReport(settlement, eventTypeCode = "FIGHT")

        jdbc.sql("UPDATE settlements SET active = FALSE WHERE id = :id").param("id", settlement).update()
        jdbc.sql("UPDATE report_event_types SET active = FALSE WHERE code = 'FIGHT'").update()
        jdbc.sql("UPDATE report_categories SET active = FALSE WHERE code = 'VIOLENCE_DANGER'").update()
        try {
            val response = getPublicReport(created.publicId, created.credential)
            check(response.statusCode() == 200) { "a historical report must still resolve: ${response.body()}" }
            val body = json(response)
            check(body.get("settlement").get("name").asText() == "Alfaváros")
            check(body.get("eventType").get("code").asText() == "FIGHT")
        } finally {
            jdbc.sql("UPDATE report_event_types SET active = TRUE WHERE code = 'FIGHT'").update()
            jdbc.sql("UPDATE report_categories SET active = TRUE WHERE code = 'VIOLENCE_DANGER'").update()
        }
    }
}
