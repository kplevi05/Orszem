# Phase 7 engineering report — Service report workflow + archive backend

**Branch:** `feature/v2-service-report-workflow` (not merged — no PR opened, per the brief)
**Base:** `main` @ `9af54aa` (PR #9, Phase 6 merged — including its own targeted pre-merge fix)
**Status:** implementation, full test battery, and the cross-phase invariant review vs
Phase 6 (§Q) all complete; all 5 CI workflows green; no Phase 8 work started.

---

## A. Git

| | |
|---|---|
| Starting `main` SHA | `9af54aa` (verified against live `origin/main` before branching, §0 of the brief) |
| Branch | `feature/v2-service-report-workflow` |
| Final SHA | this commit (a commit cannot name its own hash inside itself — see `git log -1` on the branch, or the session's closing report to the owner, for the exact hash) |
| Pushed | yes |
| PR | none opened — not requested by the brief, and explicitly not to be opened |

Commits, in order:

1. `5a1c4bb` — feat(backend): Phase 7 service report workflow + archive backend
2. `02f0552` — test(backend): Phase 7 service report workflow test battery
3. `5cc07e9` — fix(backend): claim revalidates the acting user's eligibility under lock (§Q.2, the post-review cross-phase fix)
4. `8931f12` — test(backend): cross-phase assignee-eligibility concurrency tests (§Q.4)
5. **this commit** — docs: Phase 7 engineering report, including the §Q cross-phase invariant review addendum

Inspection performed before writing any code (§0): confirmed the working tree was clean,
fetched `origin`, confirmed Phase 6 was merged into `main`, inspected V001-V003 (immutable),
the existing `reports`/`report_routing_snapshots` schema, the Public report DTOs and status
mapping, `AreaScopePolicy`, Phase 6's user-management policy/repositories and locking
conventions, the existing audit infrastructure, the current Report repository/application
layer, `ApiPaths`/`ApiError`/`ApiExceptionHandler`, and all 5 CI workflow definitions. No
`V004` migration existed before this session.

## B. V004 schema

`V004__service_report_workflow.sql` — additive only, V001-V003 untouched.

**`reports` gains three columns:**

| Column | Type | Notes |
|---|---|---|
| `assigned_user_id` | `UUID NULL REFERENCES users(id)` | Current operational owner. Always a SERVICE_USER *in practice* — enforced by application code at assignment time (§D), never by the FK, which cannot check role. |
| `workflow_version` | `BIGINT NOT NULL DEFAULT 0` | Mandatory optimistic-concurrency counter (§H) — never `updated_at`. |
| `archived_at` | `TIMESTAMPTZ NULL` | Set exactly once, on the transition into ARCHIVED. |

**`ck_reports_workflow_coherence`** (CHECK): ties the three columns to `status` —
NEW ⇒ no assignee + no archive timestamp; IN_PROGRESS ⇒ assignee set + no archive
timestamp; ARCHIVED ⇒ no assignee + archive timestamp set. This is the database half of
the current-assignment invariant (§L); the "assignee is always a SERVICE_USER" half is
application-enforced (§D/§N).

**New indexes:** `ix_reports_assigned_user_status (assigned_user_id, status)` (backs "my
own IN_PROGRESS reports"); `ix_reports_archived_at (archived_at) WHERE status = 'ARCHIVED'`
(backs the Archive list's `archived_at DESC` ordering). `ix_reports_status_submitted_at`
already existed from V003 and backs the NEW queue.

**New table `report_assignments`** (operational-ownership history — a different question
from `audit_events`, §M): `id`, `report_id` FK, `assignee_user_id` FK, `assigned_by_user_id`
FK, `assigned_at`, and a nullable-together end triple `ended_at` / `ended_by_user_id` /
`end_reason` (`RETURNED` / `REASSIGNED` / `ARCHIVED`), enforced by
`ck_report_assignments_end_coherence`. Never hard-deleted.

**`ux_report_assignments_open_episode`**: a partial unique index on `(report_id) WHERE
ended_at IS NULL` — the database, not application discipline, is what makes "at most one
current assignee per report" actually true under concurrency. `ix_report_assignments_report`
backs the assignment-history-by-report query the detail endpoint uses.

## C. Exact transition matrix

| From | Action | Actor | To | Notes |
|---|---|---|---|---|
| NEW | claim | SERVICE_USER (routed, in scope, area active) | IN_PROGRESS | assignee = actor; opens an assignment episode |
| IN_PROGRESS | return | SERVICE_USER (own report) or MOD/SUPER (visible) | NEW | assignee cleared; episode ends `RETURNED` |
| IN_PROGRESS | close | SERVICE_USER (own report) or MOD/SUPER (visible) | ARCHIVED | assignee cleared, `archivedAt` set; episode ends `ARCHIVED` |
| NEW | close | MOD/SUPER (visible; **not** SERVICE_USER) | ARCHIVED | no assignment episode ever existed; none created |
| IN_PROGRESS | reassign | MOD/SUPER (visible, target valid) | IN_PROGRESS | assignee = target; old episode ends `REASSIGNED`, new one opens |
| ARCHIVED | any | — | — | terminal; every mutation rejected (409, §L) |

Forbidden and confirmed rejected: ARCHIVED → anything (§L/tests); MOD/SUPER as a persisted
assignee (§D/§N, `ck_reports_workflow_coherence` + `canAssignTarget`); NEW → IN_PROGRESS by
arbitrary manager assignment (no such endpoint exists); report reopening (no endpoint);
direct arbitrary status PATCH (no generic PATCH exists anywhere in `ReportWorkflowController`
— only the four named POST operations).

## D. Assignment model

Only a currently-ACTIVE `SERVICE_USER` may be the persisted `assigned_user_id`. MODERATOR
and SUPER_ADMIN are supervisors only — never assignees, at the database level unreachable
(`ck_reports_workflow_coherence` requires an assignee at all only for IN_PROGRESS, and
`ReportWorkflowPolicy.canAssignTarget`/`ReassignTargetCandidate` gate every assignment on
`role == SERVICE_USER && status == ACTIVE`). Verified explicitly by
`ReportWorkflowInvariantsIT`'s `` `any report's current assignee, whenever set, is always an
ACTIVE SERVICE_USER` `` test, which queries every currently-assigned report after a full
claim→reassign sequence and asserts role/status on the live row.

## E. Authorization by role

Implemented once, in `ReportWorkflowPolicy`, reusing `AreaScopePolicy` rather than
duplicating scope logic — the one deliberate divergence is documented in the policy's own
KDoc: SUPER_ADMIN bypasses even an inactive-area block that `AreaScopePolicy.canAccessArea`
itself would apply (brief §12).

| Role | NEW | IN_PROGRESS | Archive | UNCLASSIFIED |
|---|---|---|---|---|
| SERVICE_USER (territorial) | own active area(s) only | own claim only | own active area(s) only | never |
| SERVICE_USER (global) | any active normal area | own claim only | any active normal area | never |
| MODERATOR (territorial) | own active area(s), all statuses | same | same | never |
| MODERATOR (global) | everything | everything | everything | yes |
| SUPER_ADMIN | everything, any area activity | everything | everything | yes |

Confirmed by `ReportWorkflowVisibilityIT` (11 tests) against real PostgreSQL for every row
in this table, plus the scope-hiding 404 for out-of-scope detail requests.

## F. API contracts

All under `${ApiPaths.V1}/service/reports`, all Service-bearer-authenticated (existing
default-deny `SecurityConfig`; no new scheme; no Public credential ever accepted):

- `GET /new`, `GET /in-progress`, `GET /archive` — paginated (`page`/`size`, default 50, max
  100, negative rejected), filterable (`query`, `categoryCode`, `eventTypeCode`,
  `settlementId`, `areaId`, and `assigneeServiceId` on `/in-progress`).
- `GET /{publicReportId}` — full detail, including resolved assignment history.
- `POST /{publicReportId}/claim|return|close` — body `{expectedVersion}`.
- `POST /{publicReportId}/reassign` — body `{expectedVersion, targetServiceId}`.

No generic status PATCH exists anywhere (brief §78, verified by inspection of
`ReportWorkflowController` — 8 explicit mappings, nothing else). Every mutation endpoint
re-fetches through the same visibility-checked `ReportQueryUseCase.detail` path after
committing, so the response the caller sees is always the *committed* state, never a
locally-constructed guess. All 8 endpoints carry `@Operation` Swagger documentation
(`expectedVersion`, the 404 scope-hiding behaviour, 409 conflict codes, role requirements,
pagination/filter fields, `ageBucket`) — no speculative Phase 8 endpoints were added.

## G. NEW-queue ordering proof

RECENT (submitted within the last 168h, newest first) always precedes OLDER (older,
oldest-first); the public report id is the deterministic tie-break in both buckets.
`cutoff = now - 168h` is computed once by the caller from the injected `Clock`, never
per-row and never from device time.

`ReportWorkflowVisibilityIT`'s `` `the NEW queue buckets RECENT before OLDER, with the exact
168h boundary counted as RECENT` `` test uses a `MutableClock` to place three reports at
200h-before, *exactly* 168h-before, and 1h-before a fixed instant, then asserts:
`submittedAt == cutoff` is bucketed RECENT (not OLDER) — confirming the brief's explicit
`submittedAt >= cutoff` rule — and the returned order is `[recent, boundary, older]`.
A second test, `` `NEW queue ties are broken deterministically by public report id, stable
across pagination` ``, submits three reports at the identical instant and confirms `page=0
size=2` followed by `page=1 size=2` returns all three exactly once, in public-id order, with
no duplicate or dropped row across the page boundary.

## H. Optimistic concurrency semantics

`workflow_version` starts at 0 and every successful mutation increments it by exactly one —
confirmed by every functional test asserting the exact post-mutation version (claim: 0→1;
return/close from IN_PROGRESS: 1→2; reassign: 1→2, and explicitly **not** incremented on a
same-target no-op). Every mutation request requires `expectedVersion`
(`WorkflowMutationRequest`/`ReassignRequest`, `@NotNull`), compared against the row's real
current version *after* acquiring `SELECT ... FOR UPDATE` — pessimistic locking serializes
concurrent attempts; the version check rejects a request that read stale state even when it
alone would have won the lock. A stale-version test exists for every one of the four
mutations (claim/return/close/reassign), each using a real stored version from a real prior
mutation, all yielding `409 REPORT_STATE_CHANGED`.

## I. Assignment-history examples (from live test assertions)

A claim→reassign→close sequence (mirrors the live manual smoke test performed before any
automated test was written, and is now also asserted by `ReassignReportIT`):

```
1. claim(original):   report_assignments: [{assignee: original, assignedBy: original, endedAt: null}]
2. reassign(target):  report_assignments: [{assignee: original, endedAt: t2, endReason: REASSIGNED},
                                            {assignee: target,   assignedBy: supervisor, endedAt: null}]
3. close():           report_assignments: [{... REASSIGNED ...},
                                            {assignee: target, endedAt: t3, endReason: ARCHIVED}]
```

At every step, `openAssignmentCount(report) <= 1` and the report's `assigned_user_id`
(when non-null) equals the single open episode's `assignee_user_id` exactly —
`ReportWorkflowInvariantsIT` asserts this explicitly. History rows are never deleted
(confirmed: 2 rows remain after close, both readable via the detail endpoint's
`assignmentHistory`).

## J. Public status integration — proof

`PublicStatusIntegrationIT` drives the *anonymous* `GET /api/v1/public/reports/{id}`
endpoint through a full cycle using the same credential issued at submission:

```
create        -> RECEIVED
claim         -> PROCESSING
return        -> RECEIVED
claim + close -> CLOSED
```

and separately confirms a MODERATOR closing a still-NEW report directly also surfaces as
CLOSED. **Zero Public API or client code was changed** — `PublicReportController` already
rendered `report.status.toPublic().name` generically off whatever `status` value is
currently stored (verified by inspection during §0, before writing any Phase 7 code), so
Phase 4's existing mapping (`NEW→RECEIVED`, `IN_PROGRESS→PROCESSING`, `ARCHIVED→CLOSED`)
does the work with no changes at all.

## K. Tests — every command, count, and result

```
./gradlew test --tests "hu.orszembejelento.backend.reportworkflow.ReportWorkflowPolicyTest"      -> 25/25
./gradlew test --tests "hu.orszembejelento.backend.reportworkflow.ReportWorkflowVisibilityIT"     -> 11/11
./gradlew test --tests "hu.orszembejelento.backend.reportworkflow.ClaimReportIT"                  -> 10/10
./gradlew test --tests "hu.orszembejelento.backend.reportworkflow.ReturnReportIT"                 ->  6/6
./gradlew test --tests "hu.orszembejelento.backend.reportworkflow.CloseReportIT"                  ->  8/8
./gradlew test --tests "hu.orszembejelento.backend.reportworkflow.ReassignReportIT"               -> 15/15 (re-run 4x total, all green)
./gradlew test --tests "hu.orszembejelento.backend.reportworkflow.ReportWorkflowInvariantsIT"     ->  5/5
./gradlew test --tests "hu.orszembejelento.backend.reportworkflow.PublicStatusIntegrationIT"      ->  2/2
./gradlew test --tests "hu.orszembejelento.backend.reportworkflow.AssigneeEligibilityCrossPhaseIT" ->  6/6 (re-run 4x total, all green — §Q)
```

Phase 7 total: **88/88** (82 from the original implementation pass, plus 6 from the §Q
cross-phase review). Full `./gradlew test` (whole backend, all phases): **514/514**, zero
failures, zero errors. `./gradlew build`: green (compile, tests, and the full Gradle `check`
lifecycle all pass; no lint/style task failed).

`DatabaseBaselineIT` and `ReportSchemaIT` (pre-existing, Phase 3/4) were updated for the new
migration and the new coherence constraint: `DatabaseBaselineIT` now expects migrations
`001-004` and the table `report_assignments` in its exhaustive schema assertion;
`ReportSchemaIT`'s status-vocabulary fixture now supplies `assignedUserId`/`archivedAt`
where `ck_reports_workflow_coherence` requires them. Both were re-run standalone after the
fix and pass (9/9 and 33/33 respectively, folded into the 508 total above).

## L. Concurrency — actual outcomes for all 4 required races

All four ran against real PostgreSQL via `Executors.newFixedThreadPool` + a
`CountDownLatch`-gated start (the same helper `UserManagementConcurrencyIT` established in
Phase 6), so requests genuinely overlap inside the database rather than merely being issued
in sequence.

1. **8-way concurrent claim** (`ClaimReportIT`): 8 SERVICE_USERs, all with area access, all
   `POST /claim` the same report simultaneously. Actual outcome, every run: exactly 1
   `200`, exactly 7 `409 REPORT_ALREADY_ASSIGNED`; final state exactly one IN_PROGRESS row,
   `workflow_version == 1` (not 8, not 0), exactly one open assignment episode, exactly one
   `REPORT_CLAIMED` audit event.
2. **Return-vs-close** (`CloseReportIT`): a claimant's `return` races a moderator's `close`
   on the same IN_PROGRESS report. Actual outcome: exactly one `200`; the loser gets `409`;
   final state is coherently either NEW (no assignee, no open episode) or ARCHIVED (no
   assignee, `archivedAt` set, no open episode) — never a mixed state.
3. **Reassign-vs-current-assignee** (`ReassignReportIT`): the current assignee's `return`
   races a moderator's `reassign` to a different target. Actual outcome: exactly one `200`.
   When reassignment wins, the original assignee's losing `return` correctly resolves to
   `404 REPORT_NOT_FOUND` rather than `409` — once reassignment has committed, the original
   assignee is no longer the report's visible assignee, so their attempt is
   indistinguishable from a stranger's, and the existing scope-hiding rule (brief §34)
   applies exactly the same way it would to any other non-owner. This was initially written
   into the test as an assumed `409` and corrected once the real (correct) behaviour was
   observed — see the note in §N.
4. **Target-scope-change** (`ReassignReportIT`): a moderator's `reassign` races a direct
   `serviceAreas.revokeArea` on the reassignment target's own access to the report's area.
   Actual outcome, confirmed both ways depending on which side wins the row lock: either the
   reassignment's re-read of the target's scope (after acquiring the target-user lock, per
   the canonical lock order) wins and the target is genuinely still authorized at commit
   time, **or** the revoke wins and reassignment correctly rejects with
   `400 INVALID_ASSIGNEE`, leaving the original assignment completely untouched. Never a
   silent assignment based on the scope read before the lock.

See §Q for three more required races added by the cross-phase review (claim vs. role
promotion, claim vs. deactivation, claim vs. area revoke — plus the reassign-side
equivalents), covering the *actor themselves* becoming newly invalid mid-transaction, not
only the pre-existing target/scope races above.

## M. Audit events + metadata

Four new immutable event types: `REPORT_CLAIMED`, `REPORT_RETURNED_TO_NEW`,
`REPORT_REASSIGNED`, `REPORT_ARCHIVED` (added to `AuditEventType`), targeting the new
`AuditTargetType.REPORT`. Every mutation's audit insert runs inside the same transaction as
the mutation itself (confirmed by §66's rollback test: the doomed transaction produced zero
audit rows, not a stray one).

Metadata recorded (verified by direct SQL inspection during the earlier live smoke test and
by `auditEventCount`/no-audit-on-no-op assertions in the automated tests): `publicReportId`,
`fromStatus`/`toStatus`, previous/new assignee `serviceId` (never the internal user UUID),
the report's `serviceAreaId` where routed, and the resulting `workflowVersion`. **Never**
recorded: the Public access credential or its hash, Service auth tokens, passwords, or
report free text (none exists in this schema). A same-target reassignment produces **no**
audit event at all — confirmed by `` `reassigning to the current assignee is an idempotent
no-op` ``'s explicit `auditEventCount("REPORT_REASSIGNED") == 0` assertion.

## N. Security review (brief §84) — confirmed

- **SERVICE_USER-only assignee, MOD/SUPER never assignee** — §D, confirmed by a live-data
  invariant test after a real claim/reassign sequence.
- **UNCLASSIFIED never assignable** — `ReassignReportUseCase` checks `!scope.routed` before
  the status switch specifically so the correct `REPORT_UNCLASSIFIED_CANNOT_ASSIGN` surfaces
  even for a visible report; a policy bug that would have produced a scope-hiding 404
  instead (`canReassign` originally used the area-ID-dependent `roleAccessesArea` verbatim,
  which is always false for a null `serviceAreaId` regardless of role) was found and fixed
  by reasoning through the UNCLASSIFIED case *before* any code ran, then confirmed by a
  dedicated test.
- **Scope always backend-enforced** — `AreaScopePolicy` reused, never duplicated; a real
  production bug in the read-model's SQL-level visibility clause (a global SERVICE_USER was
  incorrectly restricted to explicitly-granted areas only, contradicting the already-correct
  policy rule) was found by `ReportWorkflowVisibilityIT` and fixed before this branch was
  ever pushed — see §K's note and the `feat` commit message.
- **Stale version cannot overwrite** — §H, a dedicated test per mutation.
- **Claim concurrency exactly one winner** — §L.1.
- **No assignment-history inconsistency** — §I, §L; the open-episode partial unique index is
  the enforcement mechanism, not merely application discipline (proven directly by §66's
  forced-rollback test, which deliberately triggers that exact index).
- **Out-of-scope hidden** — §E, `ReportWorkflowVisibilityIT`'s explicit 404-identical-to-
  nonexistent test.
- **Public capability unaffected** — §J; zero Public code touched.
- **Service auth unaffected** — no file under `auth/` or `identity/` touched by Phase 7.
- **No credential/hash exposed** — grepped `reportworkflow/` for `credential`/`Hash`/
  `password`; the only matches are doc-comments describing what is deliberately excluded.
- **Audit carries no secrets** — §M.
- **No report edits, no generic PATCH** — §F/§C.

## O. Regression / CI — all 5 green

**Initial push (SHA `02f0552`, before §Q):**

| Workflow | Result | Run |
|---|---|---|
| backend | success | `34339257495` |
| android | success | `34339257501` |
| web | success | `34339257591` |
| deploy-config | success | `34339257558` |
| reference-data | success | `34339257790` |

**Final push, after the §Q cross-phase review:** all 5 workflows re-confirmed green on the
final SHA — see the session's closing report to the owner for the exact run IDs (this
document cannot cite them without first being committed and pushed itself).

Locally, both before the initial push and again after the §Q fix: full backend `./gradlew
build` green (508/508, then 514/514 after §Q); Android
`:public-app:testDebugUnitTest :service-app:testDebugUnitTest :public-app:assembleDebug
:service-app:assembleDebug lint` green both times (no Android source was touched by Phase 7
at any point — this run confirms nothing else regressed); Web `npm ci && npm run typecheck
&& npm run build` green both times; `node reference-data/tools/validate-canonical.mjs
reference-data/example/manifest.json` green both times. `deploy-config`'s Caddy-binary/
sudo-dependent checks were not replicated locally (nothing under `deploy/` was touched by
Phase 7, in either pass) and were confirmed via CI instead, both times. No Phase 5/6 test
was weakened or removed to make any of this pass.

## Q. Cross-phase invariant review vs. Phase 6 (post-review addendum)

A dedicated review, requested after the initial implementation, of whether a *later* Phase 6
user-management mutation can leave an invalid current report assignment — Phase 7 defines
the current assignee as "an eligible ACTIVE SERVICE_USER", and Phase 6 was written and
merged before Phase 7's `reports` columns existed, so it was never reviewed against this
invariant.

### Q.1 The current lock graphs, before this review

**Phase 6** (`ChangeUserRoleUseCase`, `DeactivateUserUseCase`/`ReactivateUserUseCase`,
`ServiceAreaGrantUseCase`/`ServiceAreaRevokeUseCase`, `ChangeGlobalAreaAccessUseCase`): every
one of these locks the **target USER** row first (`users.lockByServiceId`), and — for the
two area operations only — the **SERVICE AREA** row second. **None of the five Phase 6
mutation use cases ever reads or locks `reports` or `report_assignments` at all.**

**Phase 7** (as implemented before this review): `ClaimReportUseCase` locked only the
**REPORT** row; `ReturnReportUseCase`/`CloseReportUseCase` likewise; `ReassignReportUseCase`
locks **REPORT** first, then the **target USER** row (`users.lockByServiceId`) second — the
canonical order documented in its own KDoc and brief §9. Critically, `ClaimReportUseCase`
locked the REPORT but never re-locked or re-read the **actor's own** USER row inside the
transaction — it authorised against the `ReportWorkflowActor` the controller had built
*before* the transaction began, and that type's own KDoc explicitly says "authentication
already implies ACTIVE" — true only at authentication time, not at the instant of the
report-locked transaction's own write.

### Q.2 What this made possible, and why it's now closed (items 1-3's first scenario / item 4)

Because Phase 6 never touched `reports`/`report_assignments`, and `claim` never re-validated
its own actor, a role promotion, deactivation, or area revoke could commit *between* the
controller's actor-load and claim's own final `UPDATE`, with nothing serializing the two —
claim could create a **new** open assignment for a user who, at the moment of that write,
was already no longer an ACTIVE SERVICE_USER with area access to the report.

**Fix applied** (`ClaimReportUseCase`, canonical lock order step 2 — see the class KDoc and
the diff): after the report lock and status switch, before the version check, claim now
re-locks the actor's own row (`users.lockByServiceId(actor.serviceId)`), re-checks
role/status freshly (403 `REPORT_WORKFLOW_FORBIDDEN` if no longer SERVICE_USER/ACTIVE), and
rebuilds a fresh `ReportWorkflowActor` from a fresh area-authority read before re-running
`canClaim` (404 `REPORT_NOT_FOUND` if area authority is gone) — exactly mirroring
`ReassignReportUseCase`'s already-correct target re-validation. **This introduces no new
lock resource and no lock-order conflict**: it is the identical REPORT-then-USER order
`ReassignReportUseCase` already established, applied to claim's own actor too. Because both
sides now contend for the same `users` row, a concurrent Phase 6 mutation against that exact
user and a concurrent claim/reassign naming that exact user as actor/target are always fully
serialized against each other — never merely raced.

`ReassignReportUseCase`'s target re-validation was already correct and already covered by
the original `ReassignReportIT` target-scope-change test; this review's new tests extend the
same proof to role promotion and deactivation, not only area revoke.

### Q.3 What this does NOT close, and why (items 1-3's second scenario) — STOPPED, not implemented

The "preferred product invariant" as stated — *a Phase 6 mutation that would invalidate an
**existing** open report assignment must itself be rejected* — is a genuinely different
problem from Q.2: it requires Phase 6 to detect, at mutation time, that its **target already
holds** an open assignment, which means reading (and, to be race-safe, locking) the affected
`reports`/`report_assignments` row(s) from *inside* a Phase 6 mutation.

Doing this safely has exactly two shapes, and both are a material lock-order change:

1. **Phase 6 locks the target USER first (as it already does today), then locks the
   affected REPORT row(s).** This is USER-then-REPORT — the *reverse* of the REPORT-then-
   USER order `ReassignReportUseCase` (and, after Q.2, `ClaimReportUseCase`) already
   establishes. A real deadlock becomes reachable: Transaction A (Phase 6, e.g. deactivate)
   holds the target USER lock and waits for the REPORT lock; Transaction B (Phase 7
   `reassign`, naming the same user as its target) holds the REPORT lock and waits for the
   *same* USER lock. PostgreSQL's deadlock detector would abort one of the two — not silent
   corruption, but a new, previously-impossible failure mode this codebase has deliberately
   avoided at every prior phase by documenting and following one canonical order per
   resource pair.
2. **Phase 6 is restructured to lock the affected REPORT row(s) first, then the target
   USER.** This avoids the specific conflict in (1), but is itself invasive: it means
   resolving "which reports are currently assigned to this target" via an unlocked read
   *before* the target is even locked (the target's identity is only known after resolving
   `serviceId → userId`, which today happens together with the USER lock acquisition), then
   introducing a *new* canonical multi-row lock order for however many open assignments a
   target might hold (by report id, say, to avoid a second deadlock class among multiple
   simultaneous Phase 6 mutations), across all five existing Phase 6 mutation use cases —
   not a targeted change confined to Phase 7.

Either path is a "material lock-order change" by any reasonable reading of that phrase, so
per the explicit instruction this review stopped here rather than implementing either one.
**No Phase 6 code was modified.** `ChangeUserRoleUseCase`, `DeactivateUserUseCase`,
`ServiceAreaRevokeUseCase` and `ChangeGlobalAreaAccessUseCase` are byte-for-byte unchanged by
this review.

**What this means concretely, demonstrated by `AssigneeEligibilityCrossPhaseIT`'s own
`if (claimResult/reassignResult.statusCode() == 200)` branches (Q.4):** if a claim or
reassignment *wins* its lock race against a concurrent Phase 6 mutation naming the same
user, the resulting assignment is valid at the instant it is written (Q.2's guarantee) — but
the Phase 6 mutation, having been blocked until that transaction committed, then proceeds
normally afterward and **can** turn that now-assigned user into a MODERATOR, DEACTIVATED
user, or someone with no area access, with the report's `assigned_user_id` left pointing at
them. Per this review's explicit instruction not to auto-return/reassign from User
Management, **nothing automatically fixes this up.** A report can end up IN_PROGRESS with an
assignee who is no longer, at query time, an eligible ACTIVE SERVICE_USER. This is a real,
now-explicit known limitation (§P) rather than a silent gap — closing it is an owner decision
between the two invasive options above (or a narrower, deliberately-scoped variant of one of
them), not a Phase 7 implementation task.

### Q.4 Tests added

`AssigneeEligibilityCrossPhaseIT` (6 tests, real PostgreSQL, the same
`Executors.newFixedThreadPool` + `CountDownLatch` pattern as every other required race in
this report), driving the **real Phase 6 HTTP endpoints** (not raw repository calls, so the
genuine canonical-lock-order contention is exercised) against a **real Phase 7 claim/
reassign**:

| # | Race | Outcome asserted |
|---|---|---|
| 1 | self-claim vs. role promotion (SERVICE_USER→MODERATOR) of the same user | exactly one coherent outcome: claim succeeds (assignee genuinely still SERVICE_USER at write time) with the role change applying afterward, **or** claim gets 403 `REPORT_WORKFLOW_FORBIDDEN` and the report stays NEW/unassigned. Role change itself always succeeds (unconditional on report state, per Q.3). |
| 2 | self-claim vs. deactivation of the same user | claim succeeds, **or** claim gets 403 (row-lock re-validation) or 401 (session already revoked — an even earlier, also-safe rejection layer); report stays NEW/unassigned in the reject case. |
| 3 | self-claim vs. revocation of the user's only area | claim succeeds, **or** claim gets 404 `REPORT_NOT_FOUND` (scope-hiding, matching every other area-loss case in this report); report stays NEW/unassigned in the reject case. |
| 4 | reassign vs. role promotion of its own target | reassign succeeds (target genuinely still SERVICE_USER at write time), **or** reassign gets 400 `INVALID_ASSIGNEE` and the original assignment is untouched. |
| 5 | reassign vs. deactivation of its own target | same shape as #4. |
| 6 | reassign vs. revocation of its own target's only area | same shape as #4 (extends the original `ReassignReportIT` target-scope test to the real HTTP endpoint rather than a raw repository call). |

Each test asserts only what Q.2's fix actually guarantees — validity *at the instant of
assignment* — and deliberately does **not** assert a blanket "the final assignee is never a
MODERATOR/DEACTIVATED/out-of-scope user", since that stronger claim is exactly Q.3's
out-of-scope case. (An earlier draft of test #4 asserted the stronger, wrong claim and failed
non-deterministically depending on which side won the race — corrected during this review,
which is itself evidence the distinction matters and is now precisely captured rather than
papered over.) All 6 tests were re-run 4 times in immediate succession and stayed green
every time, confirming the assertions hold regardless of which side the database schedules
first.

## P. Known limitations — not hidden

- **Phase 6 does not reject a mutation against a user who already holds an existing open
  report assignment** (§Q.3) — a role promotion, deactivation, or area revoke can still
  leave a report IN_PROGRESS with an assignee who is no longer, at query time, an eligible
  ACTIVE SERVICE_USER. Closing this cleanly requires Phase 6 to also lock the affected
  `reports` row(s), which is a material lock-order change either way it could be structured
  (§Q.3 spells out both options and their respective risk) — deliberately left as an owner
  decision rather than implemented under this review's own STOP instruction. What Q.2's fix
  *does* guarantee: no **new** invalid assignment can ever be created by claim/reassign
  racing a concurrent Phase 6 mutation — the two are now always fully serialized.
- **No Service Android UI.** Phase 7 is backend-only by design; the NEW/IN_PROGRESS/Archive
  queues, claim/return/close/reassign actions and detail view exist only as HTTP endpoints.
  Building the Service app's screens against them is explicitly Phase 8+ work.
- **`report_assignments` history has no pagination limit at the API level.** The detail
  endpoint returns a report's full assignment history unpaginated. In Phase 7's transition
  model this list is bounded in practice (one episode per claim/reassign cycle, and a report
  is terminal once archived), but a report reassigned an unusually large number of times
  before this session's Definition of Done would return an unbounded array. Not addressed,
  since nothing in the brief asked for history pagination and no adversarial-reassignment
  scenario is in scope.
- **The `query` filter's free-text search is a parameterized `ILIKE`**, not full-text search
  (deliberately — brief §28 forbids Elasticsearch/full-text/unaccent). It will not match
  accent-insensitive or fuzzy queries against settlement names or train identifiers.
- **`deploy-config`'s Caddy-specific checks were verified only via CI**, not reproduced
  locally, because the Caddy binary and the `sudo`-gated log-directory step aren't practical
  to install in this session's Windows environment. This is a process limitation of the
  session, not a Phase 7 code gap — nothing under `deploy/` was touched, and the CI run
  itself (§O) is authoritative.
- **No WebSocket/push/notification** of queue changes — a Service client must poll. Fully in
  scope as documented ("NOT: notifications... WebSocket, push"), restated here only so it is
  explicit rather than silently absent.
- **UNCLASSIFIED reports have no path to ever leave UNCLASSIFIED** within Phase 7 (no
  rerouting, no manual area assignment) — a global MODERATOR/SUPER_ADMIN can view and close
  one directly, but it can never be claimed or become IN_PROGRESS. This is the brief's
  explicit design (§40/§68), not an oversight, restated here for completeness.

---

**Phase 7 is complete per its own Definition of Done, including the post-implementation
cross-phase invariant review vs. Phase 6 (§Q). One item from that review (§Q.3) was
deliberately left unimplemented and is reported as a known limitation, per the review's own
explicit STOP-on-material-lock-order-change instruction, rather than risking a lock-order
inversion against Phase 7's own canonical order. No PR was opened. Phase 8 was not
started.**
