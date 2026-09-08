package hu.orszembejelento.backend.reports.domain

/** Base type for every way a Public report request can be refused. Each carries a stable [code]. */
sealed class ReportException(val code: String, message: String) : RuntimeException(message)

/**
 * The `X-Orszem-Report-Access` header is missing or does not match the expected shape
 * (`pr_` plus 43 URL-safe base64 characters). Returned only on report **creation** - a
 * malformed credential can never match a stored hash anyway, and rejecting it before any
 * database lookup leaks nothing about whether the `clientSubmissionId` already exists. The
 * lookup path (`GET`) never uses this code - see [ReportNotFoundException].
 */
class InvalidReportAccessCredentialException :
    ReportException("INVALID_REPORT_ACCESS_CREDENTIAL", "the report access credential is missing or malformed")

/** `eventTypeCode` does not name an event type, or names one that is not active. */
class InvalidEventTypeException :
    ReportException("INVALID_EVENT_TYPE", "the event type is unknown or not active")

/** `settlementId` does not name a settlement, or names one that is not active. */
class InvalidSettlementException :
    ReportException("INVALID_SETTLEMENT", "the settlement is unknown or not active")

/** `railwayLineId` was supplied but does not name any railway line. */
class InvalidRailwayLineException :
    ReportException("INVALID_RAILWAY_LINE", "the railway line is unknown")

/** `occurredAt` is further in the future than the configured clock-skew tolerance permits. */
class OccurredAtTooFarInFutureException :
    ReportException("VALIDATION_ERROR", "occurredAt must not be materially in the future")

/**
 * `trainIdentifier`, once trimmed, exceeds the maximum length.
 *
 * A distinct type from the generic bean-validation path so the use case (which normalizes
 * and re-checks the field itself, since blank-to-null normalization is a business rule, not
 * a formatting one) reports it the same way a framework-level validation failure would.
 */
class TrainIdentifierTooLongException(val maxCodePoints: Int) :
    ReportException("VALIDATION_ERROR", "trainIdentifier must be at most $maxCodePoints Unicode code point(s)")

/**
 * `clientSubmissionId` already names a report whose stored business payload or bound
 * access credential does not match this request.
 *
 * Deliberately one generic reason for both mismatches (payload or credential) - see
 * `SubmitReportUseCase` - so a caller cannot use the response to probe which part of a
 * guessed request was "closer" to the original.
 */
class IdempotencyKeyReusedException :
    ReportException("IDEMPOTENCY_KEY_REUSED", "clientSubmissionId was already used with a different payload or credential")

/**
 * A Public report lookup failed, for any of: unknown public report id, missing credential,
 * malformed credential, or wrong credential. Deliberately one outcome for all four - see
 * `GetPublicReportUseCase` - so report existence can never be inferred from which specific
 * reason a lookup failed for.
 */
class ReportNotFoundException :
    ReportException("REPORT_NOT_FOUND", "no report matches the given id and access credential")
