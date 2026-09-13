package hu.orszembejelento.backend.analytics.domain

/**
 * A dedicated set of stable error codes for the analytics surface (brief §30) — deliberately
 * not reusing Phase 6/10's area-scoping exceptions, which answer a different question (is
 * this area a valid grant or administration target) from a different actor set.
 */

/** `period` did not parse to one of the four fixed [AnalyticsPeriod] codes. */
class AnalyticsPeriodInvalidException : RuntimeException()

/**
 * `areaId` names a ServiceArea that either does not exist, or exists outside the actor's
 * current analytics scope (brief §22) — one existence-safe response for both, so a
 * territorial actor cannot enumerate other areas by probing this endpoint.
 */
class AnalyticsAreaNotAvailableException : RuntimeException()

/** `unclassifiedOnly=true` requested by a role never allowed to see `Besorolatlan` (brief §23). */
class AnalyticsUnclassifiedForbiddenException : RuntimeException()

/** `categoryCode` does not name a known backend catalog category (brief §15/§30). */
class AnalyticsCategoryNotFoundException : RuntimeException()

/** `areaId` and `unclassifiedOnly=true` were both supplied — mutually exclusive (brief §15). */
class AnalyticsFilterInvalidException(message: String) : RuntimeException(message)
