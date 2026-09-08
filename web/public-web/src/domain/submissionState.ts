/**
 * The local lifecycle of one report submission attempt (Phase 5 brief §8).
 *
 * Client-local bookkeeping, distinct from `PublicReportStatus` (the server's own workflow
 * status, meaningful only once `SUBMITTED`).
 */
export type SubmissionState = 'PENDING' | 'SUBMITTED' | 'ACCESS_LOST' | 'CONFLICT'

/** The server's Public status vocabulary (ADR 0008 Decision 6) - meaningful only once `SUBMITTED`. */
export type PublicReportStatus = 'RECEIVED' | 'PROCESSING' | 'CLOSED'
