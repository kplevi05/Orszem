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
 *
 * **Nationwide KSH Settlement Fallback phase — [unclassifiedServiceUserAccessEnabled]:** a
 * second, deliberate carve-out, exactly as narrowly scoped as the SUPER_ADMIN one above. The
 * permanent rule — an ordinary SERVICE_USER never sees or acts on UNCLASSIFIED — still lives
 * entirely in [AreaScopePolicy.canViewUnclassified] and is unchanged; this flag only adds a
 * switchable, temporary SERVICE_USER path on top of it, in [canViewReport], [canClaim] and
 * [canAssignTarget]. MODERATOR/SUPER_ADMIN's own existing UNCLASSIFIED authority is untouched
 * either way. See [hu.orszembejelento.backend.common.config.WorkflowFallbackProperties].
 */
class ReportWorkflowPolicy(
    private val areaScopePolicy: AreaScopePolicy = AreaScopePolicy(),
    private val unclassifiedServiceUserAccessEnabled: Boolean = false,
) {

    /**
     * The general list/detail visibility rule (brief §13-15). SUPER_ADMIN sees everything.
     * A global MODERATOR sees every routed report in scope plus UNCLASSIFIED; a
     * territorial MODERATOR sees every routed report in their own scope, never
     * UNCLASSIFIED. A SERVICE_USER never sees UNCLASSIFIED; for a routed report they see
     * NEW/ARCHIVED within their own scope, but IN_PROGRESS only when they are themselves
     * the current assignee (and only while still within scope) — the one place visibility
     * genuinely depends on the report's current status, not only its area.
     *
     * **Nationwide fallback, when [unclassifiedServiceUserAccessEnabled]:** an ACTIVE
     * SERVICE_USER additionally sees an UNCLASSIFIED report exactly when it is NEW (the
     * nationwide shared pool, regardless of any area grant), when it is IN_PROGRESS and they
     * are themselves the current assignee, or when it is ARCHIVED — nationwide, like NEW,
     * because `close` clears `assigned_user_id` (mirroring a routed report's own close
     * behaviour), so an ownership-only rule here could never let the very SERVICE_USER who
     * just closed a report see their own confirmation. There is no area to narrow ARCHIVED
     * visibility by the way a routed report's own scope does, so nationwide is the only
     * shape that keeps "close it according to the normal workflow" true without inventing a
     * separate, unrequested per-report ownership record. Caught during this phase's own
     * verification: closing an UNCLASSIFIED report under this flag returned 404 to the very
     * actor who had just closed it, before this branch covered ARCHIVED. MODERATOR/SUPER_ADMIN's
     * own UNCLASSIFIED visibility (`canViewUnclassified`) is completely unaffected by this
     * flag either way.
     */
    fun canViewReport(actor: ReportWorkflowActor, report: ReportScope): Boolean {
        if (actor.role == UserRole.SUPER_ADMIN) return true

        if (!report.routed) {
            if (unclassifiedServiceUserAccessEnabled && actor.role == UserRole.SERVICE_USER) {
                return when (report.status) {
                    ReportStatus.NEW, ReportStatus.ARCHIVED -> true
                    ReportStatus.IN_PROGRESS -> report.assignedUserId == actor.userId
                }
            }
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
     * Role + area/routing eligibility to claim (brief §30). Deliberately independent of the
     * report's *current* status: the caller checks NEW/IN_PROGRESS/ARCHIVED separately,
     * because which of those three produces which conflict code (§31/§32/§45) is an
     * HTTP-shaping decision, not an authorisation one.
     *
     * A SERVICE_USER may claim a routed report only within their own area access; an
     * UNCLASSIFIED one exactly when [unclassifiedServiceUserAccessEnabled] (no area check,
     * since UNCLASSIFIED has none).
     *
     * **Administrator self-claim:** a MODERATOR/SUPER_ADMIN may personally claim any report
     * they can currently *see* ([canViewReport]) — a territorial MODERATOR is therefore
     * still bounded by their own area grants exactly like everywhere else in this class, and
     * a global MODERATOR/SUPER_ADMIN may claim anything in their (unbounded) scope,
     * including UNCLASSIFIED. This does not broaden what an admin can *see* — only what they
     * may additionally *do* with a report they could already see — and it does not touch a
     * SERVICE_USER's own claim rule above at all.
     */
    fun canClaim(actor: ReportWorkflowActor, report: ReportScope): Boolean = when (actor.role) {
        UserRole.SERVICE_USER -> if (!report.routed) unclassifiedServiceUserAccessEnabled else roleAccessesArea(actor, report)
        UserRole.MODERATOR, UserRole.SUPER_ADMIN -> canViewReport(actor, report)
    }

    /**
     * Whether reassignment is even conceivable for [report] at all, independent of the
     * target — the gate [ReassignReportUseCase][hu.orszembejelento.backend.reportworkflow.application.ReassignReportUseCase]
     * checks before status/target validation (brief §40): a routed report always is; an
     * UNCLASSIFIED one only when the nationwide fallback is enabled (brief item 6 — a global
     * MODERATOR/SUPER_ADMIN may then reassign it to any ACTIVE SERVICE_USER). With the flag
     * disabled, behaviour is exactly the prior, unconditional refusal.
     */
    fun canAssignAtAll(report: ReportScope): Boolean = report.routed || unclassifiedServiceUserAccessEnabled

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
     *
     * **Nationwide fallback:** for an UNCLASSIFIED report (only reachable at all when
     * [canAssignAtAll] already let the caller past its own gate, i.e. the flag is enabled),
     * any ACTIVE SERVICE_USER is a valid target — there is no derived ServiceArea to check
     * against, and brief item 6 is explicit that area grants do not constrain the choice.
     */
    fun canAssignTarget(target: ReassignTargetCandidate, report: ReportScope): Boolean {
        if (target.status != UserStatus.ACTIVE || target.role != UserRole.SERVICE_USER) return false
        if (!report.routed) return unclassifiedServiceUserAccessEnabled
        return roleAccessesArea(target.userId, target.role, target.globalAreaAccess, target.ownActiveAreaIds, report)
    }

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
