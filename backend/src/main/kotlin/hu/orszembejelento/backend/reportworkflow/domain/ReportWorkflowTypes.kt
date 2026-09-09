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
        fun unclassified(status: ReportStatus) =
            ReportScope(status, routed = false, serviceAreaId = null, serviceAreaActive = false, assignedUserId = null)

        fun routed(status: ReportStatus, serviceAreaId: UUID, serviceAreaActive: Boolean, assignedUserId: UUID?) =
            ReportScope(status, routed = true, serviceAreaId = serviceAreaId, serviceAreaActive = serviceAreaActive, assignedUserId = assignedUserId)
    }
}

/** The NEW-queue age bucket (brief §19/§21) — computed by the backend, never rendered by it (§21: no divider text). */
enum class AgeBucket { RECENT, OLDER }

/** Why an assignment episode ended (brief §4/§6). */
enum class AssignmentEndReason { RETURNED, REASSIGNED, ARCHIVED }

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
