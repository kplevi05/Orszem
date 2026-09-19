# Phase 16 Engineering Report — V2 Release Candidate

Scope: integration, stabilization, release validation and defect correction. **No new
features, no redesign, no roadmap expansion.** No visual change of any kind was made, so no
owner visual approval is required for this phase.

## A. Git / base / branch

| | |
|---|---|
| Base (`origin/main`, verified, not assumed) | `5a2f7cc778547c94336a3d495472308979722dbb` (Merge of PR #19, Phase 15) |
| Branch | `release/v2.0.0-rc1` (no branch convention existed; created from that exact SHA) |
| Tags | none created. Only `demo-v1.1-final` exists. See §M. |
| Untracked owner files | `.claude/`, `dump1.xml`–`dump6.xml` were left untouched and uncommitted |

Phase 16 commits (each one root cause, each with a regression test that fails without it):

| Commit | Change |
|---|---|
| `76cc868` | Service: one process-wide `AuthRepository`, so the session survives Activity recreation |
| `3db4ab0` | Service: a transient refresh failure (5xx/429/transport) no longer ends the session |
| `4d0f444` | Backend: Spring Boot no longer prints a generated default password at startup |
| `cf381e5` | Backend: missing query parameter / wrong Content-Type / unsatisfiable Accept are 400/415/406, not a logged 500 |
| (docs) | this report, plus decision B11 in `docs/DECISIONS_REQUIRING_OWNER.md` |

No migration, API contract, data model, role rule or visual design changed.

## B. First blocker — the Service login/session anomaly

Phase 15 saw, on the emulator only, a fresh Service login that created a backend session while
the app stayed on the login screen until a clean reinstall. It was carried forward unexplained.

**It reproduced deterministically, and it is not emulator-specific.**

### Root cause (defect 1, `76cc868`)
`MainActivity.onCreate` built a **new** `AuthRepository` on every Activity creation. The
access token lives only in that repository's memory (by design), but `AuthViewModel` is
retained across configuration changes. After a rotation, font-scale, dark-mode or locale change
the ViewModel still said "signed in", while the *new* repository had no access token. The next
call reported "no session", the app called `forceSignedOut()`, and that cleared local state and
**deleted the stored refresh token**, although the backend session was still valid. From then
on the app showed the login screen and had nothing to restore.

Fix: `NetworkModule.authRepository()` is a process-wide singleton. Before/after was verified on
an emulator by rotating the device (before: login screen and the stored token deleted; after:
still signed in). `SessionSurvivesRecreationInstrumentedTest` (2 tests) fails 2/2 without the
fix.

### Second defect found by the same matrix (S7, `3db4ab0`)
`AuthRepository.refresh()` treated **every** non-2xx answer as "session rejected": it deleted
the token and returned `SessionEnded`. One `503` or `429` at start-up therefore signed the user
out and destroyed a still-valid session. The mid-session path had the same flaw (`withFreshToken`
returned `null`, which callers map to a forced sign-out).

Fix: only `401` (the backend's answer for every definitive rejection: invalid, expired, revoked,
replayed, deactivated user) ends the session. Other statuses keep the token and report a
recoverable failure; mid-session, the refresh failure is thrown as `IOException`, which
`apiCall` already maps to a retryable `NetworkError`. `AuthRepositoryTest` 16 → 22; four of the
six new tests fail against the old code. Verified on the emulator through a fault-injecting
proxy: 503 → honest "server unreachable" message, token kept, the next launch restores.

### Session matrix (emulator + fault-injecting proxy + real backend)

| # | Scenario | Result |
|---|---|---|
| S1 | Clean install → login | enters the app deterministically |
| S2 | Cold start with a valid refresh session | one refresh, restored |
| S3 | Logout → login | one `logout` call, nothing left to restore, second login works |
| S4 | Expired/rejected access token, valid refresh | one 401 → one refresh → one retry (200) |
| S5 | Five concurrent 401s | exactly one shared refresh (single-flight holds) |
| S6 | Refresh rejected (401 SESSION_INVALID) and a real `logout-all` from another session | login screen, token cleared, one refresh sent; revoked session refused by backend immediately |
| S7 | Refresh answered 503 | **defect 2, fixed**; token kept, message shown, next start restores |
| S8 | Refresh connection dropped before reaching the backend | recoverable error, token kept, one attempt, next start restores |
| S9 | Backend processed refresh but the response was lost | exactly **one** request (no blind retry). Next start is refused and needs a sign-in — see B11 |
| S10 | Forced first-password change; own-password change | login → change screen → app; process death mid-change persists nothing; own change revokes other sessions and keeps this one |
| S11 | Session revocation | `logout-all` revokes the other session's access token at once (401) |
| S13 | Process death (`am kill`) and relaunch | restored with one refresh |
| S14 | Secrets in logs | 0 hits for tokens, credentials or passwords across 8,330 logcat lines, the proxy log and the backend log |

Deactivated/revoked users are rejected by the backend (401 everywhere); the client ends the
session on that 401. Rotation-reuse detection was not weakened.

## C. Clean-environment bring-up

Nothing reused hidden state: a brand-new PostgreSQL 16 container with a random throwaway
password, 0 tables, and artefacts built from a clean tree.

| Step | Result |
|---|---|
| Backend `./gradlew clean build` | success, see §I |
| Boot against the empty DB | Flyway applied **V001–V006** (6/6, 0 failed, 18 tables); `/actuator/health` UP; a restart against the migrated DB applies nothing |
| First SUPER_ADMIN | created by `scripts/orszem-admin create-super-admin` (no HTTP route, no seeded credential) |
| Fictional reference dataset | `reference-validate` valid (3 settlements, 2 lines, 4 mappings) → `reference-diff` → `reference-import` → re-import is a NO-OP |
| Web `npm ci` + `typecheck` + `build` | 0 vulnerabilities; all green |
| Public and Service Android clean build | `clean assembleDebug` for both, `lint`, unit tests: green |
| Caddy | `caddy validate` valid, `caddy fmt` clean; the routing, header-redaction and CSP scripts all pass (run in the `caddy:2-alpine` container because no `caddy` binary is installed locally) |
| Backup/restore drill | `scripts/orszem-restore-drill.sh` **PASS** (barrier-proven concurrent write outside the snapshot, six negative restores refused, restored DB identical, sessions and capabilities intact, all containers removed) |

Migration immutability: no migration was added, changed or deleted in Phase 16. Historically,
exactly one commit (`8d1db87`, Phase 3C) edited V002 in place; its message records that V002
had only ever run in ephemeral Testcontainers instances, and no V2 tag or deployment exists.
Not a violation, but V001–V006 are the immutable baseline from this release candidate on.

## D. End-to-end Public → Service workflow (real HTTP, real PostgreSQL)

`e2e.mjs`, 71 checks, 0 failures. Actors: SUPER_ADMIN, territorial and global SERVICE_USER,
territorial and global MODERATOR, plus a SERVICE_USER of a different area.

- Public: catalog and reference are public; submissions routed to area 1, an ambiguous
  settlement (two candidate lines, none chosen) → UNCLASSIFIED, an unassigned line →
  UNCLASSIFIED; idempotent resubmission; capability lookup right/wrong/absent.
- Visibility by role/scope: matches the documented rules, including *territorial never sees
  UNCLASSIFIED* and *a SERVICE_USER, global or not, never sees UNCLASSIFIED*.
- Existence-safe 404s for out-of-scope reports; the Public capability is never exposed by any
  Service response.
- Workflow: claim → return → claim → close; stale version refused; archive read-only; reassign
  refuses an out-of-scope target and accepts an in-scope one.
- Moderation: SERVICE_USER 403; territorial MODERATOR cannot touch UNCLASSIFIED (404, no leak);
  global MODERATOR can; only SUPER_ADMIN restores; restored reports return to ordinary queues.
- Concurrency over HTTP: two users claiming one report at once → exactly one 200 and one 409.

**Honest note:** the first run had 3 failures. All three were wrong expectations in my script
(I assumed a global SERVICE_USER could see UNCLASSIFIED; `ReportWorkflowPolicy` says a
SERVICE_USER never does). The backend behaved to its documented spec; the script was corrected
and stricter checks were added (global MODERATOR/SUPER_ADMIN may see but not claim UNCLASSIFIED).

## E. Role and scope matrix (authorization sweep)

`sweep.mjs`: 43 of the 51 OpenAPI operations (the other 8 are the five session-ending auth calls,
change-password and the two on `/public/reports`, which are covered by §B and §D),
each as six actors with well-formed bodies (an empty body would let validation mask the
authorization decision), 137 checks, 0 failures, **0 responses ≥ 500**.

`403` = forbidden, `404` = existence-hidden, `2xx` = allowed, `4xx*` = passed authorization and
was refused by validation or state (e.g. a stale version). SU = SERVICE_USER, MOD = MODERATOR,
t = territorial, g = global.

```
operation                                                      anon  SU-t  SU-g  MOD-t MOD-g SA
GET  user-management/users                                     401   403   403   2xx   2xx   2xx
POST user-management/users                                     401   403   403   4xx*  4xx*  2xx
POST user-management/users/{id}/role|deactivate|areas|global…  401   403   403   403   403   2xx
POST user-management/users/{id}/reactivate|password-reset      401   403   403   404   403   2xx
GET  service-area-admin/* and POST service-area-admin/*        401   403   403   403   403   2xx / 4xx*
GET  audit/*                                                   401   403   403   403   403   2xx
POST moderation/reports/{id}/restore                           401   403   403   403   403   4xx*
POST moderation/reports/{id}/delete                            401   403   403   404   4xx*  4xx*
GET  moderation/deleted                                        401   403   403   2xx   2xx   2xx
GET  reports/{id}                                              401   404   404   404   2xx   2xx
GET  reports/new|in-progress|archive, analytics/*, account/me  401   2xx   2xx   2xx   2xx   2xx
```

Privilege-escalation attempts (a MODERATOR creating a SUPER_ADMIN or another MODERATOR,
granting global access, promoting a user to SUPER_ADMIN; a SERVICE_USER promoting themself)
were all refused. The complete per-operation table was produced by the sweep; the excerpt above
is representative and every row satisfied the assertions.

## F. Cross-domain invariants and real-PostgreSQL concurrency

- HTTP-level claim race (§D) on real PostgreSQL: one winner, one conflict, stale versions
  refused.
- The Phase 4–15 integration suites (789 backend tests, of which the concurrency and invariant
  suites run against real PostgreSQL via Testcontainers) all pass, including
  `ModerationConcurrencyIT` (green in both full backend runs; Phase 15 corrected its assertion).
- The restore drill re-proved the open-assignment and open-moderation-episode invariants as
  byte-identical constraint definitions after a real backup/restore.

## G. User management, moderation, area admin, analytics, audit

Covered by §D/§E and the existing suites: user creation with forced first-password change,
role and area grants, deactivation, moderator scope, area administration and railway-line
assignment (SUPER_ADMIN only), analytics scoping by role, audit trail readable by SUPER_ADMIN
only (403 for every other role). No defect other than those in §A was found in these surfaces.

## H. UI / accessibility regression

No UI code changed in Phase 16, so this is a regression baseline against Phase 15, not a new
review.

- Service Android on the emulator: login, forced-password-change, own-account/profile screens,
  and the reports list re-checked in Hungarian; error copy on a refresh failure is the existing
  string ("A kiszolgáló nem érhető el…").
- Instrumented suites (§I) cover navigation at 1.0×/1.3×, the three roles, the Hungarian
  date/time pickers, the history card, the audit filter and the empty/error/loading states, and
  pass.
- Public Web at 375, 768 and 1280 px: **no horizontal overflow**; keyboard Tab walks 5 stops in
  logical order, each with a visible 2.4 px focus ring; one `h1`; `lang="hu"`; no target under
  24 px. No developer terminology was found on screen.
- Not re-done: a fresh contrast re-measurement (unchanged from Phase 15 §S) and new screenshot
  files.

## I. Full clean regression — exact counts

| Suite | Result |
|---|---|
| Backend `clean build` (final code, `cf381e5`) | **789 tests, 88 suites, 0 failures, 0 errors, 0 skipped** (was 783 before the API fix; +6) |
| Service Android unit | **169 / 0 failures** (was 163) |
| Public Android unit | **39 / 0 failures** |
| Service Android instrumented (emulator) | **89 / 0 failures** on the second full run (see below) |
| Public Android instrumented (emulator) | **21 / 0 failures** |
| Android lint | Public: 0 errors, 10 warnings (unchanged); Service: 0 errors, 3 warnings (unchanged) |
| Web | `typecheck` and `build` green; **55 tests in 8 files, all passing**; `npm audit` 0 vulnerabilities |
| Reference data | `validate-canonical` valid |
| Restore drill | PASS |

Test tasks were confirmed as executed, not restored from cache (only compilation was cached).
No test was weakened, skipped or quarantined.

**One non-reproduced failure, recorded honestly.** In the first full Service instrumented run,
`AuditComposeTest.the_apply_and_clear_actions_stay_on_screen_after_scrolling_…` failed with
`ComposeNotIdleException` after 62 s. It then passed 3 of 3 in isolation and the whole suite
passed on a full rerun (89/89). Phase 15 recorded a similar emulator stall (a different test).
I could not prove a root cause and did not claim one. The test scrolls a 31-chip list in a
sheet; the pattern is consistent with an emulator stall under load, but that is a hypothesis,
not a finding. Instrumented suites are not part of the CI checks (CI builds, lints and unit
tests the apps).

## J. Security and privacy RC review

| Area | Finding |
|---|---|
| Secrets in the repo | no tracked keystore, `.env`, `local.properties` or secret-shaped string |
| Logging | no log statement writes a token, password, credential, hash, capability or coordinates; runtime scan of the backend log after the whole E2E and sweep: **0** hits |
| **Generated default password (defect 3, fixed)** | Spring Boot logged `Using generated security password: …` at start-up. Unusable (Basic/form login are disabled; all attempts 401) but a password-shaped secret in the log, and in production that log is journald. `UserDetailsServiceAutoConfiguration` is now excluded on `BackendApplication`; `NoDefaultCredentialIT` fails without it |
| **Framework errors as 500 (defect 4, fixed)** | any anonymous caller could get a 500 plus an ERROR stack trace with a missing query parameter, a missing/non-JSON Content-Type or an unsatisfiable Accept. Now 400/415/406; error bodies are always JSON; 0 unhandled-exception log entries afterwards. No new public error code was added |
| CORS | none; the Web is same-origin through Caddy |
| Android | `allowBackup=false`, no debuggable release, cleartext only for the debug emulator hosts, Service requests only `INTERNET`, Public requests `INTERNET` + location |
| Edge | OpenAPI/Swagger are enabled in Spring (a startup WARN says so) but blocked by Caddy and asserted by CI. Informational; no change |
| Tomcat-level 400 | for an illegally encoded URL the page is bare: no version, no stack, no `Server` header |
| Dependencies | no snapshot/RC/beta versions; `npm audit` clean. No new external scanner was used |

## K. Release and deployment gates — owner actions, not performed

Nothing was deployed. V1, DNS, signing keys, production databases and paid services were not
touched. The following remain **owner** actions:

1. V1 database `pg_dump -Fc` and a verified restore of that dump (`docs/deployment/V1_DATABASE_ARCHIVE.md`).
2. V1 application retirement plan (`docs/deployment/V1_DECOMMISSION.md`); the V1 public exposure is a known risk until then.
3. DNS records for `orszembejelento.hu`, `www` and `api`, and HTTPS issuance.
4. Generate the V2 release signing key, back it up off-machine (`docs/deployment/ANDROID_SIGNING.md`); optionally back up the V1 pilot key.
5. Final production configuration and secrets (`/etc/orszem/backend.env`), first SUPER_ADMIN via the maintenance CLI.
6. Decision B11 (refresh-token rotation without a grace window).
7. If the Public app is ever shipped as an App Bundle, disable language splits (Phase 15 §Z).

## L. Known limitations

- **B11 / S9:** a refresh response lost in transit forces one re-login. Deliberate security
  behaviour; documented for an owner decision, not changed.
- The 62 s `ComposeNotIdleException` in §I is unexplained (one occurrence in four runs).
- `deploy/caddy/Caddyfile;C` is an empty, untracked directory (a Windows bind-mount artefact).
  Git ignores it; it is unrelated to the release and was left in place.
- Caddy scripts were run in the Caddy container locally; CI runs them natively.
- No fresh contrast measurement or screenshot files this phase (§H).
- Physical-device testing was not done; everything ran on one emulator.

## M. Version state and RC tag proposal

Version is declared as `2.0.0-dev` in `backend/build.gradle.kts`, both Android `build.gradle.kts`
files (`versionCode = 1`) and `web/public-web/package.json` (+ lockfile). There is no
release automation and no tag. **No version string was changed and no tag was created.**

Proposal for the owner: tag the merged commit `v2.0.0-rc.1`. Bumping `2.0.0-dev` to
`2.0.0-rc.1` in the four manifests would be a separate, tiny commit. Final `v2.0.0` and the
production `versionCode` policy are for the release, not for this phase.

## N. CI

All six GitHub Actions checks were green on `7e5a778` (the report commit; the code was final since
`cf381e5`): backend `build`, android `build`, web `build`, `validate` (reference-data), `caddy` and
`backup-restore-scripts`. This section is the only later change (docs-only); CI was re-checked on
that final HEAD and its result is stated in the conclusion message, because a commit cannot
contain its own CI result.

## O. Fitness for Phase 17

See the conclusion message: it states READY or NOT READY with the evidence above.
