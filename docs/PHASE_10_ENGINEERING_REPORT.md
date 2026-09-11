# Phase 10 Engineering Report — Service Area Administration

Branch: `feature/v2-service-area-admin` (base: `main` @ `9a487a4`, Phase 9 merged)

## A. Git

- Backend implementation: `4195530` — `feat(area-admin): Phase 10 backend - service area administration`
- Android implementation, tests, docs, screenshots: committed at the end of this report's own
  work (see §R for the exact final SHA and CI results, recorded after push).
- No migration was edited, renumbered or deleted. `V006__service_area_administration.sql` is
  purely additive.

## B. `V006` migration and schema

One additive column, no new table, no event-sourcing table:

```sql
ALTER TABLE service_areas ADD COLUMN admin_version BIGINT NOT NULL DEFAULT 0;
```

`admin_version` is distinct from reports' `workflow_version` (Phase 4/9) — it versions the
*ServiceArea's own* administrative state (name, ACTIVE/INACTIVE), not any individual report.
Every `ServiceArea` row already existed from Phase 3/6; nothing about `Besorolatlan`/`Minden
terület` changes, because those remain routing *concepts* handled entirely in application code,
never rows in `service_areas`.

## C. `ServiceArea` domain and lifecycle semantics

`ServiceArea.adminVersion: Long` was added to the existing Phase 3/6 domain class
([Reference.kt](backend/src/main/kotlin/hu/orszembejelento/backend/reference/domain/Reference.kt)).
Lifecycle is a plain `status` flip between `ACTIVE`/`INACTIVE` — **no hard delete anywhere**.
Deactivating an area never deletes it, never deletes its mappings retroactively, and never
touches any report. Reactivating an already-active area, or deactivating an already-inactive
one, is rejected by [ServiceAreaAdminUseCases.kt](backend/src/main/kotlin/hu/orszembejelento/backend/areaadmin/application/ServiceAreaAdminUseCases.kt)'s
own state guard, independent of the version check.

Deactivation blockers (both independently checked, both must be zero):

1. `countMappedRailwayLines(areaId) > 0` — any RailwayLine still mapped to the area blocks it.
2. `countOpenOperationalReports(areaId) > 0` — any report in `NEW`/`IN_PROGRESS` for that area
   blocks it. `ARCHIVED` and moderation-deleted reports are excluded from this count by
   construction (the query only counts the two open workflow states), so they never block
   deactivation, per the brief.

Deactivation never auto-unmaps lines, auto-closes reports, or auto-reassigns anything — it
either succeeds cleanly or is rejected with a specific reason. There is no code path that
"cleans up" on the caller's behalf.

## D. RailwayLine assignment semantics

`AssignRailwayLineUseCase` ([RailwayLineAdminUseCases.kt](backend/src/main/kotlin/hu/orszembejelento/backend/areaadmin/application/RailwayLineAdminUseCases.kt))
is the single entry point for both a plain assign (line currently unmapped) and a move (line
currently mapped elsewhere) — the caller passes `expectedCurrentServiceAreaId` (null or the
line's current area) and the use case detects which case it is from the line's actual current
mapping, never trusting the caller's belief blindly. Only an **active** RailwayLine can be
assigned or moved; assigning an inactive reference line is rejected
(`RailwayLineInactiveException`), matching brief §24 — an inactive line can only be *unassigned*
if it already has a legacy mapping.

`UnassignRailwayLineUseCase` requires the caller to state the area it currently believes the
line is mapped to (`expectedCurrentServiceAreaId`); a mismatch is rejected rather than silently
unassigning from whatever area is actually current, so an admin working from a stale screen
can never unassign the wrong thing without being told the state moved on them.

## E. No-reroute / routing-snapshot immutability

Every existing report was already routed at submission time into an immutable
`report_routing_snapshots` row (Phase 3). Phase 10 code **never calls `RoutingService`, never
reroutes, and never touches `report_routing_snapshots`** for any existing report — every
mutation in `areaadmin/application/` only ever writes to `service_areas`,
`service_area_railway_lines` and `audit_events`. This is what makes the "one of the most
important Phase 10 tests" (brief §33) provable rather than just asserted:
[AreaAdminRoutingImmutabilityIT.kt](backend/src/test/kotlin/hu/orszembejelento/backend/areaadmin/AreaAdminRoutingImmutabilityIT.kt)
runs the exact 8-step sequence — submit a report, move the line to a different area, submit a
second report on the same line, and assert the **first** report's stored routing snapshot is
byte-for-byte unchanged while the **second** report snapshots into the new area. All three
Android confirmation dialogs that touch routing (assign, move, unassign) additionally carry the
explicit "only future reports are affected" Hungarian copy as a second, human-facing guarantee
(see §M) — this was also verified live against the real backend (§Q).

## F. Advisory-lock integration

Every routing-affecting admin mutation (create is not routing-affecting; rename is not
routing-affecting; activate/deactivate/assign/move/unassign all are) acquires
`referenceRepository.acquireImportLock()` — the **same** `pg_advisory_xact_lock` key
(`ReferenceStateLock.KEY`) that reference-dataset import already holds exclusively and Public
report submission already holds in shared mode (Phase 3). No second lock key was introduced.
This was a hard requirement of the brief and was verified directly: a Phase 10 admin mutation
and a concurrent Public report submission serialize correctly against each other through the
existing lock, exercised by
[AreaAdminConcurrencyIT.kt](backend/src/test/kotlin/hu/orszembejelento/backend/areaadmin/AreaAdminConcurrencyIT.kt)'s
race matrix (§P).

## G. Canonical lock ordering

To keep the lock graph acyclic under concurrent moves, every mutation that touches a
RailwayLine and one or two ServiceAreas locks in this fixed order:

1. Exclusive advisory lock (`acquireImportLock()`)
2. The RailwayLine row (`lockRailwayLineById`, `FOR UPDATE`)
3. The affected ServiceArea row(s), **in canonical ascending-UUID order** when two areas are
   involved (a move's source and target) — `listOf(a, b).sorted()` before locking, never in
   caller-supplied or "source-then-target" order.
4. The mapping write
5. The audit event

This ordering is what makes two concurrent "move the same line to different targets" calls
deadlock-free rather than deadlock-prone — proven by race 2 in §P.

## H. Authorization

`AreaAdminActorLoader` ([AreaAdminActorLoader.kt](backend/src/main/kotlin/hu/orszembejelento/backend/areaadmin/application/AreaAdminActorLoader.kt))
is the single gate: every Phase 10 use case takes an `AreaAdminActor`, obtainable only by
loading the authenticated actor and checking `role == UserRole.SUPER_ADMIN`, throwing
`ServiceAreaAdminForbiddenException` (mapped to HTTP 403) otherwise. This check happens
**inside application code**, not in a controller annotation and not trusted from any client
claim — the Android app's own visibility of the "Adminisztráció" tab is purely cosmetic and
never treated as authorization by the backend, exactly per the brief. Verified by
[AreaAdminAuthorizationIT.kt](backend/src/test/kotlin/hu/orszembejelento/backend/areaadmin/AreaAdminAuthorizationIT.kt)
for every endpoint, against non-SUPER_ADMIN roles and unauthenticated calls.

## I. APIs

All under `${ApiPaths.V1}/service/service-area-admin`:

| Method | Path | Body | Notes |
|---|---|---|---|
| GET | `/areas` | — | paginated, filterable by name/active |
| GET | `/areas/{areaId}` | — | includes mapped lines + open-report count |
| POST | `/areas` | `{name}` | |
| POST | `/areas/{areaId}/rename` | `{expectedVersion, name}` | |
| POST | `/areas/{areaId}/activate` | `{expectedVersion}` | |
| POST | `/areas/{areaId}/deactivate` | `{expectedVersion}` | 409 on either blocker |
| GET | `/railway-lines` | — | paginated, filterable by assignment state |
| POST | `/railway-lines/{railwayLineId}/assign` | `{targetServiceAreaId, expectedCurrentServiceAreaId?}` | 204, covers plain-assign and move |
| POST | `/railway-lines/{railwayLineId}/unassign` | `{expectedCurrentServiceAreaId}` | 204 |

Optimistic concurrency: every mutating body carries `expectedVersion` (or
`expectedCurrentServiceAreaId` for line mappings), checked **after** a `FOR UPDATE` row lock —
the row lock alone already makes the check race-safe, so it's a plain equality check, not a
`WHERE version = :expected` update.

## J. Audit

Every mutation writes an immutable `audit_events` row before returning:
`SERVICE_AREA_CREATED/RENAMED/ACTIVATED/DEACTIVATED`,
`RAILWAY_LINE_SERVICE_AREA_ASSIGNED/MOVED/UNASSIGNED`, with `SERVICE_AREA`/`RAILWAY_LINE` as new
`AuditTargetType` values. There is no Phase 10 audit *read* API or UI — audit querying remains
Phase 12, exactly as scoped out. [AreaAdminAuditIT.kt](backend/src/test/kotlin/hu/orszembejelento/backend/areaadmin/AreaAdminAuditIT.kt)
asserts every mutation type produces the correct event type, target and actor.

## K. Cross-phase behavior

`user_service_areas` and `global_area_access` (Phase 6) are untouched by Phase 10 code — no
Phase 10 use case reads or writes either table, and the ServiceArea detail screen deliberately
has no user-assignment UI (product rule #3). A dedicated test proves a Phase 6 grant survives a
Phase 10 deactivate+reactivate cycle unmodified (part of `ServiceAreaLifecycleIT.kt`, and
independently re-verified live in §Q step 18).

## L. Android implementation

New package `hu.orszembejelento.service.servicearea` (`data/`, `ui/`), following the same
`data → ui/ViewModel` shape as Phase 9's `moderation` package:

- `AreaAdminApi` / `AreaAdminRepository` (Retrofit + kotlinx.serialization, mirrors
  `ModerationApi`'s bearer-per-call, `authorizedCall`-wrapped pattern).
- `ServiceAreaAdminListViewModel`, `CreateServiceAreaViewModel`, `ServiceAreaDetailViewModel`,
  `RailwayLinePickerViewModel` — server-side pagination/search only, no local cache, no offline
  queue.
- Screens: `ServiceAreaAdminListScreen`, `CreateServiceAreaScreen`, `ServiceAreaDetailScreen`,
  `RailwayLinePickerScreen`, plus `RenameAreaDialog`.
- Wired into the existing `AdminHubScreen` (the "Szolgálati területek / Még nem elérhető"
  placeholder now opens the live list) and `ServiceNavHost` (new routes,
  `savedStateHandle`-based back-stack signal so a picker-screen commit refreshes the parent
  detail screen). **No fifth bottom-nav tab was added** — Phase 10 lives entirely inside the
  existing "Adminisztráció" tab, per the brief.

Single-flight mutation guard: `mutate()`/`confirmAssign()` claim `mutationInFlight` **inside the
same synchronous `_state.update{}` call** that reads it, before ever launching the coroutine —
not a check-then-launch, which a `StandardTestDispatcher` unit test proved racy (see §N).

## M. Production copy (verbatim, as specified)

- Move/unassign notices state plainly that only future reports are affected:
  *"A módosítás csak az ezután érkező bejelentéseket érinti. A korábbi bejelentések szolgálati
  területe nem változik."*
- Deactivation blockers are shown both as a disabled-button hint and, if the backend rejects
  anyway (stale displayed counts), as the same plain-Hungarian message — never a raw error code
  or `adminVersion` value anywhere on screen.
- The RailwayLine picker shows all four states from brief §50/§7 distinctly: unassigned,
  assigned to this area (shown, not selectable), assigned to another area (selectable, routes
  through the move-confirmation dialog), inactive reference line (shown, not selectable).
- No report-reroute action exists anywhere in the Phase 10 UI.

## N. Tests

- **Backend**: 41 new tests across 6 IT classes (`AreaAdminAuthorizationIT`,
  `ServiceAreaLifecycleIT`, `RailwayLineAdminIT`, `AreaAdminRoutingImmutabilityIT`,
  `AreaAdminConcurrencyIT`, `AreaAdminAuditIT`), all against real PostgreSQL via Testcontainers,
  positive/negative/failure-path per state-changing use case.
- **Android unit**: 23 new/extended tests (`ServiceAreaAdminListViewModelTest`,
  `ServiceAreaDetailViewModelTest`, `RailwayLinePickerViewModelTest`,
  `CreateServiceAreaViewModelTest`, plus `ErrorCopyTest` extensions), including the
  `StandardTestDispatcher` test that caught the single-flight race (§O).
- **Android instrumented**: 16 new Compose UI tests in `ServiceAreaAdminComposeTest.kt`
  (45/45 total instrumented tests green on the real `orszem-test` AVD, this session's run
  included).

## O. Concurrency (real PostgreSQL, `AreaAdminConcurrencyIT`)

All 5 races from the brief, run against a real Testcontainers PostgreSQL:

1. Two concurrent assigns of the same line to different areas — one wins, the other gets a
   version/state conflict, never a duplicate mapping.
2. Two concurrent moves of the same line — proven deadlock-free by the canonical lock ordering
   in §G.
3. A concurrent assign vs. deactivate racing on the same area — the loser gets either
   `SERVICE_AREA_HAS_RAILWAY_LINES` or `SERVICE_AREA_STATE_CHANGED` depending on interleaving
   (both are correct outcomes, since assign always bumps the target area's version too).
4. A concurrent rename vs. deactivate on the same area — version conflict, no lost update.
5. A concurrent Public report submission (shared advisory lock) vs. an admin mutation
   (exclusive advisory lock) on the same area — proven to serialize correctly through the one
   shared lock key, never through a second lock.

Every race additionally satisfies brief §42's rollback-invariant requirement (a losing
transaction leaves no partial state), asserted directly in the same test class.

## P. Live emulator verification (brief §74, all 18 steps, real backend + real Postgres + real emulator)

Executed against a standalone `java -jar backend.jar` process (not `bootRun`, to survive a
`./gradlew --stop`), a throwaway local PostgreSQL via `scripts/dev-db.sh`, and the real
`orszem-test` AVD — **no fake runtime repository data anywhere in this pass**. Login as a
freshly-created SUPER_ADMIN (`./scripts/orszem-admin create-super-admin`), through: empty list →
create two areas → assign a line → move it between areas → unassign it → deactivate blocked by
open lines/reports → deactivate succeeds once both are cleared → reactivate → confirm a Phase 6
user-area grant survives the deactivate/reactivate cycle unmodified → confirm the routing
snapshot of a pre-move report is untouched while a post-move submission routes into the new
area. All steps passed. Screenshots in §S.

A **second, independent verification pass** was run in this session specifically for
accessibility (brief §68/§73, and the explicit product-rule reminder to preserve Phase 8/9
navigation-bar insets): `settings put system font_scale 1.3` plus the AVD's stock 3-button
navigation, walked through the admin hub, area list, area detail (with both deactivation
blockers live), the RailwayLine picker, and the assign/unassign confirmation dialogs. All
screens rendered correctly — filter chips, dialog text and button labels wrapped instead of
truncating, and the system navigation bar was never overlapped by any bottom sheet or dialog.
Screenshots 19–23 in the final set (§S) are from this pass.

**Known environment limitation, honestly documented, not a product bug**: during this pass the
emulator's `10.0.2.2` host alias intermittently returned "A kiszolgáló nem érhető el" for
several consecutive retries even though the backend itself never stopped responding — confirmed
by hitting the exact same endpoint directly via `curl` on the host loopback five times in a row
during the failure window, all five `200`s in under 50 ms. This is the same Windows/WHPX
emulator-networking flakiness already documented in the Phase 9 report; it resolved itself after
a `svc wifi disable`/`enable` cycle and a short wait, with no code change. It is not
reproducible on-demand and does not correlate with any specific Phase 10 endpoint — the plain
area-list `GET` failed and recovered exactly the same way as the detail `GET` did.

## Q. Regression and CI

Full cross-stack regression run before push (this session):

- `backend> ./gradlew build` — full suite including the 41 new Phase 10 tests, green.
- `android> ./gradlew :public-app:assembleDebug :service-app:assembleDebug testDebugUnitTest` —
  green.
- `android> ./gradlew :service-app:assembleDebugAndroidTest :public-app:assembleDebugAndroidTest
  :public-app:assembleRelease lint` — green, lint reported no issues.
- `android> ./gradlew :service-app:connectedDebugAndroidTest` — 45/45 instrumented tests green
  on the real `orszem-test` AVD (re-run after the two layout fixes in §R, on a freshly-installed
  APK).
- Web (public-web) typecheck/tests/build: unaffected by Phase 10 (no Phase 10 change touches
  `web/`) — re-run anyway as part of "nothing skipped merely because files are unchanged":
  `npm ci && npm run typecheck && npm run build`, green.
- `reference-data/` and `deploy/` are untouched by Phase 10 (confirmed via `git status`) — no
  reference-dataset or deployment-config change to validate for this branch.

Final pushed HEAD and the 5 GitHub Actions workflow results on that exact SHA are recorded in
§R, checked directly against the pushed commit (not a prior one), per the Phase 9 "CI green only
on previous SHA" lesson.

## R. Two cosmetic Compose layout fixes (found and corrected before push)

Live on-device screenshots (not any automated test) surfaced two instances of the same layout
class the Phase 9 correction pass already fixed once: an unweighted trailing element in a `Row`
claims its full natural width and squeezes a `weight(1f)` sibling into a narrow, mid-word wrap.

1. `RailwayLinePickerScreen`'s assignment-filter chip row: "Hozzárendelés nélküli" wrapped its
   own label mid-word. Fixed by changing the chip `Row` to a `FlowRow` (`androidx.compose.foundation.layout.FlowRow`,
   `ExperimentalLayoutApi`), so a chip that doesn't fit wraps the whole row instead of
   squeezing.
2. Both `ServiceAreaDetailScreen`'s mapped-line rows and `RailwayLinePickerScreen`'s own line
   rows: a long, realistic RailwayLine display name was squeezed by an unweighted trailing
   status label / unassign button. Fixed by restructuring both from a side-by-side `Row` to a
   stacked `Column` (name/code, then the trailing action or status on its own line).

Both fixes were verified live at both normal (1.0) and 1.3 font scale after the fix (§P, second
pass) — no further wrapping issues found. `./gradlew :service-app:compileDebugKotlin` and the
full regression in §Q were both re-run clean after these fixes, and the fixes are included in
the final pushed HEAD.

## S. Owner screenshots

23 screenshots sent alongside this report (exceeds the ≥15 minimum), using realistic Hungarian
throwaway names (e.g. "Keleti Teruleti Kozpont", "Szombathelyi Teruleti Kozpont",
"Szombathely-Koszeg vasutvonal"): the full create → assign → move → unassign → deactivate
(blocked, then succeeding) → reactivate lifecycle at normal font scale, plus 5 screenshots from
the dedicated font-scale-1.3 / layout-fix verification pass in §P.

## T. Known limitations

- The emulator-host networking flakiness described in §P is an environment characteristic of
  this Windows/WHPX development machine, not a product defect; it does not affect the real
  backend, which was independently confirmed reliable throughout.
- No Phase 11 (analytics) or Phase 12 (audit query/UI) work was started, per the brief and the
  owner's explicit instruction.
- **Owner visual approval: PENDING.** No PR has been opened. No merge has occurred. This report
  and the accompanying screenshots are submitted for that approval.
