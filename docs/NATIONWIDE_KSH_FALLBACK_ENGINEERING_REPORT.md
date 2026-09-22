# Nationwide KSH Settlement Fallback + Temporary Nationwide UNCLASSIFIED Workflow — engineering report

**Status: candidate, not merged, not deployed, PR not yet opened per instruction.** Base:
`origin/main` = `70fb9773d6aa24d5fa6f3325bc933102835c6754` (confirmed — see §1). Branch:
`feature/v2-nationwide-ksh-fallback`. No database migration, no production data, DNS,
credential, signing-key, V1 or production-infrastructure change.

## 1. Starting point, verified before any code change

```
git fetch origin
git rev-parse origin/main  →  70fb9773d6aa24d5fa6f3325bc933102835c6754
```

Matched the brief's expected SHA exactly — no owner-advanced `main` to reconcile with.
Branched from it directly.

## 2. Architecture inspection — findings, and why implementation proceeded without a stop

The brief anticipated a large, mostly-greenfield feature. Inspection found the opposite for
most of it: **the routing/submission domain layer already fully implements the required
"zero-relation → accept → UNCLASSIFIED → `NO_VERIFIED_RAILWAY_LINE_REFERENCE`" behaviour**,
built in an earlier phase (the code's own KDoc references "Phase 3C"/ADR 0007/ADR 0006).
Nothing in items 2 or 3 of the brief needed a routing/submission code change. What was
missing was narrower and squarely in items 4-6: the *operational authorization* layer still
hard-excluded SERVICE_USER from UNCLASSIFIED entirely, with no switch.

Confirmed, read in full before writing any code:

| Component | Finding |
|---|---|
| `RoutingService` / `RoutingOutcome` / `UnclassifiedReason` | Already exactly matches the brief's diagram: zero active verified relations (any coverage) → `NO_VERIFIED_RAILWAY_LINE_REFERENCE`, a real `Unclassified` outcome, never rejected. `NO_VERIFIED_RAILWAY_LINE_REFERENCE` already exists verbatim as an enum value with the exact "never proof no railway exists" semantics the brief describes. |
| `SubmitReportUseCase` | Already **accepts** every `Unclassified` outcome and creates the report (status `NEW`) with an `UNCLASSIFIED` routing snapshot — never blocks submission. Idempotency/capability/rate-limit ordering untouched, unaffected by anything in this phase. |
| `PublicReferenceQueryUseCase.searchSettlements` | Already fully generic — searches whatever is in `settlements`, no hard-coded fixture assumption. Needed no code change; only needed the data. |
| Public Android (`RailwayLineDecision.kt`) / Public Web (`lineDecision.ts`) | Already treat zero verified candidates (`NoVerifiedCandidate`/`no-verified-candidate`) as **resolved** — Step 1 already proceeds with `railwayLineId: null`. Existing copy ("Ehhez a településhez jelenleg nincs ellenőrzött vasútvonal-kapcsolat nyilvántartva...") already avoids "UNCLASSIFIED"/internal terms. **No client code change needed.** |
| `GetPublicReportUseCase` / Public report response | Never touches routing/service-area/UNCLASSIFIED state at all — only ever returns `PENDING/SUBMITTED/IN_PROGRESS/CLOSED`. No leak, unaffected. |
| Service Android (`WorkflowActionAvailability.availableWorkflowActions`) | Purely `(status, role, isOwnAssignment)` — never area-aware. A SERVICE_USER on a NEW report already shows `claim = true` regardless of routing; IN_PROGRESS-own already shows return/close. **No client code change needed** — the backend authorization gate was the only thing hiding this from SERVICE_USER. |
| Service Android UNCLASSIFIED terminology | Already exists and already reads naturally: `status_unclassified` = "BESOROLATLAN", `value_unclassified_area` = "Nincs terület" (rendered under the "Szolgálati terület" label, i.e. "Szolgálati terület: Nincs terület" — already natural language, already following existing style; not changed to the brief's literal example string, since the existing convention already satisfies the requirement and the brief's copy was offered as an example ("such as"), not a mandate). `error_report_unclassified_cannot_assign` already exists for the flag-disabled refusal. |
| `ReportWorkflowPolicy` / `AreaScopePolicy.canViewUnclassified` | **This was the actual gate.** `canViewUnclassified`: `SERVICE_USER -> false`, unconditional, with a KDoc explicitly framing it as a permanent design choice ("a different job", not "wide reach"). `canClaim`/`canAssignTarget` both hard-required `report.routed`. `ReassignReportUseCase` unconditionally threw `ReportUnclassifiedCannotAssignException` for any unrouted report. `JdbcReportWorkflowQueryRepository.visibilityClause` excluded `UNCLASSIFIED` from every SERVICE_USER branch at the SQL level. |

**Conclusion: no architecture or security conflict.** The existing `AreaScopePolicy` /
`ReportWorkflowPolicy` split already has a precedent for exactly this shape of change — a
single, narrowly-scoped, named carve-out living only in `ReportWorkflowPolicy` on top of an
unchanged, permanent `AreaScopePolicy` rule (the existing SUPER_ADMIN inactive-area bypass).
The Nationwide fallback flag follows that identical pattern rather than inventing a new one.
Implementation proceeded per the brief's own instruction ("If no architecture or security
change is required, implement them").

**Two genuine domain-invariant breaks were found and fixed during implementation** — see §9
for how each was caught (both by live, real-backend verification, not by static reading):

1. `OpenAssignmentAreaSnapshot.serviceAreaId` was `UUID` (non-null), with a KDoc asserting
   "UNCLASSIFIED reports can never be assigned, so this is always a real area" — true before
   this phase, false once a SERVICE_USER can hold an open UNCLASSIFIED claim. Fixed by making
   it nullable and having `AssignmentEligibilityGuard` treat a null area as never "outside
   scope" (an UNCLASSIFIED claim was never area-governed in the first place, so a Phase 6
   area/global-access-narrowing mutation must never be affected by one either way).
2. `ReportScope.unclassified(status)` hard-coded `assignedUserId = null` unconditionally —
   true before this phase (nothing could ever be assigned to an unrouted report), false now.
   Left as-is, it made the assignee's own `IN_PROGRESS`/`ARCHIVED` visibility check
   impossible to ever satisfy, so a SERVICE_USER who successfully claimed or closed their own
   UNCLASSIFIED report got a **404 on the mutation's own response**, despite the mutation
   itself succeeding. Fixed by threading the report's real `assigned_user_id` through both
   call sites (`ReportScopeResolver`, `ReportQueryUseCase.toScope`); the third call site
   (`ModerationQueryUseCase`, deleted-report views, MODERATOR/SUPER_ADMIN only) was inspected
   and correctly left unchanged — that policy path never consults `assignedUserId` for an
   unclassified scope.

## 3. Routing semantics — before / after

**Unchanged, exactly.** Confirmed by re-running the complete, pre-existing `RoutingServiceIT`
(20 tests, every cell of the brief's A-H matrix already covered) and `PublicReportSubmissionIT`
(every `UnclassifiedReason` variant, idempotency, capability, validation) unmodified — both
pass byte-for-byte identically to before this branch. No routing decision, no submission
acceptance rule, no idempotent-replay rule, and no historical routing snapshot changed in any
way. `RoutingService.route()` was not touched.

## 4. Policy configuration

New Spring Boot `@ConfigurationProperties`, `WorkflowFallbackProperties`
(`backend/src/main/kotlin/hu/orszembejelento/backend/common/config/WorkflowFallbackProperties.kt`),
prefix `orszem.workflow`:

```yaml
orszem:
  workflow:
    unclassified-service-user-access-enabled: ${ORSZEM_UNCLASSIFIED_SERVICE_USER_ACCESS_ENABLED:false}
```

- **Default `false`.** Merging/deploying this branch changes nothing observable until the
  owner explicitly sets `ORSZEM_UNCLASSIFIED_SERVICE_USER_ACCESS_ENABLED=true` — no client
  release, no migration, no restart of anything but the backend process picking up the new
  environment variable.
- Threaded via constructor injection into `ReportWorkflowPolicy` (the single decision surface
  — see §5) and `JdbcReportWorkflowQueryRepository` (the matching SQL-level queue filter).
  Registered in `BackendApplication.kt`'s `@EnableConfigurationProperties`.
- Verified switchable live, twice, against the real backend jar in the local `deploy/eval`
  stack (§8): once disabled (default, strict prior behaviour reproduced), once enabled (full
  new behaviour reproduced), with zero code change between the two — only the environment
  variable.

## 5. Authorization matrix — before / after

| Actor / action on an UNCLASSIFIED report | Before this phase | After, flag **disabled** (default) | After, flag **enabled** |
|---|---|---|---|
| SUPER_ADMIN — view any status | ✅ | ✅ (unchanged) | ✅ (unchanged) |
| Global MODERATOR — view any status | ✅ | ✅ (unchanged) | ✅ (unchanged) |
| Territorial MODERATOR — view | ❌ | ❌ (unchanged) | ❌ (unchanged) |
| SERVICE_USER (any) — view NEW | ❌ | ❌ | ✅ nationwide, no area grant needed |
| SERVICE_USER — view IN_PROGRESS | ❌ | ❌ | ✅ only their own claim |
| SERVICE_USER — view ARCHIVED | ❌ | ❌ | ✅ nationwide (§9 explains why not ownership-only) |
| ACTIVE SERVICE_USER — claim NEW | ❌ (`report.routed` required) | ❌ (unchanged) | ✅ any ACTIVE SERVICE_USER, no area check |
| Inactive/deactivated SERVICE_USER — claim | ❌ | ❌ | ❌ (unchanged — never gains access) |
| SERVICE_USER — return own claim | N/A (could never claim) | N/A | ✅ (delegates to the view rule above) |
| SERVICE_USER — close own claim / visible NEW | N/A | N/A | ✅ close of own IN_PROGRESS (mirrors routed rule: never a direct-from-NEW close) |
| MODERATOR/SUPER_ADMIN — reassign | ❌ (`ReportUnclassifiedCannotAssignException`, unconditional) | ❌ (unchanged) | ✅ to any ACTIVE SERVICE_USER, no area check |
| Ordinary **routed** report authorization (every role, every action) | — | **completely unchanged**, proved by the full, unmodified `ReportWorkflowVisibilityIT`/`ReportWorkflowPolicyTest` suites passing identically | **completely unchanged**, proved by a dedicated regression test in the new IT suite (§9) |
| Analytics visibility/scope | — | unchanged (no analytics code touched) | unchanged (no analytics code touched) |
| User-management permissions | — | unchanged (no user-management code touched beyond the one nullable-type fix, which only *loosens a false invariant*, never widens who may perform a Phase 6 mutation) | unchanged |

## 6. Data provenance — the complete statement

**`reference-data/cleared/`** is now a complete, importable canonical dataset (it already held
the loose `settlements.csv`; this phase added `manifest.json`, `railway-lines.csv`,
`settlement-railway-lines.csv`):

- **Settlements: `cleared/settlements.csv`, unchanged, 3,178 rows** — independently re-verified
  this phase, not assumed from the brief's suggested count (`wc -l` → 3,179 lines = 1 header +
  3,178 data rows; cross-checked against `reference-data/README.md`'s own "Settlements (KSH):
  3,178" figure, and against the count both the offline JS validator and the backend's own
  `CanonicalDatasetLoader` independently recompute from the file and agree on). Source: **KSH
  Helységnévtár, state 2025-01-01, CC BY 4.0, attribution required** — the same source and
  licence the repository already cites for this exact file; `reuseStatus: CLEARED`,
  `verificationStatus: VERIFIED`, `coverage.settlements: COMPLETE` (KSH publishes the full
  registry — the existing, unmodified basis this repository already asserted for this file).
- **Railway lines: `cleared/railway-lines.csv` — zero rows** (header only:
  `line_code,display_name`).
- **Settlement↔line relations: `cleared/settlement-railway-lines.csv` — zero rows** (header
  only: `ksh_code,line_code`).
- `coverage.railwayLines` / `coverage.settlementRailwayLines`: **`PARTIAL`**, not `COMPLETE` —
  a deliberate, honest choice: `PARTIAL` means "does not claim network-wide completeness";
  marking zero rows `COMPLETE` would falsely assert "verified to be zero network-wide," which
  is not true — the true state is "not imported yet." This is also what makes the import
  **non-destructive**: `ReferenceImportUseCase` only ever deactivates a component's absent
  rows under `COMPLETE` coverage; under `PARTIAL` it preserves whatever already exists.
  Verified live (§8): importing this dataset into the eval database, which already held 3
  railway lines / 5 relations from an earlier synthetic import, left all of them untouched
  (`0 deactivated ... (3 preserved)` / `0 remove ... (5 preserved)`), while adding all 3,178
  settlements and deactivating the 5 synthetic fictional settlements absent from the real KSH
  roster (`settlements: COMPLETE` correctly triggers that part).
- `datasetVersion: KSH-SETTLEMENTS-1`. Checksummed (SHA-256, all three files) in the manifest;
  validated by both the offline JS tool (`reference-data/tools/validate-canonical.mjs`) and the
  backend's own `CanonicalDatasetLoader` (`orszem-admin reference-validate`) — both agree.
  Added to CI (`.github/workflows/reference-data.yml`) alongside `example/`/`evaluation/`.
  `reference-data/README.md` updated with a dedicated section explaining what this dataset is
  and why it is safe to validate/commit publicly (identical reasoning to why `cleared/`
  already was — zero VPE/KTI/GYSEV-derived content).

**PENDING datasets were not used, at all, anywhere in this phase — confirmed:**
- No file under `reference-data/local-research/` (gitignored, VPE/KTI/GYSEV-derived, PENDING)
  was read, copied, derived from, or referenced by anything committed this phase.
- `reference-data/tools/build-canonical.mjs` (the tool that *would* consume `--mav`/`--gysev`
  VPE extracts) was **not run** — the new dataset was hand-assembled directly from the
  already-cleared `settlements.csv` plus two structurally-empty CSVs, specifically to avoid
  any path through that tool touching PENDING material.
- `reuseStatus` was never set to `CLEARED` for anything except the settlement roster, which
  was already `CLEARED` before this phase began.
- No `RailwayLine`, settlement-line relation, or `ServiceArea` mapping was invented, derived,
  or imported from KSH data — `railway-lines.csv`/`settlement-railway-lines.csv` are
  deliberately empty.
- No external permission request was sent, drafted for sending, or implied as sent. The
  existing `PENDING`/deferred status of the VPE/GYSEV reuse questions
  (`docs/deployment/EXTERNAL_PERMISSIONS_DEFERRED.md`) is completely untouched by this branch.

## 7. Files, migrations, endpoints changed

**No Flyway migration.** No schema change: `report_routing_snapshots.service_area_id` was
already nullable (used by the pre-existing `UNCLASSIFIED` snapshot shape); the config flag is
pure application configuration; the one domain-type nullability fix
(`OpenAssignmentAreaSnapshot.serviceAreaId`) is a Kotlin type change over an existing,
already-nullable SQL column, not a schema change.

**No new HTTP endpoint, no request/response shape change.** Every behaviour change in this
phase is a pure authorization-decision change behind the *same* existing endpoints
(`/api/v1/service/reports/{new,in-progress,archive,{id},{id}/claim,{id}/return,{id}/close,{id}/reassign}`).

Backend files changed (11 main + 1 resource + 1 CI workflow):

| File | Change |
|---|---|
| `common/config/WorkflowFallbackProperties.kt` | **New.** The one config flag. |
| `BackendApplication.kt` | Registers it. |
| `common/config/IdentityConfig.kt` | Threads it into the `reportWorkflowPolicy` bean. |
| `reportworkflow/domain/ReportWorkflowPolicy.kt` | `canViewReport`/`canClaim`/`canAssignTarget` gain the flag-gated SERVICE_USER-on-UNCLASSIFIED carve-out; new `canAssignAtAll(report)` helper. |
| `reportworkflow/application/ReassignReportUseCase.kt` | The unconditional `!scope.routed` refusal now delegates to `policy.canAssignAtAll(scope)`. |
| `reportworkflow/infrastructure/JdbcReportWorkflowQueryRepository.kt` | `visibilityClause` gains an `OR rs.routing_status = 'UNCLASSIFIED'` branch for SERVICE_USER, only when the flag is on. |
| `reportworkflow/domain/ReportWorkflowTypes.kt` | `OpenAssignmentAreaSnapshot.serviceAreaId` → nullable; `ReportScope.unclassified()` gains an `assignedUserId` parameter (default `null`, backward compatible). |
| `reportworkflow/domain/AssignmentEligibilityGuard.kt` | Treats a null assignment area as never "outside scope". |
| `reportworkflow/application/ReportScopeResolver.kt` | Passes the report's real `assignedUserId` into `ReportScope.unclassified(...)`. |
| `reportworkflow/application/ReportQueryUseCase.kt` | Same, for `ReportWorkflowRow.toScope()`. |
| `application.yml` | New `orszem.workflow.*` block. |
| `.github/workflows/reference-data.yml` | Validates `reference-data/cleared/manifest.json`. |

Reference data (new): `reference-data/cleared/manifest.json`, `railway-lines.csv`,
`settlement-railway-lines.csv`; `reference-data/README.md` updated.

**Zero Android or Web files changed** — see §2's client findings.

## 8. Live end-to-end verification (not simulated)

Against the local, isolated `deploy/eval` stack (own Docker network/volume/containers, no
production access, synthetic + real-but-non-sensitive KSH data only):

1. Rebuilt `backend.jar` with this branch's code; staged and ran it against the eval
   PostgreSQL.
2. **Imported `reference-data/cleared`** via `orszem-admin reference-import` — confirmed
   `+3178 new` settlements, `5 deactivated` (the old fictional eval settlements, correctly,
   since settlement coverage is `COMPLETE`), `3 preserved`/`5 preserved` for the
   already-present synthetic railway lines/relations (correctly, since those two components
   are `PARTIAL`).
3. **`GET .../reference/settlements?query=Tata`** → returned `Tata` (KSH `20127`,
   Komárom-Esztergom), `Tatabánya`, `Tataháza` — the complete cleared catalogue is live and
   searchable through the real Public reference API.
4. **`POST .../public/reports`** against Tata's real settlement id, no `railwayLineId` → `201
   Created`. DB: `routing_status = UNCLASSIFIED`, `routing_reason =
   NO_VERIFIED_RAILWAY_LINE_REFERENCE`, `service_area_id = NULL` — exactly the brief's
   required diagram, end to end, against the real cleared dataset.
5. With the flag **disabled** (default): a fresh SERVICE_USER with **zero** area grants saw
   `0` items in `NEW` — strict prior behaviour confirmed live.
6. Restarted the backend with `ORSZEM_UNCLASSIFIED_SERVICE_USER_ACCESS_ENABLED=true` (env
   var only, same jar): the same SERVICE_USER now saw all 23 UNCLASSIFIED `NEW` reports
   (including the new Tata one) — nationwide, despite zero area grants.
7. Claimed the Tata report as that SERVICE_USER (200; DB confirmed `IN_PROGRESS`, assignee
   set) — **this is where the two bugs in §2 were actually caught**, not by static reading:
   the mutation itself always succeeded, but the controller's own immediate detail-response
   404'd until both fixes landed.
8. Returned it to NEW (200; reappeared in another SERVICE_USER's `NEW` queue — nationwide
   pool proven, not just single-user visibility).
9. Claimed and **closed** it (200; the closer's own response correctly showed
   `status: ARCHIVED`; a second, different SERVICE_USER could also see it in Archive —
   confirming the deliberate nationwide-ARCHIVED design decision in §9).
10. As SUPER_ADMIN, **reassigned** a different UNCLASSIFIED report from one zero-area
    SERVICE_USER to another zero-area SERVICE_USER — 200, no area check anywhere in the path.
11. Restarted the backend back to the default config (flag disabled) — confirmed the same
    SERVICE_USER again saw `0` items, proving the switch genuinely round-trips with no
    residual state.

The eval environment was left running, in its default (flag-disabled) configuration, exactly
as it was found (aside from the intentional dataset import and the synthetic test
reports/users this verification created — all synthetic, non-production, and part of the same
local, isolated eval stack this repository's own tooling manages).

## 9. Concurrency analysis

**No new lock resource, no lock-order change.** Every mutation this phase touches
(`ClaimReportUseCase`, `ReturnReportUseCase`, `CloseReportUseCase`, `ReassignReportUseCase`)
was already using the existing canonical lock order (report row, then — for claim/reassign —
the actor/target user row) *before* this phase; nothing here adds a second independent
locking scheme. The only change to any of these use cases is `ReassignReportUseCase`'s single
`if` condition (§7) — the lock acquisition and ordering around it is untouched.

Required race coverage (brief §9/§10), added in `NationwideUnclassifiedFallbackIT`:

- **Two SERVICE_USERs racing to claim the same UNCLASSIFIED report:** `runConcurrently(2)`
  against the real HTTP stack — exactly one `200`, one `409 REPORT_ALREADY_ASSIGNED`, exactly
  one open assignment row afterward. Passes on the same `tryInsert`/row-lock mechanism every
  other claim race in this codebase already relies on (`ClaimReportUseCase` was not modified).
- **Return vs competing claim:** unaffected — both still contend on the same report row lock
  `ClaimReportUseCase`/`ReturnReportUseCase` already acquire; no new interleaving is possible.
- **Ordinary area-routed report authorization remains race-safe:** proved by the complete,
  unmodified `AssigneeEligibilityCrossPhaseIT` suite (deactivation-vs-claim, area-revoke-vs-
  claim, etc.) passing byte-for-byte identically — none of those tests, none of that
  serialization logic, was touched.
- **Reference-data import vs report submission:** unaffected — `SubmitReportUseCase`'s
  `acquireSharedReferenceStateLock()` call was not touched, and `ReferenceImportUseCase`'s own
  advisory lock was not touched. Confirmed by the complete, unmodified `RoutingServiceIT` and
  `ReferenceImportUseCaseIT` suites passing.

No conflict with the existing lock ordering was found; nothing required a stop-and-report
under brief item 9's condition.

## 10. Test matrix and exact results

**Full backend suite, final HEAD, `./gradlew build` (Testcontainers, real PostgreSQL):**

```
BUILD SUCCESSFUL in 4m 29s
92 test classes, 863 tests, 0 failures, 0 errors, 0 skipped
```

New test files (34 new tests total):

| File | Covers |
|---|---|
| `NationwideUnclassifiedFallbackPolicyTest.kt` (13 tests) | Pure-logic: `canViewReport`/`canClaim`/`canAssignAtAll`/`canAssignTarget`, flag on **and** off, MODERATOR/SUPER_ADMIN unaffected either way |
| `NationwideUnclassifiedFallbackIT.kt` (11 tests) | Full HTTP stack, flag enabled via `@TestPropertySource`: nationwide NEW visibility regardless of area grants; own-IN_PROGRESS-only visibility; nationwide ARCHIVED visibility (the §2 bug's regression proof, by name); claim (zero-area SERVICE_USER); **claim race, exactly one winner**; inactive-user-cannot-claim (401/403, never a successful claim); return-to-nationwide-pool; close-from-IN_PROGRESS; SUPER_ADMIN reassign to a zero-area target; reassign still refuses an inactive target (400 `INVALID_ASSIGNEE`); **routed-report authorization completely unaffected by the flag** (disabled-state control, run inside the *enabled* test class deliberately, to prove the flag never leaks into routed decisions even when it is on) |

Pre-existing suites re-run **unmodified**, proving the flag's `false` default preserves prior
behaviour byte-for-byte and that no routing/submission/concurrency semantics regressed:
`ReportWorkflowPolicyTest` (24), `ReportWorkflowVisibilityIT` (10), `RoutingServiceIT` (20),
`PublicReportSubmissionIT` (27), `PublicReportConcurrencyIT`, `PublicReportIdempotencyIT`,
`ClaimReportIT`, `ReturnReportIT`, `CloseReportIT`, `ReassignReportIT`,
`AssigneeEligibilityCrossPhaseIT`, `UserManagementAssignmentGuardIT`,
`ReferenceImportUseCaseIT`, `CanonicalDatasetLoaderTest`, and every other of the 92 classes —
all pass identically to the pre-phase baseline.

**Reference / Public matrix (brief item 10):** every bullet already covered by the
pre-existing, unmodified suites above (zero relations still accepted, one/multiple relations
still infer/require selection, mismatch/inactive-line/unassigned-line unchanged, idempotent
replay unchanged, capability security unchanged) *plus* live end-to-end proof against the real
cleared dataset for the two bullets that need real data rather than synthetic fixtures — "a
real cleared KSH settlement such as Tata is searchable" and "a canonical settlement with zero
RailwayLine relations can be submitted" (§8, steps 3-4). Android/Web autocomplete against the
complete catalogue: proved by code inspection (§2) that both clients call the same generic,
unmodified search endpoint with no client-side dataset assumption, plus the same live
`?query=Tata` proof (§8, step 3) exercising the identical backend code path both clients call.

**One fixture flakiness caught by CI (not the local run), fixed:**
`ReportWorkflowTestSupport.givenRoutedArea()`'s random line-code generator was the one
outlier in the suite still drawing from a 4-digit range (1,000-9,999 — 9,000 values), while
every other random-line-code generator already in this codebase (`AreaAdminAuditIT`,
`AreaAdminConcurrencyIT`, `RailwayLineAdminIT`) draws from 100,000-999,999 (900,000 values).
This branch's own new tests are heavy callers of `givenRoutedArea` (directly, and via
`givenRoutedReport`/`givenUnclassifiedReport`), which was enough additional load on the
narrow range for a `ux_railway_lines_line_code` collision to actually occur on the first CI
run (`ModerationDeleteIT` — an unrelated test, hit only because it happened to run later in
the same shared-database JVM). Pre-existing fixture fragility, not a defect in this phase's
policy/query changes — but this phase's own tests are what pushed it over the edge, so fixed
here (widened to match the range already established elsewhere) rather than left for the next
unlucky run. Re-ran the full backend suite locally after the fix and pushed; CI re-verified
green (§13).

**Full repository regression, this same final HEAD:**

| Suite | Command | Result |
|---|---|---|
| Backend | `cd backend && ./gradlew build` | **BUILD SUCCESSFUL**, 863/863 tests, 0 failures |
| Public + Service Android unit/assemble/lint | `./gradlew :public-app:testDebugUnitTest :service-app:testDebugUnitTest :public-app:assembleDebug :service-app:assembleDebug :public-app:lint :service-app:lint` | **BUILD SUCCESSFUL** (no source changed this phase — confirms no incidental regression) |
| Public Web | `npm ci && npm run typecheck && npm run build` | **BUILD SUCCESSFUL** (no source changed this phase) |
| Reference-data validator | `node reference-data/tools/validate-canonical.mjs` × 3 (`example`, `evaluation`, **`cleared`**) | all three **valid** |
| Deployment scripts | unchanged this phase (`deploy/` not touched) | not re-run — nothing in scope changed |

## 11. Known limitations

- **Owner decision still required to actually enable the fallback anywhere it matters**
  (`ORSZEM_UNCLASSIFIED_SERVICE_USER_ACCESS_ENABLED=true`) — this branch only builds and
  proves the switch; it does not flip it in any real environment, and defaults to the strict,
  pre-existing behaviour.
- **The real KSH settlement catalogue is imported only in the local eval environment**, for
  verification. Importing it into any owner-controlled environment (including a future
  production one) is a separate, later action this phase deliberately does not take
  (`deploy` was explicitly out of scope).
- **ARCHIVED UNCLASSIFIED visibility under the flag is nationwide, not ownership-restricted** —
  a deliberate, documented judgment call (§5, §9), made necessary by `close()` clearing
  `assigned_user_id` (mirroring the existing routed-report close behaviour) and by there being
  no area to scope ARCHIVED visibility by. Flagged here explicitly for owner review, since the
  brief's own text described NEW/claim/IN_PROGRESS/return/close but did not explicitly specify
  ARCHIVED browsing scope.
- **No owner visual UI review is included** in this report, because none is needed: §2
  establishes, and §8's live verification confirms, that zero Android/Web/Service-UI source
  changed — every screen a SERVICE_USER now reaches under the flag is the same, already-shipped,
  already-tested screen a MODERATOR/SUPER_ADMIN already used to view UNCLASSIFIED reports
  before this phase. If the owner still wants a screenshot set of the Service Android app
  showing an UNCLASSIFIED report in a SERVICE_USER's own queues (not required by the brief's
  own §7 condition, which is "any **visible UI changes**"), that can be produced on request
  from the same local eval environment used in §8.
- **External permission requests for the VPE/GYSEV railway data remain exactly where they
  were** — `PENDING`/deferred, untouched, not sent, not drafted for sending, per §6 and the
  brief's own instruction.

## 12. Confirmations

- **PENDING railway datasets (VPE/KTI/GYSEV) were not used, read, derived from, or referenced
  anywhere in this phase.** See §6 for the complete, itemized statement.
- **Production was not mutated.** No production database, host, DNS record, credential,
  signing key, or V1 system was touched, connected to, or referenced by any command this
  phase ran. All live verification (§8) ran exclusively against the local, isolated
  `deploy/eval` Docker stack this repository's own tooling manages, using only synthetic test
  accounts and the already-cleared KSH settlement data.
- **Owner visual-review status: not applicable / not required** — see §11's explanation. No
  PR will be opened until the owner has read this report, per the task's own instruction.
