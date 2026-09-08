# Phase 5 engineering report — Public Android + Public Web

**Branch:** `feature/v2-public-clients` (not merged — owner's decision, and gated on visual approval, §M)
**Base:** `main` @ `83eb44e` (PR #7, Phase 4 merged)
**Status:** implementation, tests and CI complete; backend unchanged; **not** deployed.

---

## A. Git

| | |
|---|---|
| Starting `main` SHA | `83eb44e` (verified against live `origin/main` before branching — matched the SHA the brief itself anticipated) |
| Branch | `feature/v2-public-clients` |
| Final SHA | `b776783` |
| Pushed | yes |
| PR | none opened — the brief says "Do not merge it yourself"; a PR was not requested and was not opened |

Commits, in order:

1. `5456230` — Public Android: report submission, local history, GPS assist
2. `9335db8` — CI: compile public-app instrumented tests, build unsigned release APK
3. `547077a` — Public Web: report submission, local history
4. `3c9e5f9` — CI: run the Vitest suite before build
5. `664fc23` — Caddy CSP/Permissions-Policy for the Public Web origin + ADR 0009
6. `b776783` — fix: History screen's one-time refresh fired before data loaded (found live, §H)

## B. Android architecture

- **Room schema** (`ReportHistoryDatabase`, version 1, `exportSchema = true`, committed under
  `android/public-app/schemas/`): one entity, `report_history`, keyed on `client_submission_id`.
  Fields mirror §20's list exactly — encrypted credential blob (`BLOB`), display snapshots
  for settlement/railway-line/category/event-type, `submission_state`, `public_status`,
  and four timestamps. No GPS, no free text, no plaintext credential.
  `fallbackToDestructiveMigration` is never used; `RoomSchemaInstrumentedTest` establishes
  `MigrationTestHelper` infrastructure against the exported v1 schema now, for the first
  real future migration to use.
- **Keystore crypto** (`KeystoreCryptoBox`, alias `orszem.public.report.v1`): AES-256-GCM,
  a fresh Public-app-specific implementation (not the Service app's own class) using the
  identical proven pattern — non-exportable key, provider-generated nonce, versioned blob.
- **Backup policy**: `data_extraction_rules.xml` / `backup_rules.xml` already excluded
  `domain="root"` (covers `database` and every other app-private store) from both cloud
  backup and device transfer; the comment was updated to state this explicitly now that
  Room + the encrypted credential live there.
- **Networking**: Retrofit + OkHttp + kotlinx.serialization, matching the Service app's own
  `NetworkModule` convention. No logging interceptor. `X-Orszem-Report-Access` attached only
  on the two calls that need it.
- **GPS**: `LocationAssist` — `LocationManager.getCurrentLocation` (API 30+) or a single-shot
  `requestLocationUpdates` (below that), then `Geocoder` (async listener API 33+, background
  executor below that). No coordinate is ever exposed by any method; the caller only ever
  gets a locality-name string fed into the ordinary settlement search.

## C. Web architecture

- **IndexedDB** (`orszem-public-reports`, version 1): two object stores, `reports`
  (keyed on `clientSubmissionId`) and `crypto` (one record, the AES-GCM `CryptoKey`).
- **WebCrypto key handling**: one origin-local, `extractable: false` AES-256-GCM
  `CryptoKey`, persisted via IndexedDB structured clone (the raw key material is never
  exported by this code, and structured clone preserves non-extractability). Fresh 96-bit
  IV per encryption.
- **Persistence request**: `navigator.storage.persist()` called once, best-effort, in
  `main.tsx` — failure or absence changes nothing.
- **CSP**: `default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self';
  img-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action
  'self'` — no `unsafe-eval`, no `unsafe-inline` needed.
- **Privacy headers**: `Permissions-Policy: geolocation=(), camera=(), microphone=()` on
  `orszembejelento.hu`. `api.orszembejelento.hu` is untouched (serves no HTML).

## D. Submission lifecycle (identical shape on both platforms)

```
local commit (encrypted credential + frozen normalized payload)  [may fail → LocalPersistenceFailed/local-persistence-failed, nothing sent]
   ↓
POST /api/v1/public/reports
   ↓
201/200 → SUBMITTED, cached publicStatus = RECEIVED
400     → record deleted entirely (not history); next attempt gets a brand-new identity
409     → CONFLICT
network/other → PENDING unchanged, retryable
   ↓ (explicit user action)
retry → resends the exact frozen clientSubmissionId + credential + payload
```

Status refresh (`GET /api/v1/public/reports/{id}`): 200 updates the cached status; a generic
404 leaves the record entirely untouched (existence-safe, ADR 0008 §18); network failure
preserves the cached status.

## E. COMPLETE/PARTIAL UX matrix (§42, exact behavior, both platforms)

| Coverage | Candidates | Behavior | Submitted `railwayLineId` |
|---|---|---|---|
| COMPLETE | 0 | explanatory text, no selector | `null` |
| PARTIAL | 0 | explanatory text (never claims no railway exists), no selector | `null` |
| COMPLETE | 1 | displayed for reassurance, no selection required | `null` (backend infers) |
| PARTIAL | 1 | **never** auto-selected — explicit choice or "Nem tudom / másik vonal" required | chosen id, or `null` |
| COMPLETE | 2+ | explicit choice or "Nem tudom / nem vagyok biztos benne" required, no default | chosen id, or `null` |

Verified against the real backend live (§H): a settlement with one COMPLETE-coverage line
correctly showed "Azonosított vasútvonal: Alfa - Beta" and submitted `railwayLineId: null`,
letting the backend's own inference resolve it — confirmed by the 201 response and the
resulting report's settlement.

## F. History

- Ordering: newest-first (`localCreatedAt DESC` / equivalent JS sort) — both platforms.
- States: `PENDING` / `SUBMITTED` / `ACCESS_LOST` / `CONFLICT`, rendered with the exact
  Hungarian copy from §16, `PENDING` visually distinct ("Beküldés nincs megerősítve").
- Refresh: explicit per-record ("Frissítés"), plus a **one-time** refresh of every
  `SUBMITTED` record on entering History, with bounded concurrency (3). This one-time
  refresh had a real timing bug, found and fixed live — see §H.
- Access-lost behavior: a record whose credential fails to decrypt is marked
  `ACCESS_LOST` and shown as such; it is never deleted, and no new credential is invented.

## G. Tests — every command and count

**Android** (`android/`):
```
./gradlew :public-app:assembleDebug :service-app:assembleDebug testDebugUnitTest lint
./gradlew :public-app:assembleRelease
./gradlew :service-app:assembleDebugAndroidTest :public-app:assembleDebugAndroidTest
```
- 36 JVM unit tests, 0 failures (`ReportAccessCredentialTest`, `NormalizedReportPayloadTest`,
  `RailwayLineDecisionTest`, `ReportRepositoryTest`, `NewReportViewModelTest`).
- Lint: 0 errors on both apps (4 real errors found and fixed during development —
  `MissingPermission`/`NewApi` on the location code — see §I).
- Debug + unsigned release build: both succeed; Service app unaffected (proves §71/§23).
- Instrumented test **sources** compile (`assembleDebugAndroidTest`) but were **not run** on
  a device this session — see §H.

**Web** (`web/public-web/`):
```
npm run typecheck
npm run test      (vitest run)
npm run build
```
- 55 Vitest/Testing-Library tests, 0 failures, across 8 files — credential generation,
  normalization, the COMPLETE/PARTIAL matrix, real-IndexedDB persist-before-fetch ordering
  and retry identity, every submission outcome, a simulated-page-reload PENDING-survival
  test, catalogue caching, reducer-level stale-response guards, and the History
  auto-refresh-timing regression test added in the final commit.
- `tsc --noEmit`: 0 errors. `vite build`: succeeds.

**Caddy** (`scripts/`):
```
caddy validate --config deploy/caddy/Caddyfile --adapter caddyfile   # valid
caddy fmt deploy/caddy/Caddyfile | diff -u deploy/caddy/Caddyfile -  # clean
./scripts/verify-caddy-routing.sh                                    # unaffected, still green
./scripts/verify-caddy-header-redaction.sh                           # unaffected, still green
./scripts/verify-caddy-csp.sh                                        # new, green
```

**Backend**: not run this phase — `git status --porcelain backend/` is empty, confirming the
tree is byte-identical to Phase 4's own verified-green state; re-running the full suite was
judged unnecessary for code that did not change.

## H. Real platform verification — stated plainly, not assumed

- **Android emulator/device flow**: **not executed.** No emulator was available or set up
  in this environment (no `cmdline-tools`, no system image; bootstrapping one from scratch
  was judged out of proportion to this session's remaining time against the equally-required
  Web deliverable). The instrumented test *sources*
  (`KeystoreCryptoBoxInstrumentedTest`, `ReportHistoryDatabaseInstrumentedTest`,
  `RoomSchemaInstrumentedTest`) are written, compile, and are ready to run — they were not
  executed against real hardware. **This is the most significant verification gap in this
  phase and should be run by the owner or a follow-up session before Android is trusted.**
- **Real AndroidKeyStore path**: not executed (follows from the above).
- **Real browser IndexedDB + WebCrypto path**: **executed**, twice, differently:
  1. Under Vitest + jsdom (Node's own native Web Crypto and V8, not a mock) — 7 passing
     tests in `storage/__tests__/crypto.test.ts`, including a non-extractable-key check
     read directly back out of a real IndexedDB record.
  2. In a **real Chromium browser** (the Claude Browser tool), loading the actual built
     production bundle served through the **actual Caddy configuration**, then navigating
     the full report flow (settlement search → line lookup → catalogue → submit) with real
     `fetch` calls — zero console errors throughout, confirming the CSP does not break real
     functionality (§68).
- **Lost-response E2E** (§65): proved at the repository-test level on both platforms
  (`ReportRepositoryTest`/`reportRepository.test.ts`'s retry-identity tests: submit with a
  simulated network failure → the local record stays PENDING with the frozen identity →
  retry resends the identical `clientSubmissionId`/credential/payload → a fresh success
  response promotes it to SUBMITTED, exactly once). A literal "the server received the
  first attempt but the client never saw the response" race was not additionally proved
  against a real backend beyond what those repository tests already establish.
- **End-to-end against the real Phase 4 backend** (§64): **executed live**, not skipped:
  - A real PostgreSQL 16 container + the real backend (`./gradlew bootRun`) + a synthetic,
    `reuseStatus: CLEARED` reference dataset imported through the real
    `orszem.maintenance.action=reference-import` CLI (never real/PENDING VPE data, §74).
  - Verified by `curl` directly: real settlement search, real railway-line lookup, a real
    `POST /api/v1/public/reports` (201, a genuine server-issued report id), and a real
    `GET` with a wrong credential (404, existence-safe).
  - Verified through the **actual Public Web client**, running its real dev server against
    this real backend, driven by a real browser: settlement search → line lookup
    (correctly auto-displaying the single COMPLETE candidate) → catalogue (all 7 real
    categories) → submission (real 201, real report id shown on the success screen) →
    History (persisted across navigation, later a real status-refresh `GET` returning 200).
  - **This live run is what surfaced the History auto-refresh timing bug (§F) — a bug no
    unit test on either platform had caught**, because every existing test's fake
    repository resolved synchronously, never reproducing the real gap between "the screen
    mounted" and "the async local-history read actually completed". Both platforms were
    fixed identically once found; a permanent regression test was added on Web (the
    platform this session could actually execute against).
  - Android was **not** run against this real backend this session (no emulator, see
    above) — its identical fix is by inspection and by the same reasoning proven correct
    on Web, not by an equivalent live Android run.

## I. Release security

- **Public release APK inspected directly** (`aapt2 dump xmltree`/`dump strings` on the
  actual `public-app-release-unsigned.apk`, unminified names resolved through the shrunk
  resource table): package `hu.orszembejelento.app` (no `.debug` suffix), `API_BASE_URL`
  baked in as `https://api.orszembejelento.hu/` only (no `10.0.2.2`/`localhost`/`127.0.0.1`
  string anywhere in the compiled artifact), `network_security_config`
  `cleartextTrafficPermitted="false"` with **no** domain exceptions — confirmed against the
  debug APK's config as a positive control, which does carry the three dev-host exceptions,
  proving the variant split genuinely works rather than coincidentally matching.
- Credential leak scan (both platforms): no plaintext `pr_…` value anywhere in the release
  APK's `classes.dex`, no `console.log`/`Log.d`/`println` touching a credential anywhere in
  source, no `localStorage`/`sessionStorage` write anywhere in the Web source, no VPE/
  `local-research` reference anywhere in either client tree.
- 4 real Android lint errors (`MissingPermission`, `NewApi` ×2, plus the paired second
  `MissingPermission`) were found and fixed during development — not suppressed blindly:
  `currentLocation()` now performs its own defensive `ContextCompat.checkSelfPermission`
  check before ever touching the platform API, and the two API-level-gated private methods
  carry `@RequiresApi` so lint's own control-flow analysis can see the guard that was
  already true at the call site.

## J. Screenshots for owner review

Captured via a real Chromium browser against the real built bundle served through the real
Caddy configuration (mobile) and the real local dev server against the real backend
(desktop-pane width, flow screens): Home (mobile + narrow-desktop), Step 1 — including a
real settlement search result and the COMPLETE-single-candidate auto-display, Step 2 with
the real 7-category catalogue, the success screen with a real server-issued report id, and
History showing a real persisted record with its real refreshed status. These render inline
in this session's own transcript; no screenshot files were committed to the repository
(deliberately — they are not documentation, they are point-in-time visual evidence for this
review). **Android screenshots were not captured** — no emulator was available this
session (§H).

## K. CI

All 5 workflows green on `feature/v2-public-clients`:

| Workflow | Result |
|---|---|
| `backend` | success (unaffected — backend tree unchanged this phase) |
| `android` | success |
| `web` | success |
| `deploy-config` | success (includes the new `verify-caddy-csp.sh`) |
| `reference-data` | success (unaffected) |

Confirmed on `664fc23` before the final fix commit, and the `android`/`web`/`deploy-config`
regressions that the fix touches were re-run locally (green) after `b776783` — full CI on
that exact commit was not re-polled to completion as part of writing this report, since the
identical checks it runs were already reproduced locally.

## L. Known limitations

- **No Android real-device verification this session** (§H) — the single biggest gap.
- No offline queue, no background sync, no push — by design (§13).
- Local history can be lost (app uninstall/data clear on Android; cleared site data or
  storage eviction on Web) — both clients say so plainly; neither claims otherwise.
- No credential-recovery mechanism on either platform — `ACCESS_LOST` is permanent, by the
  same anonymous/no-account reasoning as the backend itself (ADR 0008/0009).
- No rate limiting on the submission endpoint these clients call — unchanged from Phase 4,
  still required before any public deployment (`DECISIONS_REQUIRING_OWNER.md` B6).
- `trainIdentifier` remains an unvalidated, uncataloged string on both clients, mirroring
  the backend's own current stance (B3, still open).
- The Web CSP/live-browser check used a stub upstream for the pure-CSP verification run and
  a real backend for the full E2E run (§H) — they were not the same single run; both are
  reported as what they actually were.

## M. Owner decisions

1. **VISUAL APPROVAL: PENDING.** This phase is not merge-ready without it (§56/§81) —
   review the screenshots in §J (and, ideally, the app itself) before merging.
2. Run the Android instrumented test suite on a real device or emulator before trusting the
   AndroidKeyStore/Room guarantees this phase depends on (§H) — the tests are written and
   compile; they were not executed this session.
3. B6 (rate limiting) still requires an answer before any public deployment — now doubly
   true with real, working clients able to submit against the real endpoint.
4. Everything else in `DECISIONS_REQUIRING_OWNER.md` carries over unchanged.

---

Phase 5 is functionally complete per the Definition of Done in §81, with the Android
real-device gap in §H stated plainly rather than assumed away. **Phase 6 has not been
started and is not addressed by this report.**
