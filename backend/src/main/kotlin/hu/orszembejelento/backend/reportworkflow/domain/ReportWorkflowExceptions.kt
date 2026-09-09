package hu.orszembejelento.backend.reportworkflow.domain

/**
 * Returned identically for a nonexistent report and one that exists but is outside the
 * actor's visibility — including a SERVICE_USER probing another user's IN_PROGRESS report,
 * or a territorial actor probing an UNCLASSIFIED or out-of-area report (brief §29/§34/§46).
 * A caller must never be able to distinguish "does not exist" from "not yours to see" by
 * the response alone.
 */
class ReportNotVisibleException : RuntimeException("no such report, or not visible to this actor")

/** The actor's role has no authority to call this workflow operation at all, independent of any specific report. */
class ReportWorkflowForbiddenException : RuntimeException("not permitted to perform this workflow operation")

/** The report is no longer NEW because another actor already claimed it — the specific claim-race loser outcome (brief §31/§32). */
class ReportAlreadyAssignedException : RuntimeException("this report has already been claimed")

/** The report's status or workflow version no longer matches what the caller expected, for a reason other than the two more specific cases below. */
class ReportStateChangedException : RuntimeException("the report's workflow state has changed")

/** Any mutation attempted against a report that is already ARCHIVED — terminal in Phase 7 (brief §2/§45). */
class ReportAlreadyArchivedException : RuntimeException("this report is already archived")

/** An UNCLASSIFIED report cannot be assigned to a SERVICE_USER — there is no authoritative ServiceArea to validate the target against (brief §40/§70). */
class ReportUnclassifiedCannotAssignException : RuntimeException("an unclassified report cannot be assigned")

/** The reassignment target fails eligibility: does not exist, not ACTIVE, not SERVICE_USER, or lacks current area access (brief §39). */
class InvalidAssigneeException : RuntimeException("the reassignment target is not a valid assignee")
