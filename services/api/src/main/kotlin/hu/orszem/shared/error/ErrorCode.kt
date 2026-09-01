package hu.orszem.shared.error

import org.springframework.http.HttpStatus

/**
 * Stable, machine-readable error codes. These are part of the public contract
 * (`contracts/openapi/orszem-v1.yaml`) and must not change meaning.
 */
enum class ErrorCode(val status: HttpStatus, val title: String) {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Validation failed"),
    EVENT_TYPE_INVALID(HttpStatus.BAD_REQUEST, "Invalid event type"),
    OCCURRED_AT_IN_FUTURE(HttpStatus.BAD_REQUEST, "Event time is in the future"),
    REPORT_ID_CONFLICT(HttpStatus.CONFLICT, "Report ID conflict"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Authentication failed"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Unauthorized"),
    REPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "Report not found"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
    REPORT_NOT_ACCEPTABLE(HttpStatus.CONFLICT, "Invalid report state"),
    REPORT_NOT_ARCHIVABLE(HttpStatus.CONFLICT, "Invalid report state"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error"),
}
