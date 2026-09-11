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
