package hu.orszembejelento.backend.moderation

import hu.orszembejelento.backend.identity.domain.ServiceId
import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.moderation.domain.ModerationPolicy
import hu.orszembejelento.backend.reports.domain.ReportStatus
import hu.orszembejelento.backend.reportworkflow.domain.ReportScope
import hu.orszembejelento.backend.reportworkflow.domain.ReportWorkflowActor
import java.util.UUID
import org.junit.jupiter.api.Test

/**
 * Pure-logic coverage of the Phase 9 moderation authorisation surface (brief §2-3/§62) - no
 * database, no Spring context, mirrors [hu.orszembejelento.backend.reportworkflow.ReportWorkflowPolicyTest]'s shape.
 */
class ModerationPolicyTest {

    private val policy = ModerationPolicy()
    private val alpha = UUID.randomUUID()

    private fun sid() = ServiceId.ofTrusted("SZ-%06d".format((0..999_999).random()))
    private fun actor(role: UserRole, global: Boolean = false, ownAreas: Set<UUID> = emptySet()) =
        ReportWorkflowActor(UUID.randomUUID(), sid(), role, global, ownAreas)
    private fun routed(status: ReportStatus = ReportStatus.NEW, areaId: UUID = alpha, areaActive: Boolean = true) =
        ReportScope.routed(status, areaId, areaActive, null)
    private fun unclassified(status: ReportStatus = ReportStatus.NEW) = ReportScope.unclassified(status)

    // ---------------------------------------------------------------------------- canModerate

    @Test
    fun `SERVICE_USER can never moderate, regardless of area or status`() {
        val user = actor(UserRole.SERVICE_USER, ownAreas = setOf(alpha))
        check(!policy.canModerate(user, routed(areaId = alpha)))
        check(!policy.canModerate(user, unclassified()))
    }

    @Test
    fun `a territorial MODERATOR can moderate only within their own active area, never UNCLASSIFIED`() {
        val mod = actor(UserRole.MODERATOR, ownAreas = setOf(alpha))
        check(policy.canModerate(mod, routed(areaId = alpha)))
        check(!policy.canModerate(mod, routed(areaId = UUID.randomUUID())))
        check(!policy.canModerate(mod, routed(areaId = alpha, areaActive = false)))
        check(!policy.canModerate(mod, unclassified()))
    }

    @Test
    fun `a global MODERATOR can moderate any active area and UNCLASSIFIED`() {
        val mod = actor(UserRole.MODERATOR, global = true)
        check(policy.canModerate(mod, routed(areaId = UUID.randomUUID())))
        check(policy.canModerate(mod, unclassified()))
        check(!policy.canModerate(mod, routed(areaId = alpha, areaActive = false)))
    }

    @Test
    fun `SUPER_ADMIN can moderate everywhere, including an inactive area and UNCLASSIFIED`() {
        val admin = actor(UserRole.SUPER_ADMIN)
        check(policy.canModerate(admin, routed(areaId = UUID.randomUUID(), areaActive = false)))
        check(policy.canModerate(admin, unclassified()))
    }

    @Test
    fun `moderation applies uniformly to every workflow status`() {
        val mod = actor(UserRole.MODERATOR, global = true)
        for (status in ReportStatus.entries) {
            check(policy.canModerate(mod, routed(status = status, areaId = UUID.randomUUID())))
        }
    }

    // ------------------------------------------------------------------------ canViewDeleted

    @Test
    fun `deleted-report visibility is identical to canModerate - scope based, not actor-history based`() {
        val mod = actor(UserRole.MODERATOR, ownAreas = setOf(alpha))
        check(policy.canViewDeleted(mod, routed(areaId = alpha)) == policy.canModerate(mod, routed(areaId = alpha)))
        check(!policy.canViewDeleted(actor(UserRole.SERVICE_USER), routed(areaId = alpha)))
    }

    // ---------------------------------------------------------------------------- canRestore

    @Test
    fun `only SUPER_ADMIN may restore`() {
        check(policy.canRestore(actor(UserRole.SUPER_ADMIN)))
        check(!policy.canRestore(actor(UserRole.MODERATOR, global = true)))
        check(!policy.canRestore(actor(UserRole.SERVICE_USER)))
    }
}
