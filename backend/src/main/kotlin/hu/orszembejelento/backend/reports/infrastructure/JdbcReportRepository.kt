package hu.orszembejelento.backend.reports.infrastructure

import hu.orszembejelento.backend.reports.domain.Report
import hu.orszembejelento.backend.reports.domain.ReportRoutingSnapshot
import hu.orszembejelento.backend.reports.domain.ReportStatus
import hu.orszembejelento.backend.reports.domain.RoutingSnapshotStatus
import hu.orszembejelento.backend.routing.domain.UnclassifiedReason
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/** Explicit SQL over `reports` and `report_routing_snapshots` - no JPA, consistent with the rest of the backend. */
@Repository
class JdbcReportRepository(private val jdbc: JdbcClient) {

    fun findByClientSubmissionId(clientSubmissionId: UUID): Report? =
        jdbc.sql("$SELECT_REPORT WHERE client_submission_id = :id")
            .param("id", clientSubmissionId)
            .query(::mapReport)
            .optional()
            .orElse(null)

    fun findByPublicId(publicId: UUID): Report? =
        jdbc.sql("$SELECT_REPORT WHERE public_id = :id")
            .param("id", publicId)
            .query(::mapReport)
            .optional()
            .orElse(null)

    /**
     * Attempts to create the report. Returns `true` if this call created it, `false` if a
     * report with the same `client_submission_id` already existed (`ON CONFLICT DO
     * NOTHING`) - the caller then re-reads the existing row via [findByClientSubmissionId]
     * to decide between an idempotent replay and a conflict. See `SubmitReportUseCase`.
     *
     * The unique index on `client_submission_id`, not this method, is what makes "exactly
     * one report per client submission" true under concurrent, identical or conflicting
     * attempts: a competing `INSERT` that loses this race blocks briefly on the row lock
     * PostgreSQL already takes for the unique constraint, then resolves once this
     * transaction commits or rolls back - never a partial or duplicate row either way.
     */
    fun tryInsert(report: Report): Boolean {
        val inserted = jdbc.sql(
            """
            INSERT INTO reports (
                id, public_id, client_submission_id, public_access_credential_hash,
                occurred_at, submitted_at, train_identifier, settlement_id,
                submitted_railway_line_id, event_type_code, status
            ) VALUES (
                :id, :publicId, :clientSubmissionId, :credentialHash,
                :occurredAt, :submittedAt, :trainIdentifier, :settlementId,
                :railwayLineId, :eventTypeCode, :status
            )
            ON CONFLICT (client_submission_id) DO NOTHING
            """.trimIndent(),
        )
            .param("id", report.id)
            .param("publicId", report.publicId)
            .param("clientSubmissionId", report.clientSubmissionId)
            .param("credentialHash", report.publicAccessCredentialHash)
            .param("occurredAt", timestamp(report.occurredAt))
            .param("submittedAt", timestamp(report.submittedAt))
            .param("trainIdentifier", report.trainIdentifier)
            .param("settlementId", report.settlementId)
            .param("railwayLineId", report.submittedRailwayLineId)
            .param("eventTypeCode", report.eventTypeCode)
            .param("status", report.status.name)
            .update()
        return inserted > 0
    }

    fun insertRoutingSnapshot(snapshot: ReportRoutingSnapshot) {
        jdbc.sql(
            """
            INSERT INTO report_routing_snapshots (
                report_id, routing_status, routing_reason, resolved_railway_line_id,
                service_area_id, reference_dataset_version, routed_at
            ) VALUES (
                :reportId, :status, :reason, :lineId, :areaId, :version, :routedAt
            )
            """.trimIndent(),
        )
            .param("reportId", snapshot.reportId)
            .param("status", snapshot.routingStatus.name)
            .param("reason", snapshot.routingReason?.name)
            .param("lineId", snapshot.resolvedRailwayLineId)
            .param("areaId", snapshot.serviceAreaId)
            .param("version", snapshot.referenceDatasetVersion)
            .param("routedAt", timestamp(snapshot.routedAt))
            .update()
    }

    fun findRoutingSnapshot(reportId: UUID): ReportRoutingSnapshot? =
        jdbc.sql(
            """
            SELECT report_id, routing_status, routing_reason, resolved_railway_line_id,
                   service_area_id, reference_dataset_version, routed_at
              FROM report_routing_snapshots
             WHERE report_id = :id
            """.trimIndent(),
        )
            .param("id", reportId)
            .query(::mapSnapshot)
            .optional()
            .orElse(null)

    private fun mapReport(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = Report(
        id = rs.getObject("id", UUID::class.java),
        publicId = rs.getObject("public_id", UUID::class.java),
        clientSubmissionId = rs.getObject("client_submission_id", UUID::class.java),
        publicAccessCredentialHash = rs.getBytes("public_access_credential_hash"),
        occurredAt = rs.getTimestamp("occurred_at").toInstant(),
        submittedAt = rs.getTimestamp("submitted_at").toInstant(),
        trainIdentifier = rs.getString("train_identifier"),
        settlementId = rs.getObject("settlement_id", UUID::class.java),
        submittedRailwayLineId = rs.getObject("submitted_railway_line_id", UUID::class.java),
        eventTypeCode = rs.getString("event_type_code"),
        status = ReportStatus.valueOf(rs.getString("status")),
    )

    private fun mapSnapshot(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = ReportRoutingSnapshot(
        reportId = rs.getObject("report_id", UUID::class.java),
        routingStatus = RoutingSnapshotStatus.valueOf(rs.getString("routing_status")),
        routingReason = rs.getString("routing_reason")?.let(UnclassifiedReason::valueOf),
        resolvedRailwayLineId = rs.getObject("resolved_railway_line_id", UUID::class.java),
        serviceAreaId = rs.getObject("service_area_id", UUID::class.java),
        referenceDatasetVersion = rs.getString("reference_dataset_version"),
        routedAt = rs.getTimestamp("routed_at").toInstant(),
    )

    private fun timestamp(instant: Instant): java.sql.Timestamp = java.sql.Timestamp.from(instant)

    private companion object {
        const val SELECT_REPORT = """
            SELECT id, public_id, client_submission_id, public_access_credential_hash,
                   occurred_at, submitted_at, train_identifier, settlement_id,
                   submitted_railway_line_id, event_type_code, status
              FROM reports
        """
    }
}
