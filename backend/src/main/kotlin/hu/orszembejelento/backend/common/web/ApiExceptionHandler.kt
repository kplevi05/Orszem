package hu.orszembejelento.backend.common.web

import hu.orszembejelento.backend.auth.application.InvalidCredentialsException
import hu.orszembejelento.backend.auth.application.PasswordChangeRequiredException
import hu.orszembejelento.backend.auth.application.RateLimitedException
import hu.orszembejelento.backend.auth.application.SessionInvalidException
import hu.orszembejelento.backend.identity.domain.PasswordPolicyException
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Translates exceptions into the stable API error shape.
 *
 * Every response body here is deliberately vague. Nothing leaks a stack trace, a database
 * message, or any detail of how a token failed to parse — a caller learns only that the
 * request was rejected, plus a correlation ID that lets an operator find the real reason in
 * the server logs.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(InvalidCredentialsException::class)
    fun handleInvalidCredentials(request: HttpServletRequest) =
        error(request, HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_CREDENTIALS, "Invalid credentials.")

    @ExceptionHandler(PasswordChangeRequiredException::class)
    fun handlePasswordChangeRequired(request: HttpServletRequest) = error(
        request,
        HttpStatus.FORBIDDEN,
        ErrorCode.PASSWORD_CHANGE_REQUIRED,
        "The initial password change must be completed before signing in.",
    )

    @ExceptionHandler(SessionInvalidException::class)
    fun handleSessionInvalid(request: HttpServletRequest) =
        error(request, HttpStatus.UNAUTHORIZED, ErrorCode.SESSION_INVALID, "The session is no longer valid.")

    @ExceptionHandler(PasswordPolicyException::class)
    fun handlePasswordPolicy(exception: PasswordPolicyException, request: HttpServletRequest) = error(
        request,
        HttpStatus.BAD_REQUEST,
        ErrorCode.PASSWORD_POLICY_VIOLATION,
        // Naming the violated rule is safe and necessary: it is about the password the
        // caller just chose, and without it they cannot correct the problem.
        when (exception.violation) {
            hu.orszembejelento.backend.identity.domain.PasswordPolicyViolation.TOO_SHORT ->
                "The password must be at least 15 characters long."
            hu.orszembejelento.backend.identity.domain.PasswordPolicyViolation.TOO_LONG ->
                "The password must be at most 128 characters long."
            hu.orszembejelento.backend.identity.domain.PasswordPolicyViolation.COMMON_PASSWORD ->
                "This password is too common. Please choose a different one."
            hu.orszembejelento.backend.identity.domain.PasswordPolicyViolation.SAME_AS_CURRENT ->
                "The new password must differ from the current one."
        },
    )

    @ExceptionHandler(RateLimitedException::class)
    fun handleRateLimited(exception: RateLimitedException, request: HttpServletRequest) =
        error(
            request,
            HttpStatus.TOO_MANY_REQUESTS,
            ErrorCode.RATE_LIMITED,
            "Too many attempts. Please wait before trying again.",
            extraHeaders = mapOf(HttpHeaders.RETRY_AFTER to exception.retryAfterSeconds.toString()),
        )

    @ExceptionHandler(MethodArgumentNotValidException::class, HttpMessageNotReadableException::class)
    fun handleValidation(request: HttpServletRequest) = error(
        request,
        HttpStatus.BAD_REQUEST,
        ErrorCode.VALIDATION_ERROR,
        // No field details: on auth endpoints, naming the offending field could confirm
        // which part of a guess was well-formed. Malformed JSON is reported the same way.
        "The request is invalid.",
    )

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(exception: Exception, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        // Logged in full server-side, never returned to the caller.
        log.error("unhandled exception while processing {} {}", request.method, request.requestURI, exception)
        return error(request, HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "Unexpected error.")
    }

    private fun error(
        request: HttpServletRequest,
        status: HttpStatus,
        code: ErrorCode,
        message: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): ResponseEntity<ErrorResponse> {
        val builder = ResponseEntity.status(status)
            // Error bodies from auth endpoints must not be cached either.
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
        extraHeaders.forEach { (name, value) -> builder.header(name, value) }
        return builder.body(ErrorResponse(code, message, CorrelationIdFilter.current(request)))
    }
}
