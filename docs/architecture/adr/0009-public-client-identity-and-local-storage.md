# ADR 0009 — Public client identity, local storage and idempotency ordering

**Status:** accepted · 2026-09-08

## Context

Phase 5 gives the anonymous, client-generated report-access model from ADR 0008 its first
real clients: Public Android and Public Web. Both are anonymous - no account, no login, no
server-side device identity - so everything that lets a citizen retry a lost submission or
later check on their report has to live entirely on the client, survive a crash or a page
reload, and never leak the one secret that guards it.

## Decision 1 — local commit strictly before the first network attempt

Both clients generate `clientSubmissionId` and the report access credential, normalize the
draft into the exact frozen payload the backend will compare a replay against, and durably
persist all three **before** the first `POST` is even attempted. If that local commit fails
- encryption fails, the database transaction fails, IndexedDB is unavailable - the report is
never sent. There is no path in either client that calls the network first and persists
second.

This is what makes the specific failure this design exists to prevent unreachable: a
response lost after the server already committed the report can never turn into "the client
has no way to prove or retry its own submission." Retrying always resends the exact
persisted identity, never a freshly reconstructed one from mutable UI state - proved by
`ReportRepositoryTest`/`reportRepository.test.ts`'s ordering and retry-identity tests on
both platforms.

## Decision 2 — credential storage is per-report, encrypted, never plaintext

Both clients generate the 256-bit access credential the same way the backend expects it
(ADR 0008): 32 bytes from a CSPRNG, `pr_` + unpadded URL-safe base64. Neither client stores
it in the clear.

- **Android**: `KeystoreCryptoBox` (AES-256-GCM, a non-exportable `AndroidKeyStore` key,
  fresh IV per encryption) - the same proven pattern as the Service app's own crypto
  (Phase 2), deliberately re-implemented rather than shared or imported (§23 of the brief):
  Service authentication code is not touched for this. The encrypted blob is one column in
  a Room row, one row per report - not a single shared secret store, because each report has
  its own independent credential (ADR 0008).
- **Web**: a single origin-local AES-256-GCM `CryptoKey`, generated `extractable: false`,
  persisted in IndexedDB via structured clone (the platform's supported way to keep a
  non-extractable key across page loads without ever exporting its raw bits). A fresh
  96-bit IV is drawn for every encryption. This is explicitly at-rest hardening, not an XSS
  defence - documented as such in `storage/crypto.ts`'s own KDoc, because same-origin script
  can always ask the module to decrypt while the page is open, exactly as it could call
  `fetch` with the value directly.

Neither client has a plaintext fallback for any local-storage failure (§34/§5's Android
equivalent): a failure to encrypt or to commit the record aborts the submission before any
request is sent, full stop.

## Decision 3 — Room and IndexedDB schemas mirror each other, not the backend

`ReportHistoryEntity` (Android) and `ReportRecord` (Web) carry the same field set: the
identity pair, the encrypted-credential blob (plus IV, stored separately on Web since
`CryptoKey`/ciphertext/IV are naturally three separate values there), display snapshots for
settlement/category/event type/train identifier (so history renders without needing the
backend's now-possibly-changed reference data), the local `submissionState`, the cached
`publicStatus`, and timestamps. Neither schema stores GPS coordinates, free text, or the
plaintext credential.

Room's version starts at 1, with `fallbackToDestructiveMigration` deliberately never used -
a future schema change ships a real, tested migration, and the exported schema (committed
under `android/public-app/schemas/`) together with `RoomSchemaInstrumentedTest`'s
`MigrationTestHelper` harness exists specifically so that migration can be written and
tested against a known starting point. IndexedDB's schema is versioned the same way
(`storage/db.ts`, version 1).

## Decision 4 — one client-local state machine, distinct from the server's

`SubmissionState` (`PENDING` / `SUBMITTED` / `ACCESS_LOST` / `CONFLICT`) is purely local
bookkeeping about *this device's* knowledge of a submission attempt. It is deliberately
never conflated with `PublicReportStatus` (`RECEIVED` / `PROCESSING` / `CLOSED`, ADR 0008
Decision 6), which is the server's workflow status and is only meaningful once a record is
`SUBMITTED`. A `PENDING` record's frozen payload is never mutated by anything except a
successful delivery (which promotes it to `SUBMITTED`), a 409 (which marks it `CONFLICT`),
or a proven-invalid 400 (which deletes it outright - see Decision 5). This is identical
across both clients; `ReportRepository.kt` and `reportRepository.ts` implement the same
`submit` → `deliver` → outcome-branch shape on purpose, so the two platforms cannot quietly
drift onto different retry semantics.

## Decision 5 — a definitive 400 is not history

A 400 response proves the backend never created a report for that attempt. Both clients
delete the local record entirely rather than leaving an orphaned, permanently-unsendable
`PENDING` row - the brief is explicit that this is "not treated as submitted history" (§10).
A later attempt after the user edits the form gets a brand-new `clientSubmissionId` and a
brand-new credential; nothing about the old identity is reused. Both platforms' test suites
assert this by submitting again after a 400 and checking the second attempt's identity
differs from the first's.

## Decision 6 — the COMPLETE/PARTIAL railway-line matrix lives once, as pure domain logic

`RailwayLineDecision.kt` and `domain/lineDecision.ts` are direct ports of each other: given
`{coverage, items}` from the reference API, they compute the exact same one of four outcomes
(`no-verified-candidate`, `single-inferred`, `requires-choice`, and the transient
loading/failed/not-applicable states), and separately track a `LineAnswer` distinguishing
"not yet answered" from an explicit "unsure" - because only the former blocks Step 1, and
only the latter is a valid `railwayLineId: null` submission. Neither client infers a line
client-side; `single-inferred` is display-only and still submits `null`, letting the
backend's own COMPLETE-coverage inference resolve it (ADR 0007) - client and server must
never disagree about what a report claims.

## Decision 7 — GPS is a text hint into the same server search, never a selector

Android's `LocationAssist` turns a device fix into a reverse-geocoded locality string and
feeds it into the ordinary settlement search box - it has no method that returns a
coordinate to any caller, and no settlement is ever chosen without the user tapping a real
search result. Web has no location capability at all (§39): settlement selection is manual
search only, and Permissions-Policy denies `geolocation` at the origin (Decision 8) so even
a future regression could not silently start requesting it.

## Decision 8 — Content-Security-Policy and Permissions-Policy are the strict target, not a starting point

`orszembejelento.hu` (only - `api.orszembejelento.hu` serves no HTML and needs neither
header) gets `default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self';
img-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action
'self'` and `Permissions-Policy: geolocation=(), camera=(), microphone=()`. No
`unsafe-inline` and no `unsafe-eval` were needed: the app has no third-party script, no
CSS-in-JS, and no inline event-handler attributes (React's synthetic event system attaches
listeners in JS, never as HTML attributes) - see `scripts/verify-caddy-csp.sh`, which serves
the real built bundle through the real Caddyfile and asserts these headers on the response,
and the live-browser verification recorded in `PHASE_5_ENGINEERING_REPORT.md` (page load,
client-side navigation, and a real same-origin `fetch` all produced zero console errors
under this policy).

## Consequences

- The two clients are independent, anonymous, and never synchronize - a report submitted on
  Android is invisible to Web and vice versa, by design (§15, §50).
- Local history can be lost (uninstalled app, cleared site data, browser storage eviction);
  neither client claims otherwise, and both say so plainly in the History screen.
- There is intentionally no credential-recovery path on either platform: once the
  encryption key is gone or a stored blob fails to decrypt, that record becomes
  `ACCESS_LOST` permanently - the anonymous, account-less design has no mechanism to prove
  who should get a replacement (ADR 0008's own reasoning, carried into both clients).
- No rate limiting exists on the submission endpoint these clients call (ADR 0008 Decision
  8, `DECISIONS_REQUIRING_OWNER.md` B6) - unchanged by this phase, and still required before
  any public deployment.
