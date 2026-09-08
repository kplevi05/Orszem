package hu.orszembejelento.app.report.domain

import java.time.Instant
import java.util.UUID

/**
 * The frozen, canonical request snapshot a retry sends - never reconstructed from mutable
 * UI state (Phase 5 brief §7-8). Equality here is what a retry compares against the value
 * already persisted, so two logically-identical submissions must normalize identically.
 */
data class NormalizedReportPayload(
    val occurredAt: Instant,
    val trainIdentifier: String?,
    val settlementId: UUID,
    val railwayLineId: UUID?,
    val eventTypeCode: String,
)

/** Raw, human-entered form values before normalization. */
data class ReportDraft(
    val occurredAt: Instant,
    val trainIdentifierInput: String?,
    val settlementId: UUID,
    val railwayLineId: UUID?,
    val eventTypeCode: String,
)

/**
 * Trims and blank-normalizes [ReportDraft.trainIdentifierInput], mirroring exactly what the
 * backend's own `SubmitReportUseCase.normalize` does - so what this client persists and
 * later compares against a server replay is the same value the server itself computes.
 */
fun ReportDraft.normalize(): NormalizedReportPayload = NormalizedReportPayload(
    occurredAt = occurredAt,
    trainIdentifier = trainIdentifierInput?.trim()?.ifEmpty { null },
    settlementId = settlementId,
    railwayLineId = railwayLineId,
    eventTypeCode = eventTypeCode,
)
