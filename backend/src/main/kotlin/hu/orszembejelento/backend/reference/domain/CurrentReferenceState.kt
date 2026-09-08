package hu.orszembejelento.backend.reference.domain

/**
 * The one active reference state, as far as routing and the public reference API need it.
 *
 * Read from the single [ReferenceDatasetImport] row with `is_current = true` - never from
 * `MAX(imported_at)` or any other recency ordering. Recency is not the same question as
 * which import an operator intended to be authoritative: a failed re-import, or a stale
 * dataset re-applied by mistake, must never silently become "current" just by having a
 * later timestamp. See ADR 0007.
 *
 * [datasetVersion] labels a routing result or a public API response as belonging to *this*
 * reference-state revision. It does **not** mean every individual fact used in a particular
 * decision was literally present in that manifest - a PARTIAL import preserves rows a
 * newer snapshot didn't re-assert, so a fact from an older revision can still be in play
 * under the newest [datasetVersion]. It means only: this is the reference-state revision
 * that was current when the decision was made. See ADR 0007.
 *
 * [settlementRailwayLinesCoverage] is what routing (and the public railway-line listing)
 * uses to decide whether the absence of another relation may be trusted as evidence, or
 * must be treated as merely unknown - see `RoutingService`.
 */
data class CurrentReferenceState(
    val datasetVersion: String,
    val settlementRailwayLinesCoverage: CoverageComponentStatus,
)
