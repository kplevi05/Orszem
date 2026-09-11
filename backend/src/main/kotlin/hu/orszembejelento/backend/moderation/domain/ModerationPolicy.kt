package hu.orszembejelento.backend.moderation.domain

import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.reportworkflow.domain.ReportScope
import hu.orszembejelento.backend.reportworkflow.domain.ReportWorkflowActor
import hu.orszembejelento.backend.reportworkflow.domain.ReportWorkflowPolicy

/**
 * Who may moderate which report (Phase 9 brief §2-3). Deliberately reuses
 * [ReportWorkflowPolicy.canViewReport] rather than a second area-authority model (brief §3):
 * a territorial MODERATOR may delete/view-deleted exactly the reports currently visible to
 * them; a global MODERATOR additionally reaches UNCLASSIFIED; SUPER_ADMIN reaches every
 * report, including one whose routing snapshot ServiceArea has since gone inactive - the
 * one deliberate SUPER_ADMIN carve-out [ReportWorkflowPolicy] itself already documents.
 *
 * A moderation-deleted report keeps its original, untouched routing snapshot (brief §1: the
 * report is never re-routed), so the same [ReportScope] a not-yet-deleted report would have
 * is exactly what deleted-report visibility is decided against too (brief §21: scope is
 * area-based, never "reports I personally deleted").
 */
class ModerationPolicy(private val workflowPolicy: ReportWorkflowPolicy = ReportWorkflowPolicy()) {

    /** SERVICE_USER never moderates at all (brief §2); everyone else needs to currently see the report. */
    fun canModerate(actor: ReportWorkflowActor, report: ReportScope): Boolean =
        actor.role != UserRole.SERVICE_USER && workflowPolicy.canViewReport(actor, report)

    /** Deleted-report list/detail visibility (brief §3/§21) - identical rule to [canModerate]: everyone but SERVICE_USER, scope-based. */
    fun canViewDeleted(actor: ReportWorkflowActor, report: ReportScope): Boolean = canModerate(actor, report)

    /** Only SUPER_ADMIN may restore (brief §2, FROZEN) - independent of area scope, since a SUPER_ADMIN already sees every report. */
    fun canRestore(actor: ReportWorkflowActor): Boolean = actor.role == UserRole.SUPER_ADMIN
}
