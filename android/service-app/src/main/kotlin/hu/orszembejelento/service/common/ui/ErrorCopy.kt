package hu.orszembejelento.service.common.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import hu.orszembejelento.service.R
import hu.orszembejelento.service.common.data.ApiResult

/**
 * Maps the server's stable error [code] (brief §78) to Hungarian production copy.
 *
 * Never shows the raw code, never dumps message/exception text - a caller that switches on
 * one specific code (e.g. to offer a "view in-progress work" action for
 * `USER_HAS_ACTIVE_REPORT_ASSIGNMENTS`, brief §61) should still fall back to this for the
 * message itself, so the copy stays centralized in one place.
 */
@StringRes
fun errorMessageRes(code: String?): Int = when (code) {
    "REPORT_ALREADY_ASSIGNED" -> R.string.error_report_already_assigned
    "REPORT_STATE_CHANGED" -> R.string.error_report_state_changed
    "REPORT_ALREADY_ARCHIVED" -> R.string.error_report_already_archived
    "REPORT_NOT_FOUND" -> R.string.error_report_not_found
    "REPORT_UNCLASSIFIED_CANNOT_ASSIGN" -> R.string.error_report_unclassified_cannot_assign
    "INVALID_ASSIGNEE" -> R.string.error_invalid_assignee
    "REPORT_WORKFLOW_FORBIDDEN" -> R.string.error_report_workflow_forbidden
    "USER_HAS_ACTIVE_REPORT_ASSIGNMENTS" -> R.string.error_user_has_active_assignments
    "USER_REQUIRES_SERVICE_AREA" -> R.string.error_user_requires_service_area
    "USER_NOT_FOUND" -> R.string.error_user_not_found
    "USER_NOT_MANAGEABLE" -> R.string.error_user_not_manageable
    "USER_MANAGEMENT_FORBIDDEN" -> R.string.error_user_management_forbidden
    "GLOBAL_ACCESS_NOT_ALLOWED" -> R.string.error_global_access_not_allowed
    "AREA_NOT_FOUND" -> R.string.error_area_not_found
    "AREA_NOT_ASSIGNABLE" -> R.string.error_area_not_assignable
    "INVALID_ROLE_TRANSITION" -> R.string.error_invalid_role_transition
    "VALIDATION_ERROR" -> R.string.error_validation
    "RATE_LIMITED" -> R.string.error_rate_limited
    "SESSION_INVALID" -> R.string.error_session_expired
    "MODERATION_FORBIDDEN" -> R.string.error_moderation_forbidden
    "REPORT_ALREADY_DELETED" -> R.string.error_report_already_deleted
    "REPORT_NOT_DELETED" -> R.string.error_report_not_deleted
    // Phase 10 - service area administration. A fresh, dedicated set of codes (brief §40) -
    // never reused from AREA_NOT_FOUND/AREA_NOT_ASSIGNABLE above, which answer a different
    // question (a user-grant target) for a different actor.
    "SERVICE_AREA_ADMIN_FORBIDDEN" -> R.string.error_service_area_admin_forbidden
    "SERVICE_AREA_NOT_FOUND" -> R.string.error_service_area_not_found
    "SERVICE_AREA_STATE_CHANGED" -> R.string.error_service_area_state_changed
    "SERVICE_AREA_ALREADY_ACTIVE" -> R.string.error_service_area_already_active
    "SERVICE_AREA_ALREADY_INACTIVE" -> R.string.error_service_area_already_inactive
    "SERVICE_AREA_HAS_RAILWAY_LINES" -> R.string.error_service_area_has_railway_lines
    "SERVICE_AREA_HAS_OPEN_REPORTS" -> R.string.error_service_area_has_open_reports
    "SERVICE_AREA_NAME_INVALID" -> R.string.error_service_area_name_invalid
    "SERVICE_AREA_NAME_ALREADY_IN_USE" -> R.string.error_service_area_name_already_in_use
    "RAILWAY_LINE_NOT_FOUND" -> R.string.error_railway_line_not_found
    "RAILWAY_LINE_INACTIVE" -> R.string.error_railway_line_inactive
    "TARGET_SERVICE_AREA_INACTIVE" -> R.string.error_target_service_area_inactive
    "RAILWAY_LINE_ASSIGNMENT_CHANGED" -> R.string.error_railway_line_assignment_changed
    "RAILWAY_LINE_ALREADY_ASSIGNED_TO_AREA" -> R.string.error_railway_line_already_assigned
    else -> R.string.error_unexpected
}

/** The full [ApiResult] version, also covering the non-`Failure` unsuccessful outcomes. */
@Composable
fun apiErrorMessage(result: ApiResult<*>): String = when (result) {
    is ApiResult.Failure -> stringResource(errorMessageRes(result.code))
    ApiResult.NetworkError -> stringResource(R.string.error_network)
    ApiResult.SessionEnded -> stringResource(R.string.error_session_expired)
    is ApiResult.Success -> ""
}
