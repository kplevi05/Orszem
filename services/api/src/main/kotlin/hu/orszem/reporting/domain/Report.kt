package hu.orszem.reporting.domain

import java.time.Instant
import java.util.UUID

enum class ReportStatus { NEW, IN_PROGRESS, ARCHIVED }

/** Client-supplied submission for `POST /public/reports` (already whitespace-normalized). */
data class PublicReportSubmission(
    val id: UUID,
    val eventTypeCode: String,
    val trainIdentifier: String,
    val settlement: String,
    val occurredAt: Instant,
)

/** Result of a successful create (or idempotent replay). */
data class CreatedReport(
    val id: UUID,
    val status: ReportStatus,
    val receivedAt: Instant,
    /** true when an identical submission already existed (idempotent replay -> HTTP 200). */
    val idempotentReplay: Boolean,
)

/** The client-controlled business fields of an existing report, for idempotency comparison. */
data class ReportBusinessFields(
    val eventTypeId: UUID,
    val trainIdentifier: String,
    val settlement: String,
    val occurredAt: Instant,
    val receivedAt: Instant,
    val status: ReportStatus,
)
