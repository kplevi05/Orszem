package hu.orszembejelento.backend.moderation.domain

/** The actor's role has no authority to call this moderation operation at all - a SERVICE_USER, or restore attempted by a non-SUPER_ADMIN. */
class ModerationForbiddenException : RuntimeException("not permitted to perform this moderation operation")

/** The report already has an open moderation episode - a repeat delete is a real conflict, never a silent no-op (brief §22). */
class ReportAlreadyDeletedException : RuntimeException("this report has already been moderation-deleted")

/** Restore was called on a report with no open moderation episode. */
class ReportNotDeletedException : RuntimeException("this report is not currently moderation-deleted")
