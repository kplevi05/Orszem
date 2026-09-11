package hu.orszembejelento.service.reports.domain

/**
 * Which workflow actions a report-detail screen should offer, purely as a function of
 * (status, role, whether the actor is the current assignee) - brief §20-21/§30/§33/§38.
 *
 * A UI convenience only: the backend independently re-authorises every one of these on the
 * actual mutation call (brief §33/§77), so a wrong/stale answer here can only ever hide or
 * show a button, never grant real authority. Kept as a pure function specifically so it is
 * unit-testable without Compose.
 */
data class WorkflowActionAvailability(
    val claim: Boolean = false,
    val returnToNew: Boolean = false,
    val close: Boolean = false,
    val reassign: Boolean = false,
)

fun availableWorkflowActions(status: String, role: String, isOwnAssignment: Boolean): WorkflowActionAvailability {
    if (status == "ARCHIVED") return WorkflowActionAvailability() // terminal (brief §32)

    val isSupervisor = role == "MODERATOR" || role == "SUPER_ADMIN"

    return when (status) {
        "NEW" -> when {
            role == "SERVICE_USER" -> WorkflowActionAvailability(claim = true)
            isSupervisor -> WorkflowActionAvailability(close = true) // supervisor closes NEW directly, never claims (brief §21)
            else -> WorkflowActionAvailability()
        }
        "IN_PROGRESS" -> when {
            role == "SERVICE_USER" && isOwnAssignment -> WorkflowActionAvailability(returnToNew = true, close = true)
            isSupervisor -> WorkflowActionAvailability(returnToNew = true, close = true, reassign = true)
            else -> WorkflowActionAvailability() // e.g. a SERVICE_USER viewing another's IN_PROGRESS should not normally be reachable at all
        }
        else -> WorkflowActionAvailability()
    }
}

/**
 * Whether the normal report-detail screen should offer the moderation-delete action (Phase 9
 * brief §2/§38) - MODERATOR/SUPER_ADMIN only, independent of the report's current status
 * (deletion is available from NEW, IN_PROGRESS and ARCHIVED alike, unlike the ordinary
 * workflow actions above which go silent once a report is ARCHIVED). A UI convenience only -
 * the backend independently re-authorises by area/UNCLASSIFIED scope on the actual call.
 */
fun canModerationDelete(role: String): Boolean = role == "MODERATOR" || role == "SUPER_ADMIN"
