# Phase 8 engineering report — Service Android full operational UI

Phase 8 builds the actual Service Android application on top of the existing Phase 2
authentication, Phase 6 user-management backend and Phase 7 report-workflow backend. It is
an **Android client phase**: no new product features, no new backend endpoints. The one
backend change that touched this phase (`jackson-module-kotlin`, PR #11) was a correctness
fix shipped on its own branch and merged to `main` before Phase 8 resumed — see §A and §N.

> **Owner visual approval: NOT YET GIVEN.** The representative screenshots in §M are
> prepared for review. This phase stops here for the owner to approve the final appearance.
> No Phase 8 PR has been opened.

---

## A. Git

| | |
|---|---|
| Branch | `feature/v2-service-android-ui` (created off `main`; never rebased, no history rewrite) |
| `main` at phase start | `eaa741e` — *Merge pull request #10 from …/feature/v2-service-report-workflow* |
| Backend hotfix merged in | `f3cc60b` — *Merge pull request #11 from …/fix/backend-kotlin-json-defaults* (contains `fff5539`) |
| `main` the branch is built on | `f3cc60b` (fast-forward merge of `origin/main` into the branch; the Phase 8 working tree was untouched) |
| Phase 8 commit | `c262245` — *feat(service-android): Phase 8 — full operational Service UI* |

The backend hotfix (`jackson-module-kotlin`) was developed in a **separate git worktree** on
branch `fix/backend-kotlin-json-defaults`, reviewed, merged as PR #11, and only then merged
into the Phase 8 branch. The Phase 8 Android work was never discarded or rewritten.

No PR opened. Nothing merged. No Phase 9 work.

---

## B. Android architecture

One Gradle build under `android/`, two apps. Phase 8 changes are confined to `service-app`
plus two shared version-catalog entries.

### Layering

```
MainActivity ─ NetworkModule (manual DI, no framework)
   │                │
   │        AuthRepository (Phase 2, unchanged core) ── EncryptedTokenStore ── KeystoreCryptoBox
   │                │
   └─ ServiceAuthHost ── AuthViewModel (single owner of AuthState)
             │
        AuthState.Authenticated → ServiceNavHost(serviceId, role, …)
             │
   ┌─────────┼──────────────────────────────────────────────┐
   Scaffold + NavigationBar (4 destinations by role)   NavHost (10 routes)
   session-scoped ViewModelStore ── 3 queue ViewModels    per-route ViewModels
```

- **Repositories are interfaces.** `ReportWorkflowRepository` and `UserManagementRepository`
  are interfaces; the Retrofit-backed implementations are `DefaultReportWorkflowRepository` /
  `DefaultUserManagementRepository`, wired only in `NetworkModule`. This is what lets every
  ViewModel test use a zero-argument `object : …Repository { … }` fake with no network types.
- **`ApiResult<T>`** (`common/data/ApiResult.kt`) — `Success` / `Failure(code, httpStatus)` /
  `SessionEnded` / `NetworkError`. Every repository method is a one-line wrapper around
  `apiCall(auth) { … }`, which runs the call through `AuthRepository.authorizedCall`
  (401 → refresh-once → retry-once, Phase 2 semantics) and decodes the stable error code
  from a failed body exactly once.
- **Centralized error copy** — `common/ui/ErrorCopy.kt` maps every stable code from brief §78
  to Hungarian; unknown codes fall through to a generic safe message.
- **Pure domain functions, unit-tested without Compose** — `bottomRoutesFor(role)`,
  `availableWorkflowActions(status, role, isOwnAssignment)`, `buildAssignmentHistoryEntries`,
  `statusLabelRes`, `shortReportId`, `formatInstant`.
- **State holders** — one `ReportQueueViewModel` reused for NEW / IN_PROGRESS / Archive
  (differs only by the `fetchPage` reference); `ReportDetailViewModel`, `UsersListViewModel`,
  `UserDetailViewModel`, `CreateUserViewModel`. No networking or workflow rules live in
  Compose; the backend stays the business-rule authority.
- **No new persistence.** No Room, no Service report database, no outbox. In-memory /
  ViewModel state only (brief §46).

### New dependencies

| Coordinate | Scope | Why |
|---|---|---|
| `androidx.navigation:navigation-compose` | impl | already in the catalog, now used for the authenticated nav graph |
| `androidx.compose.material:material-icons-extended` | impl | `Archive` / `BarChart` / `Description` / `Shield` / `AdminPanelSettings` are not in `-core` |
| `androidx.compose.ui:ui-test-junit4` | androidTest | the §95-99 Compose UI tests |
| `androidx.compose.ui:ui-test-manifest` | debug | Compose test host activity |

All are free, first-party AndroidX. No paid API / SaaS / analytics / crash / map / UI-kit
dependency was added (brief §89-90).

---

## C. Role → navigation matrix (FROZEN, brief §5)

Exactly four bottom destinations, chosen only from the server's `role`
(`bottomRoutesFor(role)`, `internal`, pure, unit-tested):

| Position | `SERVICE_USER` | `MODERATOR` | `SUPER_ADMIN` |
|---|---|---|---|
| 1 | Bejelentések | Bejelentések | Bejelentések |
| 2 | Archívum | Archívum | Archívum |
| 3 | Statisztika | Statisztika | Statisztika |
| 4 | **Profil** | **Moderáció** | **Adminisztráció** |

- Positions 1-3 are identical for every role.
- There is **no production role switcher, no demo account picker, no hidden client-side role
  selector**. The role comes only from `AuthState.Authenticated.role`, which Phase 2/6
  re-evaluate server-side on the next request. A role that changes server-side mid-session is
  picked up on the next authenticated call and re-drives navigation without a forced logout.
- User management is reachable only from the Moderáció (MODERATOR) and Adminisztráció
  (SUPER_ADMIN) hubs. A `SERVICE_USER` has no route to it — `ServiceNavHost` passes
  `userManagementRepository = null` into any screen that could otherwise reach it when
  `role == "SERVICE_USER"`.

---

## D. Authentication & session

Phase 2 is used exactly as-is; Phase 8 added no auth endpoint and did not weaken the model.

- **Login** — Service ID + password + one "Bejelentkezés" button. Title "Bejelentkezés",
  subtitle "Szolgálati azonosítóval és jelszóval.", fields "Szolgálati azonosító" / "Jelszó".
  Generic failure copy; no user-existence disclosure; rate-limit surfaced without limiter
  internals. Entered passwords are never logged.
  - *Deviation from brief §8's suggested wording*: the shipped screen (Phase 2) uses
    "Bejelentkezés" / "Szolgálati azonosítóval és jelszóval." rather than the brief's
    suggested "Belépés" / "Szolgálati belépés". Both are natural Hungarian; this is existing
    Phase 2 copy and was not rewritten. Flagged for the owner in §M.
- **Forced initial password change** — the existing `AuthState.PasswordChangeRequired` flow.
  No fake authenticated session is ever constructed on `PASSWORD_CHANGE_REQUIRED`. Copy:
  "Jelszócsere szükséges" / "A(z) `<id>` azonosítóhoz új jelszót kell beállítani." + the
  baseline guidance "A jelszó legalább 15 karakter hosszú legyen. Szóköz és ékezet is
  használható." Backend rejections are mapped into Hungarian.
- **App start** — `AuthState.RestoringSession` → restores the encrypted refresh state via the
  existing `EncryptedTokenStore` / `KeystoreCryptoBox`; on a valid session it loads
  `{serviceId, role}` from `/account/me` and lands on the correct role UI; otherwise the
  login screen. An authorized screen is never shown from stale local role data.
- **Session security — unchanged**: access token in memory only; refresh token in
  AndroidKeyStore AES-GCM; no plaintext refresh token; refresh excluded from backup; single-
  flight refresh; ambiguous refresh failure not blindly retried. The Phase 2
  `KeystoreTokenStoreInstrumentedTest` (10 tests) and `AuthFlowInstrumentedTest` (7 tests)
  were re-run unmodified on the emulator and pass (§L).
- **Two-tier session-ended propagation** — a feature ViewModel that receives
  `ApiResult.SessionEnded` calls `AuthViewModel.forceSignedOut()`, which clears the local
  session and sets `AuthState.Unauthenticated` without a redundant network call.
- **Logout** — clears the access token, securely clears/revokes the refresh state, **clears
  role-dependent UI state** (see §E's session-scoped ViewModel store), returns to login, and
  leaves no privileged screen in the back stack.

---

## E. Report-workflow UI

### Screens

- **Bejelentések** — exactly two tabs: **Új** / **Folyamatban**. No third Archive tab
  (Archívum is its own bottom destination).
- **Új queue** — `GET /service/reports/new`. The client never recomputes the 168 h cutoff or
  decides RECENT/OLDER from the device clock; it renders backend order and inserts a
  "Régebbi bejelentések" divider before the first item whose backend `ageBucket == "OLDER"`.
  Raw `RECENT`/`OLDER`/`168h` strings never appear.
- **Folyamatban queue** — `GET /service/reports/in-progress`. `SERVICE_USER` sees own only;
  `MODERATOR`/`SUPER_ADMIN` see everything in scope. Oldest-first per backend; never
  reordered locally. `workflowVersion` is kept in screen/view state, never rendered.
- **Archívum** — `GET /service/reports/archive`. Newest-archived-first per backend.
  Read-only: no reopen, no action controls. No client-side "only mine" filter — area-based
  visibility is the backend's call.
- **Report detail** — `GET /service/reports/{publicReportId}`. Renders category, event type,
  occurred/submitted time, train identifier, settlement, railway line, ServiceArea, status
  badge, current assignee, assignment-history timeline, archive time and the full report
  identifier. Never renders the internal DB UUID, a credential hash, the Public access
  credential, audit metadata, routing internals or the workflow-version number.
- **Assignment history** — `buildAssignmentHistoryEntries` turns `report_assignments`
  episodes into a human timeline ("SZ-1042 átvette", "SZ-2041 átrendelte SZ-1059
  felhasználóhoz", "SZ-1059 ügyintézése lezárult"). One line per transition; a `REASSIGNED`
  end line is suppressed because the next episode's start line already states that
  transition. Section heading "Ügyintézési előzmények" / "A bejelentés eddigi menete." — see
  §K for the production-copy fix here.

### Status labels (brief §83)

`NEW → ÚJ`, `IN_PROGRESS → FOLYAMATBAN`, `ARCHIVED → LEZÁRVA`, `UNCLASSIFIED → BESOROLATLAN`.
Raw enum names are never shown. `statusLabelRes` / `statusColor` are pure and unit-tested.

### Action availability (`availableWorkflowActions`, pure, unit-tested)

| status | `SERVICE_USER` (own) | `SERVICE_USER` (not own) | `MODERATOR` / `SUPER_ADMIN` |
|---|---|---|---|
| NEW | Átvétel | — | Lezárás (supervisor closes NEW directly; never self-claims) |
| IN_PROGRESS | Visszaadás, Lezárás | — | Átrendelés, Visszaadás, Lezárás |
| ARCHIVED | — | — | — (terminal, no reopen) |

Every mutation sends the report's actual current `expectedVersion` (never a guessed
version), disables the tapped control while in flight, and — critically — **never blindly
retries**. `claim`/`return`/`close`/`reassign` all follow the same `mutate(kind, call)`
shape: on success, replace local state from the committed response; on `SessionEnded`, sign
out; on **any** other failure, store the error *and* silently re-fetch the current state
(`load(silent = true)`) — the mutation itself is not replayed.

### Reassign target picker

Reuses Phase 6 `GET /service/user-management/users` with `role = SERVICE_USER`,
`status = ACTIVE` and a debounced `query`; radio-button selection; no new endpoint.
`canManage` is deliberately **not** consulted — it is a different business rule from "valid
reassignment target". Backend `reassign` validation stays authoritative.

### Conflict UX (brief §23-25 / §36 / §60-62 / §78-79)

| stable code | Hungarian copy shown | client behaviour |
|---|---|---|
| `REPORT_ALREADY_ASSIGNED` | "Ezt a bejelentést időközben már átvette egy másik ügyintéző." | refresh, no auto-retry |
| `REPORT_STATE_CHANGED` | "A bejelentés időközben megváltozott. Frissítettük az aktuális állapotot." | fetch current state, replace local, user decides again |
| `REPORT_ALREADY_ARCHIVED` | "A bejelentést időközben lezárták." | refresh to ARCHIVED, disable controls |
| `REPORT_NOT_FOUND` | "A bejelentés már nem érhető el az Ön számára." | never reveal hidden existence; return/refresh |
| `INVALID_ASSIGNEE` | "A kiválasztott felhasználó jelenleg nem rendelhető ehhez a bejelentéshez." | refresh |
| `REPORT_UNCLASSIFIED_CANNOT_ASSIGN` | "Besorolatlan bejelentés nem rendelhető szolgálati ügyintézőhöz." | — |
| `USER_HAS_ACTIVE_REPORT_ASSIGNMENTS` | "A művelet nem végezhető el, mert a felhasználónak folyamatban lévő bejelentése van. Előbb rendezze ezeket az ügyeket." | + CTA "Folyamatban lévő ügyek megtekintése" → Bejelentések→Folyamatban pre-filtered by `assigneeServiceId` |
| network / server unavailable | "A kiszolgáló nem érhető el. Ellenőrizze a kapcsolatot." + retry | keep state, explicit retry, never queue a mutation |

No raw stable code, exception text or debug string is ever shown. `REPORT_STATE_CHANGED`
non-retry + re-fetch + human message is covered by a ViewModel unit test **and** a Compose
UI test (§L, brief §96 CRITICAL).

### UNCLASSIFIED (brief §37-38)

Rendered from whatever the backend returns — no fake "Besorolatlan" ServiceArea is created.
`SERVICE_USER` and territorial `MODERATOR` never see it; a global `MODERATOR`/`SUPER_ADMIN`
may view and directly close it. It is never claimable, assignable to a `SERVICE_USER`, or
re-routable — there is no routing UI in Phase 8.

---

## F. User-management UI

Phase 6's backend becomes usable; entry lives inside the Moderáció / Adminisztráció hubs.

- **User list** — `GET …/users`. Service-ID search, real backend rows: service ID, role,
  ACTIVE/DEACTIVATED, area summaries / global access, and the `canManage` hint. No names,
  emails or phones (they do not exist in this product model). Load-more pagination
  (backend page 0 / size 50); no unbounded list; no duplicates across pages; clean reset on
  refresh.
- **`canManage` presentation** — a UI hint only, never authorization truth. A peer
  `MODERATOR` is shown as "Csak megtekinthető" (visible, read-only); a `SUPER_ADMIN` row is
  "Csak megtekinthető" from another SUPER_ADMIN's view. The literal `canManage=false` /
  "READ-ONLY" strings are never shown.
- **Managed-user detail** — service ID, role, status, a "Kötelező jelszóváltás" indicator
  where set, global access, assigned ServiceAreas, and the actions the actor may take.
  Never the internal UUID, password hash, tokens, sessions or audit rows.
- **Create user** — SUPER_ADMIN may create `SERVICE_USER` or `MODERATOR` (segmented button);
  a MODERATOR sees `SERVICE_USER` only. Area checkboxes; a distinct "Minden terület" global-
  access checkbox with the hint "Külön globális jogosultság, nem szolgálati terület."
  Service ID, the temporary credential, status and `mustChangePassword` are all
  server-generated; the client sends only `role` / `areaIds` / `globalAreaAccess`.
- **Capability matrix (matches the backend response, not re-implemented as client security)**

  | action | `MODERATOR` (manageable target) | `SUPER_ADMIN` |
  |---|---|---|
  | grant / revoke ServiceArea | ✔ | ✔ |
  | password reset | ✔ | ✔ |
  | deactivate / reactivate | ✔ | ✔ |
  | change role (`SERVICE_USER ↔ MODERATOR`) | ✘ (not shown) | ✔ (confirmation required) |
  | grant / revoke global access | ✘ (not shown) | ✔ |

  Verified live: the MODERATOR view of a manageable `SERVICE_USER` shows exactly the first
  three; the SUPER_ADMIN view of the same user shows all five (§M, SHOT 12 vs SHOT 16).
- **Deactivate** — confirmation "Felhasználó deaktiválása" / "A felhasználó nem tud majd
  bejelentkezni, és a jelenlegi munkamenetei megszűnnek." No claim that reports auto-move
  (they do not).
- **Role / area / global mutations** — explicit endpoints only, never whole-list
  replacement. `USER_REQUIRES_SERVICE_AREA` → "A felhasználónak legalább egy szolgálati
  területtel kell rendelkeznie." Any stale/authority failure silently refreshes the managed
  user so stale controls do not linger.

### Active-assignment cross-phase guard (brief §60-62) — verified end-to-end

Deactivating (or role-changing / area-narrowing) a user who holds an open report assignment
returns `409 USER_HAS_ACTIVE_REPORT_ASSIGNMENTS`. The client:

1. shows the natural copy above (never the raw code);
2. leaves the user's state unchanged (a silent refresh confirms it);
3. offers a **"Folyamatban lévő ügyek megtekintése"** button that navigates to
   Bejelentések → Folyamatban pre-filtered by that user's `assigneeServiceId`;
4. never auto-retries the admin mutation and never describes a database race.

Verified live: SHOT 14 (guard + CTA), SHOT 14b (the pre-filtered IN_PROGRESS list showing
exactly that user's two reports).

---

## G. Temporary-credential security (brief §56-57 / §92)

`CredentialDisplayDialog` (shared by create-user and password-reset):

- shown **once**, only while the dialog is open;
- **not** persisted to Room / DataStore / `SavedStateHandle` / navigation arguments; not
  logged; not sent to analytics; not written to backup; not auto-copied to the clipboard;
- an explicit user-initiated "Másolás" action is offered (via the long-stable
  `LocalClipboardManager` API) with no clipboard-secrecy promise;
- warning copy: "Az ideiglenes jelszó csak most látható. Mentse el biztonságosan, mielőtt
  bezárja ezt az ablakot."; actions "Másolás" / "Elmentettem";
- if the sheet is lost to process death, there is **no** plaintext-recovery path — proper
  recovery is another admin password reset. The `UserDetailViewModel` /
  `CreateUserViewModel` tests prove a fresh ViewModel instance never starts with a
  credential visible (the only carried-over state is the two plain constructor arguments).

**`FLAG_SECURE`**: not implemented. Applying it cleanly to only the credential screen
requires window-flag management around the single dialog; scoping that safely against the
existing navigation was judged out of proportion for Phase 8 and is recorded here as a known
limitation (§N) rather than done half-way.

---

## H. Future modules — honest placeholders only

| Bottom / hub entry | State in Phase 8 |
|---|---|
| **Statisztika** (all roles) | "A statisztikai összesítések ebben a verzióban még nem érhetők el." No fake charts, counts, leaderboard or phase badge. |
| **Moderáció** hub | Live "Felhasználók" entry + "Saját fiók". "A további moderációs funkciók ebben a verzióban még nem érhetők el." No fake deleted reports, no delete/restore, no "Phase 9" label. |
| **Adminisztráció** hub | Live "Felhasználók" + "Saját fiók". "Szolgálati területek" and "Változási előzmények" shown as "Még nem elérhető" future entries — normal product language, no phase labels. |

No moderation backend, ServiceArea administration, analytics, real statistics or audit
query/UI was implemented. Phases 9-12 were not started.

---

## I. Visual system

Dark navy background, lighter-navy cards, gold accent reserved for primary actions and
highlights (not full-screen decoration), white primary text, muted blue-grey secondary text,
green success, restrained red danger, rounded cards, a bottom nav with a clear selected
state. Adaptive sizing (no hard-coded mockup pixels); scalable text; 48 dp targets;
content descriptions on icon-only controls; status conveyed by label + colour, never colour
alone; `AlertDialog`-based confirmations. Times are formatted for display only from backend
timestamps (`hu-HU` locale, `FormatStyle.SHORT`, raw-string fallback); the device clock is
never used for `ageBucket`, authorization, transition decisions or archive ordering.

**Accessibility checks performed**: font-scale 0.85 → 1.3 (layouts reflow, no clipping on
the report card / detail / user list); TalkBack sweep of Login, Bejelentések, report detail,
user list and the credential dialog (every actionable control is focusable and announced);
small-width (`sw320dp`) and large-phone check of the two-button workflow row and the
create-user form.

---

## J. Production-copy review (brief §105)

Searched every user-visible string (`res/values/strings.xml`) and every hard-coded
user-facing literal in `service-app/src/main` for: `PHASE`, `backend`, `workflowVersion`,
`archivedAt`, `server-side scope`, dev notes, stable error-code names, demo/role switchers,
fake/demo credentials, architecture explanations.

**Fixed:**

| Before | After | Why |
|---|---|---|
| `Assignment történet` / `Külön a biztonsági eseménynaplótól.` | `Ügyintézési előzmények` / `A bejelentés eddigi menete.` | copied from the mockup's own `class="muted"` dev annotation; mixed-language, references an internal audit system |
| `AssignmentHistoryCopy` end-reason fallback `"$endedBy · ${endReason}"` | `"$endedBy lezárta az ügyintézést"` | a latent path that would render a raw `report_assignments.end_reason` code if the backend ever adds a fourth reason (today all three — RETURNED/REASSIGNED/ARCHIVED — are handled explicitly) |

**Deliberately retained** (approved-mockup vocabulary, consistent across the whole app, not
dev notes — flagged for the owner in §M):

- Role identifiers shown as fixed tags/labels: `SERVICE_USER` / `MODERATOR` / `SUPER_ADMIN`,
  the `SERVICE_USER · ACTIVE · <area>` list rows, `Szerepkör: MODERATOR` buttons, and the
  create-user role segmented-button. The mockup uses these verbatim throughout its real
  (non-dev-note) screens.
- The reassign dialog's instruction "Csak ACTIVE SERVICE_USER választható, aki jelenleg
  jogosult a bejelentés szolgálati területére." — verbatim from the mockup's functional
  reassign modal (a plain `<p>`, not a muted annotation).

No `PHASE X`, no `workflowVersion`, no `archivedAt`, no `server-side scope`, no stable
error-code names, no demo credentials, no role switcher appears in any user-visible string.
KDoc comments and test code that contain technical terms are out of scope for this check.

---

## K. Session-scoped state fix (found during live verification)

Live testing surfaced a real Phase 8 bug: the three queue `ReportQueueViewModel`s were
hoisted in `ServiceNavHost` via the default (Activity-scoped) `ViewModelStoreOwner`, so they
**survived a logout**. A different user signing in on the same device saw the previous
user's queue until a manual refresh (reproduced: a `SUPER_ADMIN` who had a Déli-area report
in NEW logged out; the next `SERVICE_USER`, Északi-only, briefly saw that Déli report).

**Fix**: `ServiceNavHost` now creates a `ViewModelStore` scoped to the signed-in session and
clears it in `DisposableEffect { onDispose { … } }` — that composable exists only while
`AuthState.Authenticated`, so its disposal *is* the logout boundary. The queue ViewModels
are created against that owner; on the next login a fresh `ServiceNavHost` gets a fresh
store, so `init { refresh() }` loads the correct data.

**Trade-off**: the three queues now reload from the backend on a configuration change
(rotation) instead of surviving it. Everything nav-scoped (report/user detail, create-user)
still survives rotation. Given brief §44 ("refresh on screen entry") this is acceptable and
strictly better than showing another user's data.

Verified live: after the fix, the same logout → login-as-different-user sequence shows the
correct (empty) NEW queue immediately, with no stale card.

---

## L. Tests — every command, count, result

### Backend (unchanged on this branch beyond the merged PR #11)

```
cd backend && ./gradlew clean build
```
**BUILD SUCCESSFUL** — **529 tests, 0 failures, 0 errors** (524 baseline + 5 from PR #11's
`KotlinJsonDefaultsRegressionIT`).

### Android — unit (`testDebugUnitTest`)

```
cd android && ./gradlew :service-app:testDebugUnitTest :public-app:testDebugUnitTest
```

| module | tests | failures |
|---|---|---|
| `service-app` | **81** | 0 |
| `public-app` | 36 | 0 |

`service-app` unit coverage (Phase 8 additions in **bold**):
`BottomNavigationTest` (5), **`WorkflowActionAvailabilityTest`** (6), **`ErrorCopyTest`** (4),
**`ReportModelsTest`** (4), **`AssignmentHistoryCopyTest`** (4 — includes the raw-code
fallback guard), **`ReportQueueViewModelTest`** (4), **`UsersListViewModelTest`** (4),
**`ReportDetailViewModelTest`** (4), **`UserDetailViewModelTest`** (4 — §98/§99),
**`CreateUserViewModelTest`** (4 — §99), **`CreateUserRequestWireFormatTest`** (3 — pins
that `globalAreaAccess = false` is *omitted* from the wire, the client half of the PR #11
contract), plus the retained Phase 2 `auth`/`common` JVM tests.

### Android — instrumented + Compose (`connectedDebugAndroidTest`)

```
cd android && ./gradlew :service-app:connectedDebugAndroidTest
```
Device: **`orszem-test` AVD — Android 15, API 35, x86_64**.

| suite | tests | failures | brief |
|---|---|---|---|
| `AuthFlowInstrumentedTest` | 7 | 0 | §73 process death, logout (incl. offline), revoked session, session restore |
| `KeystoreTokenStoreInstrumentedTest` | 10 | 0 | §11 AndroidKeyStore AES-GCM — re-run unmodified, unweakened (§102) |
| `LoginComposeTest` | 2 | 0 | §95 — Service ID + password + one action; **no demo picker / role selector** |
| `ReportWorkflowConflictComposeTest` | 1 | 0 | §96 CRITICAL — a stale `REPORT_STATE_CHANGED` close is sent **once**, current state re-fetched, human message shown, raw code never shown |
| `CredentialDialogComposeTest` | 1 | 0 | §99 — credential visible while open, gone from the UI after "Elmentettem" |

**21 instrumented tests, 0 failures.** CI compiles both instrumented suites
(`assembleDebugAndroidTest`); they are run on an emulator (here) per the existing
`.github/workflows/android.yml` policy.

### Android — lint & builds

```
./gradlew :public-app:assembleDebug :service-app:assembleDebug \
          :public-app:assembleRelease \
          :service-app:assembleDebugAndroidTest :public-app:assembleDebugAndroidTest lint
```
**BUILD SUCCESSFUL** — **lint: 0 issues** (both modules). No existing test was weakened or
removed.

### Web

```
cd web/public-web && npm ci && npm run typecheck && npm run test && npm run build
```
typecheck OK — **55 tests, 0 failures** — build OK.

### Reference data

```
node reference-data/tools/validate-canonical.mjs reference-data/example/manifest.json
```
`canonical reference dataset is valid.`

### Deploy-config

`caddy validate` → *Valid configuration*; `caddy fmt` diff clean;
`verify-caddy-routing.sh` / `verify-caddy-header-redaction.sh` / `verify-caddy-csp.sh` all
pass; no secret-shaped file under `deploy/`.

---

## M. Emulator verification & screenshots (brief §100-103)

Verified against a **throwaway local backend** built from the merged branch state
(`f3cc60b` code) — Docker Postgres `orszem-p8-pg` (port 5436), `bootRun` on 8080, reference
data + a `SUPER_ADMIN` / `MODERATOR` / `SERVICE_USER` hierarchy + reports in NEW / IN_PROGRESS
/ ARCHIVED states, all seeded through the real HTTP API, the maintenance CLI and `psql`.
No fake runtime data ships in the app; every screen below shows real backend responses.

Device: **`orszem-test` AVD — Android 15 / API 35 / x86_64**.

Flows exercised on device: login (no demo picker); forced initial password change (new
`SERVICE_USER`); session restore after process restart; SERVICE_USER NEW → claim →
IN_PROGRESS → detail with Visszaadás/Lezárás; Archívum; Statisztika placeholder; Profil;
MODERATOR hub → Felhasználók → peer-MODERATOR read-only detail; MODERATOR IN_PROGRESS detail
→ Átrendelés/Visszaadás/Lezárás; reassign dialog with search; SUPER_ADMIN Admin hub →
Felhasználók → managed-user detail with role/global controls → Create User → one-time
credential dialog; active-assignment guard (deactivate a user with open assignments) → CTA →
pre-filtered IN_PROGRESS; logout with no privileged screen left in the back stack.

Screenshot set prepared for owner review (delivered with the review, not committed as
binary blobs — matching the Phase 5 precedent):

| # | Screen | file |
|---|---|---|
| 1 | Login | `SHOT_01_login.png` |
| 2 | Forced initial password change | `SHOT_02_forced_pw_change.png` |
| 3 | SERVICE_USER — Új queue | `SHOT_03_su_new_queue.png` |
| 4 | SERVICE_USER — report detail, NEW / IN_PROGRESS | `SHOT_04a_su_report_detail_new.png`, `SHOT_04_su_in_progress_detail.png` |
| 5 | Archívum | `SHOT_05_archive.png` |
| 6 | Statisztika placeholder | `SHOT_06_stats.png` |
| 7 | SERVICE_USER Profil | `SHOT_07_profile.png` |
| 8 | MODERATOR hub | `SHOT_08_mod_hub.png` |
| 9 | MODERATOR IN_PROGRESS supervisor actions | `SHOT_09_mod_supervisor_actions.png` |
| 10 | Reassign dialog | `SHOT_10_reassign_dialog.png` |
| 11 | User list | `SHOT_11_userlist.png` |
| 12 | Manageable SERVICE_USER detail (MODERATOR view) | `SHOT_12_mod_manageable_user.png` |
| 13 | Peer MODERATOR read-only detail | `SHOT_13_peer_mod_readonly.png` |
| 14 | Active-assignment guard + CTA + pre-filtered nav | `SHOT_14a_deactivate_confirm.png`, `SHOT_14_guard.png`, `SHOT_14b_guard_cta_nav.png` |
| 15 | SUPER_ADMIN Admin hub | `SHOT_15_su_adminhub.png` |
| 16 | SUPER_ADMIN user detail with role/global controls | `SHOT_16_su_userdetail.png` |
| 17 | Create User | `SHOT_17_createuser.png` |
| 18 | One-time temporary credential display | `SHOT_18_credential.png` |
| + | SUPER_ADMIN own-account (hub → Saját fiók) | `SHOT_19_su_own_account.png` |

### Visual deviations from the approved HTML mockup — for the owner to accept or reject

1. **Login copy** — shipped "Bejelentkezés" / "Szolgálati azonosítóval és jelszóval." vs the
   brief's suggested "Belépés" / "Szolgálati belépés". This is existing Phase 2 copy;
   changing it means touching the Phase 2 auth screen.
2. **Role/status identifiers as literal tags** — `SERVICE_USER`, `MODERATOR`, `SUPER_ADMIN`,
   `ACTIVE` appear as fixed labels in list rows, badges, the role select and the
   `Szerepkör: X` buttons, and inside the reassign dialog's instruction sentence. This
   matches the approved mockup verbatim and is consistent everywhere, but it does read as
   raw enum text in an otherwise fully-Hungarian UI. If the owner prefers fully localized
   role/status wording ("Szolgálati munkatárs", "Aktív", …), that is a single deliberate
   vocabulary change to make across the app.
3. **`ageBucket` divider / statuses** — the mockup's "168h" and raw status words are
   replaced by "Régebbi bejelentések" and ÚJ / FOLYAMATBAN / LEZÁRVA / BESOROLATLAN, per
   brief §17/§83 (intended, not a regression).
4. **Filters** — the mockup implies category/event-type/settlement filters; Phase 8 ships
   server-side free-text search + (for privileged roles) an area filter. The full catalog-
   backed filter builder is deferred — see §N.

**OWNER VISUAL APPROVAL: PENDING.** Not recorded as given. This phase stops here.

---

## N. Regression / CI — all 5 workflows

Local results are in §L. On push, the branch runs all five existing workflows unchanged
(`.github/workflows/{backend,android,web,deploy-config,reference-data}.yml`). The
`android.yml` workflow already compiles the instrumented suites and does not run them on a
device — the new Compose tests follow that same policy and were run here on the emulator
instead.

| workflow | result on `c262245` |
|---|---|
| backend | *(filled in after push)* |
| android | |
| web | |
| deploy-config | |
| reference-data | |

No CI step was removed or weakened. `android.yml` gained no new required step — the Compose
tests compile inside the existing `assembleDebugAndroidTest` step.

---

## O. Known limitations — not hidden

1. **"Aktív munkanézet" (active work-view) for `SERVICE_USER` — not built (brief §13-14 STOP
   condition).** No existing API lets an authenticated `SERVICE_USER` discover their own
   assigned ServiceAreas or global-access flag: `GET /account/me` returns only
   `{serviceId, role}`, and `GET /service/user-management/users/{id}` (the only endpoint
   that lists a user's areas) rejects a `SERVICE_USER` caller before any use case runs.
   Rather than fake the area list or misuse a privileged endpoint, the local area
   preference was **not implemented** for `SERVICE_USER`. `MODERATOR`/`SUPER_ADMIN` legitimately
   reuse `GET /service/user-management/areas` elsewhere (reassign picker, create-user).
   **Smallest backend fix**: extend `GET /account/me` to optionally include
   `globalAreaAccess` + `areas` for a `SERVICE_USER` caller. Deferred to a future phase; not
   a Phase 8 blocker because the feature is a local convenience, not authorization.
2. **Catalog-backed filters deferred (brief §41-42).** Server-side free-text `query` and an
   area filter (privileged roles) ship now. The category / event-type / settlement / line
   filter UI would require wiring the Public reference/catalog APIs as a second large
   sub-feature; recorded here rather than silently omitted.
3. **`FLAG_SECURE` on the credential screen not implemented (brief §92).** See §G — scoped
   window-flag management around the one dialog was judged out of proportion; the credential
   is already non-persistent, non-logged and backup-excluded.
4. **`§98` active-assignment guard is covered by a ViewModel unit test + live emulator
   verification, not a full-navigation Compose test.** The guard's assertions (state
   unchanged, natural error, CTA present, no retry) are ViewModel-level; the CTA's
   pre-filtered navigation was verified live (SHOT 14b). A Compose test of the whole
   `ServiceNavHost` nav graph would need a real `AuthViewModel` (concrete `AuthRepository`,
   Retrofit, Keystore) and was judged low marginal value over the two existing checks.
5. **Queue state does not survive rotation** — see §K. Deliberate trade-off for correct
   cross-session isolation.
6. **Login copy deviates from the brief's suggested wording** — see §D / §M.1. Phase 2 copy,
   not rewritten.
7. **Compose UI tests use the v1 `createComposeRule`** (deprecation warning, not an error).
   Migration to `…junit4.v2` changes the test dispatcher semantics and was left for a
   focused follow-up; the v1 API is fully functional and the tests pass.

Nothing above is a security regression, a fake data path or a user-facing implementation
leak.
