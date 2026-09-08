package hu.orszembejelento.backend.scope.domain

import hu.orszembejelento.backend.identity.domain.UserRole
import java.util.UUID

/**
 * What an authenticated actor may do, in terms of service areas.
 *
 * A small, explicit business policy — deliberately not an IAM framework. There is no
 * permission table, no action registry, no resource-policy builder and no expression
 * evaluator. Those exist to express rules nobody has decided yet; the rules here are
 * decided, few, and belong in one readable place where they can be argued about.
 *
 * Everything is derived from server-side state: the actor's role, their
 * `global_area_access` flag, their assigned areas, and the current state of the area
 * itself. Nothing the client sends is consulted — in particular, the area the Android app
 * currently displays is a UI preference, not an authorisation input.
 */
class AreaScopePolicy {

    /**
     * Whether the actor may act in every normal area without individual assignment.
     *
     * SUPER_ADMIN is global by virtue of the role. The flag is not consulted for them:
     * a SUPER_ADMIN row that somehow has `global_area_access = false` is an inconsistency
     * in the data, not a demotion, and the role is authoritative.
     */
    fun hasGlobalAreaAccess(actor: AreaActor): Boolean =
        actor.role == UserRole.SUPER_ADMIN || actor.globalAreaAccess

    /**
     * Whether the actor may act in a specific area.
     *
     * An inactive area is closed to everyone, including SUPER_ADMIN. Global access means
     * "every area that is open", not "every row that exists": letting anyone work in a
     * deactivated area would defeat the point of deactivating it.
     */
    fun canAccessArea(actor: AreaActor, area: AreaSnapshot): Boolean {
        if (!area.active) return false
        if (hasGlobalAreaAccess(actor)) return true
        return area.id in actor.assignedAreaIds
    }

    /**
     * Whether the actor may see and triage reports that resolved to no area at all.
     *
     * This is deliberately **not** implied by global area access.
     *
     * Being permitted in every area is a statement about breadth of ordinary operational
     * work. UNCLASSIFIED traffic is different in kind: it is everything the routing rules
     * could not place, so it is unfiltered, potentially misdirected, and needs judgement
     * about where it belongs rather than action within a known area. That is a moderation
     * responsibility, so it is limited to SUPER_ADMIN and to a MODERATOR whose remit is
     * already national.
     *
     * A SERVICE_USER with `global_area_access` therefore still cannot see it — they have
     * wide reach, not a different job.
     */
    fun canViewUnclassified(actor: AreaActor): Boolean = when (actor.role) {
        UserRole.SUPER_ADMIN -> true
        UserRole.MODERATOR -> actor.globalAreaAccess
        UserRole.SERVICE_USER -> false
    }

    /**
     * The areas an actor may act in, out of [candidateAreas].
     *
     * Callers pass the current areas; this filters rather than queries, so the policy has
     * no repository dependency and stays testable as pure logic.
     */
    fun accessibleAreas(actor: AreaActor, candidateAreas: List<AreaSnapshot>): List<AreaSnapshot> =
        candidateAreas.filter { canAccessArea(actor, it) }
}

/**
 * The authorisation-relevant facts about an actor, read from current server-side state.
 *
 * Separate from `AuthenticatedActor` on purpose: that type belongs to authentication and
 * knows about sessions, while this one carries only what area decisions need. Keeping them
 * apart stops session details leaking into authorisation logic.
 */
data class AreaActor(
    val userId: UUID,
    val role: UserRole,
    val globalAreaAccess: Boolean,
    val assignedAreaIds: Set<UUID>,
)

/** The current state of an area, as far as an access decision is concerned. */
data class AreaSnapshot(
    val id: UUID,
    val active: Boolean,
)
