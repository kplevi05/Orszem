/** The frozen, canonical request snapshot a retry sends - never reconstructed from mutable UI state (§7-8). */
export interface NormalizedReportPayload {
  readonly occurredAt: string // ISO-8601 instant text
  readonly trainIdentifier: string | null
  readonly settlementId: string
  readonly railwayLineId: string | null
  readonly eventTypeCode: string
  readonly note?: string | null
}

/** Raw, human-entered form values before normalization. */
export interface ReportDraft {
  readonly occurredAt: Date
  readonly trainIdentifierInput: string | null
  readonly settlementId: string
  readonly railwayLineId: string | null
  readonly eventTypeCode: string
  readonly noteInput?: string | null
}

/**
 * Trims and blank-normalizes `trainIdentifierInput` and `noteInput`, mirroring exactly what
 * the backend's own `SubmitReportUseCase.normalize` does - so what this client persists and
 * later compares against a server replay is the same value the server itself computes.
 */
export function normalizeReportDraft(draft: ReportDraft): NormalizedReportPayload {
  const trimmed = draft.trainIdentifierInput?.trim() ?? ''
  const trimmedNote = draft.noteInput?.trim() ?? ''
  return {
    occurredAt: draft.occurredAt.toISOString(),
    trainIdentifier: trimmed.length > 0 ? trimmed : null,
    settlementId: draft.settlementId,
    railwayLineId: draft.railwayLineId,
    eventTypeCode: draft.eventTypeCode,
    note: trimmedNote.length > 0 ? trimmedNote : null,
  }
}

export function payloadsEqual(a: NormalizedReportPayload, b: NormalizedReportPayload): boolean {
  return (
    new Date(a.occurredAt).getTime() === new Date(b.occurredAt).getTime() &&
    a.trainIdentifier === b.trainIdentifier &&
    a.settlementId === b.settlementId &&
    a.railwayLineId === b.railwayLineId &&
    a.eventTypeCode === b.eventTypeCode &&
    (a.note ?? null) === (b.note ?? null)
  )
}
