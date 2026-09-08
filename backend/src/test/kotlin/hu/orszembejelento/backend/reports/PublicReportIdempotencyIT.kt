package hu.orszembejelento.backend.reports

import hu.orszembejelento.backend.reports.support.PublicReportTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import java.util.UUID
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/**
 * Client-driven idempotency over real HTTP (ADR 0008 Decision 2): a replay is recognised by
 * comparing normalized business fields, never a raw-request hash, and that recognition
 * happens strictly before any business state is re-validated (§44).
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class PublicReportIdempotencyIT : PublicReportTestSupport() {

    private fun reportCount(): Int = jdbc.sql("SELECT COUNT(*) FROM reports").query(Int::class.java).single()

    @Test
    fun `an identical sequential retry replays the same report as 200, and no second row is created`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        val credential = randomCredential()
        val clientSubmissionId = UUID.randomUUID()
        val body = submitReportBody(clientSubmissionId = clientSubmissionId, settlementId = settlement)

        val first = submitReport(body, credential)
        check(first.statusCode() == 201) { first.body() }
        val firstId = json(first).get("reportId").asText()

        val second = submitReport(body, credential)
        check(second.statusCode() == 200) { second.body() }
        check(json(second).get("reportId").asText() == firstId) { "a replay must return the same public id" }
        check(json(second).get("submittedAt").asText() == json(first).get("submittedAt").asText()) {
            "a replay must return the ORIGINAL submittedAt, not a new one"
        }

        check(reportCount() == 1) { "a replay must never create a second row" }
    }

    @Test
    fun `JSON whitespace and property order never change idempotency semantics`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        val credential = randomCredential()
        val clientSubmissionId = UUID.randomUUID()
        val occurredAt = clock.instant().toString()

        val compact = """{"clientSubmissionId":"$clientSubmissionId","occurredAt":"$occurredAt",""" +
            """"settlementId":"$settlement","eventTypeCode":"FIGHT"}"""
        val reordered = """
            {
                "eventTypeCode"        :   "FIGHT"   ,
                "settlementId": "$settlement",

                "occurredAt": "$occurredAt",
                "clientSubmissionId": "$clientSubmissionId"
            }
        """.trimIndent()

        val first = submitReport(compact, credential)
        check(first.statusCode() == 201) { first.body() }

        val second = submitReport(reordered, credential)
        check(second.statusCode() == 200) { "differently-formatted but semantically identical JSON must replay: ${second.body()}" }
        check(json(second).get("reportId").asText() == json(first).get("reportId").asText())
        check(reportCount() == 1)
    }

    @Test
    fun `equivalent textual timestamp representations of the same instant still replay`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        val credential = randomCredential()
        val clientSubmissionId = UUID.randomUUID()

        // The test clock is fixed at 2026-01-01T12:00:00Z, so this must be in the past.
        val bodyZ = submitReportBody(
            clientSubmissionId = clientSubmissionId,
            occurredAt = java.time.Instant.parse("2026-01-01T11:00:00Z"),
            settlementId = settlement,
        )
        val first = submitReport(bodyZ, credential)
        check(first.statusCode() == 201) { first.body() }

        // Same instant (11:00 UTC), a different (but equivalent) textual offset representation.
        val bodyOffset = """
            {"clientSubmissionId":"$clientSubmissionId","occurredAt":"2026-01-01T12:00:00+01:00",
             "settlementId":"$settlement","eventTypeCode":"FIGHT"}
        """.trimIndent()
        val second = submitReport(bodyOffset, credential)
        check(second.statusCode() == 200) { "an equivalent instant in a different textual form must replay: ${second.body()}" }
        check(reportCount() == 1)
    }

    @Test
    fun `a differing payload under the same clientSubmissionId is refused as IDEMPOTENCY_KEY_REUSED`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        val otherSettlement = insertSettlement("00002", "Beta")
        val credential = randomCredential()
        val clientSubmissionId = UUID.randomUUID()

        val first = submitReport(submitReportBody(clientSubmissionId = clientSubmissionId, settlementId = settlement), credential)
        check(first.statusCode() == 201) { first.body() }

        val conflicting = submitReport(
            submitReportBody(clientSubmissionId = clientSubmissionId, settlementId = otherSettlement),
            credential,
        )
        check(conflicting.statusCode() == 409) { conflicting.body() }
        check(errorCode(conflicting) == "IDEMPOTENCY_KEY_REUSED")
        check(reportCount() == 1) { "a rejected conflict must not create a second row" }
    }

    @Test
    fun `a differing eventTypeCode under the same clientSubmissionId is refused as IDEMPOTENCY_KEY_REUSED`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        val credential = randomCredential()
        val clientSubmissionId = UUID.randomUUID()

        val first = submitReport(
            submitReportBody(clientSubmissionId = clientSubmissionId, settlementId = settlement, eventTypeCode = "FIGHT"),
            credential,
        )
        check(first.statusCode() == 201) { first.body() }

        val conflicting = submitReport(
            submitReportBody(clientSubmissionId = clientSubmissionId, settlementId = settlement, eventTypeCode = "ROBBERY"),
            credential,
        )
        check(conflicting.statusCode() == 409) { conflicting.body() }
        check(errorCode(conflicting) == "IDEMPOTENCY_KEY_REUSED")
    }

    @Test
    fun `a different credential under the same clientSubmissionId and payload is refused as IDEMPOTENCY_KEY_REUSED`() {
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        val clientSubmissionId = UUID.randomUUID()
        val body = submitReportBody(clientSubmissionId = clientSubmissionId, settlementId = settlement)

        val first = submitReport(body, randomCredential())
        check(first.statusCode() == 201) { first.body() }

        val conflicting = submitReport(body, randomCredential())
        check(conflicting.statusCode() == 409) { conflicting.body() }
        check(errorCode(conflicting) == "IDEMPOTENCY_KEY_REUSED")
        check(reportCount() == 1)
    }

    @Test
    fun `IDEMPOTENCY_KEY_REUSED is the same code for a payload mismatch and a credential mismatch`() {
        // Deliberately one generic reason - a caller cannot use the response to work out
        // which part of a guessed request was closer to the original (ADR 0008).
        setCurrentReferenceState()
        val settlementA = insertSettlement("00001")
        val settlementB = insertSettlement("00002", "Beta")
        val clientSubmissionId = UUID.randomUUID()
        val credential = randomCredential()

        val first = submitReport(submitReportBody(clientSubmissionId = clientSubmissionId, settlementId = settlementA), credential)
        check(first.statusCode() == 201)

        val payloadMismatch = submitReport(
            submitReportBody(clientSubmissionId = clientSubmissionId, settlementId = settlementB),
            credential,
        )
        val credentialMismatch = submitReport(
            submitReportBody(clientSubmissionId = clientSubmissionId, settlementId = settlementA),
            randomCredential(),
        )
        check(errorCode(payloadMismatch) == errorCode(credentialMismatch))
        check(payloadMismatch.statusCode() == credentialMismatch.statusCode())
    }

    @Test
    fun `a replay is recognised BEFORE revalidating against today's reference state`() {
        // The ADR 0008 example verbatim: a settlement active on day 1, deactivated by the
        // time of a day-30 retry, must still return the original report - never a fresh
        // INVALID_SETTLEMENT computed against the now-inactive settlement.
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        val credential = randomCredential()
        val clientSubmissionId = UUID.randomUUID()
        val body = submitReportBody(clientSubmissionId = clientSubmissionId, settlementId = settlement)

        val day1 = submitReport(body, credential)
        check(day1.statusCode() == 201) { day1.body() }

        // Day 30: the settlement is deactivated and the clock has moved on materially.
        jdbc.sql("UPDATE settlements SET active = FALSE WHERE id = :id").param("id", settlement).update()
        mutableClock.advance(java.time.Duration.ofDays(29))

        val retry = submitReport(body, credential)
        check(retry.statusCode() == 200) { "a replay must succeed even though the settlement is now inactive: ${retry.body()}" }
        check(json(retry).get("reportId").asText() == json(day1).get("reportId").asText())
    }

    @Test
    fun `a replay is recognised even after the reference dataset becomes unavailable`() {
        // Same principle as the settlement-deactivation case, but for the more extreme
        // "no current dataset at all" state - an already-accepted report must remain
        // retryable regardless of what happens to the reference state afterward.
        setCurrentReferenceState()
        val settlement = insertSettlement("00001")
        val credential = randomCredential()
        val clientSubmissionId = UUID.randomUUID()
        val body = submitReportBody(clientSubmissionId = clientSubmissionId, settlementId = settlement)

        val first = submitReport(body, credential)
        check(first.statusCode() == 201) { first.body() }

        jdbc.sql("UPDATE reference_dataset_imports SET is_current = FALSE WHERE is_current").update()

        val retry = submitReport(body, credential)
        check(retry.statusCode() == 200) { "a replay must not require a current reference state: ${retry.body()}" }
    }
}
