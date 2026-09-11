package hu.orszembejelento.service.common.ui

import androidx.annotation.StringRes
import hu.orszembejelento.service.R

/**
 * The single place the frozen backend moderation-reason enum (Phase 9 brief §4-5) is turned
 * into Hungarian for the screen - mirrors [roleLabelRes]/[userStatusLabelRes] exactly.
 * Nothing else in the app should render `SPAM` / `TROLL_OR_FALSE_REPORT` / … directly.
 *
 * Presentation only: the enum strings themselves are still what every request body carries -
 * this never touches the wire.
 */
@StringRes
fun moderationReasonLabelRes(reason: String): Int = when (reason) {
    "SPAM" -> R.string.moderation_reason_spam
    "TROLL_OR_FALSE_REPORT" -> R.string.moderation_reason_troll_or_false_report
    "DUPLICATE" -> R.string.moderation_reason_duplicate
    "INCORRECT" -> R.string.moderation_reason_incorrect
    "IRRELEVANT" -> R.string.moderation_reason_irrelevant
    "OTHER" -> R.string.moderation_reason_other
    // An unknown reason from a newer backend still gets a safe, non-leaking label.
    else -> R.string.moderation_reason_other
}

/** Every selectable reason, in the fixed order the delete dialog presents them (brief §39). */
val MODERATION_REASONS: List<String> = listOf("SPAM", "TROLL_OR_FALSE_REPORT", "DUPLICATE", "INCORRECT", "IRRELEVANT", "OTHER")

/**
 * The restore-confirmation copy, keyed by [DeletedReportDetailResponse.statusBeforeDelete][
 * hu.orszembejelento.service.moderation.data.DeletedReportDetailResponse] (brief §48-49) - a
 * pure mapping, kept separate from [hu.orszembejelento.service.moderation.ui.RestoreConfirmDialog]
 * itself so the exact-copy-per-status rule is unit-testable without Compose.
 */
@StringRes
fun restoreDialogTextRes(statusBeforeDelete: String): Int = when (statusBeforeDelete) {
    "ARCHIVED" -> R.string.restore_dialog_text_archived
    "IN_PROGRESS" -> R.string.restore_dialog_text_in_progress
    // NEW is the common case and also the safe fallback for any unrecognized value - "returns
    // to Új bejelentések" is true of a restored NEW report and never misleading for one this
    // client doesn't otherwise understand.
    else -> R.string.restore_dialog_text_new
}
