package hu.orszembejelento.backend.reportworkflow

import hu.orszembejelento.backend.identity.domain.ServiceId
import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.identity.domain.UserStatus
import hu.orszembejelento.backend.reports.domain.ReportStatus
import hu.orszembejelento.backend.reportworkflow.domain.ReassignTargetCandidate
import hu.orszembejelento.backend.reportworkflow.domain.ReportScope
import hu.orszembejelento.backend.reportworkflow.domain.ReportWorkflowActor
import hu.orszembejelento.backend.reportworkflow.domain.ReportWorkflowPolicy
import hu.orszembejelento.backend.scope.domain.AreaScopePolicy
import java.util.UUID
import org.junit.jupiter.api.Test

/**
 * Pure-logic coverage of the Nationwide KSH Settlement Fallback carve-outs in
 * [ReportWorkflowPolicy] - the `unclassifiedServiceUserAccessEnabled` constructor flag. No
 * database, no Spring context - mirrors [ReportWorkflowPolicyTest]'s shape exactly, but with
 * the flag turned on, so these prove the *additional* permission surface the flag opens.
 * [ReportWorkflowPolicyTest] itself, unmodified and still using the flag's `false` default,
 * is what proves the flag disabled restores the strict prior behaviour byte-for-byte.
 */
class NationwideUnclassifiedFallbackPolicyTest {

    private val enabledPolicy = ReportWorkflowPolicy(AreaScopePolicy(), unclassifiedServiceUserAccessEnabled = true)
    private val disabledPolicy = ReportWorkflowPolicy(AreaScopePolicy(), unclassifiedServiceUserAccessEnabled = false)

    private fun sid() = ServiceId.ofTrusted("SZ-%06d".format((0..999_999).random()))

    private fun actor(role: UserRole, global: Boolean = false, ownAreas: Set<UUID> = emptySet(), userId: UUID = UUID.randomUUID()) =
        ReportWorkflowActor(userId, sid(), role, global, ownAreas)

    private fun target(role: UserRole, status: UserStatus = UserStatus.ACTIVE, global: Boolean = false, ownAreas: Set<UUID> = emptySet()) =
        ReassignTargetCandidate(UUID.randomUUID(), sid(), role, status, global, ownAreas)

    private fun unclassified(status: ReportStatus, assignee: UUID? = null) = ReportScope.unclassified(status, assignee)

    // ---------------------------------------------------------------------------- canViewReport

    @Test
    fun `with the flag enabled, any ACTIVE SERVICE_USER sees a NEW UNCLASSIFIED report regardless of area grants`() {
        val noAreasAtAll = actor(UserRole.SERVICE_USER)
        check(enabledPolicy.canViewReport(noAreasAtAll, unclassified(ReportStatus.NEW)))
    }

    @Test
    fun `with the flag enabled, a SERVICE_USER sees an IN_PROGRESS UNCLASSIFIED report only when they are the assignee`() {
        val selfId = UUID.randomUUID()
        val assignee = actor(UserRole.SERVICE_USER, userId = selfId)
        val stranger = actor(UserRole.SERVICE_USER)
        check(enabledPolicy.canViewReport(assignee, unclassified(ReportStatus.IN_PROGRESS, assignee = selfId)))
        check(!enabledPolicy.canViewReport(stranger, unclassified(ReportStatus.IN_PROGRESS, assignee = selfId)))
    }

    @Test
    fun `with the flag enabled, any ACTIVE SERVICE_USER sees an ARCHIVED UNCLASSIFIED report - nationwide, like NEW`() {
        // Not ownership-restricted: close() clears assigned_user_id (mirrors a routed
        // report's own close behaviour), so an ownership-only rule here would hide the very
        // SERVICE_USER who just closed the report from their own confirmation - caught live
        // during this phase's own verification, see ReportWorkflowPolicy's KDoc.
        val someUser = actor(UserRole.SERVICE_USER)
        check(enabledPolicy.canViewReport(someUser, unclassified(ReportStatus.ARCHIVED, assignee = null)))
    }

    @Test
    fun `with the flag disabled, SERVICE_USER visibility of UNCLASSIFIED is exactly the strict prior behaviour`() {
        val user = actor(UserRole.SERVICE_USER, global = true)
        check(!disabledPolicy.canViewReport(user, unclassified(ReportStatus.NEW)))
        check(!disabledPolicy.canViewReport(user, unclassified(ReportStatus.ARCHIVED)))
    }

    @Test
    fun `the flag never changes MODERATOR or SUPER_ADMIN's own UNCLASSIFIED visibility`() {
        val globalMod = actor(UserRole.MODERATOR, global = true)
        val territorialMod = actor(UserRole.MODERATOR)
        val admin = actor(UserRole.SUPER_ADMIN)
        for (policy in listOf(enabledPolicy, disabledPolicy)) {
            check(policy.canViewReport(globalMod, unclassified(ReportStatus.NEW)))
            check(!policy.canViewReport(territorialMod, unclassified(ReportStatus.NEW)))
            check(policy.canViewReport(admin, unclassified(ReportStatus.NEW)))
        }
    }

    // -------------------------------------------------------------------------------- canClaim

    @Test
    fun `with the flag enabled, any ACTIVE SERVICE_USER may claim an UNCLASSIFIED report, no area check`() {
        val noAreasAtAll = actor(UserRole.SERVICE_USER)
        check(enabledPolicy.canClaim(noAreasAtAll, unclassified(ReportStatus.NEW)))
    }

    @Test
    fun `administrator self-claim of UNCLASSIFIED follows their own UNCLASSIFIED visibility, completely independent of this flag`() {
        // A global MODERATOR/SUPER_ADMIN already sees UNCLASSIFIED unconditionally
        // (canViewUnclassified / SUPER_ADMIN's own blanket rule) - self-claim inherits that
        // exactly, in both flag states. A territorial MODERATOR never sees UNCLASSIFIED at
        // all, flag or no flag, so self-claim stays refused for them too.
        val globalMod = actor(UserRole.MODERATOR, global = true)
        val admin = actor(UserRole.SUPER_ADMIN)
        val territorialMod = actor(UserRole.MODERATOR, ownAreas = setOf(UUID.randomUUID()))
        for (policy in listOf(enabledPolicy, disabledPolicy)) {
            check(policy.canClaim(globalMod, unclassified(ReportStatus.NEW)))
            check(policy.canClaim(admin, unclassified(ReportStatus.NEW)))
            check(!policy.canClaim(territorialMod, unclassified(ReportStatus.NEW)))
        }
    }

    @Test
    fun `with the flag disabled, claiming an UNCLASSIFIED report is exactly the strict prior refusal`() {
        val user = actor(UserRole.SERVICE_USER, global = true)
        check(!disabledPolicy.canClaim(user, unclassified(ReportStatus.NEW)))
    }

    // ----------------------------------------------------------------------------- canAssignAtAll

    @Test
    fun `canAssignAtAll is true for UNCLASSIFIED exactly when the flag is enabled, and always true for routed`() {
        check(enabledPolicy.canAssignAtAll(unclassified(ReportStatus.IN_PROGRESS)))
        check(!disabledPolicy.canAssignAtAll(unclassified(ReportStatus.IN_PROGRESS)))
        check(enabledPolicy.canAssignAtAll(ReportScope.routed(ReportStatus.IN_PROGRESS, UUID.randomUUID(), true, null)))
        check(disabledPolicy.canAssignAtAll(ReportScope.routed(ReportStatus.IN_PROGRESS, UUID.randomUUID(), true, null)))
    }

    // -------------------------------------------------------------------------- canAssignTarget

    @Test
    fun `with the flag enabled, any ACTIVE SERVICE_USER is a valid reassignment target for an UNCLASSIFIED report`() {
        val noAreasAtAll = target(UserRole.SERVICE_USER)
        check(enabledPolicy.canAssignTarget(noAreasAtAll, unclassified(ReportStatus.IN_PROGRESS)))
    }

    @Test
    fun `with the flag enabled, an inactive or non-SERVICE_USER target is still always rejected for UNCLASSIFIED`() {
        val deactivated = target(UserRole.SERVICE_USER, status = UserStatus.DEACTIVATED)
        val moderator = target(UserRole.MODERATOR)
        check(!enabledPolicy.canAssignTarget(deactivated, unclassified(ReportStatus.IN_PROGRESS)))
        check(!enabledPolicy.canAssignTarget(moderator, unclassified(ReportStatus.IN_PROGRESS)))
    }

    @Test
    fun `with the flag disabled, a target is never accepted for an UNCLASSIFIED report - the strict prior refusal`() {
        val validOtherwise = target(UserRole.SERVICE_USER, global = true)
        check(!disabledPolicy.canAssignTarget(validOtherwise, unclassified(ReportStatus.IN_PROGRESS)))
    }
}
