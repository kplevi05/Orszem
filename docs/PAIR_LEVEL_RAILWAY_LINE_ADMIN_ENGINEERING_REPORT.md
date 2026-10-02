# Pair-level railway lines in the Service app's ServiceArea administration — engineering report

**Status: draft PR, awaiting owner review.** No production data, reference dataset, county-v2 policy,
routing snapshot or `v2.0.4` tag/Release was touched. Nothing was deployed, imported or applied.
This is code, tests and documentation only.

## 1. The defect (as observed in production v2.0.4)

Production now carries 146 pair-level `(settlement, railway line) → ServiceArea` mappings and 0
line-level ones (`service_area_railway_lines = 0`). The v2.0.4 Service Android app's
"Vasútvonal kiválasztása" screen still models only the old whole-line `RailwayLine → ServiceArea`
mapping, so:

- line 1 was shown as **"Nincs szolgálati területhez rendelve"** although it is pair-routed
  (Tata + line 1 correctly routes to Székesfehérvár);
- it appeared in the **"Hozzárendelés nélküli"** filter;
- a SUPER_ADMIN could try to assign the *whole* line (e.g. to Budapest) — which the backend
  correctly refuses with `409 SETTLEMENT_LINE_MIXED_ROUTING_MODES` (ADR 0011 Decision 2) —
- and the app, not knowing that code, showed only **"Váratlan hiba történt."**

The backend was already right; the bug was the *read model* (the list could not say "per
settlement") and the client.

## 2. Architecture inspected

**Backend** (`areaadmin`): the list query joined only `service_area_railway_lines`; a null whole-line
area was the only "state"; `ASSIGNED`/`UNASSIGNED` filtered on exactly that. The write path was
already correct: `AssignRailwayLineUseCase` throws `MIXED_ROUTING_MODES` when
`hasSettlementLineMappings(line)`, `SettlementLineMappingUseCase.validate` refuses the reverse, and
everything is SUPER_ADMIN-only. `GET .../settlement-line-mappings` exists but is paged by *area*, with no
per-line filter.

**Service Android** (`servicearea`): `RailwayLineAdminListItemResponse` carried only the whole-line area;
the picker row derived its label from it; `errorMessageRes` had no entry for any `SETTLEMENT_LINE_*` code.

## 3. API design — extend the list DTO, or add a detail endpoint? (investigated, not assumed)

| Option | Verdict |
|---|---|
| **A. List DTO only** (embed each line's settlements in the list rows) | Rejected. Payload grows with `lines × settlements` (146 pairs today, unbounded later) for data most rows never show; couples a paged list to reference-data growth; every list refresh re-sends everything. |
| **B. Detail endpoint only** (list unchanged) | Rejected. The list could not show a line's *mode* without one request per row — exactly the N+1 the task forbids — and the "unassigned" filter would remain wrong (it is a server-side filter). |
| **C. Hybrid (chosen)** | Scalar, **additive** fields on the list (`assignmentMode`, `settlementMappingCount`) computed in the same single query, plus a separate, bounded, read-only detail endpoint fetched **on demand for one line**. |

Why C is the most compatible: older clients ignore unknown JSON fields (`ignoreUnknownKeys` is set in the
app), `currentServiceAreaId/Name` keep their meaning, `ASSIGNED` keeps its legacy meaning, and the
only changed semantics is the deliberate bug fix (`UNASSIGNED` no longer contains pair-configured
lines). A newer client talking to an *older* backend degrades to the legacy inference (documented:
deploy the backend first).

### 3.1 Backend changes

- `GET /service/service-area-admin/railway-lines`: rows gain `assignmentMode`
  (`UNASSIGNED | WHOLE_LINE | PER_SETTLEMENT`) and `settlementMappingCount`; one grouped LEFT JOIN
  (`p`, one row per line) so a line's row can never be multiplied. `assignment` accepts the new
  `PER_SETTLEMENT`; `UNASSIGNED` = no whole-line **and** no pair mapping; unknown values still
  fall back to `ALL` (unchanged).
- **New** `GET /service/service-area-admin/railway-lines/{id}/settlement-mappings` (SUPER_ADMIN, read-only):
  `{ railwayLineId, lineCode, displayName, active, assignmentMode, settlementCount, items[], truncated }`,
  each item `{ settlementId, kshCode, settlementName, countyName, settlementActive, serviceAreaId,
  serviceAreaName, serviceAreaActive }`. The area is the *effective* one (pair mapping, else the line's
  whole-line mapping, else null). **One joined query** for all rows plus one count — no per-settlement or
  per-area lookup. Hungarian ordering is done in the application with `java.text.Collator(hu)` (never an
  assumed database collation), tie-broken by KSH code. Bounded at 500 rows with an exact `settlementCount`
  and an honest `truncated` flag. Empty → `items: []`; unknown line → `404 RAILWAY_LINE_NOT_FOUND`.
- No routing reason, evidence, reference/dataset version, revision (`adminVersion`) or any internal field
  is in either response (asserted by a test on the raw body).
- **Unchanged and re-asserted:** the whole-line `assign`/`unassign` still reject a pair-configured line
  (`409`, mixed modes / assignment-changed), only SUPER_ADMIN may read or write, no write path was added.

### 3.2 Service Android changes

- DTOs (`assignmentMode`, `settlementMappingCount` with safe defaults; the detail DTOs), API call,
  repository method, `RailwayLineAssignmentFilter.PER_SETTLEMENT`.
- A small **pure** presentation layer (`servicearea/domain/RailwayLineAssignmentState.kt`):
  `routingMode()`, `lineRowStatus()`, `canOfferWholeLineAssign()` — unit-testable without Compose.
- **Picker row**: a pair-configured line reads **"Településenként hozzárendelve"** with "Beállított települések: N"
  and a hint that it cannot be assigned as a whole; it is **not clickable** and never reaches the assign
  confirmation. A new **"Településenként"** filter chip. "Hozzárendelés nélküli" now lists only genuinely
  unassigned lines (server-side).
- **Settlement detail**, in the same card, behind a separate toggle ("Települések megtekintése/elrejtése"):
  title **"Jelenleg elérhető települések ezen a vonalon"**, note **"A lista az ellenőrzött referenciaadatok
  bővítésével frissül."** (the owner's exact wording; no `PARTIAL`/`VERIFIED`/evidence/reason/internal-id
  text anywhere), then each settlement with its ServiceArea (or "Nincs szolgálati területhez rendelve"),
  inactive settlement/area marked in words. Opening the list **never selects or assigns** the line; it is
  fetched once per line on demand (no per-row request when the list is drawn); a bounded scroll window
  (`heightIn(max = 280.dp)`) for long lists; empty, loading, failed-with-retry and truncated states.
- **Accessibility**: the toggle announces the line, the number of configured settlements and
  "nyitva/zárva" (`contentDescription` + `stateDescription`); the title is a heading; each settlement is one
  merged node; every state is spelled out in words, never by colour alone; layout uses `wrap`/`heightIn`,
  no fixed heights, so large system fonts do not break the card.
- **Localised error**: `SETTLEMENT_LINE_MIXED_ROUTING_MODES` → "Ez a vonal településenként van konfigurálva,
  ezért nem rendelhető teljes egészében egyetlen szolgálati területhez. A vonal településpárjai külön-külön
  rendelhetők területhez." — so an old/stale client that still calls the old endpoint tells the user *why*.
- `confirmAssign` additionally refuses (no network call) for a pair-configured line — defence in depth;
  the backend remains the authority.

## 4. Tests

| Area | Tests |
|---|---|
| Backend `RailwayLinePairLevelAdminIT` (new, 15; Testcontainers/PostgreSQL) | pair line listed `PER_SETTLEMENT` with count, null whole-line area; **excluded from `UNASSIGNED`**, included in `PER_SETTLEMENT`, excluded from `ASSIGNED`, the three modes partition the whole list; whole-line fixture and free line unchanged; **old whole-line assign (and a "move") → 409 `SETTLEMENT_LINE_MIXED_ROUTING_MODES`, nothing changes**; old unassign refused; existing snapshots and per-pair routing unchanged; detail lists mapped/unmapped settlements with the right areas and **exposes no internal field**; **Hungarian ordering** (a case where binary order differs); whole-line-mode detail; empty list; **long list (505 → 500 + `truncated`)**; inactive settlement flagged; unknown line 404; **SUPER_ADMIN only (403 for every other role, 401 anonymous)**; reading changes nothing |
| Backend regression | the full suite (see §5), incl. `SettlementLineMappingIT`, `RailwayLineAdminIT`, `AreaAdminAuthorizationIT`, `AreaAdminRoutingImmutabilityIT`, routing and workflow suites |
| Android unit `PairLevelRailwayLineTest` (new, 17) | pair line status ≠ unassigned; three states stay distinct; old-backend fallback; DTO decoding incl. unknown future mode; whole-line assign offered only where it can succeed; assign for a pair line never reaches the network; **localised mixed-mode error** (and an old client surfacing it); detail fetched once, never assigns; **no N+1** (a 50-line list triggers 0 detail requests); empty / long / truncated; retry; session end; the DTOs carry no internal field; the **owner-approved strings are exactly as specified and contain no internal terminology** |
| Android instrumented `RailwayLinePickerPairLevelComposeTest` (new, 4; **run on an emulator**) | pair line labelled and **not clickable** while a free line is clickable; list opens with the approved wording and never assigns; toggle semantics (name, count, open/closed); empty list wording |
| Existing Android | unit + the whole instrumented suite (**104 tests, 0 failures**) incl. the pre-existing four-state picker test; `ErrorCopyTest` extended |

Note on the emulator: the first full instrumented run showed 8 failures in unrelated screens
(analytics, moderation, workflow, and the existing picker test). Root cause was a **leftover `wm size
840x1774` display override** on the local `orszem-test` AVD (a 320dp-wide, short viewport pushes content
off-screen), not this change; after `wm size reset` the same suite is **104/104 green**. The
override was reset on the local emulator.

## 5. Regression (this branch)

Run locally on this branch:

| Suite | Result |
|---|---|
| Backend `test --rerun --no-build-cache` (Testcontainers/PostgreSQL 16) | **902 tests, 0 failures, 0 skipped** (887 existing + 15 new), BUILD SUCCESSFUL in 5 m 51 s |
| Android `:public-app:assembleDebug :service-app:assembleDebug`, both apps' unit tests, `lint` | BUILD SUCCESSFUL |
| Android Service unit tests | all green, incl. the 17 new and the extended `ErrorCopyTest` |
| Android Service instrumented, emulator API 35, default 1080x2280 | **104 tests, 0 failures** (incl. the 4 new Compose tests and the pre-existing four-state picker test) |
| Public Web `npm ci`, `typecheck`, `npm test`, `build` | 74/74 tests, build OK (no Web code changed) |
| Python/reference-data `unittest discover` | 75/75 |
| Canonical dataset validators (`example`, `evaluation`, `cleared`) | all valid |

CI is the final gate on the PR itself.

## 6. Compatibility and rollout notes

- **Deploy order: backend first, then the app.** The new app against an old backend degrades to the
  legacy inference (it cannot tell a pair line from a free one). The old app against the new backend is
  unaffected (additive fields; the changed filter is the intended fix).
- No Flyway migration, no schema change, no reference-data change.
- Not changed: the 146 production mappings and their semantics, routing/routing snapshots, the
  reference dataset, the county-v2 policy, `ReportWorkflowPolicy`/`AreaScopePolicy`, the Public clients.

## 7. Decisions worth the owner's attention

1. **ADR 0011 gained an addendum (Decision 6)** recording the read-model decision (§3).
2. **Unassign of a pair-configured line** still answers `409 RAILWAY_LINE_ASSIGNMENT_CHANGED` (the line has
   no whole-line mapping to remove). It is not reachable from the new UI; I did not change it to keep the
   write path untouched. A dedicated code would be a one-line follow-up if wanted.
3. **Pair-level editing is out of scope.** The app can now *show* per-settlement assignments; changing them
   remains the existing SUPER_ADMIN `settlement-line-mappings` preview/apply API (no UI), as before.
4. The ServiceArea **detail** screen still shows whole-line lines and the pair *count* only; a per-area
   list of its pair mappings would be a separate feature.
5. This fix needs a **new signed Service APK** to reach users (the release/signing flow is unchanged and
   owner-gated).
