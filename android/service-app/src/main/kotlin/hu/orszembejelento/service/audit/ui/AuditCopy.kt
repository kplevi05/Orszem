package hu.orszembejelento.service.audit.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import hu.orszembejelento.service.R
import hu.orszembejelento.service.audit.data.AuditPeriod
import hu.orszembejelento.service.common.ui.roleLabelRes

/**
 * The single place the frozen backend audit event/target/detail codes (Phase 12 brief §20/§56/
 * §69) are turned into Hungarian for the screen - mirrors [roleLabelRes]/`statusLabelRes`/
 * `moderationReasonLabelRes` exactly. Nothing else in this feature should render a raw
 * `USER_CREATED` / `SERVICE_AREA` / `OLD_ROLE` / … directly.
 *
 * Every mapper here returns nullable ([auditEventTypeLabelRes]/[auditTargetTypeLabelRes]) or
 * falls back to a safe generic ([auditDetailCodeLabelRes]) for a code this build doesn't
 * recognise - the same forward-compatibility stance the backend's own safe projector takes
 * (brief §6/§27): never a crash, never the raw code rendered, always a safe generic instead.
 * [AuditOptionsMappingCoverageTest] (unit) proves every code the *current* backend enum can
 * actually send has a real (non-null) label here - brief §69's mandatory contract test.
 */
@StringRes
internal fun auditPeriodLabelRes(period: AuditPeriod): Int = when (period) {
    AuditPeriod.TODAY -> R.string.analytics_period_today
    AuditPeriod.LAST_7_DAYS -> R.string.analytics_period_last_7_days
    AuditPeriod.LAST_30_DAYS -> R.string.analytics_period_last_30_days
    AuditPeriod.LAST_90_DAYS -> R.string.analytics_period_last_90_days
    AuditPeriod.ALL -> R.string.audit_period_all
}

internal fun auditPeriodChoices(): List<Pair<AuditPeriod, Int>> = AuditPeriod.entries.map { it to auditPeriodLabelRes(it) }

@StringRes
internal fun auditEventTypeLabelRes(code: String): Int? = when (code) {
    "SUPER_ADMIN_CREATED" -> R.string.audit_event_super_admin_created
    "SUPER_ADMIN_PASSWORD_RESET" -> R.string.audit_event_super_admin_password_reset
    "INITIAL_PASSWORD_CHANGED" -> R.string.audit_event_initial_password_changed
    "PASSWORD_CHANGED" -> R.string.audit_event_password_changed
    "SESSION_CREATED" -> R.string.audit_event_session_created
    "SESSION_REVOKED" -> R.string.audit_event_session_revoked
    "LOGOUT_ALL" -> R.string.audit_event_logout_all
    "REFRESH_TOKEN_REUSE_DETECTED" -> R.string.audit_event_refresh_token_reuse_detected
    "REFERENCE_DATASET_IMPORTED" -> R.string.audit_event_reference_dataset_imported
    "USER_CREATED" -> R.string.audit_event_user_created
    "USER_PASSWORD_RESET" -> R.string.audit_event_user_password_reset
    "USER_DEACTIVATED" -> R.string.audit_event_user_deactivated
    "USER_REACTIVATED" -> R.string.audit_event_user_reactivated
    "USER_ROLE_CHANGED" -> R.string.audit_event_user_role_changed
    "USER_AREA_GRANTED" -> R.string.audit_event_user_area_granted
    "USER_AREA_REVOKED" -> R.string.audit_event_user_area_revoked
    "USER_GLOBAL_ACCESS_GRANTED" -> R.string.audit_event_user_global_access_granted
    "USER_GLOBAL_ACCESS_REVOKED" -> R.string.audit_event_user_global_access_revoked
    "REPORT_CLAIMED" -> R.string.audit_event_report_claimed
    "REPORT_RETURNED_TO_NEW" -> R.string.audit_event_report_returned_to_new
    "REPORT_REASSIGNED" -> R.string.audit_event_report_reassigned
    "REPORT_ARCHIVED" -> R.string.audit_event_report_archived
    "REPORT_MODERATION_DELETED" -> R.string.audit_event_report_moderation_deleted
    "REPORT_MODERATION_RESTORED" -> R.string.audit_event_report_moderation_restored
    "SERVICE_AREA_CREATED" -> R.string.audit_event_service_area_created
    "SERVICE_AREA_RENAMED" -> R.string.audit_event_service_area_renamed
    "SERVICE_AREA_ACTIVATED" -> R.string.audit_event_service_area_activated
    "SERVICE_AREA_DEACTIVATED" -> R.string.audit_event_service_area_deactivated
    "RAILWAY_LINE_SERVICE_AREA_ASSIGNED" -> R.string.audit_event_railway_line_assigned
    "RAILWAY_LINE_SERVICE_AREA_MOVED" -> R.string.audit_event_railway_line_moved
    "RAILWAY_LINE_SERVICE_AREA_UNASSIGNED" -> R.string.audit_event_railway_line_unassigned
    else -> null
}

@StringRes
internal fun auditTargetTypeLabelRes(code: String): Int? = when (code) {
    "USER" -> R.string.audit_target_type_user
    "SESSION" -> R.string.audit_target_type_session
    "REFERENCE_DATASET" -> R.string.audit_target_type_reference_dataset
    "REPORT" -> R.string.audit_target_type_report
    "SERVICE_AREA" -> R.string.audit_target_type_service_area
    "RAILWAY_LINE" -> R.string.audit_target_type_railway_line
    else -> null
}

/** Every event type the filter sheet offers, in a fixed, readable order - not the backend's own enum declaration order. */
internal val AUDIT_EVENT_TYPE_ORDER: List<String> = listOf(
    "USER_CREATED", "USER_PASSWORD_RESET", "USER_DEACTIVATED", "USER_REACTIVATED", "USER_ROLE_CHANGED",
    "USER_AREA_GRANTED", "USER_AREA_REVOKED", "USER_GLOBAL_ACCESS_GRANTED", "USER_GLOBAL_ACCESS_REVOKED",
    "REPORT_CLAIMED", "REPORT_RETURNED_TO_NEW", "REPORT_REASSIGNED", "REPORT_ARCHIVED",
    "REPORT_MODERATION_DELETED", "REPORT_MODERATION_RESTORED",
    "SERVICE_AREA_CREATED", "SERVICE_AREA_RENAMED", "SERVICE_AREA_ACTIVATED", "SERVICE_AREA_DEACTIVATED",
    "RAILWAY_LINE_SERVICE_AREA_ASSIGNED", "RAILWAY_LINE_SERVICE_AREA_MOVED", "RAILWAY_LINE_SERVICE_AREA_UNASSIGNED",
    "SUPER_ADMIN_CREATED", "SUPER_ADMIN_PASSWORD_RESET",
    "INITIAL_PASSWORD_CHANGED", "PASSWORD_CHANGED",
    "SESSION_CREATED", "SESSION_REVOKED", "LOGOUT_ALL", "REFRESH_TOKEN_REUSE_DETECTED",
    "REFERENCE_DATASET_IMPORTED",
)

@StringRes
private fun auditDetailCodeLabelRes(code: String): Int? = when (code) {
    "OLD_ROLE" -> R.string.audit_detail_old_role
    "NEW_ROLE" -> R.string.audit_detail_new_role
    "OLD_NAME" -> R.string.audit_detail_old_name
    "NEW_NAME" -> R.string.audit_detail_new_name
    "FROM_AREA" -> R.string.audit_detail_from_area
    "TO_AREA" -> R.string.audit_detail_to_area
    "AREA" -> R.string.audit_detail_area
    "AREAS" -> R.string.audit_detail_areas
    "GLOBAL_ACCESS" -> R.string.audit_detail_global_access
    "FROM_STATUS" -> R.string.audit_detail_from_status
    "TO_STATUS" -> R.string.audit_detail_to_status
    "STATUS_BEFORE_DELETE" -> R.string.audit_detail_status_before_delete
    "RESULTING_STATUS" -> R.string.audit_detail_resulting_status
    "PREVIOUS_ASSIGNEE" -> R.string.audit_detail_previous_assignee
    "NEW_ASSIGNEE" -> R.string.audit_detail_new_assignee
    "REASON" -> R.string.audit_detail_reason
    "REVOCATION_REASON" -> R.string.audit_detail_revocation_reason
    "REVOKED_SESSIONS" -> R.string.audit_detail_revoked_sessions
    "RAILWAY_LINE" -> R.string.audit_detail_railway_line
    "SOURCE" -> R.string.audit_detail_source
    "DATASET_VERSION" -> R.string.audit_detail_dataset_version
    "SETTLEMENTS_IMPORTED" -> R.string.audit_detail_settlements_imported
    "RAILWAY_LINES_IMPORTED" -> R.string.audit_detail_railway_lines_imported
    "MAPPINGS_IMPORTED" -> R.string.audit_detail_mappings_imported
    else -> null
}

@StringRes
private fun revocationReasonLabelRes(reason: String): Int = when (reason) {
    "LOGOUT" -> R.string.audit_revocation_reason_logout
    "LOGOUT_ALL" -> R.string.audit_revocation_reason_logout_all
    "PASSWORD_CHANGED" -> R.string.audit_revocation_reason_password_changed
    "INITIAL_PASSWORD_CHANGED" -> R.string.audit_revocation_reason_initial_password_changed
    "ADMIN_PASSWORD_RESET" -> R.string.audit_revocation_reason_admin_password_reset
    "ADMIN_USER_DEACTIVATED" -> R.string.audit_revocation_reason_admin_user_deactivated
    "REFRESH_TOKEN_REUSE" -> R.string.audit_revocation_reason_refresh_token_reuse
    else -> R.string.audit_revocation_reason_logout
}

@StringRes
private fun sourceLabelRes(source: String): Int = when (source) {
    "MAINTENANCE_CLI" -> R.string.audit_source_maintenance_cli
    else -> R.string.audit_source_maintenance_cli
}

private val STATUS_VALUE_CODES = setOf("FROM_STATUS", "TO_STATUS", "STATUS_BEFORE_DELETE", "RESULTING_STATUS")
private val ROLE_VALUE_CODES = setOf("OLD_ROLE", "NEW_ROLE")

/** A safe display label for one [code], falling back to [R.string.audit_generic_event_title]'s reasoning (never the raw code) if unrecognised. */
@Composable
internal fun auditDetailCodeLabel(code: String): String = auditDetailCodeLabelRes(code)?.let { stringResource(it) } ?: code

/**
 * A [code]'s stored [rawValue], localized (brief §55/§56) - the one place every detail-code
 * VALUE (as opposed to the code itself) is turned into Hungarian. Values already safe as
 * human/admin identifiers (a Service ID, a resolved area/line name, a moderation reason coming
 * back through the existing mapper, a count) pass through unchanged.
 */
@Composable
internal fun auditDetailValueLabel(code: String, rawValue: String): String = when (code) {
    in ROLE_VALUE_CODES -> stringResource(roleLabelRes(rawValue))
    in STATUS_VALUE_CODES -> stringResource(hu.orszembejelento.service.reports.domain.statusLabelRes(rawValue))
    "REASON" -> stringResource(hu.orszembejelento.service.common.ui.moderationReasonLabelRes(rawValue))
    "REVOCATION_REASON" -> stringResource(revocationReasonLabelRes(rawValue))
    "SOURCE" -> stringResource(sourceLabelRes(rawValue))
    "GLOBAL_ACCESS" -> stringResource(if (rawValue == "true") R.string.common_yes else R.string.common_no)
    else -> rawValue
}
