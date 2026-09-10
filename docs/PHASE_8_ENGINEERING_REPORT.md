# Phase 8 engineering report — Service Android full operational UI

Phase 8 builds the actual Service Android application on top of the existing Phase 2
authentication, Phase 6 user-management backend and Phase 7 report-workflow backend. It is
an **Android client phase**. The `jackson-module-kotlin` correctness fix (PR #11) was
shipped on its own branch and merged to `main` before Phase 8 resumed — see §A and §N.

A **post-implementation correction pass** followed the first review round (see §P for the
full list). It made three functional additions on top of the pure-client scope:

- **one small additive backend change** — `GET /account/me` now also returns the caller's
  **own** service-area scope (`globalAreaAccess` + assigned areas), nothing else (§Q);
- the **"Aktív munkanézet"** local area lens for `SERVICE_USER` (§E), now that the client
  can discover its own areas without a management endpoint;
- the **catalogue-backed report filter sheet** (§E) — category / event type / settlement /
  area, every value from the Public reference/catalogue APIs, nothing hard-coded.

Plus presentation-only changes: role/status enums are now localised through one central
mapping (§J), and a second sweep of production copy (§J).

> **Owner visual approval: PENDING — NOT GIVEN.** The representative screenshots in §M are
> prepared for review. This phase stops here for the owner to approve the final appearance.
> No Phase 8 PR has been opened. No merge. No Phase 9 work.

---

## A. Git

| | |
|---|---|
| Branch | `feature/v2-service-android-ui` (created off `main`; never rebased, no history rewrite) |
| `main` at phase start | `eaa741e` — *Merge pull request #10 from …/feature/v2-service-report-workflow* |
| Backend hotfix merged in | `f3cc60b` — *Merge pull request #11 from …/fix/backend-kotlin-json-defaults* (contains `fff5539`) |
| `main` the branch is built on | `f3cc60b` (fast-forward merge of `origin/main` into the branch; the Phase 8 working tree was untouched) |
| Phase 8 commit | `39ee6d3` — *feat(service-android): Phase 8 — full operational Service UI* |
| CI bookkeeping commits | `8602b18`, `d501f74`, `64f735d` — docs only (engineering-report SHA / CI-run references) |
| Correction-pass commit | `d05b592` — *fix(service-android): Phase 8 correction pass — localisation, filters, active work view* (§P) |
| **Two small owner-requested UI corrections (code + strings + this report)** | **`4eeb1fb`** — *fix(service-android): user-list manageability label layout + create-user helper copy* (§P) |
| Final branch HEAD | a docs-only child of `4eeb1fb` recording the §N CI run IDs — no code/config/test/workflow change, so its five workflow results are identical to those on `4eeb1fb` |

**The SHA the §N CI results are measured against is `4eeb1fb`** — every non-docs file on
the branch is at that commit (it is `d05b592` plus the two UI corrections). The final HEAD
is a docs-only child that records those run IDs.

The backend hotfix (`jackson-module-kotlin`) was developed in a **separate git worktree** on
branch `fix/backend-kotlin-json-defaults`, reviewed, merged as PR #11, and only then merged
into the Phase 8 branch. The Phase 8 Android work was never discarded or rewritten.

No PR opened. Nothing merged. No Phase 9 work.

---

## B. Android architecture

One Gradle build under `android/`, two apps. Phase 8 client changes are confined to
`service-app` plus two shared version-catalog entries. The correction pass additionally made
one additive backend change (§Q) — a single field-group on `GET /account/me`, no new
endpoint, no contract removed or renamed.

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
  `{serviceId, role, globalAreaAccess, areas}` from `/account/me` (§Q) and lands on the
  correct role UI; otherwise the login screen. `role` still drives navigation exactly as
  before; `globalAreaAccess` / `areas` only feed the local area lens and filter chips (§E).
  An authorized screen is never shown from stale local role data.
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

### Report filter sheet (brief §41-42) — added in the correction pass

A compact `ModalBottomSheet` opened from a funnel icon in the queue's search row (a
`BadgedBox` badge shows the active facet count). It offers four server-side facets on top of
the retained free-text `query`:

| facet | source of truth | wire param |
|---|---|---|
| service area | the caller's **own** areas from `GET /account/me` (§Q); shown only when that list is non-empty | `areaId` |
| category | `GET /api/v1/public/report-catalog` | `categoryCode` |
| event type | the selected category's `eventTypes` from the same catalogue response | `eventTypeCode` |
| settlement | debounced `GET /api/v1/public/reference/settlements?query=` (min 2 chars) | `settlementId` |

Nothing is hard-coded — category / event-type / settlement values are always the Public
reference/catalogue truth, so the Service app can never disagree with what a Public reporter
saw. Selecting a category clears any stale event-type selection (event types belong to a
category). "Alkalmaz" calls back one `ReportFilter`; the queue `ViewModel.updateFilter`
re-requests from page 0 with every facet, so paging state always resets on a filter change.
"Szűrők törlése" clears the sheet's facets and leaves the free-text `query` and the
`assigneeServiceId` CTA filter untouched. All three queues (Új / Folyamatban / Archívum)
carry the same sheet.

This is not an advanced query builder — no boolean logic, no saved queries, no unbounded
local data dump (the settlement list is a bounded server search, the area list is the
caller's own small set). It is `ReportFilterSheet` + `CatalogRepository` + wiring only; the
Phase 7 queue endpoints already accepted every one of these params.

### "Aktív munkanézet" — local UI preference only (brief §13-14)

A per-`serviceId` `SharedPreferences` value (`SharedPrefsActiveWorkAreaStore`, keyed
`"area::$serviceId"`) holding one `areaId?` (or nothing = "all my areas"). It is **not
authorization**: it is applied *only* as the `areaId` query param on the three queues, so it
can only ever narrow what the actor already legitimately sees; the backend scope stays
authoritative. Shown as a FilterChip row on the `SERVICE_USER` **Profil** screen (Minden
terület + the caller's own areas from §Q); `MODERATOR`/`SUPER_ADMIN` do not get this
selector (their own-account route leaves the params at their defaults, hiding the section).

Recovery: on session start a stored area that is no longer in the caller's `ownAreas` (and
who is not global) is dropped back to "all areas" while seeding; the backend independently
self-heals (an out-of-scope `areaId` simply returns nothing). A different user signing in on
the same device never inherits the previous user's lens — verified live (§K, §M).

---

## F. User-management UI

Phase 6's backend becomes usable; entry lives inside the Moderáció / Adminisztráció hubs.

- **User list** — `GET …/users`. Service-ID search, real backend rows: service ID, role,
  status, area summaries / global access, and the `canManage` hint. Role and status are
  shown through the localised presentation mapping (§J): a row reads
  "Szolgálati munkatárs · Aktív · Északi terület", never "SERVICE_USER · ACTIVE · …". No
  names, emails or phones (they do not exist in this product model). The `canManage` hint
  ("Kezelhető" / "Csak megtekinthető") sits on **its own bottom row** inside each user card,
  so a long summary can never squeeze it into a vertically-wrapped column. Load-more
  pagination (backend page 0 / size 50); no unbounded list; no duplicates across pages;
  clean reset on refresh.
- **`canManage` presentation** — a UI hint only, never authorization truth. A peer
  `MODERATOR` is shown as "Csak megtekinthető" (visible, read-only); a `SUPER_ADMIN` row is
  "Csak megtekinthető" from another SUPER_ADMIN's view. The literal `canManage=false` /
  "READ-ONLY" strings are never shown.
- **Managed-user detail** — service ID, role, status, a "Kötelező jelszóváltás" indicator
  where set, global access, assigned ServiceAreas, and the actions the actor may take.
  Never the internal UUID, password hash, tokens, sessions or audit rows.
- **Create user** — SUPER_ADMIN may create a `SERVICE_USER` or a `MODERATOR`; the segmented
  button shows the localised labels ("Szolgálati munkatárs" / "Moderátor") but the client
  still sends the **unchanged backend enum value** (`SERVICE_USER` / `MODERATOR`). A
  MODERATOR sees "Szolgálati munkatárs" only. Area checkboxes; a distinct "Minden terület"
  global-access checkbox with the hint "Hozzáférés minden szolgálati területhez." The
  footer rules paragraph reads *"Egy moderátor csak a saját jogosultsági körében hozhat
  létre szolgálati munkatársat, legalább egy területtel. A főadminisztrátor szolgálati
  munkatársat vagy moderátort hozhat létre."* (finalised vocabulary — no "Rendszergazda",
  no "szolgálati felhasználó"). Service ID, the temporary credential, status and
  `mustChangePassword` are all server-generated; the client sends only `role` / `areaIds` /
  `globalAreaAccess`.
- **Capability matrix (matches the backend response, not re-implemented as client security)**

  | action | `MODERATOR` (manageable target) | `SUPER_ADMIN` |
  |---|---|---|
  | grant / revoke ServiceArea | ✔ | ✔ |
  | temporary-credential issue ("Ideiglenes jelszó kiadása") | ✔ | ✔ |
  | deactivate / reactivate | ✔ | ✔ |
  | change role ("Szerepkör: Moderátor" / "Szerepkör: Szolgálati munkatárs") | ✘ (not shown) | ✔ (confirmation required) |
  | grant / revoke global access ("Minden terület jogosultság megadása") | ✘ (not shown) | ✔ |

  Verified live: the MODERATOR view of a manageable `SERVICE_USER` shows exactly the first
  three; the SUPER_ADMIN view of the same user shows all five (§M, SHOT 14 vs SHOT 18).
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

Verified live in the correction pass: SHOT 16a (deactivate confirm), SHOT 16 (guard + human
copy + CTA, user still "Aktív"), SHOT 16b (the CTA lands on Folyamatban pre-filtered by that
user's `assigneeServiceId`).

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

## J. Production-copy review (brief §105) & role/status localisation

Searched every user-visible string (`res/values/strings.xml`) and every hard-coded
user-facing literal in `service-app/src/main` for: `PHASE`, `backend`, `workflowVersion`,
`archivedAt`, `server-side scope`, dev notes, stable error-code names, demo/role switchers,
fake/demo credentials, architecture explanations.

### Centralised role/status presentation mapping (correction pass)

`common/ui/RoleStatusLabels.kt` — one place that turns a backend enum value into a localised
label resource:

| backend value | label | backend value | label |
|---|---|---|---|
| `SERVICE_USER` | Szolgálati munkatárs | `ACTIVE` | Aktív |
| `MODERATOR` | Moderátor | `DEACTIVATED` | Deaktiválva |
| `SUPER_ADMIN` | Főadminisztrátor | *(unknown)* | safe non-leaking fallback |

`roleLabelRes(role)` / `userStatusLabelRes(status)` are pure and unit-tested, including that
an unrecognised future enum value still renders a real localised label, never the raw
string. **Presentation only** — no backend or API enum name changed; the Create-User role
selector shows the localised label but sends the unchanged `SERVICE_USER` / `MODERATOR`
value. Applied at every site the raw enum used to surface: user list rows, managed-user
detail header, create-user segmented button, the account/profile "Szerepkör:" line.

### Copy fixes

| Before | After | Why |
|---|---|---|
| `Assignment történet` / `Külön a biztonsági eseménynaplótól.` | `Ügyintézési előzmények` / `A bejelentés eddigi menete.` | copied from the mockup's own `class="muted"` dev annotation; mixed-language, references an internal audit system |
| `AssignmentHistoryCopy` end-reason fallback `"$endedBy · ${endReason}"` | `"$endedBy lezárta az ügyintézést"` | a latent path that would render a raw `report_assignments.end_reason` code if the backend ever adds a fourth reason (today all three — RETURNED/REASSIGNED/ARCHIVED — are handled explicitly) |
| `Jelszó reset` | `Ideiglenes jelszó kiadása` | "reset" is an English dev term; the action issues a one-time credential |
| global-access hint `Külön globális jogosultság, nem szolgálati terület.` | `Hozzáférés minden szolgálati területhez.` | states what the checkbox does, plainly |
| reassign subtitle `Csak ACTIVE SERVICE_USER választható, aki jelenleg jogosult a bejelentés szolgálati területére.` | `Csak aktív szolgálati munkatárs választható, aki jogosult a bejelentés szolgálati területére.` | raw enum names in an otherwise Hungarian sentence |
| list rows `SERVICE_USER · ACTIVE · <area>` etc. | `Szolgálati munkatárs · Aktív · <area>` | via the central mapping above |
| user-list manageability label `KEZELHETŐ` | `Kezelhető` | all-caps shouted in an otherwise sentence-case UI; now pairs with "Csak megtekinthető" |
| create-user helper `… Rendszergazda szolgálati felhasználót vagy moderátort hozhat létre.` | `Egy moderátor csak a saját jogosultsági körében hozhat létre szolgálati munkatársat, legalább egy területtel. A főadminisztrátor szolgálati munkatársat vagy moderátort hozhat létre.` | "Rendszergazda" / "szolgálati felhasználó" are not the finalised vocabulary |

**Deliberately unchanged** (existing Phase 2 copy, already approved by the owner):

- **Login**: "Bejelentkezés" / "Szolgálati azonosítóval és jelszóval." — not rewritten to
  match any earlier suggested wording.

No `PHASE X`, no `workflowVersion`, no `archivedAt`, no `server-side scope`, no stable
error-code names, no raw role/status enum, no demo credentials, no role switcher appears in
any user-visible string. KDoc comments and test code that contain technical terms are out of
scope for this check.

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
store, so `init { refresh() }` loads the correct data. **This fix is preserved unchanged**
through the correction pass, and the queue ViewModels still seed from a session-fresh
`ReportFilter` (the "Aktív munkanézet" area, §E) so the lens is per-session too.

**Re-tested in the correction pass**: User A (`SZ-596961`, Északi+Déli, reports in every
state) → queue visible → in-app logout → User B (`SZ-841277`, Déli only) login. B's Új queue
shows *only* the one Déli report immediately, with no flash of A's Északi reports and no
inherited filter badge (§M). The area lens, keyed by `serviceId`, is likewise not inherited.

**Trade-off**: the three queues now reload from the backend on a configuration change
(rotation) instead of surviving it. Everything nav-scoped (report/user detail, create-user)
still survives rotation. Given brief §44 ("refresh on screen entry") this is acceptable and
strictly better than showing another user's data.

Verified live: after the fix, the same logout → login-as-different-user sequence shows the
correct (empty) NEW queue immediately, with no stale card.

---

## L. Tests — every command, count, result

All figures below are **after** the correction pass, on the final branch HEAD.

### Backend — `./gradlew clean build`

```
cd backend && ./gradlew clean build
```
**BUILD SUCCESSFUL** — **533 tests, 0 failures, 0 errors, 0 skipped**
(529 previous total + 4 new `OwnAccountScopeIT` cases; the existing
`AuthenticationFlowIT` `/me` test was rewritten in place, not added).

Focused tests for the `GET /account/me` extension (§Q):

| test | asserts |
|---|---|
| `AuthenticationFlowIT` › *"me returns the service id, role and the caller's own area scope - nothing more"* | body key set is exactly `{serviceId, role, globalAreaAccess, areas}`; `!globalAreaAccess`, empty `areas` for a bare account |
| `OwnAccountScopeIT` › SERVICE_USER own areas + flag, exact field set | a `SERVICE_USER` sees its own assigned areas and nothing about any other user |
| `OwnAccountScopeIT` › inactive assigned area still listed with `INACTIVE` status | an area's activation state is surfaced, not filtered |
| `OwnAccountScopeIT` › globally-scoped `MODERATOR` → `globalAreaAccess = true` | the global flag is read from real scope |
| `OwnAccountScopeIT` › scope read from current state (just-granted area appears same token) | no stale cache |

### Android — unit (`testDebugUnitTest`)

```
cd android && ./gradlew :service-app:testDebugUnitTest :public-app:testDebugUnitTest
```

| module | tests | failures |
|---|---|---|
| `service-app` | **90** | 0 |
| `public-app` | 36 | 0 |

`service-app` unit coverage — correction-pass additions in **bold**:
`BottomNavigationTest` (5), `WorkflowActionAvailabilityTest` (6), `ErrorCopyTest` (4),
`ReportModelsTest` (4), `AssignmentHistoryCopyTest` (4 — raw-code fallback guard),
`ReportQueueViewModelTest` (4), `UsersListViewModelTest` (4), `ReportDetailViewModelTest`
(4), `UserDetailViewModelTest` (4), `CreateUserViewModelTest` (4),
`CreateUserRequestWireFormatTest` (3),
**`RoleStatusLabelsTest`** (3 — every role/status maps to its own label; an unknown value
never falls through to a raw string),
**`ActiveWorkAreaStoreTest`** (3 — per-`serviceId` store, a different user starts with no
lens, clearing to null removes it),
**`ReportFilterTest`** (3 — `activeFacetCount` counts only the explicit server-side facets,
`updateFilter` always re-requests from page 0 with every facet, a seeded `initialFilter` is
used for the first load),
plus the retained Phase 2 `auth`/`common` JVM tests.

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

**21 instrumented tests, 0 failures** (re-run on the emulator after the correction pass; no
instrumented test was modified — the new screen params all have defaults, so the existing
call sites compile and behave unchanged). CI compiles both instrumented suites
(`assembleDebugAndroidTest`); they are run on an emulator (here) per the existing
`.github/workflows/android.yml` policy.

### Android — lint & builds

```
./gradlew :public-app:assembleDebug :service-app:assembleDebug \
          :public-app:assembleRelease \
          :service-app:assembleDebugAndroidTest :public-app:assembleDebugAndroidTest lint
```
**BUILD SUCCESSFUL** — `:public-app:assembleRelease` builds the unsigned release APK
(minification, resource shrinking, strict release network-security config) end to end.
**lint: `service-app` "No issues found"**; `public-app` reports only the pre-existing
`GradleDependency` version-nag warnings (unchanged by this phase), `abortOnError = true` and
the build is green. No existing test was weakened or removed.

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

Verified against a **throwaway local backend** running the **final branch HEAD** code
(includes the `GET /account/me` extension) — Docker Postgres `orszem-p8-pg` (port 5436),
the built `backend.jar` on 8080. Seeded entirely through the real HTTP API + `psql` for
config-only rows (`service_areas` / `service_area_railway_lines`, which Phase 10 would own):
two areas (Északi ← line 900, Déli ← line 901), one `SUPER_ADMIN`, one territorial and one
global `MODERATOR`, three `SERVICE_USER`s with area grants, and **Public reports submitted
through `POST /api/v1/public/reports`** routed by settlement → railway line → area, then
claimed/closed through the real workflow API to populate NEW / IN_PROGRESS / ARCHIVED.
Two reports had their `submitted_at` back-dated in the throwaway DB so the NEW queue shows a
real `OLDER` bucket and the "Régebbi bejelentések" divider. No fake runtime data ships in
the app; every screen below is a real backend response.

Device: **`orszem-test` AVD — Android 15 / API 35 / x86_64**.

Flows re-exercised on device in the correction pass: login; forced initial password change
(a freshly-created `SERVICE_USER`); SERVICE_USER NEW (with the OLDER divider) → detail →
claim → IN_PROGRESS detail with Visszaadás/Lezárás; **report filter sheet** (area / category
→ event types / settlement search) → apply → badge + narrowed queue; **"Aktív munkanézet"**
selector → narrowed queue; Archívum; Statisztika placeholder; Profil; MODERATOR hub →
Felhasználók (localised role/status rows) → manageable-user detail / peer-MODERATOR
read-only; MODERATOR IN_PROGRESS detail → Átrendelés/Visszaadás/Lezárás; reassign dialog
(new localised subtitle); SUPER_ADMIN Admin hub → managed-user detail with role/global
controls → Create User (localised segmented button) → one-time credential dialog;
active-assignment guard → CTA → pre-filtered IN_PROGRESS; **session isolation**
(A login → logout → B login, no data leak); **`REPORT_ALREADY_ASSIGNED`** conflict (human
copy, no auto-retry).

Screenshot set for owner review — recaptured wherever the visible UI changed; delivered with
the review, not committed as binary blobs (Phase 5 precedent). New this pass: **#5** and
**#6**.

| # | Screen | file(s) | changed this pass? |
|---|---|---|---|
| 1 | Login | `SHOT_01_login.png` | no (Phase 2 copy) |
| 2 | Forced initial password change | `SHOT_02_forced_pw_change.png` | no |
| 3 | SERVICE_USER — Új queue (OLDER divider + filter icon) | `SHOT_03_su_new_queue.png` | **yes** — funnel/badge added |
| 4 | SERVICE_USER — report detail, NEW / IN_PROGRESS | `SHOT_04a_su_report_detail_new.png`, `SHOT_04_su_in_progress_detail.png` | no |
| **5** | **Report filter sheet** (area / category / event type / settlement search) + applied badge | `SHOT_05_report_filters.png`, `SHOT_05b_report_filters_settlement_search.png`, `SHOT_05c_filters_applied.png` | **new** |
| **6** | **"Aktív munkanézet"** area selector on Profil | `SHOT_06_active_work_view.png` | **new** |
| 7 | Archívum | `SHOT_07_archive.png` | no |
| 8 | Statisztika placeholder | `SHOT_08_stats.png` | no |
| 9 | SERVICE_USER Profil (localised "Szerepkör: Szolgálati munkatárs" + §6 section) | `SHOT_09_profile_full.png` | **yes** |
| 10 | MODERATOR hub | `SHOT_10_mod_hub.png` | no |
| 11 | MODERATOR IN_PROGRESS supervisor actions | `SHOT_11_mod_supervisor_actions.png` | no |
| 12 | Reassign dialog (localised subtitle, no raw enums) | `SHOT_12_reassign_dialog.png` | **yes** |
| 13 | User list (localised role · status · area rows; manageability label on its own bottom row) | `SHOT_13_userlist.png` | **yes** — recaptured again in round 2 for the label layout |
| 14 | Manageable SERVICE_USER detail — MODERATOR view (localised, "Ideiglenes jelszó kiadása") | `SHOT_14_mod_manageable_user.png` | **yes** |
| 15 | Peer MODERATOR read-only detail (localised) | `SHOT_15_peer_mod_readonly.png` | **yes** |
| 16 | Active-assignment guard + CTA + pre-filtered nav | `SHOT_16a_deactivate_confirm.png`, `SHOT_16_guard.png`, `SHOT_16b_guard_cta_nav.png` | no |
| 17 | SUPER_ADMIN Admin hub | `SHOT_17_su_adminhub.png` | no |
| 18 | SUPER_ADMIN user detail — role/global controls (localised) | `SHOT_18_su_userdetail.png` | **yes** |
| 19 | Create User (localised segmented button + "Hozzáférés minden szolgálati területhez." + finalised footer copy) | `SHOT_19_createuser.png` | **yes** — recaptured again in round 2 for the helper copy |
| 20 | One-time temporary credential display | `SHOT_20_credential.png` | no |
| 21 | SUPER_ADMIN own-account ("Szerepkör: Főadminisztrátor") | `SHOT_21_su_own_account.png` | **yes** |

### Visual deviations from the approved HTML mockup — for the owner to accept or reject

1. **Login copy** — shipped "Bejelentkezés" / "Szolgálati azonosítóval és jelszóval." This
   is existing Phase 2 copy, approved by the owner, and was **not** rewritten.
2. **`ageBucket` divider / statuses** — the mockup's "168h" and raw status words are shown as
   "Régebbi bejelentések" and ÚJ / FOLYAMATBAN / LEZÁRVA / BESOROLATLAN, per brief §17/§83
   (intended, not a regression).

**Previously flagged, now resolved:**

- *Role/status raw enum tags* → all localised through one central mapping (§J). `SERVICE_USER`
  / `MODERATOR` / `SUPER_ADMIN` / `ACTIVE` no longer appear anywhere in the UI.
- *Catalog-backed filters deferred* → the full category / event-type / settlement / area
  filter sheet now ships (§E), every value from the Public reference/catalogue APIs.
- *User-list manageability label wrapping* → the "Kezelhető" / "Csak megtekinthető" label
  now has its **own bottom row** inside the user card (`labelMedium`, colour-coded), so a
  long role/status/area summary never squeezes it into a vertically-wrapped column.
  Verified at font-scale 1.3 and at ~sw288dp (below the sw320dp spec check): the summary
  reflows word-by-word and the label always stays on its own horizontal line. The `Card`
  layout is otherwise unchanged. `label_manageable` was also normalised from all-caps
  "KEZELHETŐ" to "Kezelhető" so it pairs with "Csak megtekinthető".
- *Create-user helper copy* → "Rendszergazda" / "szolgálati felhasználó" replaced with the
  finalised vocabulary: *"Egy moderátor csak a saját jogosultsági körében hozhat létre
  szolgálati munkatársat, legalább egy területtel. A főadminisztrátor szolgálati munkatársat
  vagy moderátort hozhat létre."*

**OWNER VISUAL APPROVAL: PENDING.** Not recorded as given. This phase stops here.

---

## N. Regression / CI — all 5 workflows

Local results are in §L. On push, the branch runs all five existing workflows unchanged
(`.github/workflows/{backend,android,web,deploy-config,reference-data}.yml`). The
`android.yml` workflow already compiles the instrumented suites and does not run them on a
device — the Compose tests follow that same policy and were run here on the emulator instead.

All five workflows ran on **`4eeb1fb`** (the two UI corrections on top of the
correction pass) and are **green** (run URLs: `https://github.com/kplevi05/Orszem/actions/runs/<id>`):

| workflow | run id (on `4eeb1fb`) | result |
|---|---|---|
| backend | 34523622659 | ✅ success |
| android | 34523622811 | ✅ success |
| web | 34523622644 | ✅ success |
| deploy-config | 34523622559 | ✅ success |
| reference-data | 34523622631 | ✅ success |

The final branch HEAD is a docs-only child of `4eeb1fb` (it adds exactly this table of
run IDs). It changes no code, config, test or workflow file, so re-running the five
workflows on it produces the identical five green results.

Earlier green run set on the correction-pass commit `d05b592` (retained for the record):
backend [34518887314], android [34518887712], web [34518887230],
deploy-config [34518887269], reference-data [34518887369].

Superseded green runs (docs-only commit `8602b18`):
backend [34505090807], android [34505090815], web [34505090803],
deploy-config [34505090846], reference-data [34505090786].

No CI step was removed or weakened. `android.yml` gained no new required step — the Compose
tests compile inside the existing `assembleDebugAndroidTest` step. The backend workflow's
`./gradlew build` now runs the 4 new `OwnAccountScopeIT` cases and the rewritten
`AuthenticationFlowIT` `/me` test with no config change.

---

## O. Known limitations — not hidden

1. **`FLAG_SECURE` on the credential screen not implemented (brief §92, optional).** See §G —
   scoped window-flag management around the one dialog was judged out of proportion; the
   credential is already non-persistent, non-logged and backup-excluded. Not a blocker.
2. **Active-assignment guard is covered by a ViewModel unit test + live emulator
   verification, not a full-navigation Compose test.** The guard's assertions (state
   unchanged, natural error, CTA present, no retry) are ViewModel-level; the CTA's
   pre-filtered navigation was verified live (SHOT 16b). A Compose test of the whole
   `ServiceNavHost` nav graph would need a real `AuthViewModel` (concrete `AuthRepository`,
   Retrofit, Keystore) and was judged low marginal value over the two existing checks.
3. **Queue state does not survive rotation** — see §K. Deliberate trade-off for correct
   cross-session isolation.
4. **Compose UI tests use the v1 `createComposeRule`** (deprecation warning, not an error).
   Migration to `…junit4.v2` changes the test dispatcher semantics and was left for a
   focused follow-up; the v1 API is fully functional and the tests pass.
5. **Test-environment only: an idle OkHttp connection pool can surface a transient
   "A kiszolgáló nem érhető el." on the next request.** Seen on the throwaway emulator after
   the local backend was restarted mid-session or after a long idle: the first
   filter/queue request fails on a stale pooled connection, and the app correctly shows its
   standard *retryable* network error (never a crash, never a blind retry, never stale data).
   A fresh app start or the in-app "Újrapróbálkozás" recovers it. This is an artifact of the
   restart-heavy local test loop, not a code defect — the app's contract on a transport
   failure ("keep state, explicit retry") is exactly what it should be.

**Resolved by the correction pass** (previously listed here):

- *"Aktív munkanézet" not built (brief §13-14 STOP condition)* — the smallest additive
  backend change (`GET /account/me` self-scope, §Q) was approved and made; the local area
  lens now ships for `SERVICE_USER` (§E), strictly as a local preference.
- *Catalog-backed filters deferred (brief §41-42)* — the full filter sheet now ships (§E).

Nothing above is a security regression, a fake data path or a user-facing implementation
leak.

---

## P. Correction pass — what changed after the first review round

The owner withheld visual approval and asked for a completion/correction pass before
re-review. The overall dark-navy/gold direction stayed approved; no redesign. Items:

1. **Localise raw role/status enums** through one central presentation mapping (§J). Backend
   / API enum names unchanged; Create-User selector shows localised labels, sends raw values.
2. **Second production-copy sweep** (§J): "Jelszó reset" → "Ideiglenes jelszó kiadása";
   global-access hint → "Hozzáférés minden szolgálati területhez."; reassign subtitle
   de-enumed. Login left unchanged (approved).
3. **"Aktív munkanézet"** (§E) — resolved the brief §13-14 STOP condition with the smallest
   additive backend change (§Q). Local UI preference only.
4. **Report filter sheet** (§E) — catalogue/reference-backed category / event type /
   settlement / area filters; nothing hard-coded.
5. **Final SHA / CI reconciliation** (§A, §N) — one unambiguous HEAD, all 5 workflows green
   on it.
6. **FLAG_SECURE** — remains an accepted known limitation (§O.1); not forced in.
7. **Session-isolation fix preserved** and re-tested (§K).
8. **Conflict UX reconfirmed** — `REPORT_ALREADY_ASSIGNED` re-verified live (§M); the other
   two remain covered by the unchanged ViewModel + Compose tests (§L).
9. **No later-phase work** — Statisztika / Moderáció / Adminisztráció placeholders unchanged;
   no fake statistics / moderation / ServiceArea / audit data; no "PHASE X" strings.
10. **Full regression re-run** (§L) — backend clean build, Android unit + instrumented +
    lint + all builds, web, reference-data, deploy-config. No existing test weakened.
11. **Screenshots recaptured** for every changed screen; owner set renumbered, +#5 +#6 (§M).
12. **This report updated**; owner visual approval kept **PENDING**.

### Second round — two small owner-requested UI corrections (commit `4eeb1fb`)

The owner approved the correction pass and the visual direction, and asked for exactly two
production-UI fixes before final approval — no other redesign:

- **User-list manageability label layout** — "Kezelhető" / "Csak megtekinthető" moved to its
  own bottom row inside the user card (was competing for width with the summary and could
  wrap character-by-character into a narrow column). `labelMedium`, colour-coded, `Card`
  otherwise unchanged. Re-verified at font-scale 1.3 and ~sw288dp. Label also normalised
  from "KEZELHETŐ" to "Kezelhető".
- **Create-user helper copy** — replaced with the finalised vocabulary (no "Rendszergazda",
  no "szolgálati felhasználó"): see §F / §J.

Affected checks re-run: `service-app` unit **90** / 0, `public-app` unit 36 / 0, both
`assembleDebug` + `assembleDebugAndroidTest`, `:public-app:assembleRelease`, **lint
`service-app` "No issues found"**. No backend change; no test weakened. `SHOT_13` and
`SHOT_19` recaptured. All 5 CI workflows green on `4eeb1fb` (§N).

Not done (out of scope, per the brief): opening the Phase 8 PR, merging, Phase 9.

---

## Q. The one backend change — `GET /api/v1/service/account/me`

Investigated first: the Phase 2 DTO was `MeResponse(serviceId, role)`, built in
`ServiceAccountController.me()` from the `AuthenticatedActor` alone, asserted by
`AuthenticationFlowIT`. Extending it is clean and additive — it does not touch auth,
sessions, or any other endpoint.

**Change:**

- `MeResponse` gains `globalAreaAccess: Boolean` and `areas: List<MeServiceAreaResponse>`
  where `MeServiceAreaResponse(id, name, status)`. Existing fields unmoved.
- New `GetOwnAccountUseCase` (auth/application) → `scopeOf(userId)` returns the caller's
  `UserRole`, `globalAreaAccess`, and assigned `ServiceArea`s, reading
  `JdbcServiceAreaRepository.loadAreaActor()` + a new `assignedAreas(userId)` query
  (`user_service_areas` ⋈ `service_areas`, ordered by name). A user with no area actor row
  degrades to `SERVICE_USER / false / []`.
- `ServiceAccountController.me()` maps that into the response; `Cache-Control: no-store`
  unchanged.

**Deliberately not exposed:** any other user, `canManage`, any management permission, audit
data, internal UUIDs beyond the caller's own area ids, or anything unrelated to the caller's
own scope. It is self-account information — the same trust level as already returning the
caller's own `role`.

**Compatibility:** additive only. Older clients ignore the new fields; the Android client
declares them with defaults (`globalAreaAccess = false`, `areas = emptyList()`), so a
response without them still deserialises. No migration. Tests: §L.

The Active Work View and the filter sheet's area chips are the only consumers; both use the
data strictly as a local narrowing hint, never as an authorisation claim (§E).
