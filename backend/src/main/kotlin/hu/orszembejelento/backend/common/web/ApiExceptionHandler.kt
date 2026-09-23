package hu.orszembejelento.backend.common.web

import hu.orszembejelento.backend.analytics.domain.AnalyticsAreaNotAvailableException
import hu.orszembejelento.backend.analytics.domain.AnalyticsCategoryNotFoundException
import hu.orszembejelento.backend.analytics.domain.AnalyticsFilterInvalidException
import hu.orszembejelento.backend.analytics.domain.AnalyticsPeriodInvalidException
import hu.orszembejelento.backend.analytics.domain.AnalyticsUnclassifiedForbiddenException
import hu.orszembejelento.backend.areaadmin.domain.RailwayLineAdminInactiveException
import hu.orszembejelento.backend.areaadmin.domain.RailwayLineAdminNotFoundException
import hu.orszembejelento.backend.areaadmin.domain.RailwayLineAlreadyAssignedToAreaException
import hu.orszembejelento.backend.areaadmin.domain.RailwayLineAssignmentChangedException
import hu.orszembejelento.backend.areaadmin.domain.ServiceAreaAdminForbiddenException
import hu.orszembejelento.backend.areaadmin.domain.ServiceAreaAlreadyActiveException
import hu.orszembejelento.backend.areaadmin.domain.ServiceAreaAlreadyInactiveException
import hu.orszembejelento.backend.areaadmin.domain.ServiceAreaHasOpenReportsException
import hu.orszembejelento.backend.areaadmin.domain.ServiceAreaHasRailwayLinesException
import hu.orszembejelento.backend.areaadmin.domain.ServiceAreaNameAlreadyInUseException
import hu.orszembejelento.backend.areaadmin.domain.ServiceAreaNameBlankException
import hu.orszembejelento.backend.areaadmin.domain.ServiceAreaNotFoundException
import hu.orszembejelento.backend.areaadmin.domain.ServiceAreaStateChangedException
import hu.orszembejelento.backend.areaadmin.domain.TargetServiceAreaInactiveException
import hu.orszembejelento.backend.audit.domain.AuditEventNotFoundException
import hu.orszembejelento.backend.audit.domain.AuditEventTypeInvalidException
import hu.orszembejelento.backend.audit.domain.AuditForbiddenException
import hu.orszembejelento.backend.audit.domain.AuditPeriodInvalidException
import hu.orszembejelento.backend.audit.domain.AuditQueryInvalidException
import hu.orszembejelento.backend.audit.domain.AuditTargetTypeInvalidException
import hu.orszembejelento.backend.auth.application.InvalidCredentialsException
import hu.orszembejelento.backend.auth.application.PasswordChangeRequiredException
import hu.orszembejelento.backend.auth.application.RateLimitedException
import hu.orszembejelento.backend.auth.application.SessionInvalidException
import hu.orszembejelento.backend.identity.domain.PasswordPolicyException
import hu.orszembejelento.backend.moderation.domain.ModerationForbiddenException
import hu.orszembejelento.backend.moderation.domain.ReportAlreadyDeletedException
import hu.orszembejelento.backend.moderation.domain.ReportNotDeletedException
import hu.orszembejelento.backend.reference.domain.ReferenceDatasetUnavailableException
import hu.orszembejelento.backend.reference.domain.SettlementQueryTooShortException
import hu.orszembejelento.backend.reports.domain.IdempotencyKeyReusedException
import hu.orszembejelento.backend.reports.domain.InvalidEventTypeException
import hu.orszembejelento.backend.reports.domain.InvalidRailwayLineException
import hu.orszembejelento.backend.reports.domain.InvalidReportAccessCredentialException
import hu.orszembejelento.backend.reports.domain.InvalidSettlementException
import hu.orszembejelento.backend.reports.domain.OccurredAtTooFarInFutureException
import hu.orszembejelento.backend.reports.domain.ReportNotFoundException
import hu.orszembejelento.backend.reports.domain.SubmissionRateLimitedException
import hu.orszembejelento.backend.reports.domain.TrainIdentifierTooLongException
import hu.orszembejelento.backend.reportworkflow.domain.InvalidAssigneeException
import hu.orszembejelento.backend.reportworkflow.domain.ReportAlreadyArchivedException
import hu.orszembejelento.backend.reportworkflow.domain.ReportAlreadyAssignedException
import hu.orszembejelento.backend.reportworkflow.domain.ReportNotVisibleException
import hu.orszembejelento.backend.reportworkflow.domain.ReportStateChangedException
import hu.orszembejelento.backend.reportworkflow.domain.ReportUnclassifiedCannotAssignException
import hu.orszembejelento.backend.reportworkflow.domain.ReportWorkflowForbiddenException
import hu.orszembejelento.backend.usermanagement.domain.AreaNotAssignableException
import hu.orszembejelento.backend.usermanagement.domain.AreaNotFoundException
import hu.orszembejelento.backend.usermanagement.domain.GlobalAccessNotAllowedException
import hu.orszembejelento.backend.usermanagement.domain.InvalidRoleTransitionException
import hu.orszembejelento.backend.usermanagement.domain.UserHasActiveReportAssignmentsException
import hu.orszembejelento.backend.usermanagement.domain.UserManagementForbiddenException
import hu.orszembejelento.backend.usermanagement.domain.UserNotFoundException
import hu.orszembejelento.backend.usermanagement.domain.UserNotManageableException
import hu.orszembejelento.backend.usermanagement.domain.UserRequiresServiceAreaException
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

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

    /**
     * A Public source has created too many reports in a short time (decision B6). Same public
     * contract as the login throttle: 429, `RATE_LIMITED`, `Retry-After`. The body says nothing
     * about any report or credential - only that this caller must wait.
     */
    @ExceptionHandler(SubmissionRateLimitedException::class)
    fun handleSubmissionRateLimited(exception: SubmissionRateLimitedException, request: HttpServletRequest) =
        error(
            request,
            HttpStatus.TOO_MANY_REQUESTS,
            ErrorCode.RATE_LIMITED,
            "Too many reports in a short time. Please wait before trying again.",
            extraHeaders = mapOf(HttpHeaders.RETRY_AFTER to exception.retryAfterSeconds.toString()),
        )

    @ExceptionHandler(
        MethodArgumentNotValidException::class,
        HttpMessageNotReadableException::class,
        MethodArgumentTypeMismatchException::class,
        MissingServletRequestParameterException::class,
    )
    fun handleValidation(request: HttpServletRequest) = error(
        request,
        HttpStatus.BAD_REQUEST,
        ErrorCode.VALIDATION_ERROR,
        // No field details: on auth endpoints, naming the offending field could confirm
        // which part of a guess was well-formed. Malformed JSON is reported the same way.
        // MethodArgumentTypeMismatchException covers a path/query value that fails to
        // convert to its declared type - a malformed {settlementId} UUID, for instance -
        // which would otherwise fall through to the generic 500 handler below.
        // MissingServletRequestParameterException is a required query parameter that was
        // not sent at all: a client mistake, exactly like the malformed value above.
        "The request is invalid.",
    )

    /**
     * The request body's Content-Type is missing or is not one the endpoint consumes (for
     * example a form post, or no Content-Type at all, against a JSON endpoint). A client
     * mistake, so 415 - not the generic 500 it fell into before, which also wrote an ERROR
     * stack trace to the log for every such request from an unauthenticated caller. Reuses
     * VALIDATION_ERROR: the public error-code set is part of the API and is not widened here.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun handleUnsupportedMediaType(request: HttpServletRequest) = error(
        request,
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        ErrorCode.VALIDATION_ERROR,
        "The request content type is not supported.",
    )

    /** The caller asked (Accept) for a representation this API never produces; it only speaks JSON. */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException::class)
    fun handleNotAcceptable(request: HttpServletRequest) = error(
        request,
        HttpStatus.NOT_ACCEPTABLE,
        ErrorCode.VALIDATION_ERROR,
        "This resource is only available as application/json.",
    )

    /**
     * A request path that maps to no handler at all (Phase 13 brief - a routing error must
     * not masquerade as an internal server failure). Since Spring Boot 3.2, an unmapped path
     * surfaces as [org.springframework.web.servlet.resource.NoResourceFoundException] - a
     * genuinely 404-shaped condition by construction (it extends `ErrorResponseException`
     * with `HttpStatus.NOT_FOUND` already attached) - not [Exception] in general. Without
     * this handler it was being caught by [handleUnexpected] below purely because that
     * catch-all is declared on `Exception::class`, which is more general than this one
     * specific type; Spring always prefers the more specific `@ExceptionHandler` within one
     * advice bean, so declaring this handler is enough to take precedence - no change to
     * dispatch configuration, no per-route special-casing.
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException::class)
    fun handleNoResourceFound(request: HttpServletRequest) =
        error(request, HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "No such resource.")

    /**
     * A request whose path matches a real `@RequestMapping` but whose HTTP method does not -
     * e.g. `POST` against a `@GetMapping`-only route (brief §54's audit/analytics case). Same
     * reasoning as [handleNoResourceFound]: [HttpRequestMethodNotSupportedException] is a
     * genuinely 405-shaped condition Spring itself would map correctly if nothing more
     * general caught it first. The `Allow` header is populated from the methods Spring itself
     * already knows are valid for that path, exactly as its own default handling would.
     */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotSupported(
        exception: org.springframework.web.HttpRequestMethodNotSupportedException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        val allow = exception.supportedMethods?.joinToString(", ").orEmpty()
        return error(
            request,
            HttpStatus.METHOD_NOT_ALLOWED,
            ErrorCode.METHOD_NOT_ALLOWED,
            "This HTTP method is not supported for this resource.",
            extraHeaders = if (allow.isNotEmpty()) mapOf(HttpHeaders.ALLOW to allow) else emptyMap(),
        )
    }

    @ExceptionHandler(SettlementQueryTooShortException::class)
    fun handleSettlementQueryTooShort(exception: SettlementQueryTooShortException, request: HttpServletRequest) =
        error(
            request,
            HttpStatus.BAD_REQUEST,
            ErrorCode.VALIDATION_ERROR,
            "query must be at least ${exception.minimumCodePoints} character(s).",
        )

    @ExceptionHandler(ReferenceDatasetUnavailableException::class)
    fun handleReferenceDatasetUnavailable(request: HttpServletRequest) = error(
        request,
        HttpStatus.SERVICE_UNAVAILABLE,
        ErrorCode.REFERENCE_DATASET_UNAVAILABLE,
        "No reference dataset is currently available.",
    )

    @ExceptionHandler(InvalidReportAccessCredentialException::class)
    fun handleInvalidReportAccessCredential(request: HttpServletRequest) = error(
        request,
        HttpStatus.BAD_REQUEST,
        ErrorCode.INVALID_REPORT_ACCESS_CREDENTIAL,
        "The report access credential is missing or malformed.",
    )

    @ExceptionHandler(InvalidEventTypeException::class)
    fun handleInvalidEventType(request: HttpServletRequest) =
        error(request, HttpStatus.BAD_REQUEST, ErrorCode.INVALID_EVENT_TYPE, "The event type is unknown or not active.")

    @ExceptionHandler(InvalidSettlementException::class)
    fun handleInvalidSettlement(request: HttpServletRequest) =
        error(request, HttpStatus.BAD_REQUEST, ErrorCode.INVALID_SETTLEMENT, "The settlement is unknown or not active.")

    @ExceptionHandler(InvalidRailwayLineException::class)
    fun handleInvalidRailwayLine(request: HttpServletRequest) =
        error(request, HttpStatus.BAD_REQUEST, ErrorCode.INVALID_RAILWAY_LINE, "The railway line is unknown.")

    @ExceptionHandler(OccurredAtTooFarInFutureException::class, TrainIdentifierTooLongException::class)
    fun handleReportValidation(exception: RuntimeException, request: HttpServletRequest) =
        error(request, HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, exception.message ?: "The request is invalid.")

    @ExceptionHandler(IdempotencyKeyReusedException::class)
    fun handleIdempotencyKeyReused(request: HttpServletRequest) = error(
        request,
        HttpStatus.CONFLICT,
        ErrorCode.IDEMPOTENCY_KEY_REUSED,
        "clientSubmissionId was already used with a different payload or credential.",
    )

    @ExceptionHandler(ReportNotFoundException::class)
    fun handleReportNotFound(request: HttpServletRequest) =
        error(request, HttpStatus.NOT_FOUND, ErrorCode.REPORT_NOT_FOUND, "No report matches the given id and access credential.")

    // ------------------------------------------------------------ Phase 6 - user management

    @ExceptionHandler(UserNotFoundException::class)
    fun handleUserNotFound(request: HttpServletRequest) =
        error(request, HttpStatus.NOT_FOUND, ErrorCode.USER_NOT_FOUND, "No such user.")

    @ExceptionHandler(UserManagementForbiddenException::class)
    fun handleUserManagementForbidden(request: HttpServletRequest) =
        error(request, HttpStatus.FORBIDDEN, ErrorCode.USER_MANAGEMENT_FORBIDDEN, "Not permitted to perform this operation.")

    @ExceptionHandler(UserNotManageableException::class)
    fun handleUserNotManageable(request: HttpServletRequest) =
        error(request, HttpStatus.FORBIDDEN, ErrorCode.USER_NOT_MANAGEABLE, "This user is not manageable by the current actor.")

    @ExceptionHandler(InvalidRoleTransitionException::class)
    fun handleInvalidRoleTransition(request: HttpServletRequest) =
        error(request, HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ROLE_TRANSITION, "The requested role transition is not allowed.")

    @ExceptionHandler(AreaNotFoundException::class)
    fun handleAreaNotFound(request: HttpServletRequest) =
        error(request, HttpStatus.NOT_FOUND, ErrorCode.AREA_NOT_FOUND, "No such service area.")

    @ExceptionHandler(AreaNotAssignableException::class)
    fun handleAreaNotAssignable(request: HttpServletRequest) =
        error(request, HttpStatus.BAD_REQUEST, ErrorCode.AREA_NOT_ASSIGNABLE, "This service area cannot be assigned by the current actor.")

    @ExceptionHandler(UserRequiresServiceAreaException::class)
    fun handleUserRequiresServiceArea(request: HttpServletRequest) = error(
        request,
        HttpStatus.CONFLICT,
        ErrorCode.USER_REQUIRES_SERVICE_AREA,
        "A moderator may not remove a user's last service area.",
    )

    @ExceptionHandler(GlobalAccessNotAllowedException::class)
    fun handleGlobalAccessNotAllowed(request: HttpServletRequest) =
        error(request, HttpStatus.FORBIDDEN, ErrorCode.GLOBAL_ACCESS_NOT_ALLOWED, "Only SUPER_ADMIN may change global area access.")

    @ExceptionHandler(UserHasActiveReportAssignmentsException::class)
    fun handleUserHasActiveReportAssignments(request: HttpServletRequest) = error(
        request,
        HttpStatus.CONFLICT,
        ErrorCode.USER_HAS_ACTIVE_REPORT_ASSIGNMENTS,
        "This mutation would invalidate an existing open report assignment.",
    )

    // ------------------------------------------------------ Phase 7 - service report workflow

    @ExceptionHandler(ReportNotVisibleException::class)
    fun handleReportNotVisible(request: HttpServletRequest) =
        error(request, HttpStatus.NOT_FOUND, ErrorCode.REPORT_NOT_FOUND, "No such report.")

    @ExceptionHandler(ReportWorkflowForbiddenException::class)
    fun handleReportWorkflowForbidden(request: HttpServletRequest) =
        error(request, HttpStatus.FORBIDDEN, ErrorCode.REPORT_WORKFLOW_FORBIDDEN, "Not permitted to perform this workflow operation.")

    @ExceptionHandler(ReportAlreadyAssignedException::class)
    fun handleReportAlreadyAssigned(request: HttpServletRequest) =
        error(request, HttpStatus.CONFLICT, ErrorCode.REPORT_ALREADY_ASSIGNED, "This report has already been claimed.")

    @ExceptionHandler(ReportStateChangedException::class)
    fun handleReportStateChanged(request: HttpServletRequest) =
        error(request, HttpStatus.CONFLICT, ErrorCode.REPORT_STATE_CHANGED, "The report's workflow state has changed.")

    @ExceptionHandler(ReportAlreadyArchivedException::class)
    fun handleReportAlreadyArchived(request: HttpServletRequest) =
        error(request, HttpStatus.CONFLICT, ErrorCode.REPORT_ALREADY_ARCHIVED, "This report is already archived.")

    @ExceptionHandler(ReportUnclassifiedCannotAssignException::class)
    fun handleReportUnclassifiedCannotAssign(request: HttpServletRequest) = error(
        request,
        HttpStatus.CONFLICT,
        ErrorCode.REPORT_UNCLASSIFIED_CANNOT_ASSIGN,
        "An unclassified report cannot be assigned to a service user.",
    )

    @ExceptionHandler(InvalidAssigneeException::class)
    fun handleInvalidAssignee(request: HttpServletRequest) =
        error(request, HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ASSIGNEE, "The reassignment target is not a valid assignee.")

    // ------------------------------------------------------------------------ Phase 9 - moderation

    @ExceptionHandler(ModerationForbiddenException::class)
    fun handleModerationForbidden(request: HttpServletRequest) =
        error(request, HttpStatus.FORBIDDEN, ErrorCode.MODERATION_FORBIDDEN, "Not permitted to perform this moderation operation.")

    @ExceptionHandler(ReportAlreadyDeletedException::class)
    fun handleReportAlreadyDeleted(request: HttpServletRequest) =
        error(request, HttpStatus.CONFLICT, ErrorCode.REPORT_ALREADY_DELETED, "This report has already been moderation-deleted.")

    @ExceptionHandler(ReportNotDeletedException::class)
    fun handleReportNotDeleted(request: HttpServletRequest) =
        error(request, HttpStatus.CONFLICT, ErrorCode.REPORT_NOT_DELETED, "This report is not currently moderation-deleted.")

    // ------------------------------------------------------ Phase 10 - service area administration

    @ExceptionHandler(ServiceAreaAdminForbiddenException::class)
    fun handleServiceAreaAdminForbidden(request: HttpServletRequest) = error(
        request,
        HttpStatus.FORBIDDEN,
        ErrorCode.SERVICE_AREA_ADMIN_FORBIDDEN,
        "Not permitted to perform this administration operation.",
    )

    @ExceptionHandler(ServiceAreaNotFoundException::class)
    fun handleServiceAreaNotFound(request: HttpServletRequest) =
        error(request, HttpStatus.NOT_FOUND, ErrorCode.SERVICE_AREA_NOT_FOUND, "No such service area.")

    @ExceptionHandler(ServiceAreaStateChangedException::class)
    fun handleServiceAreaStateChanged(request: HttpServletRequest) =
        error(request, HttpStatus.CONFLICT, ErrorCode.SERVICE_AREA_STATE_CHANGED, "The service area's state has changed.")

    @ExceptionHandler(ServiceAreaAlreadyActiveException::class)
    fun handleServiceAreaAlreadyActive(request: HttpServletRequest) =
        error(request, HttpStatus.CONFLICT, ErrorCode.SERVICE_AREA_ALREADY_ACTIVE, "This service area is already active.")

    @ExceptionHandler(ServiceAreaAlreadyInactiveException::class)
    fun handleServiceAreaAlreadyInactive(request: HttpServletRequest) =
        error(request, HttpStatus.CONFLICT, ErrorCode.SERVICE_AREA_ALREADY_INACTIVE, "This service area is already inactive.")

    @ExceptionHandler(ServiceAreaHasRailwayLinesException::class)
    fun handleServiceAreaHasRailwayLines(request: HttpServletRequest) = error(
        request,
        HttpStatus.CONFLICT,
        ErrorCode.SERVICE_AREA_HAS_RAILWAY_LINES,
        "This service area still has railway lines assigned to it.",
    )

    @ExceptionHandler(ServiceAreaHasOpenReportsException::class)
    fun handleServiceAreaHasOpenReports(request: HttpServletRequest) = error(
        request,
        HttpStatus.CONFLICT,
        ErrorCode.SERVICE_AREA_HAS_OPEN_REPORTS,
        "This service area still has open reports assigned to it.",
    )

    @ExceptionHandler(ServiceAreaNameBlankException::class)
    fun handleServiceAreaNameBlank(request: HttpServletRequest) =
        error(request, HttpStatus.BAD_REQUEST, ErrorCode.SERVICE_AREA_NAME_INVALID, "The service area name must not be blank.")

    @ExceptionHandler(ServiceAreaNameAlreadyInUseException::class)
    fun handleServiceAreaNameAlreadyInUse(request: HttpServletRequest) =
        error(request, HttpStatus.CONFLICT, ErrorCode.SERVICE_AREA_NAME_ALREADY_IN_USE, "This service area name is already in use.")

    @ExceptionHandler(RailwayLineAdminNotFoundException::class)
    fun handleRailwayLineAdminNotFound(request: HttpServletRequest) =
        error(request, HttpStatus.NOT_FOUND, ErrorCode.RAILWAY_LINE_NOT_FOUND, "No such railway line.")

    @ExceptionHandler(RailwayLineAdminInactiveException::class)
    fun handleRailwayLineAdminInactive(request: HttpServletRequest) =
        error(request, HttpStatus.CONFLICT, ErrorCode.RAILWAY_LINE_INACTIVE, "This railway line is not active.")

    @ExceptionHandler(TargetServiceAreaInactiveException::class)
    fun handleTargetServiceAreaInactive(request: HttpServletRequest) =
        error(request, HttpStatus.CONFLICT, ErrorCode.TARGET_SERVICE_AREA_INACTIVE, "The target service area is not active.")

    @ExceptionHandler(RailwayLineAssignmentChangedException::class)
    fun handleRailwayLineAssignmentChanged(request: HttpServletRequest) = error(
        request,
        HttpStatus.CONFLICT,
        ErrorCode.RAILWAY_LINE_ASSIGNMENT_CHANGED,
        "This railway line's assignment has changed.",
    )

    @ExceptionHandler(RailwayLineAlreadyAssignedToAreaException::class)
    fun handleRailwayLineAlreadyAssignedToArea(request: HttpServletRequest) = error(
        request,
        HttpStatus.CONFLICT,
        ErrorCode.RAILWAY_LINE_ALREADY_ASSIGNED_TO_AREA,
        "This railway line is already assigned to that service area.",
    )

    @ExceptionHandler(hu.orszembejelento.backend.areaadmin.domain.SettlementLineConfigurationException::class)
    fun handleSettlementLineConfiguration(
        ex: hu.orszembejelento.backend.areaadmin.domain.SettlementLineConfigurationException,
        request: HttpServletRequest,
    ) = error(
        request,
        if (ex.problem == hu.orszembejelento.backend.areaadmin.domain.SettlementLineConfigurationProblem.INVALID_BATCH)
            HttpStatus.BAD_REQUEST else HttpStatus.CONFLICT,
        ErrorCode.valueOf("SETTLEMENT_LINE_${ex.problem.name}"),
        "The settlement-line configuration request cannot be applied. Refresh and review the configuration.",
    )

    // ------------------------------------------------------------------ Phase 11 - analytics

    @ExceptionHandler(AnalyticsAreaNotAvailableException::class)
    fun handleAnalyticsAreaNotAvailable(request: HttpServletRequest) =
        error(request, HttpStatus.NOT_FOUND, ErrorCode.ANALYTICS_AREA_NOT_AVAILABLE, "This service area is not available for analytics.")

    @ExceptionHandler(AnalyticsUnclassifiedForbiddenException::class)
    fun handleAnalyticsUnclassifiedForbidden(request: HttpServletRequest) =
        error(request, HttpStatus.FORBIDDEN, ErrorCode.ANALYTICS_UNCLASSIFIED_FORBIDDEN, "This role may not view unclassified analytics.")

    @ExceptionHandler(AnalyticsPeriodInvalidException::class)
    fun handleAnalyticsPeriodInvalid(request: HttpServletRequest) =
        error(request, HttpStatus.BAD_REQUEST, ErrorCode.ANALYTICS_PERIOD_INVALID, "The requested period is invalid.")

    @ExceptionHandler(AnalyticsCategoryNotFoundException::class)
    fun handleAnalyticsCategoryNotFound(request: HttpServletRequest) =
        error(request, HttpStatus.NOT_FOUND, ErrorCode.ANALYTICS_CATEGORY_NOT_FOUND, "No such category.")

    @ExceptionHandler(AnalyticsFilterInvalidException::class)
    fun handleAnalyticsFilterInvalid(exception: AnalyticsFilterInvalidException, request: HttpServletRequest) =
        error(request, HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, exception.message ?: "The request is invalid.")

    // ------------------------------------------------------------------------------- Phase 12 - audit

    @ExceptionHandler(AuditForbiddenException::class)
    fun handleAuditForbidden(request: HttpServletRequest) =
        error(request, HttpStatus.FORBIDDEN, ErrorCode.AUDIT_FORBIDDEN, "Not permitted to query the audit trail.")

    @ExceptionHandler(AuditEventNotFoundException::class)
    fun handleAuditEventNotFound(request: HttpServletRequest) =
        error(request, HttpStatus.NOT_FOUND, ErrorCode.AUDIT_EVENT_NOT_FOUND, "No such audit event.")

    @ExceptionHandler(AuditPeriodInvalidException::class)
    fun handleAuditPeriodInvalid(request: HttpServletRequest) =
        error(request, HttpStatus.BAD_REQUEST, ErrorCode.AUDIT_PERIOD_INVALID, "The requested period is invalid.")

    @ExceptionHandler(AuditEventTypeInvalidException::class)
    fun handleAuditEventTypeInvalid(request: HttpServletRequest) =
        error(request, HttpStatus.BAD_REQUEST, ErrorCode.AUDIT_EVENT_TYPE_INVALID, "The requested event type is invalid.")

    @ExceptionHandler(AuditTargetTypeInvalidException::class)
    fun handleAuditTargetTypeInvalid(request: HttpServletRequest) =
        error(request, HttpStatus.BAD_REQUEST, ErrorCode.AUDIT_TARGET_TYPE_INVALID, "The requested target type is invalid.")

    @ExceptionHandler(AuditQueryInvalidException::class)
    fun handleAuditQueryInvalid(exception: AuditQueryInvalidException, request: HttpServletRequest) =
        error(request, HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, exception.message ?: "The request is invalid.")

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
            // An error body is always JSON, whatever the caller's Accept header says. Left to
            // content negotiation, an Accept this API cannot satisfy made the error body itself
            // unwritable and bounced the request into the container's error dispatch.
            .contentType(MediaType.APPLICATION_JSON)
            // Error bodies from auth endpoints must not be cached either.
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
        extraHeaders.forEach { (name, value) -> builder.header(name, value) }
        return builder.body(ErrorResponse(code, message, CorrelationIdFilter.current(request)))
    }
}
