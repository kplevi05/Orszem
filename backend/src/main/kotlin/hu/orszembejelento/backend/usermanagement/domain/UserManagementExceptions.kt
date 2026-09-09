package hu.orszembejelento.backend.usermanagement.domain

/**
 * Returned identically for a target that does not exist and a target the actor's scope
 * cannot see (§28/§41 of the Phase 6 brief) — a MODERATOR must never be able to tell the
 * two apart by probing service IDs.
 */
class UserNotFoundException : RuntimeException("no such user, or not visible to this actor")

/** The actor may not perform this operation at all (e.g. a SERVICE_USER calling any endpoint here). */
class UserManagementForbiddenException : RuntimeException("not permitted to perform this operation")

/**
 * The actor can see the target (it was resolved) but is not authorised to *change* it —
 * e.g. a territorial MODERATOR against a peer MODERATOR, or a target with any assignment
 * outside the actor's current scope.
 */
class UserNotManageableException : RuntimeException("this target is not manageable by the current actor")

/** A role-change request that is not one of the two allowed transitions, or names SUPER_ADMIN either way. */
class InvalidRoleTransitionException : RuntimeException("the requested role transition is not allowed")

class AreaNotFoundException : RuntimeException("the service area does not exist")

/** The area exists but is not currently assignable: inactive, or outside the actor's own scope. */
class AreaNotAssignableException : RuntimeException("the service area cannot be assigned by this actor")

/** Revoking would leave a non-global SERVICE_USER with zero service areas, and the actor is a MODERATOR (§9). */
class UserRequiresServiceAreaException : RuntimeException("a moderator may not remove a user's last service area")

/** A MODERATOR attempted to grant/revoke global area access, which is SUPER_ADMIN-only (§10). */
class GlobalAccessNotAllowedException : RuntimeException("only SUPER_ADMIN may change global area access")
