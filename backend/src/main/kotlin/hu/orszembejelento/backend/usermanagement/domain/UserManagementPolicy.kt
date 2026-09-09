package hu.orszembejelento.backend.usermanagement.domain

import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.scope.domain.AreaActor
import hu.orszembejelento.backend.scope.domain.AreaScopePolicy
import hu.orszembejelento.backend.scope.domain.AreaSnapshot

/**
 * Who may see and change whom. A small, explicit business policy — deliberately not a
 * generic IAM/permission framework (Phase 6 brief §3): no permission table, no
 * resource/action registry, no expression evaluator. The rules below are the whole of
 * Phase 6's authorisation surface, and they stay arguable in plain Kotlin.
 *
 * Every method is pure and takes already-loaded, current server-side state — nothing here
 * ever consults anything the client sent about its own authority (Phase 6 brief §33/§34).
 *
 * Whether a specific area sits inside an actor's authority is delegated to the existing
 * [AreaScopePolicy] rather than reimplemented: that class already carries the exact rule
 * ("inactive is closed to everyone", "global means every active area", "otherwise only an
 * assigned area") and this module must not risk quietly drifting from it.
 *
 * ## Visibility vs manageability (§4)
 * Seeing a row and being allowed to change it are different questions. A peer MODERATOR may
 * be visible to another territorial MODERATOR ([canViewTarget] true) while never
 * manageable ([canManageTarget] always false for that pair). Every mutation re-derives
 * [canManageTarget] from current state; a prior read's answer is never trusted.
 */
class UserManagementPolicy(private val areaScopePolicy: AreaScopePolicy = AreaScopePolicy()) {

    // --------------------------------------------------------------------- listing / reads

    /** SERVICE_USER has no user-management authority at all (§2). */
    fun canListUsers(actor: ManagementActor): Boolean = actor.role != UserRole.SERVICE_USER

    /**
     * Whether [actor] may see [target] in a list or detail response.
     *
     * SUPER_ADMIN sees everyone, [target] included even when it is itself a SUPER_ADMIN
     * (§5) — Phase 6 only restricts what may be *mutated*, not what a SUPER_ADMIN may look
     * at. A MODERATOR never sees a SUPER_ADMIN row (§6). A global MODERATOR sees every
     * other non-SUPER_ADMIN user, assigned or not. A territorial MODERATOR sees only a
     * target whose effective scope overlaps their own current active areas.
     */
    fun canViewTarget(actor: ManagementActor, target: ManagedUser): Boolean = when (actor.role) {
        UserRole.SERVICE_USER -> false
        UserRole.SUPER_ADMIN -> true
        UserRole.MODERATOR ->
            if (target.role == UserRole.SUPER_ADMIN) {
                false
            } else if (actor.globalAreaAccess) {
                true
            } else {
                overlapsActorScope(actor, target)
            }
    }

    // ------------------------------------------------------------------------ manageability

    /**
     * Whether [actor] may *change* [target] right now.
     *
     * SUPER_ADMIN may manage any non-SUPER_ADMIN target (§2). A MODERATOR may manage only a
     * SERVICE_USER that is not global and whose every current area assignment — including
     * one to a now-INACTIVE area, which is in nobody's active scope — sits inside the
     * moderator's own current authority. A **territorial** moderator additionally cannot
     * manage a currently-unassigned SERVICE_USER at all (§7); a **global** moderator can,
     * since an empty assignment set trivially satisfies "every assignment in scope" and
     * global reach does not depend on the moderator's own area list.
     */
    fun canManageTarget(actor: ManagementActor, target: ManagedUser): Boolean {
        if (actor.role == UserRole.SERVICE_USER) return false
        if (actor.role == UserRole.SUPER_ADMIN) return target.role != UserRole.SUPER_ADMIN

        // actor.role == MODERATOR
        if (target.role != UserRole.SERVICE_USER) return false
        if (target.globalAreaAccess) return false
        if (!actor.globalAreaAccess && target.assignedAreas.isEmpty()) return false

        return target.assignedAreas.all { area -> areaWithinActorAuthority(actor, area) }
    }

    /** Password reset shares exactly [canManageTarget]'s rule (§19) — no separate carve-out. */
    fun canResetPassword(actor: ManagementActor, target: ManagedUser): Boolean = canManageTarget(actor, target)

    // ------------------------------------------------------------------------------ creation

    /**
     * Whether [actor] may create an account with [requestedRole].
     *
     * SUPER_ADMIN may create SERVICE_USER or MODERATOR, never another SUPER_ADMIN — there
     * is no HTTP path to that role at all (§46). A MODERATOR may create only SERVICE_USER.
     */
    fun canCreateRole(actor: ManagementActor, requestedRole: UserRole): Boolean = when (actor.role) {
        UserRole.SERVICE_USER -> false
        UserRole.SUPER_ADMIN -> requestedRole != UserRole.SUPER_ADMIN
        UserRole.MODERATOR -> requestedRole == UserRole.SERVICE_USER
    }

    // ---------------------------------------------------------------------------- area scope

    /**
     * Whether [actor] may grant this specific area at all — independent of any particular
     * target, and independent of whether the area is being granted at creation time or via
     * the explicit grant endpoint. Exactly [AreaScopePolicy.canAccessArea]: SUPER_ADMIN or a
     * global MODERATOR may grant any ACTIVE area; a territorial MODERATOR only one already
     * inside their own current scope (§8) — a moderator can never widen anyone's reach
     * beyond their own.
     */
    fun canAssignArea(actor: ManagementActor, area: AssignedArea): Boolean {
        if (actor.role == UserRole.SERVICE_USER) return false
        return areaWithinActorAuthority(actor, area)
    }

    /**
     * Whether removing an area would leave [target] — a non-global SERVICE_USER — with zero
     * assigned areas, which only a MODERATOR actor is forbidden to do (§9). A SUPER_ADMIN
     * may leave a user unassigned; a MODERATOR revoking a target's only remaining area would
     * create an account that immediately falls outside all territorial management, which is
     * exactly the orphaning this guards against.
     */
    fun wouldViolateLastAreaRule(actor: ManagementActor, target: ManagedUser, remainingAreaCount: Int): Boolean =
        actor.role == UserRole.MODERATOR && !target.globalAreaAccess && remainingAreaCount == 0

    // ------------------------------------------------------------------- SUPER_ADMIN-only ops

    /** Role change is SUPER_ADMIN-only, full stop — not a manageability question (§22). */
    fun canChangeRole(actor: ManagementActor): Boolean = actor.role == UserRole.SUPER_ADMIN

    /** Global area access is SUPER_ADMIN-only, full stop (§10, §24). */
    fun canChangeGlobalAccess(actor: ManagementActor): Boolean = actor.role == UserRole.SUPER_ADMIN

    // ------------------------------------------------------------------------------- private

    /** Whether [area] sits inside [actor]'s own authority, per the shared [AreaScopePolicy] rule. */
    private fun areaWithinActorAuthority(actor: ManagementActor, area: AssignedArea): Boolean =
        areaScopePolicy.canAccessArea(actorAsAreaActor(actor), AreaSnapshot(area.id, area.isActive))

    /**
     * Whether [target]'s effective scope overlaps [actor]'s own current active areas.
     *
     * A global target's effective scope is "every active area", so it overlaps any
     * non-empty actor scope (§6: "a global SERVICE_USER may be visible as read-only"). A
     * non-global target overlaps only if it holds an assignment [areaWithinActorAuthority]
     * finds inside the actor's own scope.
     */
    private fun overlapsActorScope(actor: ManagementActor, target: ManagedUser): Boolean =
        if (target.globalAreaAccess) {
            actor.ownActiveAreaIds.isNotEmpty()
        } else {
            target.assignedAreas.any { areaWithinActorAuthority(actor, it) }
        }

    private fun actorAsAreaActor(actor: ManagementActor) =
        AreaActor(actor.userId, actor.role, actor.globalAreaAccess, actor.ownActiveAreaIds)
}
