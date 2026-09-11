package hu.orszembejelento.backend.areaadmin.domain

/**
 * Phase 10 brief §40: a fresh, dedicated set of stable error codes for the ServiceArea
 * administration surface, rather than reusing Phase 6's user-management-flavoured
 * `AreaNotFoundException`/`AreaNotAssignableException`. Those exist to answer "is this area
 * a valid grant target for this actor's own scope" - a question about a specific user's
 * assignment. Phase 10 asks a different question ("does this area/line exist, and is its
 * *administration* state consistent") from a different actor (SUPER_ADMIN only, no
 * territorial nuance) on a different HTTP surface entirely, so conflating the two codes
 * would make one error code answer two unrelated questions.
 */
class ServiceAreaAdminForbiddenException : RuntimeException()

class ServiceAreaNotFoundException : RuntimeException()

/** `expectedVersion` no longer matches `ServiceArea.adminVersion` (brief §30). */
class ServiceAreaStateChangedException : RuntimeException()

class ServiceAreaAlreadyActiveException : RuntimeException()

class ServiceAreaAlreadyInactiveException : RuntimeException()

/** Deactivation blocked: one or more RailwayLines still map to this area (brief §14). */
class ServiceAreaHasRailwayLinesException : RuntimeException()

/** Deactivation blocked: currently-visible NEW/IN_PROGRESS operational reports remain (brief §15). */
class ServiceAreaHasOpenReportsException : RuntimeException()

/**
 * A ServiceArea name that is blank, or identical (post-trim) to another area's current
 * name (brief §9: names are already unique in the existing schema and that rule is
 * preserved, never redesigned).
 */
class ServiceAreaNameBlankException : RuntimeException()

class ServiceAreaNameAlreadyInUseException : RuntimeException()

class RailwayLineAdminNotFoundException : RuntimeException()

/** The resolved RailwayLine reference row is not active (brief §24: never activated/deactivated from here, only reported). */
class RailwayLineAdminInactiveException : RuntimeException()

/** The line's assignment target area exists but is not ACTIVE (brief §19/§20). */
class TargetServiceAreaInactiveException : RuntimeException()

/** `expectedCurrentServiceAreaId` no longer matches the line's actual current mapping (brief §29). */
class RailwayLineAssignmentChangedException : RuntimeException()

/** The line is already mapped to the requested target area - assigning it there again is not a state change. */
class RailwayLineAlreadyAssignedToAreaException : RuntimeException()
