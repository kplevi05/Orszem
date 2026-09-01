package hu.orszem.shared.error

/**
 * A single field-level validation problem, mirrored into `problem+json.fieldErrors`.
 */
data class FieldErrorDetail(
    val field: String,
    val code: String,
    val message: String,
)

/**
 * Domain / application errors that map directly onto a contract error code.
 * Controllers and use cases throw these; the global handler renders them as
 * `application/problem+json`.
 */
open class ApiException(
    val errorCode: ErrorCode,
    detail: String,
    val fieldErrors: List<FieldErrorDetail> = emptyList(),
    cause: Throwable? = null,
) : RuntimeException(detail, cause) {
    val detail: String get() = message ?: errorCode.title
}

class ValidationException(
    detail: String = "Egy vagy több mező érvénytelen.",
    fieldErrors: List<FieldErrorDetail> = emptyList(),
) : ApiException(ErrorCode.VALIDATION_ERROR, detail, fieldErrors)

class EventTypeInvalidException(
    detail: String = "Ismeretlen vagy inaktív eseménytípus.",
) : ApiException(ErrorCode.EVENT_TYPE_INVALID, detail)

class OccurredAtInFutureException(
    detail: String = "Az esemény időpontja nem lehet a jövőben.",
) : ApiException(ErrorCode.OCCURRED_AT_IN_FUTURE, detail)

class ReportIdConflictException(
    detail: String = "A report azonosító már létezik eltérő tartalommal.",
) : ApiException(ErrorCode.REPORT_ID_CONFLICT, detail)

class ReportNotFoundException(
    detail: String = "A kért eset nem található.",
) : ApiException(ErrorCode.REPORT_NOT_FOUND, detail)

class ReportNotAcceptableException(
    detail: String = "Csak NEW állapotú eset fogadható el.",
) : ApiException(ErrorCode.REPORT_NOT_ACCEPTABLE, detail)

class ReportNotArchivableException(
    detail: String = "Csak IN_PROGRESS állapotú eset archiválható.",
) : ApiException(ErrorCode.REPORT_NOT_ARCHIVABLE, detail)

class InvalidCredentialsException(
    detail: String = "Hibás azonosító vagy jelszó.",
) : ApiException(ErrorCode.INVALID_CREDENTIALS, detail)

class RateLimitedException(
    val retryAfterSeconds: Long,
    detail: String = "Túl sok kérés érkezett rövid időn belül.",
) : ApiException(ErrorCode.RATE_LIMITED, detail)
