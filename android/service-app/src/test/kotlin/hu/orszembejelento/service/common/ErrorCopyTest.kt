package hu.orszembejelento.service.common

import hu.orszembejelento.service.R
import hu.orszembejelento.service.common.ui.errorMessageRes
import org.junit.Assert.assertEquals
import org.junit.Test

/** brief §78 - every stable backend error code maps to a specific, non-generic Hungarian resource. */
class ErrorCopyTest {

    @Test
    fun `every stable code the brief names maps to its own specific message`() {
        val expected = mapOf(
            "REPORT_ALREADY_ASSIGNED" to R.string.error_report_already_assigned,
            "REPORT_STATE_CHANGED" to R.string.error_report_state_changed,
            "REPORT_ALREADY_ARCHIVED" to R.string.error_report_already_archived,
            "REPORT_NOT_FOUND" to R.string.error_report_not_found,
            "REPORT_UNCLASSIFIED_CANNOT_ASSIGN" to R.string.error_report_unclassified_cannot_assign,
            "INVALID_ASSIGNEE" to R.string.error_invalid_assignee,
            "REPORT_WORKFLOW_FORBIDDEN" to R.string.error_report_workflow_forbidden,
            "USER_HAS_ACTIVE_REPORT_ASSIGNMENTS" to R.string.error_user_has_active_assignments,
            "USER_REQUIRES_SERVICE_AREA" to R.string.error_user_requires_service_area,
            "USER_NOT_FOUND" to R.string.error_user_not_found,
            "USER_NOT_MANAGEABLE" to R.string.error_user_not_manageable,
            "USER_MANAGEMENT_FORBIDDEN" to R.string.error_user_management_forbidden,
            // Phase 9 moderation codes (brief §23).
            "MODERATION_FORBIDDEN" to R.string.error_moderation_forbidden,
            "REPORT_ALREADY_DELETED" to R.string.error_report_already_deleted,
            "REPORT_NOT_DELETED" to R.string.error_report_not_deleted,
            // Phase 10 service-area-administration codes (brief §56-59).
            "SERVICE_AREA_ADMIN_FORBIDDEN" to R.string.error_service_area_admin_forbidden,
            "SERVICE_AREA_NOT_FOUND" to R.string.error_service_area_not_found,
            "SERVICE_AREA_STATE_CHANGED" to R.string.error_service_area_state_changed,
            "SERVICE_AREA_ALREADY_ACTIVE" to R.string.error_service_area_already_active,
            "SERVICE_AREA_ALREADY_INACTIVE" to R.string.error_service_area_already_inactive,
            "SERVICE_AREA_HAS_RAILWAY_LINES" to R.string.error_service_area_has_railway_lines,
            "SERVICE_AREA_HAS_OPEN_REPORTS" to R.string.error_service_area_has_open_reports,
            "SERVICE_AREA_NAME_INVALID" to R.string.error_service_area_name_invalid,
            "SERVICE_AREA_NAME_ALREADY_IN_USE" to R.string.error_service_area_name_already_in_use,
            "RAILWAY_LINE_NOT_FOUND" to R.string.error_railway_line_not_found,
            "RAILWAY_LINE_INACTIVE" to R.string.error_railway_line_inactive,
            "TARGET_SERVICE_AREA_INACTIVE" to R.string.error_target_service_area_inactive,
            "RAILWAY_LINE_ASSIGNMENT_CHANGED" to R.string.error_railway_line_assignment_changed,
            "RAILWAY_LINE_ALREADY_ASSIGNED_TO_AREA" to R.string.error_railway_line_already_assigned,
        )
        expected.forEach { (code, res) -> assertEquals(code, res, errorMessageRes(code)) }
    }

    @Test
    fun `every mapped code produces a distinct message - no silent collapsing to the generic fallback`() {
        val codes = listOf(
            "REPORT_ALREADY_ASSIGNED", "REPORT_STATE_CHANGED", "REPORT_ALREADY_ARCHIVED", "REPORT_NOT_FOUND",
            "REPORT_UNCLASSIFIED_CANNOT_ASSIGN", "INVALID_ASSIGNEE", "REPORT_WORKFLOW_FORBIDDEN",
            "USER_HAS_ACTIVE_REPORT_ASSIGNMENTS", "USER_REQUIRES_SERVICE_AREA", "USER_NOT_FOUND",
            "USER_NOT_MANAGEABLE", "USER_MANAGEMENT_FORBIDDEN",
            "MODERATION_FORBIDDEN", "REPORT_ALREADY_DELETED", "REPORT_NOT_DELETED",
            "SERVICE_AREA_ADMIN_FORBIDDEN", "SERVICE_AREA_NOT_FOUND", "SERVICE_AREA_STATE_CHANGED",
            "SERVICE_AREA_ALREADY_ACTIVE", "SERVICE_AREA_ALREADY_INACTIVE", "SERVICE_AREA_HAS_RAILWAY_LINES",
            "SERVICE_AREA_HAS_OPEN_REPORTS", "SERVICE_AREA_NAME_INVALID", "SERVICE_AREA_NAME_ALREADY_IN_USE",
            "RAILWAY_LINE_NOT_FOUND", "RAILWAY_LINE_INACTIVE", "TARGET_SERVICE_AREA_INACTIVE",
            "RAILWAY_LINE_ASSIGNMENT_CHANGED", "RAILWAY_LINE_ALREADY_ASSIGNED_TO_AREA",
        )
        codes.forEach { code -> org.junit.Assert.assertNotEquals(code, R.string.error_unexpected, errorMessageRes(code)) }
    }

    @Test
    fun `an unknown or null code falls back to the generic message, never a raw code or crash`() {
        assertEquals(R.string.error_unexpected, errorMessageRes(null))
        assertEquals(R.string.error_unexpected, errorMessageRes("SOMETHING_NEW_THE_CLIENT_DOES_NOT_KNOW"))
    }

    @Test
    fun `SESSION_INVALID maps to the session-expired message`() {
        assertEquals(R.string.error_session_expired, errorMessageRes("SESSION_INVALID"))
    }
}
