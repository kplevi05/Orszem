package hu.orszembejelento.app.report.domain

/**
 * The local lifecycle of one report submission attempt (Phase 5 brief §8).
 *
 * This is client-local bookkeeping, distinct from [PublicReportStatus] (the server's own
 * workflow status, meaningful only once [SUBMITTED]).
 */
enum class SubmissionState {
    /**
     * Locally committed, but this client does not yet have a confirmed successful server
     * receipt. May mean the request never reached the server, reached it but the response
     * was lost, or hit a transient/ambiguous error. The persisted payload is frozen and a
     * retry must resend it byte-for-byte - see [NormalizedReportPayload].
     */
    PENDING,

    /** A server receipt (201, or a recognised idempotent 200 replay) was confirmed. */
    SUBMITTED,

    /**
     * The locally encrypted access credential could not be decrypted (Keystore key gone or
     * invalidated, or the stored blob is corrupt). There is intentionally no recovery
     * endpoint - see the class KDoc on `ReportRepository`.
     */
    ACCESS_LOST,

    /**
     * The server refused a retry with 409 `IDEMPOTENCY_KEY_REUSED` - the same
     * `clientSubmissionId` is already bound to a different payload or credential than what
     * this record holds. Should never happen in a correct client; not auto-repaired.
     */
    CONFLICT,
}

/** The server's Public status vocabulary (ADR 0008 Decision 6) - meaningful only once [SubmissionState.SUBMITTED]. */
enum class PublicReportStatus {
    RECEIVED,
    PROCESSING,
    CLOSED,
}
