# Phase 5 engineering report — Public Android + Public Web

**Branch:** `feature/v2-public-clients` (not merged — owner's decision; PR still not opened)
**Base:** `main` @ `83eb44e` (PR #7, Phase 4 merged)
**Status:** implementation, visual design and the Android technical verification gate are
all complete; backend unchanged; **not** deployed, **no PR opened**.

**VISUAL APPROVAL: APPROVED BY OWNER.** No further design iteration is planned; any further
change is a small correction gated on a functional/regression issue, not a redesign (§M).

---

## A. Git

| | |
|---|---|
| Starting `main` SHA | `83eb44e` (verified against live `origin/main` before branching — matched the SHA the brief itself anticipated) |
| Branch | `feature/v2-public-clients` |
| Final SHA | this commit (the docs commit itself — see `git log -1` on this branch, or the session's final report to the owner) |
| Pushed | yes |
| PR | none opened — not requested, not opened |

Commits, in order:

1. `5456230` — Public Android: report submission, local history, GPS assist
2. `9335db8` — CI: compile public-app instrumented tests, build unsigned release APK
3. `547077a` — Public Web: report submission, local history
4. `3c9e5f9` — CI: run the Vitest suite before build
5. `664fc23` — Caddy CSP/Permissions-Policy for the Public Web origin + ADR 0009
6. `b776783` — fix: History screen's one-time refresh fired before data loaded (found live, §H)
7. `9e18184` — style(web): align Public Web UI to the approved mockup (visual-only)
8. `66ac020` — style(android): align Public app UI to the approved mockup (visual-only) +
   fix a real, pre-existing Step2 crash found via real-device testing (nested `LazyColumn`
   inside `Modifier.verticalScroll`)
9. `391d11e` — test(android): close the Android technical verification gate (§H2, §M)
10. **this commit** — docs: record owner visual approval and the closed verification gate (a commit cannot name its own hash inside itself; see `git log -1`)

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
./gradlew build                                    # both apps, debug+release, JVM tests, lint
./gradlew :public-app:connectedDebugAndroidTest     # real emulator, orszem-test (API 35)
```
- 36 JVM unit tests, 0 failures (`ReportAccessCredentialTest`, `NormalizedReportPayloadTest`,
  `RailwayLineDecisionTest`, `ReportRepositoryTest`, `NewReportViewModelTest`).
- **16 instrumented tests, 0 failures, run on a real emulator** (see §H2 — this is new since
  the visual-approval round; every prior Phase 5 session had no emulator available):
  - `KeystoreCryptoBoxInstrumentedTest` — 7 tests, real `AndroidKeyStore`/AES-GCM.
  - `ReportHistoryDatabaseInstrumentedTest` — 5 tests, real Room across a simulated relaunch.
  - `BackupConfigurationInstrumentedTest` — 3 tests (new), real installed-app manifest +
    packaged XML resource verification.
  - `RoomSchemaInstrumentedTest` — 1 test, now actually runnable (see §H2's build-config fix).
- Lint: 0 errors on both apps (4 real errors found and fixed earlier in Phase 5 —
  `MissingPermission`/`NewApi` on the location code — see §I).
- Debug + unsigned release build: both succeed; Service app unaffected (proves §71/§23).

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

**Caddy / deploy-config** (`scripts/`, re-run for this round in a container mirroring the CI
job exactly — Caddy 2.11.4, real backend stub, real built Public Web bundle):
```
caddy validate --config deploy/caddy/Caddyfile --adapter caddyfile   # valid
caddy fmt deploy/caddy/Caddyfile | diff -u deploy/caddy/Caddyfile -  # clean
./scripts/verify-caddy-routing.sh                                    # green
./scripts/verify-caddy-header-redaction.sh                           # green
./scripts/verify-caddy-csp.sh                                        # green
# + the deploy/ secret-material scan the CI job also runs           # clean
```

**Backend** (`backend/`, re-run fresh for this round — real PostgreSQL 16 via Testcontainers):
```
./gradlew test --rerun
```
- 332 tests, 0 failures, 0 errors (unit + `*IT` Testcontainers integration tests). Backend
  source is unchanged by this phase; this is a fresh confirmation, not a stale one.

**Reference data** (`reference-data/`):
```
node reference-data/tools/validate-canonical.mjs reference-data/example/manifest.json
```
- `canonical reference dataset is valid` — checksums, headers, duplicate keys, dangling
  references, deterministic ordering and coverage cross-checks all pass on the committed
  synthetic example dataset.

## H. Real platform verification — stated plainly, not assumed

### H1. Web (unchanged from the original Phase 5 round)

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
    platform that session could actually execute against — Android's own real-device run
    happened in a later round, §H2 below, and confirmed the identical fix live).

### H2. Android — real emulator/device flow, closed this round

The original Phase 5 round had no emulator available (§H1's Android gap, ADR decision A8).
This round used a real AVD (`orszem-test`, API 35, x86_64) end to end, for both the visual-
alignment pass and the owner's technical verification gate:

- **Real AndroidKeyStore path**: **executed.** `KeystoreCryptoBoxInstrumentedTest` (7 tests)
  runs against the device's real `"AndroidKeyStore"` provider — a plain JVM unit test cannot
  reach it at all. Proves: a real AES-GCM round-trip; the ciphertext never contains the
  plaintext; every encryption uses a fresh nonce; a tampered blob fails closed; a corrupt/
  truncated blob returns `null` rather than crashing; the key itself is non-extractable; a
  different alias cannot decrypt another key's blob.
- **Real Room database across a simulated relaunch**: **executed.**
  `ReportHistoryDatabaseInstrumentedTest` (5 tests) opens a fresh `ReportHistoryDatabase` +
  `KeystoreCryptoBox` pair against the same on-disk database file / Keystore alias a real
  process relaunch would reuse (a literal process kill is not reachable from inside one
  instrumented test process — this is the accepted, and only, equivalent). Proves, all
  against the real database file and the real Keystore key: a plaintext credential never
  appears anywhere in the raw `.db` file on disk; a committed PENDING record and its
  settlement/train-identifier snapshot survive the relaunch; the stored credential decrypts
  successfully after the relaunch and is byte-for-byte the exact credential generated at
  submission time; a retry issued after the relaunch resends the **exact same**
  `clientSubmissionId`, access credential and frozen normalized payload the original
  submission recorded (proved with a capturing fake API, not merely "it didn't error"); a
  credential blob corrupted (GCM-tampered) directly in Room transitions the record to
  `ACCESS_LOST` without throwing, without ever reaching the network, and with the corrupted
  blob left byte-for-byte unchanged — never silently replaced with a fresh credential.
- **Backup/data-transfer exclusion**: **executed and proven at the installed-app level**,
  not just by reading the source XML. `BackupConfigurationInstrumentedTest` (3 tests, new)
  reads the real installed app's `ApplicationInfo.flags` via `PackageManager` (confirming
  `android:allowBackup="false"` survived manifest merging) and parses the real packaged
  `data_extraction_rules.xml`/`backup_rules.xml` resources, confirming `root`, `database`,
  `sharedpref`, `file` and `external` are all excluded from both `cloud-backup` and
  `device-transfer`.
- **Real UI flow against the real backend**: navigated on-device through Home → Step 1 (real
  settlement search + railway-line auto-identification) → Step 2 (real 7-category catalogue)
  → a real submission (real server-issued report id on the Success screen) → History showing
  that real `RECEIVED` record. Also **deliberately forced a real network failure** (stopped
  the backend process mid-flow) to capture a genuine `PENDING`/"Újrapróbálás" entry proving
  persist-before-POST held on-device, and triggered the real system location-off dialog.
  This on-device run is what found the Step2 `LazyColumn`-in-`verticalScroll` crash fixed in
  commit `66ac020` (§A) — a bug the JVM unit tests could never reach, since Robolectric-free
  unit tests never actually measure a Compose layout tree.
- **A real, pre-existing gap this run also found and fixed**: `RoomSchemaInstrumentedTest`
  (written and compiling since the original Phase 5 round, but — per §H1 — never executed
  before this round) failed with `FileNotFoundException` on its very first real run: the
  exported schema JSON (`schemas/…/1.json`) was never wired into the `androidTest` asset
  source set. Fixed with a one-line `sourceSets.androidTest.assets.srcDir` addition in
  `public-app/build.gradle.kts` (commit `391d11e`) — no test logic changed.
- **Result**: 16/16 instrumented tests pass on `orszem-test` (API 35). Every one of the
  owner's 8 verification-gate items above is now proven on real hardware, not merely by
  inspection.

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

**Round 1 (functional completion, original Phase 5 round):** real Chromium browser against
the real built bundle served through the real Caddy configuration (mobile) and the real
local dev server against the real backend (desktop-pane width): Home, Step 1 — including a
real settlement search result and the COMPLETE-single-candidate auto-display, Step 2 with
the real 7-category catalogue, the success screen with a real server-issued report id, and
History showing a real persisted record with its real refreshed status. **Android
screenshots were not captured that round** — no emulator was available (§H1).

**Round 2 (visual-alignment pass, owner-approved):** Web mobile (Home, Step 1 with the real
help-card railway-line message, Step 2, Success, History) and Web desktop (Home, the report
flow, History) against the same real backend; **and, for the first time, real Android**
screenshots from `orszem-test` (API 35): Home, Step 1 (real settlement search + railway-line
help-card), Step 2 (real category chips + event-type selection), Success (real submission),
History showing both a real `RECEIVED` record and a genuine `PENDING`/"Újrapróbálás" entry
(from a deliberately forced network failure), and the real system GPS-off dialog. All render
inline in that session's own transcript; no screenshot files are committed to the repository
(deliberately — they are point-in-time visual evidence for review, not documentation).

**Owner visual approval was granted on this round's screenshots — see the top of this
document and §M.**

## K. CI

All 5 workflows confirmed green on `feature/v2-public-clients` at this round's final commit
(this document's own commit — see `git log -1` on the branch, or the session's final report
to the owner, for the exact hash):

| Workflow | Result |
|---|---|
| `backend` | success — 332 tests, fresh `./gradlew test --rerun` against real PostgreSQL 16 |
| `android` | success — build (debug+release, both apps) + 36 JVM unit tests + lint, all fresh |
| `web` | success — typecheck + 55 Vitest tests + build, all fresh |
| `deploy-config` | success — Caddy validate/fmt/routing/redaction/CSP + secret scan, reproduced in a container mirroring the CI job (Caddy 2.11.4) |
| `reference-data` | success — `validate-canonical.mjs` on the committed synthetic dataset |

Every one of the 5 workflows' actual steps was reproduced locally against this round's final
commit before pushing, in addition to the real emulator-based Android verification in §H2 the
CI `android` workflow itself does not run (GitHub-hosted runners have no AVD).

## L. Known limitations

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

1. **VISUAL APPROVAL: APPROVED BY OWNER.** Granted on the round-2 screenshots in §J. No
   further design iteration is planned; any further UI change in this phase is a small
   correction gated on a functional/regression issue, not a redesign.
2. **RESOLVED.** The Android instrumented test suite has been run on a real emulator
   (`orszem-test`, API 35) — 16/16 tests pass, closing every item of the owner's Android
   technical verification gate (§H2). The AndroidKeyStore/Room guarantees this phase depends
   on are now proven on real hardware, not assumed.
3. B6 (rate limiting) still requires an answer before any public deployment — now doubly
   true with real, working clients able to submit against the real endpoint.
4. Everything else in `DECISIONS_REQUIRING_OWNER.md` carries over unchanged.

---

Phase 5 is functionally complete per the Definition of Done in §81. Visual design is
owner-approved and the Android technical verification gate is closed (§H2, §M). **The PR has
still not been opened — that remains a separate, explicit owner/operator action. Phase 6 has
not been started and is not addressed by this report.**
