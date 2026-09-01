package hu.orszem.shared.error

import hu.orszem.shared.web.currentCorrelationId
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.NoHandlerFoundException

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(ApiException::class)
    fun handleApiException(ex: ApiException): ResponseEntity<ProblemResponse> {
        val headers = HttpHeaders()
        if (ex is RateLimitedException) {
            headers.add(HttpHeaders.RETRY_AFTER, ex.retryAfterSeconds.coerceAtLeast(1).toString())
        }
        if (ex.errorCode == ErrorCode.INTERNAL_ERROR) {
            log.error("Handled internal error [{}]", currentCorrelationId(), ex)
        }
        return problem(ex.errorCode, ex.detail, ex.fieldErrors.map { FieldErrorResponse(it.field, it.code, it.message) }, headers)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleBodyValidation(ex: MethodArgumentNotValidException): ResponseEntity<ProblemResponse> {
        val fieldErrors = ex.bindingResult.fieldErrors.map {
            FieldErrorResponse(it.field, (it.code ?: "INVALID").uppercase(), it.defaultMessage ?: "Érvénytelen érték.")
        }
        return problem(ErrorCode.VALIDATION_ERROR, "Egy vagy több mező érvénytelen.", fieldErrors)
    }

    @ExceptionHandler(HandlerMethodValidationException::class)
    fun handleHandlerValidation(ex: HandlerMethodValidationException): ResponseEntity<ProblemResponse> =
        problem(ErrorCode.VALIDATION_ERROR, "Egy vagy több paraméter érvénytelen.")

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(ex: ConstraintViolationException): ResponseEntity<ProblemResponse> {
        val fieldErrors = ex.constraintViolations.map {
            FieldErrorResponse(it.propertyPath.toString().substringAfterLast('.'), "INVALID", it.message)
        }
        return problem(ErrorCode.VALIDATION_ERROR, "Egy vagy több paraméter érvénytelen.", fieldErrors)
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(ex: HttpMessageNotReadableException): ResponseEntity<ProblemResponse> =
        problem(ErrorCode.VALIDATION_ERROR, "A kérés törzse hibás vagy hiányzik.")

    @ExceptionHandler(MissingServletRequestParameterException::class, MethodArgumentTypeMismatchException::class)
    fun handleBadParam(ex: Exception): ResponseEntity<ProblemResponse> =
        problem(ErrorCode.VALIDATION_ERROR, "Hiányzó vagy érvénytelen kérésparaméter.")

    @ExceptionHandler(NoHandlerFoundException::class)
    fun handleNotFound(ex: NoHandlerFoundException): ResponseEntity<ProblemResponse> =
        problem(ErrorCode.REPORT_NOT_FOUND, "Az erőforrás nem található.")

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ProblemResponse> {
        log.error("Unhandled exception [{}]", currentCorrelationId(), ex)
        return problem(ErrorCode.INTERNAL_ERROR, "Váratlan szerverhiba történt.")
    }

    private fun problem(
        code: ErrorCode,
        detail: String,
        fieldErrors: List<FieldErrorResponse> = emptyList(),
        headers: HttpHeaders = HttpHeaders(),
    ): ResponseEntity<ProblemResponse> {
        val body = ProblemResponse(
            title = code.title,
            status = code.status.value(),
            code = code.name,
            detail = detail,
            correlationId = currentCorrelationId(),
            fieldErrors = fieldErrors.ifEmpty { null },
        )
        return ResponseEntity.status(code.status)
            .headers(headers)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(body)
    }

    companion object {
        val UNAUTHORIZED_STATUS: HttpStatus = HttpStatus.UNAUTHORIZED
    }
}
