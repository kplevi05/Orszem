# ADR 0011 — pair-level `Settlement + RailwayLine → ServiceArea` routing

**Status:** accepted · 2026-09-23

## Context

Before PR #32, `ServiceArea` assignment was per `RailwayLine`: an entire line belonged to at
most one `ServiceArea`. That is wrong wherever a real Hungarian railway line crosses more
than one operational territory (a county, or Budapest as its own territory) — the whole-line
model could only assign the *entire* line to one territory, misrouting every settlement on
the wrong side of a crossing.

PR #32 introduces `service_area_settlement_lines` (migration `V007`, composite primary key
`(settlement_id, railway_line_id)`, `service_area_id` foreign key) so a specific
`(settlement, line)` **pair** can carry its own `ServiceArea`, independent of every other
settlement on that same line.

This ADR records the precedence, compatibility and safety rules the routing service and the
admin write path already implement, so a later reader does not have to reconstruct them from
`RoutingService.kt`.

## Decision 1 — a line is either whole-line-routed or pair-routed, never both

`resolveLineToArea` (`RoutingService.kt`) checks, per line, whether
`service_area_settlement_lines` has **any** row for that `railwayLineId`:

- **Yes (pair mode):** the `ServiceArea` for a report on this line comes *only* from
  `findAreaOfSettlementLine(settlementId, lineId)`. If that specific pair has no row, the
  report becomes `UNCLASSIFIED / RAILWAY_LINE_UNASSIGNED` — it never falls back to a
  whole-line mapping for that line, even if one happens to exist from before the line was
  switched to pair mode.
- **No (legacy/whole-line mode):** the `ServiceArea` comes from the pre-existing
  `findAreaOfRailwayLine(lineId)` lookup, exactly as before PR #32.

**The admin write path refuses to create a mixed line** — applying any pair mapping for a
line that already has a whole-line `ServiceArea`, or vice versa, is rejected before it can be
written. A line's mode is a property of what has actually been assigned to it, and the two
representations are mutually exclusive by construction, not by convention.

**Rationale:** a per-report `if (has pair rows) pair else whole-line` branch with silent
fallback between the two would let a missing pair mapping silently borrow the (wrong)
whole-line assignment, or vice versa — exactly the "a missing pair cannot inherit another
settlement's ServiceArea" property this ADR exists to guarantee. Refusing to mix modes at
write time removes the ambiguity a per-report fallback would otherwise have to resolve.

## Decision 2 — a missing pair means `UNCLASSIFIED`, never inference from geography or a sibling settlement

There is no "nearest settlement on the same line" or "same county" fallback. A settlement with
no pair mapping on a pair-routed line is `UNCLASSIFIED / RAILWAY_LINE_UNASSIGNED`, exactly
like an unassigned whole-line. This is deliberate: inferring a `ServiceArea` from a
neighbour's assignment is exactly the kind of fabricated routing mapping the Nationwide KSH
Fallback phase (see `docs/NATIONWIDE_KSH_FALLBACK_ENGINEERING_REPORT.md`) was built to avoid,
and the pair model must not reintroduce it through a different door.

## Decision 3 — historical reports are immune to both re-routing and mode switches

`RoutingOutcome`/routing snapshots are written once, at submission time, and are never
recomputed. Changing a line from whole-line to pair mode, or editing pair mappings
afterwards, only ever affects **future** reports. This is unchanged from the general routing
immutability rule already established for the KSH fallback phase and is re-verified for the
pair model specifically by this phase's routing tests.

## Decision 4 — only `SUPER_ADMIN` may preview/apply pair mappings, and every apply is atomic and audited

Batch pair-mapping updates go through preview → apply, `SUPER_ADMIN`-only, inside one
transaction with an audit row per apply, using the repository's existing canonical lock
ordering (report row → actor/target user row; here, the equivalent settlement/line/area rows
are locked in a fixed, documented order) so two concurrent applies cannot interleave into a
corrupt intermediate state. This mirrors the precedent already established for `RailwayLine`
administration and area-grant administration elsewhere in `usermanagement`/`areaadmin`,
rather than inventing a second authorization or locking model.

## Decision 5 — reference-data deactivation can never silently break an in-use pair mapping

`reference-import`'s existing `REFERENCE_LINE_IN_USE` refusal (ADR 0006) - a railway line
assigned to a `ServiceArea` cannot be deactivated by an ordinary import - extends unchanged
to a line carrying pair mappings: `service_area_settlement_lines` has
`ON DELETE RESTRICT` foreign keys to both `service_areas` and `settlement_railway_lines`, so
the database itself refuses a deactivation/removal that would orphan a pair mapping, on top
of the application-level check.

## Consequences

- A future settlement/line administration UI can show, per line, whether it is in whole-line
  or pair mode, and refuse to let an operator create a mapping of the wrong kind for that
  line — the backend already enforces this; the UI only needs to surface it clearly.
- The OSM-derived candidate railway dataset (`reference-data/osm-review/`, see
  `docs/RAILWAY_TERRITORY_MAPPING_ENGINEERING_REPORT.md` and
  `reference-data/osm-review/review-policy.json`) is expected to populate pair mappings, not
  whole-line ones, once promoted - the 50 lines already known to cross more than one
  `ServiceArea` are exactly why pair-level routing exists.
- This ADR does not change `AreaScopePolicy` or `ReportWorkflowPolicy` authorization: a
  territorial `SERVICE_USER` still only sees reports routed to their own granted
  `ServiceArea`(s), regardless of whether that routing came from a pair or a whole-line
  mapping.
