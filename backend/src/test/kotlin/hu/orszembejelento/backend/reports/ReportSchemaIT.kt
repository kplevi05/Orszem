package hu.orszembejelento.backend.reports

import hu.orszembejelento.backend.reports.support.PublicReportTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import java.util.UUID
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/**
 * The V003 `reports` / `report_routing_snapshots` invariants, asserted against real
 * PostgreSQL - the rules that make idempotency, credential secrecy and routing coherence
 * true even under a bug in application code, not only by convention (ADR 0008).
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class ReportSchemaIT : PublicReportTestSupport() {

    // ------------------------------------------------------------------ helpers

    private fun insertReport(
        id: UUID = UUID.randomUUID(),
        publicId: UUID = UUID.randomUUID(),
        clientSubmissionId: UUID = UUID.randomUUID(),
        credentialHash: ByteArray = ByteArray(32) { it.toByte() },
        trainIdentifier: String? = null,
        settlementId: UUID,
        railwayLineId: UUID? = null,
        eventTypeCode: String = "FIGHT",
        status: String = "NEW",
    ) {
        jdbc.sql(
            """
            INSERT INTO reports (
                id, public_id, client_submission_id, public_access_credential_hash,
                occurred_at, submitted_at, train_identifier, settlement_id,
                submitted_railway_line_id, event_type_code, status
            ) VALUES (
                :id, :publicId, :clientSubmissionId, :hash,
                now(), now(), :trainIdentifier, :settlementId,
                :railwayLineId, :eventTypeCode, :status
            )
            """.trimIndent(),
        )
            .param("id", id).param("publicId", publicId).param("clientSubmissionId", clientSubmissionId)
            .param("hash", credentialHash).param("trainIdentifier", trainIdentifier)
            .param("settlementId", settlementId).param("railwayLineId", railwayLineId)
            .param("eventTypeCode", eventTypeCode).param("status", status)
            .update()
    }

    private fun insertSnapshot(
        reportId: UUID,
        status: String,
        reason: String? = null,
        resolvedLineId: UUID? = null,
        areaId: UUID? = null,
        version: String = "v1",
    ) {
        jdbc.sql(
            """
            INSERT INTO report_routing_snapshots (
                report_id, routing_status, routing_reason, resolved_railway_line_id,
                service_area_id, reference_dataset_version, routed_at
            ) VALUES (
                :reportId, :status, :reason, :lineId, :areaId, :version, now()
            )
            """.trimIndent(),
        )
            .param("reportId", reportId).param("status", status).param("reason", reason)
            .param("lineId", resolvedLineId).param("areaId", areaId).param("version", version)
            .update()
    }

    // --------------------------------------------------------------------- reports

    @Test
    fun `public_id is unique`() {
        val settlement = insertSettlement("00001")
        val publicId = UUID.randomUUID()
        insertReport(publicId = publicId, settlementId = settlement)
        val rejected = runCatching { insertReport(publicId = publicId, settlementId = settlement) }.isFailure
        check(rejected) { "a duplicate public_id must be rejected" }
    }

    @Test
    fun `client_submission_id is unique - the idempotency key`() {
        val settlement = insertSettlement("00001")
        val clientSubmissionId = UUID.randomUUID()
        insertReport(clientSubmissionId = clientSubmissionId, settlementId = settlement)
        val rejected = runCatching {
            insertReport(clientSubmissionId = clientSubmissionId, settlementId = settlement)
        }.isFailure
        check(rejected) { "a duplicate client_submission_id must be rejected" }
    }

    @Test
    fun `the credential hash must be exactly 32 bytes - never the plaintext credential`() {
        val settlement = insertSettlement("00001")
        check(runCatching { insertReport(credentialHash = ByteArray(31), settlementId = settlement) }.isFailure) {
            "a 31-byte hash must be rejected"
        }
        check(runCatching { insertReport(credentialHash = ByteArray(33), settlementId = settlement) }.isFailure) {
            "a 33-byte hash must be rejected"
        }
        check(runCatching { insertReport(credentialHash = ByteArray(0), settlementId = settlement) }.isFailure) {
            "an empty hash must be rejected"
        }
        // 32 bytes is accepted - proven by every other test in this file succeeding at all.
        insertReport(credentialHash = ByteArray(32), settlementId = settlement)
    }

    @Test
    fun `a non-null train identifier must already be trimmed`() {
        val settlement = insertSettlement("00001")
        check(runCatching { insertReport(trainIdentifier = " G1234", settlementId = settlement) }.isFailure) {
            "leading whitespace must be rejected at the database level"
        }
        check(runCatching { insertReport(trainIdentifier = "G1234 ", settlementId = settlement) }.isFailure) {
            "trailing whitespace must be rejected at the database level"
        }
        insertReport(trainIdentifier = "G1234", settlementId = settlement)
    }

    @Test
    fun `train identifier length is capped at 64 characters`() {
        val settlement = insertSettlement("00001")
        insertReport(trainIdentifier = "x".repeat(64), settlementId = settlement)
        check(runCatching { insertReport(trainIdentifier = "x".repeat(65), settlementId = settlement) }.isFailure) {
            "a 65-character train identifier must be rejected"
        }
    }

    @Test
    fun `train identifier may be null`() {
        val settlement = insertSettlement("00001")
        insertReport(trainIdentifier = null, settlementId = settlement)
    }

    @Test
    fun `the status vocabulary is enforced`() {
        val settlement = insertSettlement("00001")
        insertReport(status = "NEW", settlementId = settlement)
        insertReport(status = "IN_PROGRESS", settlementId = settlement)
        insertReport(status = "ARCHIVED", settlementId = settlement)
        check(runCatching { insertReport(status = "DELETED", settlementId = settlement) }.isFailure) {
            "an unknown status must be rejected"
        }
        check(runCatching { insertReport(status = "new", settlementId = settlement) }.isFailure) {
            "the vocabulary is case-sensitive"
        }
    }

    @Test
    fun `a report cannot reference a settlement that does not exist`() {
        check(runCatching { insertReport(settlementId = UUID.randomUUID()) }.isFailure) {
            "a dangling settlement reference must be rejected"
        }
    }

    @Test
    fun `a report cannot reference an event type that does not exist`() {
        val settlement = insertSettlement("00001")
        check(runCatching { insertReport(settlementId = settlement, eventTypeCode = "NO_SUCH_EVENT_TYPE") }.isFailure) {
            "a dangling event type reference must be rejected"
        }
    }

    @Test
    fun `a report cannot reference a railway line that does not exist, but may name none at all`() {
        val settlement = insertSettlement("00001")
        insertReport(settlementId = settlement, railwayLineId = null)
        check(runCatching { insertReport(settlementId = settlement, railwayLineId = UUID.randomUUID()) }.isFailure) {
            "a dangling railway line reference must be rejected"
        }
    }

    @Test
    fun `an inactive settlement, event type or railway line may still be referenced by a report row`() {
        // The database does not enforce "active" for a report's own foreign keys - that
        // business rule belongs to submission-time validation (SubmitReportUseCase), never
        // the schema, precisely so a historical report referencing something since
        // deactivated remains a valid row.
        val settlement = insertSettlement("00001", active = false)
        val line = insertLine("1", active = false)
        insertRelation(settlement, line)
        insertReport(settlementId = settlement, railwayLineId = line)
    }

    // ------------------------------------------------------- report_routing_snapshots

    @Test
    fun `a ROUTED snapshot requires an area and a resolved line, and no reason`() {
        val settlement = insertSettlement("00001")
        val line = insertLine("1")
        val area = insertArea()
        val report = UUID.randomUUID()
        insertReport(id = report, settlementId = settlement, railwayLineId = line)

        insertSnapshot(report, status = "ROUTED", resolvedLineId = line, areaId = area, reason = null)
    }

    @Test
    fun `a ROUTED snapshot without an area is rejected`() {
        val settlement = insertSettlement("00001")
        val line = insertLine("1")
        val report = UUID.randomUUID()
        insertReport(id = report, settlementId = settlement)

        val rejected = runCatching {
            insertSnapshot(report, status = "ROUTED", resolvedLineId = line, areaId = null, reason = null)
        }.isFailure
        check(rejected) { "ROUTED must always name a service area" }
    }

    @Test
    fun `a ROUTED snapshot without a resolved line is rejected`() {
        val settlement = insertSettlement("00001")
        val area = insertArea()
        val report = UUID.randomUUID()
        insertReport(id = report, settlementId = settlement)

        val rejected = runCatching {
            insertSnapshot(report, status = "ROUTED", resolvedLineId = null, areaId = area, reason = null)
        }.isFailure
        check(rejected) { "ROUTED must always name a resolved line" }
    }

    @Test
    fun `a ROUTED snapshot carrying a reason is rejected`() {
        val settlement = insertSettlement("00001")
        val line = insertLine("1")
        val area = insertArea()
        val report = UUID.randomUUID()
        insertReport(id = report, settlementId = settlement)

        val rejected = runCatching {
            insertSnapshot(report, status = "ROUTED", resolvedLineId = line, areaId = area, reason = "RAILWAY_LINE_INACTIVE")
        }.isFailure
        check(rejected) { "ROUTED must never carry an unclassified reason" }
    }

    @Test
    fun `an UNCLASSIFIED snapshot requires a reason and never an area`() {
        val settlement = insertSettlement("00001")
        val report = UUID.randomUUID()
        insertReport(id = report, settlementId = settlement)

        insertSnapshot(report, status = "UNCLASSIFIED", reason = "NO_VERIFIED_RAILWAY_LINE_REFERENCE", areaId = null)
    }

    @Test
    fun `an UNCLASSIFIED snapshot without a reason is rejected`() {
        val settlement = insertSettlement("00001")
        val report = UUID.randomUUID()
        insertReport(id = report, settlementId = settlement)

        val rejected = runCatching {
            insertSnapshot(report, status = "UNCLASSIFIED", reason = null, areaId = null)
        }.isFailure
        check(rejected) { "UNCLASSIFIED must always carry a reason" }
    }

    @Test
    fun `an UNCLASSIFIED snapshot naming an area is rejected`() {
        val settlement = insertSettlement("00001")
        val area = insertArea()
        val report = UUID.randomUUID()
        insertReport(id = report, settlementId = settlement)

        val rejected = runCatching {
            insertSnapshot(report, status = "UNCLASSIFIED", reason = "RAILWAY_LINE_UNASSIGNED", areaId = area)
        }.isFailure
        check(rejected) { "UNCLASSIFIED must never name a service area" }
    }

    @Test
    fun `an UNCLASSIFIED snapshot's resolved line is unconstrained - present or absent are both valid`() {
        val settlement = insertSettlement("00001")
        val line = insertLine("1")
        val reportWithoutLine = UUID.randomUUID()
        val reportWithLine = UUID.randomUUID()
        insertReport(id = reportWithoutLine, settlementId = settlement)
        insertReport(id = reportWithLine, settlementId = settlement, railwayLineId = line)

        // Reached before any line is resolved.
        insertSnapshot(reportWithoutLine, status = "UNCLASSIFIED", reason = "NO_VERIFIED_RAILWAY_LINE_REFERENCE")
        // A line WAS resolved and only then found e.g. inactive.
        insertSnapshot(reportWithLine, status = "UNCLASSIFIED", reason = "RAILWAY_LINE_INACTIVE", resolvedLineId = line)
    }

    @Test
    fun `the routing status vocabulary is enforced`() {
        val settlement = insertSettlement("00001")
        val report = UUID.randomUUID()
        insertReport(id = report, settlementId = settlement)
        val rejected = runCatching {
            insertSnapshot(report, status = "PENDING", reason = "NO_VERIFIED_RAILWAY_LINE_REFERENCE")
        }.isFailure
        check(rejected) { "an unknown routing_status must be rejected" }
    }

    @Test
    fun `every one of the six unclassified reasons is individually accepted`() {
        val settlement = insertSettlement("00001")
        val line = insertLine("1")
        val reasons = listOf(
            "NO_VERIFIED_RAILWAY_LINE_REFERENCE", "RAILWAY_LINE_NOT_SELECTED", "REFERENCE_MISMATCH",
            "RAILWAY_LINE_UNASSIGNED", "RAILWAY_LINE_INACTIVE", "SERVICE_AREA_INACTIVE",
        )
        reasons.forEach { reason ->
            val report = UUID.randomUUID()
            insertReport(id = report, settlementId = settlement, railwayLineId = line)
            insertSnapshot(report, status = "UNCLASSIFIED", reason = reason)
        }
    }

    @Test
    fun `an unknown routing reason string is rejected`() {
        val settlement = insertSettlement("00001")
        val report = UUID.randomUUID()
        insertReport(id = report, settlementId = settlement)
        val rejected = runCatching {
            insertSnapshot(report, status = "UNCLASSIFIED", reason = "MADE_UP_REASON")
        }.isFailure
        check(rejected) { "an unrecognised routing_reason must be rejected" }
    }

    @Test
    fun `a report has at most one routing snapshot`() {
        val settlement = insertSettlement("00001")
        val report = UUID.randomUUID()
        insertReport(id = report, settlementId = settlement)
        insertSnapshot(report, status = "UNCLASSIFIED", reason = "NO_VERIFIED_RAILWAY_LINE_REFERENCE")

        val rejected = runCatching {
            insertSnapshot(report, status = "UNCLASSIFIED", reason = "RAILWAY_LINE_NOT_SELECTED")
        }.isFailure
        check(rejected) { "report_id is the primary key - a second snapshot for the same report must be rejected" }
    }

    @Test
    fun `a snapshot cannot reference a report that does not exist`() {
        val rejected = runCatching {
            insertSnapshot(UUID.randomUUID(), status = "UNCLASSIFIED", reason = "NO_VERIFIED_RAILWAY_LINE_REFERENCE")
        }.isFailure
        check(rejected) { "a dangling report_id reference must be rejected" }
    }

    @Test
    fun `a snapshot cannot resolve a railway line or service area that does not exist`() {
        val settlement = insertSettlement("00001")
        val report = UUID.randomUUID()
        insertReport(id = report, settlementId = settlement)

        check(
            runCatching {
                insertSnapshot(report, status = "UNCLASSIFIED", reason = "RAILWAY_LINE_INACTIVE", resolvedLineId = UUID.randomUUID())
            }.isFailure,
        ) { "a dangling resolved_railway_line_id must be rejected" }

        val report2 = UUID.randomUUID()
        val line = insertLine("1")
        insertReport(id = report2, settlementId = settlement, railwayLineId = line)
        check(
            runCatching {
                insertSnapshot(report2, status = "ROUTED", resolvedLineId = line, areaId = UUID.randomUUID())
            }.isFailure,
        ) { "a dangling service_area_id must be rejected" }
    }
}
