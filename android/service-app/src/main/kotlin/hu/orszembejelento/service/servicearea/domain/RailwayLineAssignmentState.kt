package hu.orszembejelento.service.servicearea.domain

import hu.orszembejelento.service.servicearea.data.RailwayLineAdminListItemResponse

/**
 * The three mutually exclusive routing modes of a RailwayLine (ADR 0011) as the admin UI sees
 * them. A pair-configured line is `PER_SETTLEMENT` - it is never "unassigned", even though it
 * has no whole-line ServiceArea.
 */
enum class LineRoutingMode { UNASSIGNED, WHOLE_LINE, PER_SETTLEMENT }

/**
 * The line's routing mode, from the backend's explicit `assignmentMode`.
 *
 * Only a response from a backend older than that field (no `assignmentMode` at all) falls back
 * to the legacy inference from `currentServiceAreaId` - which cannot tell a pair-configured line
 * from a free one, so the backend must be deployed before this client.
 */
fun RailwayLineAdminListItemResponse.routingMode(): LineRoutingMode = when (assignmentMode) {
    "PER_SETTLEMENT" -> LineRoutingMode.PER_SETTLEMENT
    "WHOLE_LINE" -> LineRoutingMode.WHOLE_LINE
    "UNASSIGNED" -> LineRoutingMode.UNASSIGNED
    else -> if (currentServiceAreaId != null) LineRoutingMode.WHOLE_LINE else LineRoutingMode.UNASSIGNED
}

/** What a picker row says about the line, in priority order. */
enum class LineRowStatus { INACTIVE, ALREADY_IN_THIS_AREA, IN_OTHER_AREA, PER_SETTLEMENT, UNASSIGNED }

fun lineRowStatus(line: RailwayLineAdminListItemResponse, targetAreaId: String): LineRowStatus = when {
    !line.active -> LineRowStatus.INACTIVE
    line.routingMode() == LineRoutingMode.PER_SETTLEMENT -> LineRowStatus.PER_SETTLEMENT
    line.currentServiceAreaId == targetAreaId -> LineRowStatus.ALREADY_IN_THIS_AREA
    line.routingMode() == LineRoutingMode.WHOLE_LINE -> LineRowStatus.IN_OTHER_AREA
    else -> LineRowStatus.UNASSIGNED
}

/**
 * Whether the legacy *whole-line* assign/move may be offered for this row at all. Never for a
 * pair-configured line (the backend rejects it with SETTLEMENT_LINE_MIXED_ROUTING_MODES; the UI
 * must not even offer it), never for an inactive line, never for the line's current area.
 * A UI convenience only: the backend independently re-authorises every call.
 */
fun canOfferWholeLineAssign(line: RailwayLineAdminListItemResponse, targetAreaId: String): Boolean =
    when (lineRowStatus(line, targetAreaId)) {
        LineRowStatus.UNASSIGNED, LineRowStatus.IN_OTHER_AREA -> true
        LineRowStatus.INACTIVE, LineRowStatus.ALREADY_IN_THIS_AREA, LineRowStatus.PER_SETTLEMENT -> false
    }

/**
 * Whether the backend behind this session understands pair-level routing (ADR 0011).
 *
 * Decided from the list itself, never assumed: a backend that knows pair-level routing sends
 * `assignmentMode` on EVERY line row; a legacy backend sends none. [LEGACY] is conservative and
 * wins as soon as any row lacks the field - the new pair-level filter and the settlement detail
 * (which would call an endpoint a legacy backend does not have) are then neither shown nor run,
 * and the whole-line administration keeps working exactly as before. [UNKNOWN] (nothing loaded
 * yet, or only empty pages) behaves like [LEGACY] for these two features.
 */
enum class PairLevelSupport { UNKNOWN, SUPPORTED, LEGACY }

fun detectPairLevelSupport(previous: PairLevelSupport, loaded: List<RailwayLineAdminListItemResponse>): PairLevelSupport = when {
    loaded.any { it.assignmentMode == null } -> PairLevelSupport.LEGACY
    loaded.isNotEmpty() -> PairLevelSupport.SUPPORTED
    else -> previous
}
