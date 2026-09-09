package hu.orszembejelento.backend.reportworkflow.domain

import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.identity.domain.UserStatus
import hu.orszembejelento.backend.reports.domain.ReportStatus
import hu.orszembejelento.backend.scope.domain.AreaActor
import hu.orszembejelento.backend.scope.domain.AreaScopePolicy
import hu.orszembejelento.backend.scope.domain.AreaSnapshot

/**
 * Who may see and act on which report. A small, explicit business policy (brief §16) —
 * deliberately not a generic workflow/IAM engine. Every area-authority question is
 * delegated to the existing [AreaScopePolicy] rather than reimplemented (brief §11), with
 * exactly one deliberate carve-out: a SUPER_ADMIN's *report* authority does not stop at an
 * area that has since gone INACTIVE (brief §12) — [AreaScopePolicy.canAccessArea] closes an
 * inactive area to everyone, SUPER_ADMIN included, which is correct for Phase 6's *grant a
 * currently-active area* question but not for Phase 7's *handle a report whose historical
 * routing pointed at an area that later retired* question. That single, named difference
 * lives only in [roleAccessesArea] below.
 *
 * Every method takes already-loaded, current server-side state (brief §8: "authorize
 * CURRENT actor against CURRENT report state") — nothing here is ever evaluated against a
 * stale read from before the report's row lock was acquired.
 */
class ReportWorkflowPolicy(private val areaScopePolicy: AreaScopePolicy = AreaScopePolicy()) {

    /**
     * The general list/detail visibility rule (brief §13-15). SUPER_ADMIN sees everything.
     * A global MODERATOR sees every routed report in scope plus UNCLASSIFIED; a
     * territorial MODERATOR sees every routed report in their own scope, never
     * UNCLASSIFIED. A SERVICE_USER never sees UNCLASSIFIED; for a routed report they see
     * NEW/ARCHIVED within their own scope, but IN_PROGRESS only when they are themselves
     * the current assignee (and only while still within scope) — the one place visibility
     * genuinely depends on the report's current status, not only its area.
     */
    fun canViewReport(actor: ReportWorkflowActor, report: ReportScope): Boolean {
        if (actor.role == UserRole.SUPER_ADMIN) return true

        if (!report.routed) {
            return areaScopePolicy.canViewUnclassified(actorAsAreaActor(actor))
        }

        return when (actor.role) {
            UserRole.SUPER_ADMIN -> true
            UserRole.MODERATOR -> roleAccessesArea(actor, report)
            UserRole.SERVICE_USER -> when (report.status) {
                ReportStatus.IN_PROGRESS -> report.assignedUserId == actor.userId && roleAccessesArea(actor, report)
                ReportStatus.NEW, ReportStatus.ARCHIVED -> roleAccessesArea(actor, report)
            }
        }
    }

    /**
     * Role + area/routing eligibility to claim (brief §30) — SERVICE_USER only, a routed
     * report, current area access. Deliberately independent of the report's *current*
     * status: the caller checks NEW/IN_PROGRESS/ARCHIVED separately, because which of
     * those three produces which conflict code (§31/§32/§45) is an HTTP-shaping decision,
     * not an authorisation one.
     */
    fun canClaim(actor: ReportWorkflowActor, report: ReportScope): Boolean =
        actor.role == UserRole.SERVICE_USER && report.routed && roleAccessesArea(actor, report)

    /** Whether [actor] may return this report at all, independent of its current status (checked separately by the caller). */
    fun canReturn(actor: ReportWorkflowActor, report: ReportScope): Boolean = canViewReport(actor, report)

    /** Whether [actor] may close this report at all, independent of its current status (checked separately by the caller). */
    fun canClose(actor: ReportWorkflowActor, report: ReportScope): Boolean = canViewReport(actor, report)

    /**
     * Reassignment is a supervisor-only operation (brief §38) on a report the supervisor
     * can currently see — reuses [canViewReport] rather than the narrower [roleAccessesArea]
     * deliberately: a global MODERATOR/SUPER_ADMIN can *see* an UNCLASSIFIED report (§14/§70)
     * and must get the specific `REPORT_UNCLASSIFIED_CANNOT_ASSIGN` conflict when they try to
     * reassign it, not a scope-hiding 404 — the caller checks `report.routed` (and status,
     * must be IN_PROGRESS) separately, after this visibility gate, for exactly that reason.
     */
    fun canReassign(actor: ReportWorkflowActor, report: ReportScope): Boolean =
        (actor.role == UserRole.MODERATOR || actor.role == UserRole.SUPER_ADMIN) && canViewReport(actor, report)

    /**
     * Whether [target] is a valid reassignment target for a report in [report]'s area
     * (brief §39): ACTIVE, SERVICE_USER, and currently has operational access to that
     * area — via an explicit assignment or global normal-area access, exactly the same
     * rule [AreaScopePolicy] already applies to anyone else.
     */
    fun canAssignTarget(target: ReassignTargetCandidate, report: ReportScope): Boolean =
        target.status == UserStatus.ACTIVE &&
            target.role == UserRole.SERVICE_USER &&
            report.routed &&
            roleAccessesArea(target.userId, target.role, target.globalAreaAccess, target.ownActiveAreaIds, report)

    // ------------------------------------------------------------------------------- private

    private fun roleAccessesArea(actor: ReportWorkflowActor, report: ReportScope): Boolean =
        roleAccessesArea(actor.userId, actor.role, actor.globalAreaAccess, actor.ownActiveAreaIds, report)

    /**
     * Whether a [role] with the given global flag/own-area set may currently act in
     * [report]'s area — [AreaScopePolicy.canAccessArea] for everyone except SUPER_ADMIN,
     * which bypasses the inactive-area gate entirely (see class KDoc, brief §12).
     */
    private fun roleAccessesArea(
        userId: java.util.UUID,
        role: UserRole,
        globalAreaAccess: Boolean,
        ownActiveAreaIds: Set<java.util.UUID>,
        report: ReportScope,
    ): Boolean {
        if (role == UserRole.SUPER_ADMIN) return true
        val areaId = report.serviceAreaId ?: return false
        val areaActor = AreaActor(userId, role, globalAreaAccess, ownActiveAreaIds)
        return areaScopePolicy.canAccessArea(areaActor, AreaSnapshot(areaId, report.serviceAreaActive))
    }

    private fun actorAsAreaActor(actor: ReportWorkflowActor) =
        AreaActor(actor.userId, actor.role, actor.globalAreaAccess, actor.ownActiveAreaIds)
}
