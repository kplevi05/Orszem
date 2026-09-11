# Phase 9 engineering report — Report moderation (soft deletion)

Phase 9 adds report moderation — soft deletion, never physical deletion — on top of the
existing Phase 4 report domain, Phase 6 user-management, and Phase 7 service report
workflow. It touches **both** the backend (new `moderation` package + one migration) and
the Service Android app (a new `moderation` feature module wired into the existing
report-detail screen and hub navigation). It is **not** a UI-only phase.

> **Owner visual approval: PENDING.** This report and the screenshot set in §O are
> submitted for owner review. No PR has been opened, nothing has been merged, and Phase 10
> has not started — see §S.

---

## A. Git

| | |
|---|---|
| Branch | `feature/v2-moderation` (created off `main`; never rebased, no history rewrite) |
| `main` at branch creation | `986d53e` — *Merge pull request #12 from …/feature/v2-service-android-ui* (confirmed Phase 8 merged before any Phase 9 work started, per §0's instruction) |
| Phase 9 core commit | `8a92d9d` — *feat(moderation): Phase 9 backend + Service Android moderation core* |
| Phase 9 test commit | `d1339c8` — *test(moderation): Service Android Compose/instrumented coverage (§67)* |
| Current branch HEAD | `d1339c8` |

No PR opened. Nothing merged. No destructive git operations (no reset --hard, no
force-push, no rebase of shared history). No Phase 10 work.

---

## B. Product rule & moderation semantics (brief §1/§8/§9, FROZEN)

Moderation is **soft deletion**: a report row is never removed and its assignment history
is never destroyed. "Currently deleted" is answered by exactly one fact —
`report_moderation_episodes` has a row for the report with `restored_at IS NULL` — never a
flag on `reports` itself. This is deliberate: it means deletion never has to touch
`reports.status` in a way that could conflict with the existing (Phase 7)
`ck_reports_workflow_coherence` CHECK.

Delete semantics by state:

| Before delete | `reports.status` after | Assignment | Episode |
|---|---|---|---|
| NEW | stays NEW | none touched | opens, `statusBeforeDelete=NEW` |
| IN_PROGRESS | → NEW, unassigned | open assignment ended with `MODERATION_DELETED` | opens, `statusBeforeDelete=IN_PROGRESS` |
| ARCHIVED | stays ARCHIVED, `archived_at` unchanged | none touched | opens, `statusBeforeDelete=ARCHIVED` |

Restore (SUPER_ADMIN only) closes the open episode and — this is the elegance the brief
hoped for and the migration's own comments document at length — **never has to write
`reports.status`, `assigned_user_id` or `archived_at` for any of the three cases**, because
delete already left the `reports` row in exactly the state restore would target:

| `statusBeforeDelete` | Restore target |
|---|---|
| NEW | NEW |
| IN_PROGRESS | NEW, **never** re-assigned — the prior assignment stays visible historically with `MODERATION_DELETED`, never resurrected |
| ARCHIVED | ARCHIVED |

---

## C. Authorization & scope (brief §2-3)

`ModerationPolicy` deliberately reuses `ReportWorkflowPolicy.canViewReport` rather than
inventing a second area-authority model:

```kotlin
class ModerationPolicy(private val workflowPolicy: ReportWorkflowPolicy = ReportWorkflowPolicy()) {
    fun canModerate(actor: ReportWorkflowActor, report: ReportScope): Boolean =
        actor.role != UserRole.SERVICE_USER && workflowPolicy.canViewReport(actor, report)
    fun canViewDeleted(actor: ReportWorkflowActor, report: ReportScope): Boolean = canModerate(actor, report)
    fun canRestore(actor: ReportWorkflowActor): Boolean = actor.role == UserRole.SUPER_ADMIN
}
```

- **SERVICE_USER**: cannot moderate at all — not even see deleted-report endpoints or
  deleted reports in ordinary queues. Enforced at two layers: `requireModerationActor`
  (403 `MODERATION_FORBIDDEN`, independent of any one report) and the identical
  `ReportWorkflowPolicy` scope check every other role uses.
- **Territorial MODERATOR**: own active areas, never UNCLASSIFIED — same rule as Phase 7.
- **Global MODERATOR**: all operational areas + UNCLASSIFIED. May **never** restore.
- **SUPER_ADMIN**: everywhere, including UNCLASSIFIED and a report whose ServiceArea has
  since gone inactive (the one deliberate SUPER_ADMIN carve-out `ReportWorkflowPolicy`
  itself already documents). **Only** role that may restore.
- **Deleted-report visibility is area-based, not actor-history-based** (brief §21): a
  MODERATOR sees a report a *different* MODERATOR deleted, as long as it's in their own
  scope. Verified in `ModerationVisibilityIT` and live in §O.
- Out-of-scope or nonexistent report ids are existence-safe: both produce the identical
  404 `REPORT_NOT_FOUND` shape (`ModerationVisibilityIT`'s dedicated "no existence leak" test).

---

## D. Data model — `V005__report_moderation.sql`

Additive only. Never edits V001-V004.

- **`report_moderation_episodes`** (append-only): `id`, `report_id`, `reason` (the frozen
  6-value enum, DB-enforced via CHECK), `deleted_by_user_id`, `deleted_at`,
  `status_before_delete`, `restored_by_user_id` (nullable), `restored_at` (nullable).
  A restore-coherence CHECK enforces both restore columns null or both non-null together —
  the same pattern V004 already uses for `report_assignments`' own
  `ended_at`/`ended_by_user_id`/`end_reason` triple.
- **`ux_report_moderation_episodes_open_episode`** — a partial unique index on
  `(report_id) WHERE restored_at IS NULL`: at most one open episode per report,
  **database-enforced**, not application discipline. The identical technique V004 uses for
  `ux_report_assignments_open_episode`.
- **`report_assignments.end_reason`** widens from `VARCHAR(16)` to `VARCHAR(32)` (a
  metadata-only ALTER in PostgreSQL, no table rewrite) and its CHECK gains
  `'MODERATION_DELETED'` as a fourth value — every previously-valid value stays valid, no
  data migration needed. **This width fix was a genuine bug caught during verification —
  see §M.1.**
- Two supporting indexes: `ix_report_moderation_episodes_report` (history-by-report) and
  a partial `ix_report_moderation_episodes_deleted_at` (backs the deleted-list's `deletedAt
  DESC` sort without a full scan, only ever matched against currently-open episodes).

---

## E. Backend architecture

`moderation.domain` stays framework-free (no Spring imports) exactly like
`reportworkflow.domain`. `ModerationPolicy` is wired as a `@Bean` in the existing
`IdentityConfig` — mirroring how `ReportWorkflowPolicy` is wired there — rather than
annotating the domain class itself with `@Component` (see §M.2 for the bug this caught).

**Canonical lock order — unchanged from Phase 7 (brief §11).** Every moderation mutation
locks the `reports` row first via the shared `lockAndResolve` chokepoint, and that is the
**only** lock either use case ever takes — never the assignee's own `users` row, exactly
like `CloseReportUseCase`'s existing no-user-lock precedent. Deleting an assigned report
ends the open assignment episode through a plain `UPDATE … WHERE report_id = ?`, not a
`users` row lock. **This introduces no new lock edge and no new deadlock possibility** —
moderation never contends with the Phase 6 mutations that lock `users` first
(deactivation, role/scope changes). Verified by `ModerationCrossPhaseIT` (§H) and
documented in the migration's own comments.

`lockAndResolve` (shared with Claim/Return/Close/Reassign) gained one parameter:
`isModerationDeleted: (UUID) -> Boolean`. Every ordinary-workflow caller passes
`moderation::hasOpenEpisode` and gets `ReportNotVisibleException` (existence-safe 404) for
a currently-deleted report. `ModerationDeleteUseCase` itself passes `{ false }` — it
deliberately must still be able to *see* a report that turns out to already be deleted, so
it can answer the specific `REPORT_ALREADY_DELETED` conflict rather than a generic 404.

`ModerationDeleteUseCase.delete()` check order (matters for the concurrency proofs in
§H): role gate → `lockAndResolve` (report lock + scope check) →
`hasOpenEpisode` (→ `REPORT_ALREADY_DELETED`) → version check (→ `REPORT_STATE_CHANGED`) →
mutate. `ModerationRestoreUseCase.restore()`: role gate → `canRestore` → `lockAndResolve` →
resolve open episode (→ `REPORT_NOT_DELETED`) → version check → mutate.

Both use cases are single-transaction (`@Transactional`): report lock, version validation,
(for a formerly-IN_PROGRESS delete) assignment-episode end + assignee clear + status
transition, moderation-episode write, `workflow_version` increment, and the audit event —
all in one commit. No intermediate state is ever observable (brief §25/§26, proven by
`ModerationRollbackInvariantIT`, §H).

---

## F. Ordinary workflow hiding & Public API (brief §12-15)

- `JdbcReportWorkflowQueryRepository`'s `FROM_JOINS` gained a `LEFT JOIN
  report_moderation_episodes … ON … AND restored_at IS NULL`, and every list/detail query's
  `WHERE` now includes `rme.id IS NULL`. A moderation-deleted report simply never appears
  in NEW/IN_PROGRESS/Archive lists or ordinary detail, even though the underlying
  `reports.status` may still say NEW or ARCHIVED.
- `ClaimReportUseCase` / `ReturnReportUseCase` / `CloseReportUseCase` /
  `ReassignReportUseCase` all reject a moderation-deleted report with the same
  existence-safe 404 `REPORT_NOT_FOUND` a nonexistent report would produce — never a
  distinguishable `REPORT_DELETED_BY_MODERATOR` code (brief §13).
- `GetPublicReportUseCase` computes `currentlyModerationDeleted =
  moderation.hasOpenEpisode(report.id)` and forces `publicStatus = CLOSED` whenever true,
  regardless of the real underlying NEW/IN_PROGRESS/ARCHIVED status. The response **shape**
  is unchanged — no moderation field, reason, actor, or timestamp is ever added to the
  Public payload (brief §14, verified by `ModerationPublicIntegrationIT`'s dedicated
  "identical shape, field for field" test and live in §O).
- Restored-report Public behaviour matches §15 exactly, verified live: a deleted NEW report
  shows Public `CLOSED`, then `RECEIVED` again once restored; a deleted IN_PROGRESS report
  shows `CLOSED`, then `RECEIVED` (never `PROCESSING`) once restored as NEW.

---

## G. Moderation API (FROZEN shape, brief §17)

```
POST /api/v1/service/moderation/reports/{publicReportId}/delete   {expectedVersion, reason} → 204
POST /api/v1/service/moderation/reports/{publicReportId}/restore  {expectedVersion}          → 204  (SUPER_ADMIN only)
GET  /api/v1/service/moderation/deleted                                                      → 200 paginated list
GET  /api/v1/service/moderation/deleted/{publicReportId}                                     → 200 detail
```

`delete`/`restore` return `204 No Content` via `ResponseEntity.noContent().build()` —
matching the existing `ServiceAuthController.logout()` precedent rather than inventing a
new response shape (see §M.3 for the design correction this went through). Deleted-list
paging: `page=0`/`size=50` default, `size` capped at 100, sorted `deletedAt DESC` with a
public-report-id tie-break; filters `query`/`reason`/`areaId`, all server-side —
`JdbcModerationQueryRepository`'s `visibilityClause` deliberately mirrors
`JdbcReportWorkflowQueryRepository`'s own clause exactly (SUPER_ADMIN → no restriction,
global MODERATOR → no restriction, territorial → own-active-areas `IN` clause with a
`NEVER_MATCHES` sentinel for an empty set).

---

## H. Concurrency & invariants — real PostgreSQL, real overlapping transactions

All races use `runConcurrently` (the existing Phase 7 `CountDownLatch`-based helper — every
worker starts, all release together) against a real Testcontainers PostgreSQL, never a loop
of sequential calls.

| Test | Proves |
|---|---|
| Two moderators delete the same report concurrently | Exactly one open episode survives; the loser sees `REPORT_ALREADY_DELETED` |
| Two SUPER_ADMIN restores race | Exactly one wins (204); the other sees `REPORT_NOT_DELETED`; exactly one version increment |
| A stale delete races a restore of an already-deleted report | Restore is **unconditionally** 204 (delete can only ever read-and-reject, never consume the episode — see §M.4 for a test-logic bug this exact reasoning caught); the delete sees either `REPORT_ALREADY_DELETED` or `REPORT_STATE_CHANGED` depending on lock order, both coherent |
| Delete races claim on a NEW report | Exactly one side succeeds; never a deleted report with a hidden IN_PROGRESS ownership |
| Delete races close / return / reassign on an IN_PROGRESS report | Final state always coherent; no open assignment survives on a deleted report either way |
| Rollback invariant: losing side of a two-restore race | No duplicate close, no extra audit row, no extra version bump |
| Rollback invariant: losing side of a delete-vs-reassign race | Absolutely no trace — no episode, no ended assignment, no audit row, no version bump |

A classic single-threaded "pre-corrupt the DB, then trigger a late constraint violation"
rollback test (the pattern `ReportWorkflowInvariantsIT` uses) is architecturally
**unreachable** for this code: `hasOpenEpisode()`'s predicate is identical to the one the
partial unique index enforces, so any pre-corrupted state is caught by the early
application-level check — zero mutations attempted — rather than surviving to a late
DB-level rollback. `ModerationRollbackInvariantIT` documents this reasoning at length and
substitutes genuine concurrent-race tests, which do exercise real multi-statement
transaction atomicity for the losing side.

**Cross-phase race (brief §27/§32, `ModerationCrossPhaseIT`):** moderation-deleting an
IN_PROGRESS report ends its open assignment; a Phase 6 role/deactivation/scope mutation
that was blocked by that specific assignment must no longer be blocked afterward — proven
without any special-casing, because the existing open-assignment query simply stops seeing
the ended episode. A genuine concurrent race (delete vs. deactivation of the assignee) is
also proven: no deadlock, no invalid current assignment; user-management may conservatively
reject if it observes the still-open assignment before deletion ends it, and a retry after
deletion succeeds — no new lock order was added solely to eliminate that safe conservative
conflict.

---

## I. Android architecture

`moderation/data` (`ModerationApi`, `ModerationRepository`/`DefaultModerationRepository`) and
`moderation/ui` (list/detail screens + ViewModels, the delete-reason and restore-confirm
dialogs) follow the exact shape of the Phase 7/8 `reports` feature — Compose → ViewModel →
repository interface → backend, no persistent deleted-report database, no offline queue, no
blind mutation retries (every rejected delete/restore silently re-fetches instead of
resending), single-flight mutation guards.

`ServiceNavHost` wiring:

- Two new routes, `moderation/deleted` and `moderation/deleted/{publicReportId}`.
- The deleted-list `ViewModel` is **session-scoped** (hoisted via the same `sessionOwner`
  the report queues use), not `NavBackStackEntry`-scoped — specifically so a restore from
  the detail screen (a different back-stack entry) can refresh the same list instance
  before popping back to it (brief §50).
- `ReportDetailViewModel` gained an optional `moderationRepository: ModerationRepository?`
  parameter — `null` for SERVICE_USER, mirroring `userManagementRepository`'s identical
  null-for-SERVICE_USER shape, so moderation is unreachable by that role not just
  UI-gated but structurally absent from its dependency graph.
- Optional confirmation snackbars ("A bejelentés törölve." / "A bejelentés visszaállítva.")
  are hoisted **above** the `NavHost`, at `ServiceNavHost`'s own `Scaffold`, specifically so
  the message survives the `popBackStack()` that immediately follows a successful mutation
  instead of being torn down with the screen that triggered it.

---

## J. Production copy, localisation & assignment-history copy (brief §60/§71)

The frozen 6-reason vocabulary and the restore-confirmation copy are each a single mapping
function (`moderationReasonLabelRes`, `restoreDialogTextRes` in
`common/ui/ModerationReasonLabels.kt`) — nothing else in the app renders a raw reason code
or constructs restore copy inline. `AssignmentHistoryCopy.kt` gained one case for
`MODERATION_DELETED`; it deliberately names the **assignee** (`episode.assigneeServiceId`),
never the moderator who performed the deletion — the sentence is about whose handling
ended, not who ended it (a bug I caught in my own first draft before it was ever compiled —
see the file's own comment).

`grep` across `android/service-app/src/main` for `workflowVersion`, `PHASE.?9`,
`moderation_state`, and every raw backend code (`SPAM`, `TROLL_OR_FALSE_REPORT`,
`MODERATION_DELETED`, `MODERATION_FORBIDDEN`, `REPORT_ALREADY_DELETED`,
`REPORT_NOT_DELETED`) found no hits in `strings.xml` and no hits in a `Text(...)`/
`stringResource(...)` call anywhere — every occurrence in Kotlin source is either a DTO
field name or a control-flow comparison (`result.code == "..."`), never rendered. The
`moderation_functions_unavailable` Moderáció-hub placeholder was reworded so it refers only
to ServiceArea management (Phase 10) — it no longer claims moderation itself is
unavailable, now that "Törölt bejelentések" is live.

---

## K. Bugs found and fixed during verification

Four genuine defects were found — all before or during live verification, all fixed and
re-verified, none left in the final state:

**K.1 — `report_assignments.end_reason` too narrow for the new value.** V004 declared the
column `VARCHAR(16)`, sized for `RETURNED`/`REASSIGNED`/`ARCHIVED`. `'MODERATION_DELETED'`
is 19 characters. Every delete of an IN_PROGRESS report failed with a real
`DataIntegrityViolationException` ("value too long for type character varying(16)") the
instant it tried to end the open assignment. Since V005 had never been applied to any
persistent environment (uncommitted, unpushed), the fix widens the column to `VARCHAR(32)`
**inside V005 itself** rather than adding a V006 — a plain `ALTER COLUMN TYPE` on
`VARCHAR`, metadata-only in PostgreSQL, no table rewrite, every existing value still valid.

**K.2 — `ModerationPolicy` never registered as a Spring bean.** The domain class was
written correctly but never wired anywhere, so every moderation Spring context failed to
start (`NoSuchBeanDefinitionException` inside `ConstructorResolver`) — 65 of 72 tests
failed with an identical `IllegalStateException` at context-load time, none of them a real
business-logic failure. Fixed by adding a `@Bean` method to the existing `IdentityConfig`,
mirroring the exact pattern already used there for `ReportWorkflowPolicy`.

**K.3 — A test-logic bug in the delete-vs-restore concurrency test**, found only because
the fix for K.1/K.2 let the suite actually run: `ModerationConcurrencyIT`'s "stale delete
races a restore" test assumed `restoreResult.statusCode() == 204` meant "restore won the
lock race," and only checked the alternative branch (`REPORT_ALREADY_DELETED`) when it
didn't. But the repeat delete can **never** actually win — it only ever reads the open
episode and rejects (delete never closes an episode, only restore does) — so restore is
**unconditionally** 204 regardless of lock order, and the real branching variable is which
conflict the delete observes. This was a genuinely reproducible failure (~66% of runs, 4/6
observed) caused entirely by the test's own incorrect assumption, not a backend defect —
confirmed by 8/8 clean reruns after correcting the assertion (§H). The backend logic was
correct throughout.

**K.4 — `DatabaseBaselineIT`'s hardcoded table/migration inventory.** Two pre-existing
Phase 0-4 regression assertions pin the exact migration-version list and the exact table
set that should exist, specifically to catch a future-phase table appearing too early.
Both needed a one-line addition for V005 / `report_moderation_episodes` — an expected,
intentional update to a test whose entire job is to notice new tables, not a weakening of
the test.

None of these were architecture-level mistakes; all four surfaced only once a real
Testcontainers PostgreSQL and a real emulator were exercising the actual code paths, which
is exactly why the brief requires them rather than accepting compile success alone.

---

## L. Tests — every command, count, result

**Backend, Phase 9 suite only:**
```
cd backend && ./gradlew test --tests "hu.orszembejelento.backend.moderation.*"
```
**72 tests, 0 failures** — `ModerationDeleteIT` (16), `ModerationRestoreIT` (9),
`ModerationVisibilityIT` (10), `ModerationOrdinaryWorkflowHidingIT` (7),
`ModerationPublicIntegrationIT` (5), `ModerationAssignmentAndAuditIT` (6),
`ModerationConcurrencyIT` (7 real races), `ModerationCrossPhaseIT` (3, one concurrent),
`ModerationRollbackInvariantIT` (2), `ModerationPolicyTest` (7 pure unit tests).
`ModerationConcurrencyIT`'s delete-vs-restore test additionally re-run 8/8 clean after §K.3.

**Backend, full regression:**
```
cd backend && ./gradlew clean build
```
**605 tests, 0 failures** — every Phase 0-8 test unchanged and still green, plus the 72
above. `DatabaseBaselineIT` updated per §K.4. `check`/`build` succeed.

**Service Android, unit:**
```
cd android && ./gradlew :service-app:testDebugUnitTest
```
**Green** — new/extended: `ModerationReasonLabelsTest` (6), `DeletedReportsListViewModelTest`
(4), `DeletedReportDetailViewModelTest` (5), plus extensions to `ErrorCopyTest`,
`AssignmentHistoryCopyTest`, `WorkflowActionAvailabilityTest`, and `ReportDetailViewModelTest`
(5 new delete-flow cases: success, `REPORT_ALREADY_DELETED`-as-success, stale-version
no-blind-retry, `SessionEnded`, no-op-without-repository). All pre-existing Phase 2-8 unit
tests unchanged and still green.

**Service Android, instrumented (real `orszem-test` AVD, API 35):**
```
cd android && ./gradlew :service-app:connectedDebugAndroidTest
```
**28/28 tests, 0 failures, 0 skipped** — the 5 pre-existing Phase 2/8 suites unchanged and
green, plus 7 new: `ModerationDeleteActionComposeTest` (3 — SERVICE_USER sees no delete
action; MODERATOR's reason dialog blocks confirm until a reason is chosen and shows no
free-text field; a stale delete conflict shows the human message, refetches once, never
retries), `DeletedReportDetailComposeTest` (3 — MODERATOR gets the read-only hint with no
restore button; SUPER_ADMIN restoring a formerly-IN_PROGRESS report sees the exact matching
copy; the moderation section shows the localised reason, never the raw code),
`DeletedReportsListComposeTest` (1 — a card shows the localised reason and the `TÖRÖLVE`
UI label, never a raw code).

**Service Android, builds & lint:**
```
cd android && ./gradlew :public-app:assembleDebug :service-app:assembleDebug lint
```
Both debug APKs build. `service-app` lint: **0 errors, 0 warnings** (the two
`UnusedResources` warnings for the snackbar strings, present before the snackbar wiring was
finished, are gone). `public-app` lint: 9 pre-existing warnings, unrelated — no `public-app`
file was touched this phase.

**Web:**
```
cd web/public-web && npm run typecheck && npm test && npm run build
```
Typecheck clean. **55/55 tests pass.** Production build succeeds. Unaffected by this
phase — no web file was changed.

**Reference-data validator / deploy-config checks:** no file under `reference-data/` or
`deploy/` (or `docs/deployment/`) changed on this branch — confirmed by `git diff --stat`
against the merge base. Nothing to validate; nothing to regress.

---

## M. Future analytics invariant (brief §16)

Phase 11 is not implemented and this phase does not reach toward it. The structural
invariant the brief requires is satisfied: "currently deleted" is a single, queryable fact
(`report_moderation_episodes` with `restored_at IS NULL`) entirely separate from
`reports.status`, so a future analytics query can trivially exclude currently-deleted
reports — or include historically-deleted-then-restored ones — via a join or `NOT EXISTS`
against this table, without needing any schema change or reinterpretation of `reports`
itself.

---

## N. Live emulator verification (brief §68) — real throwaway backend, no fake data

A real PostgreSQL 16 container, the real backend via `java -jar backend.jar`, and the real
`orszem-test` AVD (API 35) — no test doubles, no synthetic in-memory data. Seeded through
the real APIs: one `SUPER_ADMIN` via the maintenance CLI (`./scripts/orszem-admin
create-super-admin`), one territorial `MODERATOR` and one `SERVICE_USER` via the real
`POST /users` endpoint, one `service_area`/`settlement`/`railway_line` inserted directly
(Phase 6 has no admin endpoint for `service_areas`, matching the Phase 6 report's own
precedent), and two real reports submitted through the real anonymous Public API.

Verified end-to-end, driven entirely through the installed app:

1. **MODERATOR, NEW-state delete**: delete action shown, clearly separated below a
   divider from the ordinary "Lezárás" action; reason dialog blocks confirm until a chip is
   chosen, no free-text field anywhere; confirming sends exactly the chosen reason; the
   report disappears from the NEW queue immediately, with the "A bejelentés törölve."
   snackbar.
2. **Moderáció hub**: live "Törölt bejelentések" entry alongside "Felhasználók"; the
   ServiceArea-management placeholder now honestly scoped to only that feature.
3. **Deleted list**: the deleted report renders with its localised reason
   ("Duplikált bejelentés," never `DUPLICATE`), the `TÖRÖLVE` UI badge, the deleting
   service ID, and the area — via the real filter sheet too.
4. **Deleted detail, MODERATOR**: the full moderation section (reason / deleted-by /
   deleted-at / status-before / restore-target), no restore button, the exact read-only
   hint copy.
5. **SUPER_ADMIN, IN_PROGRESS-state delete**: the full supervisor action row (Átrendelés/
   Visszaadás/Lezárás) clearly separated from the danger-styled delete action; the delete
   dialog shows the exact IN_PROGRESS warning copy; confirming ends the open assignment,
   removes the report from the IN_PROGRESS queue, and fires the success snackbar.
6. **Adminisztráció hub**: live "Felhasználók" and "Törölt bejelentések"; Phase 10/12
   placeholders shown honestly as "Még nem elérhető."
7. **SUPER_ADMIN sees a report a *different* moderator deleted** (brief §21) — the deleted
   list showed both reports, one deleted by the MODERATOR account and one by the
   SUPER_ADMIN account itself.
8. **Restore, formerly-IN_PROGRESS**: the exact matching confirmation copy ("A korábbi
   ügyintézői hozzárendelés nem áll vissza…"); confirming restores it, removes it from the
   deleted list, fires the "A bejelentés visszaállítva." snackbar. Confirmed via the real
   API: `assignee: null`, `workflowVersion: 3` (0 claim → 1, delete → 2, restore → 3).
9. **Public status transitions**, confirmed via the real anonymous lookup endpoint: the
   restored report now shows `RECEIVED`; the still-deleted report shows `CLOSED`; neither
   response leaks any moderation field.
10. **SERVICE_USER exclusion**: the fourth bottom-nav tab is "Profil," never Moderáció/
    Adminisztráció; opening the restored report's own detail — the exact report this
    SERVICE_USER had previously claimed and had moderation-deleted out from under them —
    shows only "Átvétel," no delete action anywhere, and the assignment history correctly
    renders the `MODERATION_DELETED` episode naming the assignee, never the moderator.

Two transient emulator network hiccups were observed mid-session (`ClientAbortException` /
"Connection reset by peer" on the emulator's virtual NAT, unrelated to any application
code — confirmed by the same request succeeding immediately via `curl` from the host). Both
times the app surfaced the honest "A kiszolgáló nem érhető el." message with a retry
button and made **zero** automatic retry attempts — exactly the deliberate no-blind-retry
behaviour documented in `NetworkModule.kt`, working as designed rather than masking the
failure. A fresh app launch (which discards the OkHttp connection pool) recovered cleanly
both times.

The throwaway PostgreSQL container and backend process were torn down afterward; no
residual infrastructure was left running.

**20 screenshots captured** (exceeding the 12 required) — every category in brief §69
covered at least once, several covered from more than one role, including 4 exact-copy
verifications (NEW delete confirmation, IN_PROGRESS delete warning, IN_PROGRESS restore
confirmation, moderator read-only hint) matched against the frozen §40-49 text and 2 live
success-state proofs (delete snackbar + queue removal, restore snackbar + list removal)
neither of which a static screenshot alone would have proven.

---

## O. Accessibility

Every interactive control reuses the existing Material3 sizing this codebase already
establishes (`Button`/`OutlinedButton`/`FilterChip`/`AlertDialog` defaults, unchanged) — no
custom touch targets were introduced. Danger styling on the delete action uses color plus
the "Bejelentés törlése" label text, never color alone. No new custom Composable bypasses
the platform's built-in accessibility semantics (all controls are standard Material3
components with their default `contentDescription`/semantics behaviour).

---

## P. Known limitations — not hidden

- **No Phase 10/11/12 work.** ServiceArea administration, analytics, and the audit-event
  viewer remain honest "Még nem elérhető" placeholders in both hubs.
- **The two emulator network hiccups in §N** were environmental (host-machine load from
  the many Gradle builds run in this same session), not reproduced against a quiescent
  host, and not a code defect — see §N's own analysis.
- **`reference-data/local-research` still holds only a README** — unrelated to this phase,
  unchanged, exactly as it was after Phase 3/8.
- Live-verification credentials (SUPER_ADMIN/MODERATOR/SERVICE_USER service IDs and
  passwords) were generated fresh in this session against a throwaway container that has
  since been destroyed; none were reused from a prior phase and none are committed anywhere.

---

## Q. Regression / CI

All test commands and their results are in §L. The full backend build (`./gradlew clean
build`, 605 tests), the full Service Android suite (unit + instrumented + both debug builds
+ lint), and the full Web suite (typecheck + 55 tests + build) are all green. No test was
weakened, skipped, or removed to reach green — §K.4's `DatabaseBaselineIT` change is an
addition to what the test asserts, not a relaxation.

The 5 GitHub Actions CI workflows have not yet been run on this branch (no PR is open — see
§S); once opened, `docs/CI_RUN_<sha>.md`-style bookkeeping will follow the exact pattern
Phase 6-8 established.

---

## R. Definition of done — checklist

- [x] Backend: migration, domain, application, infrastructure, API — all frozen shapes honored
- [x] Backend: full concurrency/invariant matrix, real PostgreSQL
- [x] Backend: full regression green (605 tests)
- [x] Android: moderation UI wired into existing screens/hubs, no redesign
- [x] Android: unit + Compose/instrumented tests, full regression green
- [x] Android: production-copy review clean
- [x] Web: unaffected, full regression green
- [x] Reference-data / deploy-config: unaffected (no files changed)
- [x] Live emulator verification, real throwaway data, all three roles + Public
- [x] 20 screenshots captured (exceeds the 12 required)
- [x] This engineering report
- [ ] **Owner visual approval** — PENDING (§S)
- [ ] PR — NOT opened, pending owner approval
- [ ] Merge — NOT done
- [ ] Phase 10 — NOT started

---

## S. Owner visual approval — record

**PENDING.** The 20 screenshots referenced in §N are being sent to the owner alongside this
report. Per the exact process established in Phase 8: no PR will be opened, nothing will be
merged, and Phase 10 will not start until the owner explicitly reviews the screenshots and
gives visual approval. This section will be updated with the date and scope of that
approval once given — not before.
