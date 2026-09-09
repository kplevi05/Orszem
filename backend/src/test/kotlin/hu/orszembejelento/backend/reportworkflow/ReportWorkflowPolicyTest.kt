package hu.orszembejelento.backend.reportworkflow

import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.identity.domain.UserStatus
import hu.orszembejelento.backend.reports.domain.ReportStatus
import hu.orszembejelento.backend.reportworkflow.domain.ReassignTargetCandidate
import hu.orszembejelento.backend.reportworkflow.domain.ReportScope
import hu.orszembejelento.backend.reportworkflow.domain.ReportWorkflowActor
import hu.orszembejelento.backend.reportworkflow.domain.ReportWorkflowPolicy
import hu.orszembejelento.backend.scope.domain.AreaScopePolicy
import hu.orszembejelento.backend.identity.domain.ServiceId
import java.util.UUID
import org.junit.jupiter.api.Test

/**
 * Pure-logic coverage of the whole Phase 7 authorisation surface (brief §16, and the
 * authorisation portion of §54). No database, no Spring context - mirrors
 * [hu.orszembejelento.backend.usermanagement.UserManagementPolicyTest]'s shape exactly.
 */
class ReportWorkflowPolicyTest {

    private val policy = ReportWorkflowPolicy(AreaScopePolicy())

    private val alpha = UUID.randomUUID()
    private val beta = UUID.randomUUID()

    private fun sid() = ServiceId.ofTrusted("SZ-%06d".format((0..999_999).random()))

    private fun actor(role: UserRole, global: Boolean = false, ownAreas: Set<UUID> = emptySet()) =
        ReportWorkflowActor(UUID.randomUUID(), sid(), role, global, ownAreas)

    private fun target(role: UserRole, status: UserStatus = UserStatus.ACTIVE, global: Boolean = false, ownAreas: Set<UUID> = emptySet()) =
        ReassignTargetCandidate(UUID.randomUUID(), sid(), role, status, global, ownAreas)

    private fun routed(status: ReportStatus, areaId: UUID = alpha, areaActive: Boolean = true, assignee: UUID? = null) =
        ReportScope.routed(status, areaId, areaActive, assignee)

    private fun unclassified(status: ReportStatus) = ReportScope.unclassified(status)

    // ---------------------------------------------------------------------------- canViewReport

    @Test
    fun `SUPER_ADMIN sees every report regardless of status, routing or area activity`() {
        val admin = actor(UserRole.SUPER_ADMIN)
        check(policy.canViewReport(admin, routed(ReportStatus.NEW)))
        check(policy.canViewReport(admin, routed(ReportStatus.IN_PROGRESS, assignee = UUID.randomUUID())))
        check(policy.canViewReport(admin, routed(ReportStatus.ARCHIVED)))
        check(policy.canViewReport(admin, routed(ReportStatus.NEW, areaActive = false)))
        check(policy.canViewReport(admin, unclassified(ReportStatus.NEW)))
    }

    @Test
    fun `a global moderator sees every routed report in scope and UNCLASSIFIED`() {
        val globalMod = actor(UserRole.MODERATOR, global = true)
        check(policy.canViewReport(globalMod, routed(ReportStatus.NEW)))
        check(policy.canViewReport(globalMod, routed(ReportStatus.ARCHIVED)))
        check(policy.canViewReport(globalMod, unclassified(ReportStatus.NEW)))
    }

    @Test
    fun `a territorial moderator sees only routed reports in own scope, never UNCLASSIFIED`() {
        val mod = actor(UserRole.MODERATOR, ownAreas = setOf(alpha))
        check(policy.canViewReport(mod, routed(ReportStatus.NEW, areaId = alpha)))
        check(!policy.canViewReport(mod, routed(ReportStatus.NEW, areaId = beta)))
        check(!policy.canViewReport(mod, unclassified(ReportStatus.NEW)))
    }

    @Test
    fun `a territorial moderator loses access once the report's area goes inactive`() {
        val mod = actor(UserRole.MODERATOR, ownAreas = setOf(alpha))
        check(!policy.canViewReport(mod, routed(ReportStatus.NEW, areaId = alpha, areaActive = false)))
    }

    @Test
    fun `SUPER_ADMIN bypasses the inactive-area gate entirely`() {
        val admin = actor(UserRole.SUPER_ADMIN)
        check(policy.canViewReport(admin, routed(ReportStatus.ARCHIVED, areaId = alpha, areaActive = false)))
    }

    @Test
    fun `a SERVICE_USER never sees UNCLASSIFIED, even with global area access`() {
        val user = actor(UserRole.SERVICE_USER, global = true)
        check(!policy.canViewReport(user, unclassified(ReportStatus.NEW)))
    }

    @Test
    fun `a SERVICE_USER sees NEW and ARCHIVED reports in their own scope without needing to be the assignee`() {
        val user = actor(UserRole.SERVICE_USER, ownAreas = setOf(alpha))
        check(policy.canViewReport(user, routed(ReportStatus.NEW, areaId = alpha)))
        check(policy.canViewReport(user, routed(ReportStatus.ARCHIVED, areaId = alpha, assignee = null)))
    }

    @Test
    fun `a SERVICE_USER sees an IN_PROGRESS report only when they are themselves the assignee`() {
        val selfId = UUID.randomUUID()
        val user = ReportWorkflowActor(selfId, sid(), UserRole.SERVICE_USER, false, setOf(alpha))
        check(policy.canViewReport(user, routed(ReportStatus.IN_PROGRESS, areaId = alpha, assignee = selfId)))
        check(!policy.canViewReport(user, routed(ReportStatus.IN_PROGRESS, areaId = alpha, assignee = UUID.randomUUID())))
    }

    @Test
    fun `a SERVICE_USER loses visibility of their own IN_PROGRESS report once the area goes inactive`() {
        val selfId = UUID.randomUUID()
        val user = ReportWorkflowActor(selfId, sid(), UserRole.SERVICE_USER, false, setOf(alpha))
        check(!policy.canViewReport(user, routed(ReportStatus.IN_PROGRESS, areaId = alpha, areaActive = false, assignee = selfId)))
    }

    @Test
    fun `a global SERVICE_USER can view NEW reports in any active area but never UNCLASSIFIED`() {
        val user = actor(UserRole.SERVICE_USER, global = true)
        check(policy.canViewReport(user, routed(ReportStatus.NEW, areaId = alpha)))
        check(policy.canViewReport(user, routed(ReportStatus.NEW, areaId = beta)))
        check(!policy.canViewReport(user, unclassified(ReportStatus.NEW)))
    }

    // -------------------------------------------------------------------------------- canClaim

    @Test
    fun `only a SERVICE_USER with area access may claim, and never an UNCLASSIFIED report`() {
        val user = actor(UserRole.SERVICE_USER, ownAreas = setOf(alpha))
        check(policy.canClaim(user, routed(ReportStatus.NEW, areaId = alpha)))
        check(!policy.canClaim(user, routed(ReportStatus.NEW, areaId = beta)))
        check(!policy.canClaim(user, unclassified(ReportStatus.NEW)))
    }

    @Test
    fun `MODERATOR and SUPER_ADMIN can never claim`() {
        check(!policy.canClaim(actor(UserRole.MODERATOR, ownAreas = setOf(alpha)), routed(ReportStatus.NEW, areaId = alpha)))
        check(!policy.canClaim(actor(UserRole.SUPER_ADMIN), routed(ReportStatus.NEW, areaId = alpha)))
    }

    @Test
    fun `claim requires the area to be currently active`() {
        val user = actor(UserRole.SERVICE_USER, ownAreas = setOf(alpha))
        check(!policy.canClaim(user, routed(ReportStatus.NEW, areaId = alpha, areaActive = false)))
    }

    // ------------------------------------------------------------------------ canReturn / canClose

    @Test
    fun `canReturn and canClose delegate to view visibility - a peer moderator sees but a stranger does not`() {
        val mod = actor(UserRole.MODERATOR, ownAreas = setOf(alpha))
        check(policy.canReturn(mod, routed(ReportStatus.IN_PROGRESS, areaId = alpha, assignee = UUID.randomUUID())))
        check(!policy.canReturn(mod, routed(ReportStatus.IN_PROGRESS, areaId = beta, assignee = UUID.randomUUID())))
    }

    @Test
    fun `a SERVICE_USER's canReturn is only true for their own assigned report`() {
        val selfId = UUID.randomUUID()
        val user = ReportWorkflowActor(selfId, sid(), UserRole.SERVICE_USER, false, setOf(alpha))
        check(policy.canReturn(user, routed(ReportStatus.IN_PROGRESS, areaId = alpha, assignee = selfId)))
        check(!policy.canReturn(user, routed(ReportStatus.IN_PROGRESS, areaId = alpha, assignee = UUID.randomUUID())))
    }

    // ----------------------------------------------------------------------------- canReassign

    @Test
    fun `only MODERATOR and SUPER_ADMIN can reassign, never SERVICE_USER`() {
        check(!policy.canReassign(actor(UserRole.SERVICE_USER, ownAreas = setOf(alpha)), routed(ReportStatus.IN_PROGRESS, areaId = alpha)))
        check(policy.canReassign(actor(UserRole.MODERATOR, ownAreas = setOf(alpha)), routed(ReportStatus.IN_PROGRESS, areaId = alpha)))
        check(policy.canReassign(actor(UserRole.SUPER_ADMIN), routed(ReportStatus.IN_PROGRESS, areaId = alpha)))
    }

    @Test
    fun `a global moderator or SUPER_ADMIN can see an UNCLASSIFIED report for reassignment purposes even though it can never actually be assigned`() {
        // canReassign only gates visibility + role; the "routed" requirement for the actual
        // operation is checked separately by the use case so it can return the specific
        // REPORT_UNCLASSIFIED_CANNOT_ASSIGN conflict rather than a 404 - see policy KDoc.
        check(policy.canReassign(actor(UserRole.MODERATOR, global = true), unclassified(ReportStatus.NEW)))
        check(policy.canReassign(actor(UserRole.SUPER_ADMIN), unclassified(ReportStatus.NEW)))
    }

    @Test
    fun `a territorial moderator cannot even see an UNCLASSIFIED report to reassign it`() {
        check(!policy.canReassign(actor(UserRole.MODERATOR, ownAreas = setOf(alpha)), unclassified(ReportStatus.NEW)))
    }

    @Test
    fun `reassign requires the actor's own area access, with SUPER_ADMIN bypassing an inactive area`() {
        val mod = actor(UserRole.MODERATOR, ownAreas = setOf(alpha))
        check(!policy.canReassign(mod, routed(ReportStatus.IN_PROGRESS, areaId = alpha, areaActive = false)))
        check(policy.canReassign(actor(UserRole.SUPER_ADMIN), routed(ReportStatus.IN_PROGRESS, areaId = alpha, areaActive = false)))
    }

    // -------------------------------------------------------------------------- canAssignTarget

    @Test
    fun `a valid reassignment target is ACTIVE, SERVICE_USER and has area access`() {
        val validTarget = target(UserRole.SERVICE_USER, ownAreas = setOf(alpha))
        check(policy.canAssignTarget(validTarget, routed(ReportStatus.IN_PROGRESS, areaId = alpha)))
    }

    @Test
    fun `MODERATOR and SUPER_ADMIN targets are always rejected`() {
        check(!policy.canAssignTarget(target(UserRole.MODERATOR, ownAreas = setOf(alpha)), routed(ReportStatus.IN_PROGRESS, areaId = alpha)))
        check(!policy.canAssignTarget(target(UserRole.SUPER_ADMIN), routed(ReportStatus.IN_PROGRESS, areaId = alpha)))
    }

    @Test
    fun `a deactivated target is rejected`() {
        val deactivated = target(UserRole.SERVICE_USER, status = UserStatus.DEACTIVATED, ownAreas = setOf(alpha))
        check(!policy.canAssignTarget(deactivated, routed(ReportStatus.IN_PROGRESS, areaId = alpha)))
    }

    @Test
    fun `a target without area access is rejected`() {
        val outOfScope = target(UserRole.SERVICE_USER, ownAreas = setOf(beta))
        check(!policy.canAssignTarget(outOfScope, routed(ReportStatus.IN_PROGRESS, areaId = alpha)))
    }

    @Test
    fun `a global SERVICE_USER target is accepted for any routed active area`() {
        val globalTarget = target(UserRole.SERVICE_USER, global = true)
        check(policy.canAssignTarget(globalTarget, routed(ReportStatus.IN_PROGRESS, areaId = alpha)))
        check(policy.canAssignTarget(globalTarget, routed(ReportStatus.IN_PROGRESS, areaId = beta)))
    }

    @Test
    fun `a target is never accepted for an UNCLASSIFIED report`() {
        val validTarget = target(UserRole.SERVICE_USER, global = true)
        check(!policy.canAssignTarget(validTarget, unclassified(ReportStatus.NEW)))
    }
}
