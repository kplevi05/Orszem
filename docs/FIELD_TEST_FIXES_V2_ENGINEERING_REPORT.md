# V2 physical-device field-test fixes — engineering report

**Status: owner-approved after visual review, PR open for merge review — not merged, not
deployed.** `v2.0.0` and the live `2.0.2` build are unchanged. Base: `origin/main` =
`480f220fdc568eb61bbc8396d9a4f70037e88955`. Branch: `fix/v2-field-test-issues`. No database
migration, no production data, DNS, credential, reference-data or deployment-configuration change.

Scope: the six physical-device field-test issues in the task brief, and only those — Service
Android narrow-width layout, Public Android settlement autocomplete focus/keyboard, GPS timeout,
refresh-on-navigation, in-app back arrows on nested Moderator/SUPER_ADMIN screens, and their
regression tests. No product redesign, no new feature, no architecture change.

## 0. Architecture inspection (done first, before any code change)

Read `ServiceNavHost.kt` in full (690 lines) plus every affected ViewModel and screen before
touching anything. Conclusion: **no conflict with the existing navigation/ViewModel/state
architecture, and no architecture or security change was required.**

- Navigation-Compose's own back-stack lifecycle already gives each destination's
  `NavBackStackEntry` a `LifecycleOwner` that reaches `RESUMED` on first composition and again
  every time the user returns to it (popping a pushed screen, or switching bottom-nav tabs back)
  — exactly the signal issue 4 needed, with no new dependency (only `LocalLifecycleOwner` +
  `DisposableEffect` + `LifecycleEventObserver`, all already reachable from
  `lifecycle-runtime-ktx`, which the module already depends on).
- The existing `Job`-cancel-and-relaunch idiom (already used for settlement search and railway-
  line lookup in `NewReportViewModel`) is the same idiom every affected list ViewModel's
  `refresh()` needed for issue 4's "no duplicate concurrent refresh" requirement — extended, not
  invented.
- `onBack = { navController.popBackStack() }` is already the established pattern for every other
  detail/nested screen in `ServiceNavHost.kt` (report detail, audit detail, service-area detail,
  the railway-line picker, account, user detail, create screens). Issue 5 is applying that same,
  already-proven wiring to four list screens that had been missing it, never inventing a second
  navigation path.
- `ExposedDropdownMenuBox` + `DropdownMenu` already exists in `Step1Content.kt`; the fix is one
  constructor parameter (`PopupProperties(focusable = false)`), not a different component.

No owner-review item was raised by this inspection.

## 1. Commits

Work was done directly on `fix/v2-field-test-issues` (branched from `origin/main`); see `git log`
for the exact sequence. No V1 code, no `docs/archive/` citation, no secret, no production target
in any script.

## 2. The six fixes

### §1 — Service Android narrow-width layout

**Root cause:** [`ReportListItemCard.kt`](../android/service-app/src/main/kotlin/hu/orszembejelento/service/reports/ui/ReportListItemCard.kt)'s
meta row was a plain, non-wrapping `Row`; a long `ServiceArea` name (a real production name, or a
`[FIKTÍV] …` placeholder) shifted or clipped the row at narrow width and/or 1.3x font scale.
`ReportDetailScreen.kt` and `DeletedReportDetailScreen.kt`'s shared `DetailRow` pattern had the
same defect in a different shape: label and value side by side in one `Row(SpaceBetween)` with no
width guard on either `Text`.

**Fix:** mirrors [`DeletedReportListItemCard.kt`](../android/service-app/src/main/kotlin/hu/orszembejelento/service/moderation/ui/DeletedReportListItemCard.kt)'s
already-correct shape (Phase 9 correction pass), used here as the template rather than invented
fresh. The card's submitted-at/category chips wrap in a `FlowRow`; the area name gets its own
full-width row below, wrapping as ordinary multi-line text. `DetailRow` changed from a
`Row(SpaceBetween)` to a `Column` — label above value, both spanning full width — which is correct
at any width/font scale by construction. **No name is ever truncated or abbreviated**; neither
`Text` carries a `maxLines`/`overflow` constraint.

Files: `ReportListItemCard.kt`, `ReportDetailScreen.kt`, `DeletedReportDetailScreen.kt`.

**Side effect caught by the regression run:** the taller stacked `DetailRow` legitimately pushes
`DeletedReportDetailScreen`'s restore button further down a scrolling screen. The pre-existing
instrumented test `DeletedReportDetailComposeTest.a_super_admin_restoring_a_formerly_in_progress_report_sees_the_exact_matching_copy`
clicked that button without scrolling to it first; fixed to `performScrollTo().performClick()`,
matching what a real user now has to do. Confirmed by running the same test against a clean
`origin/main` worktree (passes there, failed consistently on this branch until fixed) — a genuine,
expected consequence of the layout fix, not a flake.

### §2 — Public Android settlement autocomplete focus/keyboard

**Root cause:** `SettlementField`'s suggestion list was a default, *focusable* `DropdownMenu`. On
a physical device, opening or re-laying-out a focusable popup window steals window focus from the
anchor `OutlinedTextField` for an instant — the IME follows window focus, not Compose's own focus
model — which is what let every arriving/changing search result dismiss the keyboard.

**Fix:** [`Step1Content.kt`](../android/public-app/src/main/kotlin/hu/orszembejelento/app/ui/newreport/Step1Content.kt) —
one added constructor argument, `properties = PopupProperties(focusable = false)`, the standard
fix for an autocomplete-style dropdown. `NewReportViewModel.onSettlementQueryChanged` already
cleared `selectedSettlement` on edit while continuing the debounced search, and
`NewReportUiState.step1Valid` already required a non-null `selectedSettlement` — both confirmed
correct by inspection and needed no change; the defect was entirely the popup stealing focus.

### §3 — GPS timeout

**Root cause:** `LocationAssist.currentLocation()` had no bound; a device/provider combination
that never calls back left `LocateStatus.LOCATING` spinning forever.

**Fix:** [`NewReportViewModel.kt`](../android/public-app/src/main/kotlin/hu/orszembejelento/app/ui/newreport/NewReportViewModel.kt) —
`withTimeout(15_000L) { locationAssist.currentLocation() }`, catching `TimeoutCancellationException`
specifically (not `withTimeoutOrNull`, which cannot distinguish an ordinary null result from an
actual timeout) to set the new `LocateStatus.TIMEOUT` state distinctly from `FAILED`.
`withTimeout`'s cancellation propagates into `LocationAssist`'s `suspendCancellableCoroutine` and
its existing `invokeOnCancellation` handler, so the in-flight platform request is genuinely
cancelled, not merely ignored. The loading indicator always stops either way; manual settlement
selection stays available throughout (`Step1Content` renders it unconditionally); the pre-existing
`locationServicesEnabled()` GPS-disabled check and its dialog flow are untouched. A new string
(`locate_me_timeout`) gives the retryable, user-facing message.

Files: `NewReportUiState.kt` (new `LocateStatus.TIMEOUT`), `NewReportViewModel.kt`,
`Step1Content.kt`, `strings.xml`.

**Test-only change:** `LocationAssist` and its `locationServicesEnabled()`/`currentLocation()`
members were made `open` — the only seam a JVM unit test has for simulating a real,
never-resolving GPS fix (the platform APIs cannot be made to hang on demand from a mocked
`Context`). One production call site (`AppContainer.kt`), unaffected; every real caller still gets
the unmodified platform-backed implementation.

### §4 — Refresh once on navigate/activate

**Root cause:** every operational list/detail ViewModel loads once in its own `init {}`, but
nothing reloaded it on returning to an already-composed screen (most visibly: returning from a
detail screen after a claim/return/close/restore/edit).

**Fix:** new [`RefreshOnResume.kt`](../android/service-app/src/main/kotlin/hu/orszembejelento/service/common/ui/RefreshOnResume.kt) —
`LocalLifecycleOwner` + `DisposableEffect` + `LifecycleEventObserver`, firing on
`Lifecycle.Event.ON_RESUME` (never a timer — no background polling). Wired into
`ServiceNavHost.kt` at the seven required destinations: Reports (both New and In-Progress queues),
Archive, Analytics, Moderation's deleted-reports list, Service Areas, Audit; Users likewise. Manual
refresh (pull/retry buttons) is untouched.

**Duplicate-refresh guard:** every one of the six affected ViewModels
(`ReportQueueViewModel`, `ServiceAreaAdminListViewModel`, `AuditListViewModel`,
`UsersListViewModel`, `DeletedReportsListViewModel`, `AnalyticsViewModel`) gained a
`private var refreshJob: Job?` that is cancelled before a new `refresh()` coroutine is launched —
the same cancel-and-relaunch idiom already used elsewhere in the codebase for settlement search —
so `RefreshOnResume` firing close to a screen's own `init`-time load can never let a slower, stale
response overwrite a fresher one, and no two refreshes ever race.

### §5 — In-app back arrow on nested Moderator/SUPER_ADMIN screens

**Root cause:** `UsersScreen`, `ServiceAreaAdminListScreen`, `AuditListScreen` and
`DeletedReportsListScreen` relied on the Android system Back action alone.

**Fix:** each screen gained an `onBack: () -> Unit` parameter and a `Scaffold`/`TopAppBar` with a
leading `IconButton(Icons.AutoMirrored.Filled.ArrowBack)`. `ServiceNavHost.kt` wires all four to
`onBack = { navController.popBackStack() }` — the exact same call every other nested screen in the
graph already uses for its own back arrow, so it shares the real back stack and can never create a
duplicate destination (proved by `BackArrowComposeTest`, see §6).

### §6 — Regression tests

New/changed test files (grouped by issue; existing call sites updated for the new `onBack`
parameter where needed — `AuditComposeTest.kt`, `ServiceAreaAdminComposeTest.kt`,
`DeletedReportsListComposeTest.kt`):

| Issue | Test(s) |
|---|---|
| §1 layout | `NarrowWidthLayoutComposeTest` (new) — 320dp width + 1.3x font scale; asserts the full, untruncated area name renders, wraps to a taller card than a short name, and `DeletedReportDetailScreen`'s label/value are stacked, never sharing a row |
| §2 focus/keyboard | `SettlementFocusComposeTest` (new) — types into the field and asserts Compose focus survives the debounced result arrival; asserts editing a selected settlement clears `selectedSettlement` while keeping focus and continuing search; asserts unselected typed text never satisfies `step1Valid`. `NewReportViewModelTest`'s existing clear-on-edit/staleness tests were already present and re-verified |
| §3 GPS timeout | `NewReportViewModelLocateTest` (new) — virtual-time (`StandardTestDispatcher`), a `LocationAssist` subclass whose `currentLocation()` never resolves on its own; asserts `LOCATING` holds at 14.999s, `TIMEOUT` at 15.001s, the in-flight request is genuinely cancelled, and a retry after a timeout works cleanly |
| §4 refresh-on-navigate | One new test per affected ViewModel (`ReportQueueViewModelTest`, `ServiceAreaAdminListViewModelTest`, `AuditListViewModelTest`, `UsersListViewModelTest`, `DeletedReportsListViewModelTest`, `AnalyticsViewModelTest`) — `StandardTestDispatcher`, lets the `init{}`-triggered call genuinely start (`runCurrent()`), then calls `refresh()` again while it is still in flight with a slower/stale vs. faster/fresh fake response; asserts the fresh response wins |
| §5 back arrows | `BackArrowComposeTest` (new) — for each of the four screens, asserts exactly one back affordance exists, tapping it calls `onBack` exactly once, and never fires an `onOpen*`/`onCreate*` callback (i.e. never pushes a second destination) |

**Self-caught defects during authoring (all fixed before this report, none reached a passing
build):**
- Initially used `withTimeoutOrNull` for §3, which cannot distinguish an ordinary null result from
  an actual timeout — caught before any build, switched to `withTimeout` + catching
  `TimeoutCancellationException`.
- The six §4 race tests initially cancelled the `init{}`-triggered call before it had actually
  started (no call had incremented its counter yet), so nothing was really racing — caught by the
  first full unit-test run (all six failed with the same shape); fixed by calling
  `dispatcher.scheduler.runCurrent()` before the second `refresh()`, so the first call is
  genuinely in flight (past its own network call and suspended in `delay(...)`) before being
  cancelled.
- `SettlementFocusComposeTest`'s fixture used a non-UUID settlement id (`"s1"`); the real
  `ReferenceRepository.searchSettlements` parses it with `UUID.fromString` and silently drops
  anything that fails, so the search always came back empty on-device — caught by the first
  instrumented run; fixed to a real UUID string.
- Two import/reference errors (`ReportRepository` unresolved in the new GPS test; a wrong
  `onAllNodes` import and a missing `DpRect.height` extension-property import in the new
  androidTest files) — caught by compile failures, fixed immediately.
- `DeletedReportDetailComposeTest`'s scroll-to-click fix, above (§1) — a genuine consequence of the
  layout fix, verified against clean `origin/main` before changing the test.

## 3. Owner visual review (screenshots)

Screenshots were captured from the real emulator against an isolated local `deploy/eval` stack
(synthetic data only — no production database, credential or DNS touched) and reviewed directly
by the owner, both on-screen on the emulator and as attached image files, covering:

- §1/§2 narrow-width report list and detail layout, and the long-ServiceArea-name wrap, reviewed
  directly on the emulator at 320dp-equivalent width and 1.3x font scale.
- §2 the settlement autocomplete popup open over a real KSH-sourced settlement while the software
  keyboard stayed visible.
- §3 the GPS locate button's in-progress ("Helymeghatározás folyamatban…") and resulting
  retryable-error states (the emulator's simulated location provider resolves natively in well
  under a second when no fix is ever injected, so the literal 15-second-timeout wording could not
  be forced live in this environment; the 15-second boundary itself is what
  `NewReportViewModelLocateTest` verifies deterministically in virtual time — see §6. The owner
  accepted the in-progress/error screenshots plus that test as sufficient).
- §5 all four new in-app back arrows (Users, Service Areas, Audit, Deleted Reports), each tapped
  and confirmed to return to the Adminisztráció hub with no duplicate destination.

**Outcome: owner visual review complete and APPROVED.** No blocking visual regression was found on
any screen; no further UI change was made as a result of this review. One rendering artifact was
investigated during the session (garbled text after changing the emulator's `wm size` under an
already-running activity) and traced to a stale-composited-buffer glitch from the live resize
itself — absent from the accessibility tree, gone after a clean relaunch, root-caused as a testing-
methodology artifact rather than an app defect, and not seen again.

## 4. Results (final HEAD, this branch)

| Suite | Command | Result |
|---|---|---|
| Backend | `cd backend && ./gradlew build` | **BUILD SUCCESSFUL** (Testcontainers, Docker Desktop running) |
| Public + Service Android unit | `./gradlew :public-app:testDebugUnitTest :service-app:testDebugUnitTest` | **BUILD SUCCESSFUL**, 175+ tests, 0 failures on the final run |
| Public + Service Android assemble | `./gradlew :public-app:assembleDebug :service-app:assembleDebug` | **BUILD SUCCESSFUL** |
| Public + Service Android lint | `./gradlew :public-app:lint :service-app:lint` | **BUILD SUCCESSFUL**, 0 errors |
| Public + Service Android instrumented | `./gradlew :public-app:connectedDebugAndroidTest :service-app:connectedDebugAndroidTest` (real emulator, `emulator-5554`) | **BUILD SUCCESSFUL** — public-app **64** tests / 0 failures, service-app **200** tests / 0 failures |
| Public Web | `cd web/public-web && npm ci && npm run typecheck && npm run build` | **BUILD SUCCESSFUL**, 0 vulnerabilities |
| Reference-data validator | `node reference-data/tools/validate-canonical.mjs reference-data/{example,evaluation}/manifest.json` | both **valid** (unchanged this phase) |
| Deployment scripts | syntax check, executable-bit check, no-hardcoded-production-hostname check (the locally-runnable subset of `deploy-config.yml`) | **pass** (unchanged this phase; `deploy/` was not touched, so the full Caddy-install/serve CI job — which needs a `caddy` binary this machine doesn't have — was not replicated locally and is left to CI) |

**Known pre-existing flake, unrelated to this branch:** `AuthRepositoryTest.concurrent refreshes
send exactly one request` failed once inside a full 175-test parallel run, then passed 3/3 in
isolation and passed cleanly on a second full-suite run. No file under `auth/` was touched by this
branch (confirmed via `git diff` scoping); this is a pre-existing timing-sensitive test, not a
regression.

## 5. Limitations / owner items

- No version bump was made (`versionName`/`versionCode` unchanged at `2.0.2`/`3` for both apps).
  Whether this lands as `2.0.3` or folds into a later release is an owner call, not made here.
- Physical-device verification (the actual field-test devices) was not repeated by this session —
  fixes were verified on a real Android emulator (API 15/`orszem-test(AVD)`) plus JVM unit tests;
  the owner should still confirm on the original field-test hardware before considering the issues
  closed operationally.
- `deploy/caddy`'s full CI job (Caddy install, routing/redaction/CSP scripts) was not re-run
  locally (no local `caddy` binary, and `deploy/` was not touched this phase) — left to the
  `deploy-config` GitHub Actions workflow on push.

## 6. Not done (out of scope by the task's own instruction)

No deploy, no merge, no tag. A pull request from `fix/v2-field-test-issues` to `main` is opened for
merge review after owner visual approval (§3); it is not merged by this session.
