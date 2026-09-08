package hu.orszembejelento.backend.reports

import hu.orszembejelento.backend.reports.support.PublicReportTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import java.time.Duration
import java.util.UUID
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/**
 * Anonymous report creation over real HTTP: every `RoutingService` outcome must still
 * create a report, and every genuine identity/shape problem must refuse creation entirely
 * - the two halves of ADR 0008 Decision 4 (§43).
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class PublicReportSubmissionIT : PublicReportTestSupport() {

    private fun reportCount(): Int = jdbc.sql("SELECT COUNT(*) FROM reports").query(Int::class.java).single()

    private fun snapshotFor(reportId: UUID) = jdbc.sql(
        "SELECT routing_status, routing_reason FROM report_routing_snapshots WHERE report_id = :id",
    ).param("id", reportId).query { rs, _ -> rs.getString("routing_status") to rs.getString("routing_reason") }
        .single()

    private fun internalIdOf(publicId: UUID): UUID =
        jdbc.sql("SELECT id FROM reports WHERE public_id = :id").param("id", publicId).query(UUID::class.java).single()

    // -------------------------------------------------------------- every outcome creates a report

    @Test
    fun `a fully routed submission is accepted and the ROUTED snapshot is persisted`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        val line = insertLine("1")
        val area = insertArea()
        insertRelation(settlement, line)
        assignLineToArea(area, line)

        val response = submitReport(submitReportBody(settlementId = settlement, railwayLineId = line))
        check(response.statusCode() == 201) { response.body() }

        val publicId = UUID.fromString(json(response).get("reportId").asText())
        val (status, reason) = snapshotFor(internalIdOf(publicId))
        check(status == "ROUTED") { "expected ROUTED, got $status/$reason" }
        check(reason == null)
    }

    @Test
    fun `NO_VERIFIED_RAILWAY_LINE_REFERENCE still creates the report`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001") // no relations at all

        val response = submitReport(submitReportBody(settlementId = settlement))
        check(response.statusCode() == 201) { response.body() }
        val publicId = UUID.fromString(json(response).get("reportId").asText())
        val (status, reason) = snapshotFor(internalIdOf(publicId))
        check(status == "UNCLASSIFIED" && reason == "NO_VERIFIED_RAILWAY_LINE_REFERENCE") { "$status/$reason" }
    }

    @Test
    fun `RAILWAY_LINE_NOT_SELECTED - two active candidates - still creates the report`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        val lineA = insertLine("1")
        val lineB = insertLine("2")
        insertRelation(settlement, lineA)
        insertRelation(settlement, lineB)

        val response = submitReport(submitReportBody(settlementId = settlement))
        check(response.statusCode() == 201) { response.body() }
        val publicId = UUID.fromString(json(response).get("reportId").asText())
        val (status, reason) = snapshotFor(internalIdOf(publicId))
        check(status == "UNCLASSIFIED" && reason == "RAILWAY_LINE_NOT_SELECTED") { "$status/$reason" }
    }

    @Test
    fun `REFERENCE_MISMATCH - a known but unrelated line - still creates the report, never rejected as invalid input`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        val unrelatedLine = insertLine("1") // exists, but no relation to the settlement

        val response = submitReport(submitReportBody(settlementId = settlement, railwayLineId = unrelatedLine))
        check(response.statusCode() == 201) { response.body() }
        val publicId = UUID.fromString(json(response).get("reportId").asText())
        val (status, reason) = snapshotFor(internalIdOf(publicId))
        check(status == "UNCLASSIFIED" && reason == "REFERENCE_MISMATCH") { "$status/$reason" }
    }

    @Test
    fun `RAILWAY_LINE_UNASSIGNED still creates the report`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        val line = insertLine("1") // verified, active, but assigned to no service area
        insertRelation(settlement, line)

        val response = submitReport(submitReportBody(settlementId = settlement, railwayLineId = line))
        check(response.statusCode() == 201) { response.body() }
        val publicId = UUID.fromString(json(response).get("reportId").asText())
        val (status, reason) = snapshotFor(internalIdOf(publicId))
        check(status == "UNCLASSIFIED" && reason == "RAILWAY_LINE_UNASSIGNED") { "$status/$reason" }
    }

    @Test
    fun `RAILWAY_LINE_INACTIVE still creates the report`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        val line = insertLine("1", active = false) // verified relation, but the line itself is inactive
        insertRelation(settlement, line)

        // Must be explicit selection: an inactive line is never an inference candidate.
        val response = submitReport(submitReportBody(settlementId = settlement, railwayLineId = line))
        check(response.statusCode() == 201) { response.body() }
        val publicId = UUID.fromString(json(response).get("reportId").asText())
        val (status, reason) = snapshotFor(internalIdOf(publicId))
        check(status == "UNCLASSIFIED" && reason == "RAILWAY_LINE_INACTIVE") { "$status/$reason" }
    }

    @Test
    fun `SERVICE_AREA_INACTIVE still creates the report`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        val line = insertLine("1")
        val area = insertArea(status = "INACTIVE")
        insertRelation(settlement, line)
        assignLineToArea(area, line)

        val response = submitReport(submitReportBody(settlementId = settlement, railwayLineId = line))
        check(response.statusCode() == 201) { response.body() }
        val publicId = UUID.fromString(json(response).get("reportId").asText())
        val (status, reason) = snapshotFor(internalIdOf(publicId))
        check(status == "UNCLASSIFIED" && reason == "SERVICE_AREA_INACTIVE") { "$status/$reason" }
    }

    // ------------------------------------------------------------------- identity validation

    @Test
    fun `an unknown settlement id is refused as INVALID_SETTLEMENT and no report is created`() {
        setCurrentReferenceState()
        val response = submitReport(submitReportBody(settlementId = UUID.randomUUID()))
        check(response.statusCode() == 400) { response.body() }
        check(errorCode(response) == "INVALID_SETTLEMENT")
        check(reportCount() == 0)
    }

    @Test
    fun `an inactive settlement is refused as INVALID_SETTLEMENT`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001", active = false)
        val response = submitReport(submitReportBody(settlementId = settlement))
        check(response.statusCode() == 400) { response.body() }
        check(errorCode(response) == "INVALID_SETTLEMENT")
        check(reportCount() == 0)
    }

    @Test
    fun `an unknown event type code is refused as INVALID_EVENT_TYPE`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        val response = submitReport(submitReportBody(settlementId = settlement, eventTypeCode = "NO_SUCH_EVENT_TYPE"))
        check(response.statusCode() == 400) { response.body() }
        check(errorCode(response) == "INVALID_EVENT_TYPE")
        check(reportCount() == 0)
    }

    @Test
    fun `a deactivated event type is refused as INVALID_EVENT_TYPE`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        jdbc.sql("UPDATE report_event_types SET active = FALSE WHERE code = 'FIGHT'").update()
        try {
            val response = submitReport(submitReportBody(settlementId = settlement, eventTypeCode = "FIGHT"))
            check(response.statusCode() == 400) { response.body() }
            check(errorCode(response) == "INVALID_EVENT_TYPE")
            check(reportCount() == 0)
        } finally {
            jdbc.sql("UPDATE report_event_types SET active = TRUE WHERE code = 'FIGHT'").update()
        }
    }

    @Test
    fun `an unknown railway line id is refused as INVALID_RAILWAY_LINE - distinct from REFERENCE_MISMATCH`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        val response = submitReport(submitReportBody(settlementId = settlement, railwayLineId = UUID.randomUUID()))
        check(response.statusCode() == 400) { response.body() }
        check(errorCode(response) == "INVALID_RAILWAY_LINE")
        check(reportCount() == 0)
    }

    @Test
    fun `no reference dataset available refuses with 503 and creates nothing`() {
        // Deliberately no setCurrentReferenceState() call.
        val settlement = insertSettlement("00001")
        val response = submitReport(submitReportBody(settlementId = settlement))
        check(response.statusCode() == 503) { response.body() }
        check(errorCode(response) == "REFERENCE_DATASET_UNAVAILABLE")
        check(reportCount() == 0)
    }

    // ------------------------------------------------------------------------ credential shape

    @Test
    fun `a missing access credential header is refused as INVALID_REPORT_ACCESS_CREDENTIAL`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        val response = submitReport(submitReportBody(settlementId = settlement), credential = null)
        check(response.statusCode() == 400) { response.body() }
        check(errorCode(response) == "INVALID_REPORT_ACCESS_CREDENTIAL")
        check(reportCount() == 0)
    }

    @Test
    fun `a malformed access credential is refused as INVALID_REPORT_ACCESS_CREDENTIAL`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        listOf(
            "not-even-close",
            "pr_tooshort",
            "wrongprefix_" + "x".repeat(43),
            "pr_" + "x".repeat(42), // one short
            "pr_" + "x".repeat(44), // one long
            "pr_" + "!".repeat(43), // invalid characters
            "",
        ).forEach { malformed ->
            val response = submitReport(submitReportBody(settlementId = settlement), credential = malformed)
            check(response.statusCode() == 400) { "credential '$malformed': ${response.body()}" }
            check(errorCode(response) == "INVALID_REPORT_ACCESS_CREDENTIAL") { "credential '$malformed'" }
        }
        check(reportCount() == 0)
    }

    // --------------------------------------------------------------------------- occurredAt

    @Test
    fun `occurredAt within the future tolerance is accepted`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        val nearFuture = clock.instant().plus(Duration.ofMinutes(4))
        val response = submitReport(submitReportBody(settlementId = settlement, occurredAt = nearFuture))
        check(response.statusCode() == 201) { response.body() }
    }

    @Test
    fun `occurredAt materially in the future is refused as VALIDATION_ERROR and creates nothing`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        val farFuture = clock.instant().plus(Duration.ofMinutes(10))
        val response = submitReport(submitReportBody(settlementId = settlement, occurredAt = farFuture))
        check(response.statusCode() == 400) { response.body() }
        check(errorCode(response) == "VALIDATION_ERROR")
        check(reportCount() == 0)
    }

    @Test
    fun `occurredAt far in the past is accepted - no maximum age is invented`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        val longAgo = clock.instant().minus(Duration.ofDays(3650))
        val response = submitReport(submitReportBody(settlementId = settlement, occurredAt = longAgo))
        check(response.statusCode() == 201) { response.body() }
    }

    // --------------------------------------------------------------------- trainIdentifier

    @Test
    fun `a trainIdentifier at exactly the 64 code point limit is accepted`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        val response = submitReport(submitReportBody(settlementId = settlement, trainIdentifier = "x".repeat(64)))
        check(response.statusCode() == 201) { response.body() }
    }

    @Test
    fun `a trainIdentifier over the 64 code point limit is refused as VALIDATION_ERROR`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        val response = submitReport(submitReportBody(settlementId = settlement, trainIdentifier = "x".repeat(65)))
        check(response.statusCode() == 400) { response.body() }
        check(errorCode(response) == "VALIDATION_ERROR")
        check(reportCount() == 0)
    }

    @Test
    fun `a blank trainIdentifier normalizes to null rather than being rejected or stored blank`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        val response = submitReport(submitReportBody(settlementId = settlement, trainIdentifier = "   "))
        check(response.statusCode() == 201) { response.body() }
        val publicId = UUID.fromString(json(response).get("reportId").asText())
        val stored = jdbc.sql("SELECT train_identifier FROM reports WHERE public_id = :id")
            .param("id", publicId).query(String::class.java).optional()
        check(stored.isEmpty) { "a blank trainIdentifier must be normalized to null, got: $stored" }
    }

    @Test
    fun `a trainIdentifier is trimmed before storage`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        val response = submitReport(submitReportBody(settlementId = settlement, trainIdentifier = "  G123  "))
        check(response.statusCode() == 201) { response.body() }
        val publicId = UUID.fromString(json(response).get("reportId").asText())
        val stored = jdbc.sql("SELECT train_identifier FROM reports WHERE public_id = :id")
            .param("id", publicId).query(String::class.java).single()
        check(stored == "G123") { "expected trimmed 'G123', got '$stored'" }
    }

    // --------------------------------------------------------------------- required fields

    @Test
    fun `missing required fields are refused as VALIDATION_ERROR`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        val credential = randomCredential()

        val missingSettlement = """
            {"clientSubmissionId":"${UUID.randomUUID()}","occurredAt":"${clock.instant()}",
             "eventTypeCode":"FIGHT"}
        """.trimIndent()
        val r1 = submitReport(missingSettlement, credential)
        check(r1.statusCode() == 400) { r1.body() }
        check(errorCode(r1) == "VALIDATION_ERROR")

        val missingEventType = """
            {"clientSubmissionId":"${UUID.randomUUID()}","occurredAt":"${clock.instant()}",
             "settlementId":"$settlement"}
        """.trimIndent()
        val r2 = submitReport(missingEventType, credential)
        check(r2.statusCode() == 400) { r2.body() }
        check(errorCode(r2) == "VALIDATION_ERROR")

        val missingClientSubmissionId = """
            {"occurredAt":"${clock.instant()}","settlementId":"$settlement","eventTypeCode":"FIGHT"}
        """.trimIndent()
        val r3 = submitReport(missingClientSubmissionId, credential)
        check(r3.statusCode() == 400) { r3.body() }
        check(errorCode(r3) == "VALIDATION_ERROR")

        check(reportCount() == 0)
    }

    @Test
    fun `an unexpected body field is rejected rather than silently discarded`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        // No free text, no GPS, and no client-supplied categoryCode are ever accepted - the
        // backend derives category from eventTypeCode alone (ADR 0008). A body carrying one
        // of the forbidden fields must fail closed, not be silently trimmed down to the
        // fields the DTO happens to declare.
        val bodyWithForbiddenField = """
            {
              "clientSubmissionId": "${UUID.randomUUID()}",
              "occurredAt": "${clock.instant()}",
              "settlementId": "$settlement",
              "eventTypeCode": "FIGHT",
              "latitude": 47.4979
            }
        """.trimIndent()
        val response = submitReport(bodyWithForbiddenField)
        check(response.statusCode() == 400) { "an unrecognised field must be rejected, got: ${response.body()}" }
        check(errorCode(response) == "VALIDATION_ERROR")
        check(reportCount() == 0)
    }

    // ------------------------------------------------------------------------ response shape

    @Test
    fun `a successful creation returns 201, a Location header, no-store, and initialStatus RECEIVED`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        val response = submitReport(submitReportBody(settlementId = settlement))
        check(response.statusCode() == 201) { response.body() }
        check(response.headers().firstValue("Cache-Control").orElse("") == "no-store")
        val location = response.headers().firstValue("Location").orElse(null)
        check(location != null && location.contains(json(response).get("reportId").asText())) { "unexpected Location: $location" }
        check(json(response).get("initialStatus").asText() == "RECEIVED")
        check(json(response).has("submittedAt"))
        check(!json(response).has("credential")) { "the response must never echo the access credential" }
    }
}
