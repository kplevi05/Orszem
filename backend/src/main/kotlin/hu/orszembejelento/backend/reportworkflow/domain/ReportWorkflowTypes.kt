package hu.orszembejelento.backend.reportworkflow.domain

import hu.orszembejelento.backend.identity.domain.ServiceId
import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.identity.domain.UserStatus
import hu.orszembejelento.backend.reports.domain.ReportStatus
import java.util.UUID

/**
 * The authenticated actor performing a report-workflow operation — mirrors
 * [hu.orszembejelento.backend.usermanagement.domain.ManagementActor] exactly: authentication
 * already implies ACTIVE, so there is no `status` field here (contrast [ReassignTargetCandidate],
 * a third party read fresh from the database and never assumed eligible). [serviceId] is
 * carried only for safe audit metadata (brief §47) — never for any authorisation decision.
 */
data class ReportWorkflowActor(
    val userId: UUID,
    val serviceId: ServiceId,
    val role: UserRole,
    val globalAreaAccess: Boolean,
    val ownActiveAreaIds: Set<UUID>,
)

/**
 * A candidate reassignment target (brief §39/§43), read fresh from the database inside the
 * mutation's own transaction — never trusted from a prior Phase 6 user-list response.
 */
data class ReassignTargetCandidate(
    val userId: UUID,
    val serviceId: ServiceId,
    val role: UserRole,
    val status: UserStatus,
    val globalAreaAccess: Boolean,
    val ownActiveAreaIds: Set<UUID>,
)

/**
 * The authorisation-relevant facts about one report, read from its current stored state —
 * the workflow columns on `reports` plus its (immutable, brief §69) routing snapshot. This
 * is deliberately a narrow, purpose-built view for [ReportWorkflowPolicy], not the full
 * report response DTO built in the infrastructure/api layers.
 */
data class ReportScope(
    val status: ReportStatus,
    val routed: Boolean,
    val serviceAreaId: UUID?,
    val serviceAreaActive: Boolean,
    val assignedUserId: UUID?,
) {
    companion object {
        /**
         * [assignedUserId] defaults to null because an UNCLASSIFIED report was, before the
         * Nationwide KSH Settlement Fallback phase, never assignable at all - every existing
         * caller that predates that phase is passing a report that is genuinely unassigned.
         * A caller resolving a *current* UNCLASSIFIED report now must pass the report's real
         * `assigned_user_id` (see [ReportScopeResolver.resolve][hu.orszembejelento.backend.reportworkflow.application.ReportScopeResolver.resolve]
         * and `ReportWorkflowRow.toScope`) - defaulting it to null unconditionally here would
         * make [ReportWorkflowPolicy.canViewReport]'s own-IN_PROGRESS-claim check
         * (`assignedUserId == actor.userId`) impossible to ever satisfy, hiding a SERVICE_USER's
         * own freshly claimed UNCLASSIFIED report from themselves.
         */
        fun unclassified(status: ReportStatus, assignedUserId: UUID? = null) =
            ReportScope(status, routed = false, serviceAreaId = null, serviceAreaActive = false, assignedUserId = assignedUserId)

        fun routed(status: ReportStatus, serviceAreaId: UUID, serviceAreaActive: Boolean, assignedUserId: UUID?) =
            ReportScope(status, routed = true, serviceAreaId = serviceAreaId, serviceAreaActive = serviceAreaActive, assignedUserId = assignedUserId)
    }
}

/** The NEW-queue age bucket (brief §19/§21) — computed by the backend, never rendered by it (§21: no divider text). */
enum class AgeBucket { RECENT, OLDER }

/**
 * Why an assignment episode ended (brief §4/§6). [MODERATION_DELETED] is Phase 9: a
 * moderation deletion of an IN_PROGRESS report terminates its open episode this way,
 * business ownership history distinct from the security audit trail (Phase 9 brief §7).
 */
enum class AssignmentEndReason { RETURNED, REASSIGNED, ARCHIVED, MODERATION_DELETED }

/**
 * One operational-ownership episode (brief §4/§48) — "who had this report and when", a
 * deliberately different question from an audit event ("who performed this mutation").
 */
data class ReportAssignment(
    val id: UUID,
    val reportId: UUID,
    val assigneeUserId: UUID,
    val assignedByUserId: UUID,
    val assignedAt: java.time.Instant,
    val endedAt: java.time.Instant?,
    val endedByUserId: UUID?,
    val endReason: AssignmentEndReason?,
)

/**
 * The area-authority-relevant facts about one of a user's current open assignment episodes
 * — a narrow read model purpose-built for [AssignmentEligibilityGuard], not the full
 * [ReportAssignment].
 *
 * Before the Nationwide KSH Settlement Fallback phase, [serviceAreaId] was always a real
 * area for a genuinely open episode, because an UNCLASSIFIED report could never be assigned
 * at all (brief §40/§68). That invariant no longer holds unconditionally: with
 * [hu.orszembejelento.backend.common.config.WorkflowFallbackProperties.unclassifiedServiceUserAccessEnabled]
 * on, a SERVICE_USER can hold an open claim on an UNCLASSIFIED report, whose routing
 * snapshot has no `service_area_id` at all — so this is now null exactly for that case.
 * [AssignmentEligibilityGuard] treats a null area as never "outside scope": an UNCLASSIFIED
 * claim's continued validity is never governed by area grants in the first place (it is
 * governed by the actor still being an ACTIVE SERVICE_USER and the fallback flag, neither of
 * which this narrowing-mutation guard is about), so a Phase 6 area/global-access revoke must
 * never be blocked or otherwise altered by one.
 */
data class OpenAssignmentAreaSnapshot(
    val reportId: UUID,
    val serviceAreaId: UUID?,
    val serviceAreaActive: Boolean,
)
