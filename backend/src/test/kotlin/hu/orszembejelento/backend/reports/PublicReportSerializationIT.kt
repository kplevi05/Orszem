package hu.orszembejelento.backend.reports

import hu.orszembejelento.backend.reports.support.PublicReportTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import java.util.UUID
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/**
 * What the Public reporting surface must never expose (§47), and what its OpenAPI document
 * must describe (§48). A regression here is exactly the class of bug the reference API's
 * own serialization-leak test (`PublicReferenceApiIT`) already guards against elsewhere.
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class PublicReportSerializationIT : PublicReportTestSupport() {

    private fun internalIdOf(publicId: UUID): UUID =
        jdbc.sql("SELECT id FROM reports WHERE public_id = :id").param("id", publicId).query(UUID::class.java).single()

    private val forbiddenTerms = listOf(
        "serviceAreaId", "service_area_id", "routingReason", "routing_reason",
        "routingStatus", "routing_status", "referenceDatasetVersion", "reference_dataset_version",
        "assignedUser", "moderator", "auditEvent", "audit_event", "actorType",
        "publicAccessCredentialHash", "public_access_credential_hash", "credentialHash",
        "submittedRailwayLineId", "submitted_railway_line_id",
    )

    @Test
    fun `neither the submission nor the lookup response ever leaks a routing, audit or internal-identity field`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001", "Alfaváros")
        val line = insertLine("1")
        val area = insertArea()
        insertRelation(settlement, line)
        assignLineToArea(area, line)
        val credential = randomCredential()

        val submitResponse = submitReport(
            submitReportBody(settlementId = settlement, railwayLineId = line, eventTypeCode = "FIGHT"),
            credential,
        )
        check(submitResponse.statusCode() == 201) { submitResponse.body() }
        val publicId = UUID.fromString(json(submitResponse).get("reportId").asText())
        val internalId = internalIdOf(publicId)

        val getResponse = getPublicReport(publicId, credential)
        check(getResponse.statusCode() == 200) { getResponse.body() }

        listOf(submitResponse.body(), getResponse.body()).forEach { body ->
            forbiddenTerms.forEach { term ->
                check(!body.contains(term, ignoreCase = true)) { "response leaked forbidden term '$term': $body" }
            }
            check(!body.contains(internalId.toString())) { "response leaked the internal report id: $body" }
            check(!body.contains(credential)) { "response must never echo the access credential itself: $body" }
            // The internal ReportStatus vocabulary ("NEW") must never appear - only the
            // Public vocabulary ("RECEIVED"/"PROCESSING"/"CLOSED") is ever returned.
            check(!body.contains("\"NEW\"")) { "the internal status literal 'NEW' leaked: $body" }
            check(!body.contains("\"IN_PROGRESS\"")) { "an internal status literal leaked: $body" }
            check(!body.contains("\"ARCHIVED\"")) { "an internal status literal leaked: $body" }
        }
    }

    @Test
    fun `an UNCLASSIFIED report's response leaks nothing about the routing decision either`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        val credential = randomCredential()

        val submitResponse = submitReport(submitReportBody(settlementId = settlement), credential)
        check(submitResponse.statusCode() == 201) { submitResponse.body() }
        val publicId = UUID.fromString(json(submitResponse).get("reportId").asText())
        val getResponse = getPublicReport(publicId, credential)

        listOf(submitResponse.body(), getResponse.body()).forEach { body ->
            check(!body.contains("UNCLASSIFIED", ignoreCase = true)) { "leaked the routing outcome: $body" }
            check(!body.contains("NO_VERIFIED_RAILWAY_LINE_REFERENCE")) { "leaked the unclassified reason: $body" }
        }
    }

    @Test
    fun `the event catalogue response carries only code, displayName and nested eventTypes`() {
        val response = get("/api/v1/public/report-catalog")
        check(response.statusCode() == 200) { response.body() }
        val body = response.body()
        listOf("displayOrder", "display_order", "\"active\"").forEach { term ->
            check(!body.contains(term)) { "the catalogue leaked an internal ordering/lifecycle field '$term': $body" }
        }
    }

    // ------------------------------------------------------------------------------ OpenAPI

    @Test
    fun `the OpenAPI document describes report submission, lookup and the catalogue, with their key responses`() {
        val response = get("/v3/api-docs")
        check(response.statusCode() == 200) { response.body() }
        val body = response.body()

        check(body.contains("/api/v1/public/reports")) { "missing the report submission/lookup path" }
        check(body.contains("/api/v1/public/report-catalog")) { "missing the event catalogue path" }
        check(body.contains("X-Orszem-Report-Access")) { "the custom access-credential header must be documented" }

        // The three response codes a client must be prepared to branch on beyond 200/201.
        listOf("404", "409", "503").forEach { status ->
            check(body.contains("\"$status\"")) { "response $status must be documented somewhere in the API surface" }
        }
    }
}
