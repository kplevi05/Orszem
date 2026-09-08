# ADR 0007 — an explicit current reference state, and the routing decision it drives

**Status:** accepted · 2026-09-08

## Context

Phase 3C needed to answer two questions Phase 3A/3B left open: *which* imported reference
state is authoritative right now, and how a report's settlement (plus an optional railway
line) resolves to a service area given that state, honestly reflecting whether the data
behind the decision is COMPLETE or PARTIAL (ADR 0006).

## Decision 1 — an explicit `is_current` row, never `MAX(imported_at)`

`reference_dataset_imports` gains three coverage columns (mirroring the manifest's
per-component `DatasetCoverage`) and a boolean `is_current`, enforced by a **partial unique
index** — `CREATE UNIQUE INDEX ... ON reference_dataset_imports (is_current) WHERE
is_current` — so at most one row can ever be current, as a database constraint rather than
an application convention.

Recency was rejected as the signal. `MAX(imported_at)` answers "what was imported last",
not "what is authoritative now", and those quietly diverge the moment either of these
happens:

- a **failed** import (unverified, uncleared reuse, a version conflict, a line still in
  use) still has a timestamp if it partially executed before rolling back — `MAX()` over a
  table that *did* accumulate a row would pick it, `is_current` cannot, because the
  promotion step that sets it never runs on a failed import (see
  `ReferenceImportUseCase.import`, and the rollback guarantees from ADR 0006 that make a
  failed import write nothing at all in the first place);
- a **no-op** re-import of the same version and content returns before touching `is_current`
  at all, so it cannot resurrect an old timestamp as "most recent";
- two **concurrent** imports of different versions must leave exactly one, unambiguous
  winner — the advisory lock (ADR 0006) already serialises them, and the partial unique
  index is the second, independent proof: even a bug that let both transactions proceed
  could not leave two current rows.

`promoteToCurrentImport` is two statements, not one UPSERT — unset whatever was current,
then set the new row — because the unset must fully complete before the set or the partial
unique index would reject the second write. Both happen inside the same transaction as the
rest of the import, so the whole thing is atomic with everything else ADR 0006 already
guarantees.

## Decision 2 — what `referenceDatasetVersion` means

A routing result and a public API response both carry `referenceDatasetVersion`. It is the
current row's `dataset_version` **at the moment of the decision** — the reference-state
*revision*, not a claim that every fact used in that decision was physically present in
that specific manifest.

This matters because ADR 0006 made imports non-destructive under PARTIAL coverage: a
relation from an older import can outlive several newer PARTIAL imports that simply didn't
re-assert it. A routing decision built on that preserved relation still reports the
*current* version, because that is the state that was actually consulted — the version
label answers "which reference-state revision governed this decision", not "which import
introduced every row involved in it". Row-level provenance/history is deliberately **not**
added in Phase 3C to answer the second question; nothing currently needs it, and the
`reference_dataset_imports` row (plus, for a specific fact, `git blame` on the import that
last touched it) is enough for the questions that do come up.

## Decision 3 — the routing matrix

`RoutingService.route(settlementId, railwayLineId?)` is an application/domain service only
— there is no HTTP routing endpoint, and it is read-only.

| Coverage | Active candidates | Explicit line? | Result |
|---|---|---|---|
| (none) | — | — | `ReferenceDatasetUnavailable` — infrastructure, not a business result |
| COMPLETE | 0 | no | `NO_VERIFIED_RAILWAY_LINE_REFERENCE` |
| COMPLETE | 1 | no | inferred, then resolved as if selected |
| COMPLETE | 2+ | no | `RAILWAY_LINE_NOT_SELECTED` |
| PARTIAL | 0 | no | `NO_VERIFIED_RAILWAY_LINE_REFERENCE` |
| PARTIAL | 1+ | no | `RAILWAY_LINE_NOT_SELECTED` — never inferred |
| any | — | yes, unrelated/unknown | `REFERENCE_MISMATCH` |
| any | — | yes, verified (active or not) | resolved |

"Active candidates" is the count from
[Correction 1](#correction-1-2026-09-08--the-inference-candidate-set-must-be-active-only),
not every verified relation. Zero active candidates producing the same result under both
COMPLETE and PARTIAL is
[Correction 2](#correction-2-2026-09-08--zero-active-candidates-means-the-same-thing-under-partial-as-under-complete)
below.

"Resolved" then checks operational state, never reference data again:

| Resolved line / area | Result |
|---|---|
| line inactive | `RAILWAY_LINE_INACTIVE` — reachable only via explicit selection; an inferred line is always active by construction, see the correction below |
| line active, no service-area mapping | `RAILWAY_LINE_UNASSIGNED` |
| line active, service area inactive | `SERVICE_AREA_INACTIVE` |
| line active, service area active | `Routed` |

Two things are easy to get wrong here, so they are stated explicitly:

- **PARTIAL never infers, at any *nonzero* candidate count** — including exactly one. The
  tempting shortcut ("there's only one, so it must be that one") is precisely the failure
  mode ADR 0006 exists to prevent: under PARTIAL, a settlement with one known relation
  might have others the dataset hasn't captured yet, so "the only one we know about" is not
  "the only one". This does **not** extend to a *zero*-candidate PARTIAL settlement — see
  Correction 2: zero is reported the same way regardless of coverage, because it is not an
  inference at all, only a statement that nothing is currently on record.
- **An explicit line is always validated against a verified relation, regardless of
  coverage, and regardless of whether that line is currently active.** A client's selection
  is a hint to narrow the search, never a substitute for verification —
  `REFERENCE_MISMATCH` fires identically whether coverage is COMPLETE or PARTIAL, and
  identically whether the supplied id names a real, unrelated line or no line at all. An
  explicit selection of a line that *is* verified but currently inactive is
  `RAILWAY_LINE_INACTIVE`, decided in the same operational-state step as an inferred line —
  never `REFERENCE_MISMATCH`, which would incorrectly suggest the relation itself was never
  real.

### Correction 1, 2026-09-08 — the inference candidate set must be active-only

The first implementation of step 3 above read *every* verified relation
(`findVerifiedLinesOfSettlement`, since removed) for automatic inference, while the public
API's line listing (Decision 4 below) always used active lines only
(`findActiveLinesOfSettlement`, pre-existing from Phase 3A). That mismatch let the two
disagree: a settlement with one active and one inactive relation would have the public API
list exactly one selectable line while routing, given no explicit selection, saw *two*
verified relations and refused with `RAILWAY_LINE_NOT_SELECTED` — and a settlement whose
*only* relation was to an inactive line would have the public API list nothing at all,
while routing silently inferred the inactive line and only then reported
`RAILWAY_LINE_INACTIVE`, an outcome the client had no way to have anticipated from what it
was shown.

**Fixed:** automatic inference (step 3) now reads exactly the same query the public API
uses — `findActiveLinesOfSettlement` — so the two can never drift apart again. An inactive
relation remains a verified fact (`relationExists` still consults every relation,
regardless of activity), but it is not a *candidate* for automatic inference. Concretely:

- a settlement with one active and one inactive relation infers the active one, not
  `RAILWAY_LINE_NOT_SELECTED`;
- a settlement whose only relation is to an inactive line has **zero active candidates**,
  so COMPLETE coverage now correctly reports `NO_VERIFIED_RAILWAY_LINE_REFERENCE` for it,
  not a silently-inferred `RAILWAY_LINE_INACTIVE`;
- explicit selection is unaffected by this correction: selecting that same inactive line
  by id still reaches `RAILWAY_LINE_INACTIVE`, because explicit selection was never routed
  through the candidate-counting step to begin with.

One consequence worth naming plainly: `RAILWAY_LINE_INACTIVE` is now reachable **only**
through explicit selection. An inferred line is always active, because inactive lines are
no longer inference candidates at all.

### Correction 2, 2026-09-08 — zero active candidates means the same thing under PARTIAL as under COMPLETE

Correction 1 fixed *which* relations count as candidates. This correction fixes what a
**zero**-candidate result means once that fix was in place.

The original matrix had PARTIAL short-circuit to `RAILWAY_LINE_NOT_SELECTED` unconditionally,
for *any* active-candidate count including zero — reasoning that PARTIAL coverage can never
safely conclude anything, so it should never claim `NO_VERIFIED_RAILWAY_LINE_REFERENCE`
either. That conflated two different questions. `NO_VERIFIED_RAILWAY_LINE_REFERENCE` was
never a claim that PARTIAL coverage is entitled to make about the country ("no railway
exists here") — see that reason's KDoc, tightened by this same correction. It is only a
claim about the *current verified reference state*: "there is no active line relation on
record for this settlement right now." That claim is exactly as true, and exactly as
honestly limited, whether the coverage that produced it is COMPLETE or PARTIAL. Routing an
empty candidate set to `RAILWAY_LINE_NOT_SELECTED` was also a UX mismatch in its own right:
that reason means "please choose among the options", and there were no options to choose
among.

**Fixed:** the coverage check now applies only when there is at least one active candidate
to decide about. Zero active candidates is reported as
`NO_VERIFIED_RAILWAY_LINE_REFERENCE` unconditionally - COMPLETE or PARTIAL alike, and
regardless of whether an *inactive* (non-candidate) relation exists. One or more active
candidates still goes through the coverage check exactly as Correction 1 left it: COMPLETE
infers a lone candidate, PARTIAL never does, two or more is always
`RAILWAY_LINE_NOT_SELECTED` under either coverage.

## Decision 4 — the public reference API returns 503, never a misleading empty result

`GET /api/v1/public/reference/settlements` and `.../railway-lines` are unauthenticated,
read-only, and structurally incapable of naming a service area, a user, or a moderator —
their DTOs only have fields for settlement/line facts. Neither endpoint requires the
backend to have any reference dataset loaded to *start*; both refuse to *answer* without
one.

When `findCurrentReferenceState()` returns null, the endpoints return HTTP 503 with
`REFERENCE_DATASET_UNAVAILABLE` rather than an empty list. An empty search result and "we
have never loaded a dataset" are different facts, and returning 200 for both would let a
client conclude "no such settlement" during what is actually a backend that has not
finished starting up, or has not been imported into yet.

The line-list response is never a naked array — `{"coverage": "COMPLETE"|"PARTIAL",
"items": [...]}` — so a client cannot mistake a single listed line, under PARTIAL coverage,
for proof that it is the only possible one. The same reasoning as `RoutingService`'s
PARTIAL rule, surfaced to the client that will eventually let a citizen pick a line.

## Consequences

- V002 was updated in place rather than adding a V003, because it had never been applied to
  any persistent or shared environment — confirmed before this change (no deployment to the
  Oracle server has ever happened; every application of V002 to date has been an ephemeral
  Testcontainers instance, torn down at the end of each test run).
- `AreaScopePolicy` (ADR — Phase 3A) is untouched. Routing computes *which* area a report
  would belong to; it says nothing about who may later view or triage it, which stays that
  policy's job, unexercised until Phase 4 introduces reports themselves.
- No row-level provenance/history table exists. If a future phase genuinely needs "which
  import wrote this specific relation", that is a new, deliberate addition — not a gap this
  ADR quietly leaves for someone to trip over, since [Decision 2](#decision-2--what-referencedatasetversion-means)
  states plainly what today's `referenceDatasetVersion` does and does not promise.
- `RoutingService.route` does not validate that `settlementId` names a settlement that
  exists — that is explicitly the caller's responsibility, documented on the method itself.
  An unknown id degrades gracefully today only as a side effect of how the candidate query
  is written (zero rows, same shape as a real settlement with none), never as a designed
  business meaning. **Phase 4 must perform its own settlement-existence validation before
  calling `route`, and must not treat that graceful degradation as a legitimate
  `UNCLASSIFIED` outcome for an invalid id.** That validation is deliberately not built by
  this ADR.
