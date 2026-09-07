# ADR 0005 — four separate concepts behind routing

**Status:** accepted · 2026-09-07

## Context

Routing a report needs to answer: *which team is responsible for this?* The tempting
shortcut is a single table — a settlement, its area, and who may see it — because that is
what a query at request time actually wants.

Four different kinds of fact are involved, and they differ in who owns them, how often they
change, and what it means when they are wrong:

| | Fact | Owner | Changes when |
|---|---|---|---|
| A | a settlement lies on a railway line | external reality | a verified reference dataset is imported |
| B | a line is this area's responsibility | the customer | the organisation reorganises |
| C | a user may act in this area | the customer | staffing changes |
| D | this report belongs to that area | computed | — |

## Decision

Model all four separately, and let the database enforce it.

- `settlements` and `railway_lines` hold reference data only. Neither carries a
  `service_area_id`, a user list or a permission column.
- `service_area_railway_lines` holds configuration, with a **unique index on
  `railway_line_id`** giving the one-line-one-area invariant.
- `user_service_areas` holds current authorisation, plus a `global_area_access` boolean on
  `users`.
- Routing is computed, never stored.

A test asserts structurally that no reference table ever grows a column matching
`service_area`, `user_id`, `permission`, `allowed` or `role`.

## Why not the shortcut

`settlements.service_area_id` is the obvious version, and it is wrong in a specific way:
responsibility does not follow the settlement, it follows the line. A settlement on two
lines split between two areas cannot be represented at all, and every reference-data import
would rewrite operational configuration as a side effect — because the same row now holds
both a fact about Hungary and a decision by this customer.

The same argument rules out a permission array on a line: authorisation would then be
rewritten by whoever refreshes reference data.

## Consequences

- Routing is a join rather than a lookup. Acceptable: it is one query on indexed columns.
- The one-area-per-line rule is a database constraint, not a convention, so two concurrent
  assignments cannot both succeed. Proven by a test that races six areas for one line and
  asserts exactly one mapping survives.
- A reference import can be blocked by operational configuration — deactivating a line that
  an area currently owns must fail rather than silently orphan the configuration.
- `global_area_access` is a boolean rather than a `ServiceArea` named "Minden terület". A
  fake area row would appear in area lists, be assignable, renamable, deactivatable and
  mappable to a line, and every query would have to remember to exclude it.

## The rule that will be argued about later

**Global area access does not grant UNCLASSIFIED visibility**, and a `SERVICE_USER` with
the flag still cannot triage it.

Being permitted in every area describes the breadth of ordinary operational work.
UNCLASSIFIED is everything the routing rules could not place: unfiltered, possibly
misdirected, and needing a decision about where it belongs rather than action within a
known area. That is moderation, so it is limited to `SUPER_ADMIN` and to a `MODERATOR`
whose remit is already national.

Both directions are covered by tests, because the natural "simplification" is to treat
global access as implying everything.
