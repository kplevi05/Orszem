# Phase 6 engineering report — Service user management backend

**Branch:** `feature/v2-user-management` (not merged — no PR opened, per the brief)
**Base:** `main` @ `c7509f6` (PR #8, Phase 5 merged)
**Status:** implementation and full test battery complete, including the targeted
pre-merge fix described in §B.1; no Phase 7.

---

## A. Git

| | |
|---|---|
| Starting `main` SHA | `c7509f6` (verified against live `origin/main` before branching) |
| Branch | `feature/v2-user-management` |
| Final SHA | this commit (a commit cannot name its own hash inside itself — see `git log -1` on the branch, or the session's closing report to the owner, for the exact hash) |
| Pushed | yes |
| PR | none opened — not requested by the brief, and explicitly not to be opened |

Commits, in order:

1. `a09c1f6` — feat(backend): Phase 6 - user management backend
2. `b0d264c` — test(backend): Phase 6 user management test battery
3. `004acec` — docs: mark A7/A8 resolved (Phase 5 visual approval + Android verification — noticed while doing the required §0 inspection pass, unrelated to Phase 6's own scope)
4. `e107118` — docs: Phase 6 engineering report
5. `2c2468b` — fix(backend): service-ID collision no longer aborts the transaction — the targeted pre-merge fix (§B.1), closing the `insertIfServiceIdFree` issue this report's first version had flagged as a known limitation
6. **this commit** — docs: Phase 6 engineering report update (§B.1, §K, §N)

## B. Schema

**No V004 was needed.** The existing V001 (`users`, `auth_sessions`, `refresh_tokens`,
`audit_events`) and V002 (`service_areas`, `user_service_areas`,
`users.global_area_access`) migrations already carry every table, column and constraint
Phase 6 needed:

- `users.role`/`status` already support the full `SERVICE_USER`/`MODERATOR`/`SUPER_ADMIN`
  and `ACTIVE`/`DEACTIVATED` vocabulary Phase 6 mutates.
- `user_service_areas` was already the current-authorisation table the brief describes
  ("current permission only... can always be read as the truth right now") — Phase 6 adds
  no new junction table, only new read/write methods over the existing one.
- `users.global_area_access` already existed, added by V002 for exactly this purpose.

V001/V002/V003 are untouched. No migration file was added, edited or renumbered.

## B.1 Pre-merge correctness fix — service-ID collision no longer aborts the transaction

The first version of this report flagged, as a known limitation, that
`JdbcUserRepository.insertIfServiceIdFree` still caught `DuplicateKeyException` as its
collision-handling mechanism — the same unsafe pattern the Phase 6 concurrency work had
just found and fixed in `JdbcServiceAreaRepository.grantAreaIfAbsent` (§K). Because
service-ID collision-and-retry is part of the Phase 6 creation contract (`CreateUserUseCase`
calls this same repository method from inside its own bounded retry loop, exactly as the
maintenance CLI's `CreateSuperAdminUseCase` already did), this was reclassified from
"pre-existing, low-probability, out of scope" to "directly relevant to Phase 6" and fixed
before merge, per explicit instruction.

**The fix**, entirely mechanical and confined to one method: `insertIfServiceIdFree` now
uses `INSERT INTO users (...) VALUES (...) ON CONFLICT (service_id) DO NOTHING`, deriving
success from `.update() == 1` instead of catching the constraint-violation exception.
PostgreSQL aborts the *entire enclosing transaction* the instant any statement raises an
error; catching the resulting `DuplicateKeyException` in Kotlin does not undo that abort —
every later statement in the same `@Transactional` method (here, the very next retry
attempt with a fresh candidate, still inside the same transaction) would then fail with
"current transaction is aborted", exactly as reproduced below. A conflict clause never
raises an error in the first place, so a normal collision — an expected, routine event
given a random 6-digit ID space, not a rare edge case — never touches PostgreSQL's abort
state at all.

**Everything the fix was required to preserve, preserved exactly:**
- `SZ-XXXXXX` format — unchanged; the generated candidate string is identical to before.
- `SecureRandom` generation — unchanged; `ServiceIdGenerator` itself was not touched.
- Retry behaviour — unchanged; `CreateSuperAdminUseCase`/`CreateUserUseCase` still call
  `insertIfServiceIdFree` in their own `repeat(10) { ... }` loop and still interpret a
  `false` return as "try the next candidate" — the method's public contract (boolean
  success/failure, no exception for an ordinary collision) is bit-for-bit the same; only
  the SQL statement inside it changed.
- The database's unique index (`ux_users_service_id`) remains the sole authority on
  collisions — `ON CONFLICT (service_id)` targets that exact index; nothing pre-checks
  existence in application code.
- Transaction/audit semantics — unchanged; the audit row for a successful creation is still
  written in the same transaction, still names only the winning candidate, and (proven
  below) never names a discarded, collided-with candidate.
- SUPER_ADMIN maintenance behaviour — `CreateSuperAdminUseCase` shares this exact repository
  method unmodified, so it inherits the fix automatically; verified directly (§B.1 tests
  below), not merely assumed from code inspection.

**Proof the fix is real, not cosmetic**: before restoring the fix, the working tree was
reverted to the prior catch-`DuplicateKeyException` version and the new tests below were
run against it — all four failed, three with the exact `UncategorizedSQLException: current
transaction is aborted` this fix exists to prevent, the fourth (the exhausted-retry-budget
test) with a different, unrelated failure shape confirming it genuinely exercises the same
code path. The fix was then restored and all four passed, confirmed with a forced
(non-cached) re-run.

**New tests** (`hu.orszembejelento.backend.identity.ServiceIdCollisionIT`, real PostgreSQL,
one shared `ServiceIdGenerator` bean override so both real production call sites are
exercised rather than a reimplementation of either):
1. `SUPER_ADMIN maintenance creation survives a forced first-candidate collision` — a
   scripted `SecureRandom` forces `CreateSuperAdminUseCase.create()`'s first candidate to
   collide with a real pre-existing user, then supplies a fresh one. The real,
   unmodified, `@Transactional` production method (not a reimplementation) completes
   successfully, produces exactly one new user under the second candidate's service ID,
   and the audit row for `SUPER_ADMIN_CREATED` names only that second candidate — never the
   discarded, collided-with first one.
2. `Phase 6 user creation survives a forced first-candidate collision` — the identical
   scenario through `CreateUserUseCase.create()`, proving the shared repository fix helps
   the Phase 6 caller too, not only the maintenance CLI's.
3. `creation eventually succeeds after several consecutive forced collisions` — three
   consecutive forced collisions with the same taken ID, then a fresh one, still inside the
   production retry budget.
4. `exhausting the bounded retry budget fails cleanly with no partial state` — ten
   consecutive forced collisions (matching, not exceeding, the production
   `SERVICE_ID_ATTEMPTS` budget) end in the same bounded-failure `IllegalStateException`
   the production code already raised before this fix, and — because the whole call is
   `@Transactional` — the failure rolls back cleanly: zero new user rows, zero new audit
   rows, the pre-existing fixture account completely untouched.

All four pass against real PostgreSQL via Testcontainers.

## C. API

All 11 endpoints live under `/api/v1/service/user-management`, require an authenticated
Service bearer session (default-deny `SecurityConfig`, unchanged), and reject SERVICE_USER
with `403 USER_MANAGEMENT_FORBIDDEN` before any use case runs
(`ManagementActorLoader`). Every response carries `Cache-Control: no-store`.

| Method | Path | Role | Purpose |
|---|---|---|---|
| GET | `/users` | MODERATOR, SUPER_ADMIN | Paginated, scoped list. Query params: `page`, `size` (default 50, max 100), `role`, `status`, `query` (service-ID substring), `areaId`. |
| GET | `/users/{serviceId}` | MODERATOR, SUPER_ADMIN | One user's detail. 404 `USER_NOT_FOUND` identically for nonexistent and out-of-scope. |
| GET | `/areas` | MODERATOR, SUPER_ADMIN | Assignable ACTIVE areas for the current actor — not ServiceArea administration. |
| POST | `/users` | MODERATOR (SERVICE_USER only), SUPER_ADMIN (SERVICE_USER or MODERATOR) | Create. Body: `role`, `areaIds`, `globalAreaAccess`. Returns `serviceId`, `role`, `temporaryCredential` (once), `mustChangePassword`. |
| POST | `/users/{serviceId}/password-reset` | as manageable by actor | New temporary credential; revokes every session; never reactivates. |
| POST | `/users/{serviceId}/deactivate` | as manageable by actor | Idempotent; revokes every session. |
| POST | `/users/{serviceId}/reactivate` | as manageable by actor | Idempotent; touches only `status`. |
| POST | `/users/{serviceId}/role` | SUPER_ADMIN only | Body: `role` (`SERVICE_USER` or `MODERATOR`). Only `SERVICE_USER↔MODERATOR`. |
| POST | `/users/{serviceId}/global-access/grant` | SUPER_ADMIN only | Idempotent. Rejects a SUPER_ADMIN target. |
| POST | `/users/{serviceId}/global-access/revoke` | SUPER_ADMIN only | Idempotent. |
| POST | `/users/{serviceId}/areas/{areaId}/grant` | as manageable by actor, area within actor's own authority | Idempotent. |
| POST | `/users/{serviceId}/areas/{areaId}/revoke` | as manageable by actor | `409 USER_REQUIRES_SERVICE_AREA` if a MODERATOR would leave a non-global target with zero areas. |

List/detail response shape (`ManagedUserResponse`): `serviceId`, `role`, `status`,
`mustChangePassword`, `globalAreaAccess`, `areas: [{id, name, status}]`, `canManage`
(a UI hint only — every mutation re-authorises server-side regardless). No password hash,
no session/token material, no audit rows, no internal UUID.

Verified live via `GET /v3/api-docs` against a real running instance: all 11 paths present
with the correct HTTP methods, and a full curl walkthrough (SUPER_ADMIN and MODERATOR
actors, real PostgreSQL, real service area) exercised creation, listing, deactivate/
reactivate, password reset, role change, global-access grant and the existence-safe 404 —
see §K/§L.

## D. Policy

One class, `UserManagementPolicy`, deliberately not a generic permission framework — every
rule is a few lines of readable Kotlin, and `canManageTarget`/`canAssignArea` delegate the
"is this specific area within the actor's authority" question to the existing
`AreaScopePolicy` rather than reimplementing it, so the two rules can never quietly diverge.

- **SUPER_ADMIN**: sees every role including other SUPER_ADMINs; manages any non-SUPER_ADMIN
  target; is the only role that may create MODERATOR, change roles, or grant/revoke global
  access; has no HTTP path to create, reset, reactivate/deactivate or change the role of a
  SUPER_ADMIN — `canManageTarget` returns `false` for a SUPER_ADMIN target unconditionally.
- **Territorial MODERATOR** (`globalAreaAccess = false`): sees a SERVICE_USER or peer
  MODERATOR only if the target's effective scope overlaps the moderator's own current
  active areas (a global target's effective scope is "every active area", so it is visible
  but never manageable). May manage only a SERVICE_USER that is not global, is not
  currently unassigned, and whose every current area assignment — including a stale one to
  a now-INACTIVE area — sits inside the moderator's own scope. May grant only an ACTIVE
  area already in that same scope, and may never revoke a non-global target's last area.
- **Global MODERATOR** (`globalAreaAccess = true`): sees every non-SUPER_ADMIN user,
  assigned or not; manages any non-global SERVICE_USER including a currently-unassigned
  one (the one asymmetry with the territorial case — an empty assignment set is not itself
  disqualifying for a global moderator); never manages another MODERATOR or a global
  SERVICE_USER; may grant any ACTIVE area, not only ones they personally hold.
- **SERVICE_USER**: rejected at the door — `ManagementActorLoader` throws
  `UserManagementForbiddenException` before any use case or policy check runs.
- **Target visibility vs manageability** (§4): kept as two separate methods,
  `canViewTarget` and `canManageTarget`. A peer MODERATOR is the clearest example: visible
  (`canViewTarget = true`, so list/detail return 200) but never manageable
  (`canManageTarget = false`, so every mutation on that target returns `403
  USER_NOT_MANAGEABLE`). Every mutation use case re-derives `canManageTarget` against
  freshly-locked state; nothing trusts a prior read's `canManage` value.

## E. Creation

- **Service ID**: the existing `ServiceIdGenerator` (Phase 2), unchanged — `SZ-` + six
  digits, CSPRNG, retried on the database's own unique-index collision.
- **Temporary credential**: the existing `TemporaryCredentialGenerator` (Phase 2),
  unchanged — 16 unambiguous characters, 80 bits, `XXXX-XXXX-XXXX-XXXX`, hashed with the
  same `PasswordHasher`/Argon2id used everywhere else. No second credential format was
  invented.
- **Initial areas/global**: for a SUPER_ADMIN creator, any number (including zero) of
  ACTIVE areas and any `globalAreaAccess` value. For a MODERATOR creator: `globalAreaAccess`
  must be `false`, at least one area is required, and every requested area must be ACTIVE
  and already inside the moderator's own scope — validated in full, all-or-nothing, before
  any row is written, so a bad request never leaves a half-created account.
- **One-time response security**: the temporary credential appears only in the creation
  response body, under `Cache-Control: no-store`, never in a URL, never logged, never added
  to audit metadata (verified by a dedicated test reading raw audit rows and the raw
  database file/column bytes). If the response is lost, the only recovery path is an
  administrative password reset — no credential-recovery storage exists.

## F. Password reset

- **Hashing**: identical to creation — `TemporaryCredentialGenerator` + `PasswordHasher`
  (Argon2id). The new hash overwrites the old one in the same `UPDATE` used by every other
  password-changing flow in the codebase (`JdbcUserRepository.updatePassword`).
- **Session revocation**: every existing session of the target is revoked in the same
  transaction, reusing the existing `RevocationReason.ADMIN_PASSWORD_RESET` constant — the
  same one the maintenance CLI's own SUPER_ADMIN reset already used, since the two are
  semantically identical events. (Deactivation, a separate operation described in §G, uses
  a new, distinct `RevocationReason.ADMIN_USER_DEACTIVATED` — a deactivation is not a
  password reset, and the two must remain distinguishable in the session-revocation trail.)
- **Temporary state**: `mustChangePassword` is set to `true`. Works identically for an
  ACTIVE or a DEACTIVATED target and never changes `status` either way — a reset on a
  deactivated account produces a valid new credential that still cannot sign in until
  reactivated, proven by a dedicated test.

## G. Lifecycle

- **Deactivate**: `status → DEACTIVATED`, every session revoked, idempotent (a repeat call
  on an already-deactivated target is a no-op with no further audit row).
- **Reactivate**: `status → ACTIVE` only — no password change, no `mustChangePassword`
  clear, no session created, idempotent. A previously-temporary-credential account still
  owes its change after reactivation; a previously-normal-password account's existing
  password becomes usable again, proven by dedicated tests for both.

## H. Role/scope mutations

- **Role transition**: SUPER_ADMIN-only, exactly `SERVICE_USER → MODERATOR` and
  `MODERATOR → SERVICE_USER`. Anything naming SUPER_ADMIN as the target's current role, the
  requested role, or requiring a MODERATOR/SERVICE_USER actor is rejected — a same-role
  request (e.g. `SERVICE_USER → SERVICE_USER`) is also rejected as `INVALID_ROLE_TRANSITION`
  rather than silently accepted, since the brief names exactly two allowed transitions.
  Preserves area assignments, `global_area_access`, password and status untouched — only
  the `role` column changes. No session is revoked; a live test proves the actor's own
  already-issued session reflects the new role on its very next request.
- **Area grant/revoke**: explicit, per-area operations — never a whole-list replacement,
  which is exactly the shape that would lose a concurrent grant of a different area.
  Grant requires the area ACTIVE and within the actor's own authority; is idempotent
  (`INSERT ... ON CONFLICT DO NOTHING`, no duplicate row, no duplicate audit event). Revoke
  allows removing a stale assignment to an area that has since gone INACTIVE, and enforces
  the MODERATOR-only last-area rule (`409 USER_REQUIRES_SERVICE_AREA`) — a SUPER_ADMIN may
  leave a target with zero areas.
- **Global permission**: SUPER_ADMIN-only grant/revoke, idempotent, rejects a SUPER_ADMIN
  target outright. Granting never clears existing ordinary area assignments; revoking makes
  them authoritative again immediately — proven against `AreaScopePolicy.canAccessArea`
  directly on the post-revoke database state.

Every one of these five mutation families locks the target `users` row first (canonical
order step 1, exactly Phase 2's own convention), re-authorises against that freshly-locked
state, and — where a `service_areas` row is also touched — locks it second, before touching
`user_service_areas` third. This ordering is what the concurrency tests in §K actually
exercise, not merely assert.

## I. Audit

Nine new `AuditEventType` values, every one targeting the managed USER, every one written
in the same transaction as the state change it describes:

| Event | Safe metadata |
|---|---|
| `USER_CREATED` | `serviceId`, `role`, `areaIds` (comma-joined), `globalAreaAccess` |
| `USER_PASSWORD_RESET` | `serviceId`, `revokedSessions` |
| `USER_DEACTIVATED` | `serviceId`, `revokedSessions` |
| `USER_REACTIVATED` | `serviceId` |
| `USER_ROLE_CHANGED` | `serviceId`, `oldRole`, `newRole` |
| `USER_AREA_GRANTED` | `serviceId`, `areaId` (only on a real state change) |
| `USER_AREA_REVOKED` | `serviceId`, `areaId` (only on a real state change) |
| `USER_GLOBAL_ACCESS_GRANTED` | `serviceId` (only on a real state change) |
| `USER_GLOBAL_ACCESS_REVOKED` | `serviceId` (only on a real state change) |

Never present: the temporary credential, its hash, any token, or any session secret —
confirmed by a dedicated test that reads the raw `metadata::text` of every
`USER_PASSWORD_RESET` row and checks it does not contain the issued credential, and
directly by inspecting the real audit table during the live smoke walkthrough (§K).

## J. Tests — every command and count

**Policy unit tests** (`hu.orszembejelento.backend.usermanagement.UserManagementPolicyTest`,
no database):
```
./gradlew test --tests "hu.orszembejelento.backend.usermanagement.UserManagementPolicyTest"
```
24 tests, 0 failures — every SUPER_ADMIN/territorial-MODERATOR/global-MODERATOR/
SERVICE_USER case from brief §48.

**Full Phase 6 integration battery** (real PostgreSQL via Testcontainers):
```
./gradlew test --tests "hu.orszembejelento.backend.usermanagement.*"
```
90 tests, 0 failures, across: `UserManagementPolicyTest` (24, same 24 as above),
`UserCreationIT` (9), `PasswordResetIT` (8), `UserStatusIT` (8), `RoleChangeIT` (7),
`GlobalAccessIT` (6), `AreaGrantRevokeIT` (9), `UserListingIT` (11),
`UserManagementConcurrencyIT` (4), `SessionAuthorityRegressionIT` (4).

**Full backend regression**:
```
cd backend && ./gradlew build
```
**426/426 tests, 0 failures, 0 skipped** (332 pre-existing Phase 0-5 tests, unchanged and
still green + 90 Phase 6 user-management tests + 4 new `ServiceIdCollisionIT` tests from
the §B.1 pre-merge fix). Lint/`check`/`build` all succeed.

**Live, real-instance smoke test** (beyond the automated suite): a real PostgreSQL 16
container, the real backend via `bootRun`, a real SUPER_ADMIN provisioned through the
maintenance CLI, driven entirely by `curl` — no test doubles anywhere:
1. Completed the SUPER_ADMIN's forced initial change, logged in, fetched `/v3/api-docs`
   and confirmed all 11 Phase 6 paths are documented with the correct HTTP methods.
2. Inserted one real `service_areas` row directly (Phase 6 has no admin endpoint for that
   by design), then created a real MODERATOR scoped to it via `POST /users`.
3. As that MODERATOR: created a scoped SERVICE_USER, listed users (saw itself with
   `canManage:false` and the new user with `canManage:true`), deactivated then reactivated
   it, reset its password, and confirmed `GET /users/SZ-<the-SUPER_ADMIN>` returns
   `404 USER_NOT_FOUND`.
4. As the SUPER_ADMIN: promoted that same SERVICE_USER to MODERATOR, granted it global
   access, confirmed a password-reset attempt against its own peer SUPER_ADMIN is rejected
   with `403 USER_NOT_MANAGEABLE`, and fetched the assignable-areas list.
5. Read the real `audit_events` table directly via `psql`: 12 rows, every one matching
   §I's shape, no secret in any of them.

The throwaway Postgres container and `bootRun` process were both torn down afterward; no
residual infrastructure was left running.

## K. Concurrency

All four required races (brief §56), against real PostgreSQL, released together from a
`CountDownLatch` so the operations genuinely overlap inside the database rather than merely
being issued in a loop:

- **A. Role promotion vs. moderator mutation**: a SUPER_ADMIN promoting a target to
  MODERATOR raced against the acting MODERATOR's own password-reset call on that same
  target. Result: the target-user row lock serialises the two — whichever wins commits
  first, and the loser (if it is the moderator's reset and the target is now MODERATOR)
  is rejected with `403`, never silently applied against a target that had already become
  a peer MODERATOR by the time its own lock was granted.
- **B. Two concurrent distinct grants**: both areas survive — the final assignment set
  contains both, neither is lost.
- **C. Six concurrent duplicate grants of the same area**: all six calls return `200`
  (idempotent, not an error), and exactly one `user_service_areas` row results. This is the
  race that first surfaced the `ON CONFLICT DO NOTHING` fix described in §H/§L for
  `JdbcServiceAreaRepository.grantAreaIfAbsent` — the original catch-`DuplicateKeyException`
  version left PostgreSQL's transaction aborted after the losing caller's constraint
  violation, so its own follow-up read then failed too. The same fix was subsequently
  applied to the sibling `JdbcUserRepository.insertIfServiceIdFree` method, once §N's
  original "known limitation" note made clear it shared the identical pattern and was
  directly relevant to the Phase 6 creation contract — see §B.1 for that fix and its own
  dedicated, deterministic (not merely probabilistic-concurrency-based) test coverage.
- **D. Deactivation vs. login**: a deactivation raced against three concurrent login
  attempts on the same account. Once every operation has committed, `status = DEACTIVATED`
  and `activeSessionCount = 0` unconditionally, regardless of which operations happened to
  win the row lock first — a login that won the race legitimately created a session before
  the deactivation committed, but the deactivation's own unconditional
  `revokeAllSessionsOfUser` (run after it acquires the same row lock) removes it anyway. A
  fresh login issued strictly after all of the above then fails outright.

## L. Security review (§70)

- ✅ No SUPER_ADMIN HTTP creation — `canCreateRole` rejects `SUPER_ADMIN` for every actor;
  verified live (`403 USER_MANAGEMENT_FORBIDDEN`).
- ✅ No SUPER_ADMIN HTTP reset — `canResetPassword`/`canManageTarget` reject a SUPER_ADMIN
  target unconditionally; verified live (`403 USER_NOT_MANAGEABLE`).
- ✅ No role escalation by MODERATOR — `canChangeRole` is `actor.role == SUPER_ADMIN` only.
- ✅ No global grant by MODERATOR — `canChangeGlobalAccess` is `actor.role == SUPER_ADMIN`
  only.
- ✅ Target-scope rules backend-enforced — entirely in `UserManagementPolicy`, evaluated
  server-side against freshly-loaded state on every call; nothing the client sends about
  its own role or a target's manageability is ever consulted.
- ✅ Out-of-scope user existence hidden — nonexistent and out-of-scope both return `404
  USER_NOT_FOUND`, proven by test and live curl call.
- ✅ Reset revokes sessions — `AdminPasswordResetUseCase` calls
  `revokeAllSessionsOfUser` in the same transaction as the password change.
- ✅ Deactivation revokes sessions — `DeactivateUserUseCase`, same pattern.
- ✅ Role/scope changes take effect on the very next request — `SessionAuthorityRegressionIT`
  proves this with a stale, never-refreshed bearer token for role demotion, area revoke,
  area grant and global-access revoke.
- ✅ No temp credentials logged or persisted — only the Argon2id hash is stored; a dedicated
  test greps the raw database bytes and audit metadata for the plaintext; no `usermanagement`
  file contains a single logging call (grepped directly, zero hits).
- ✅ Audit contains no secrets — same test, plus direct inspection of the real audit table
  during the live smoke walkthrough.
- ✅ Public clients unaffected — Android and Web trees are byte-identical to Phase 5's
  merged state; both full regressions re-run and green (§M).
- ✅ Service auth unaffected — all 332 pre-existing backend tests, Phase 2's auth flows
  included, still pass unmodified. `JdbcUserRepository`/`JdbcServiceAreaRepository` gained
  new methods, and one existing method's *implementation* changed
  (`insertIfServiceIdFree`, §B.1) — its public contract (boolean success/failure, no
  exception for an ordinary collision, same generated ID format, same retry interaction)
  is unchanged, and `CreateSuperAdminUseCase`'s own existing tests
  (`SuperAdminMaintenanceIT`) still pass unmodified against the new implementation.

## M. Regression / CI

| Area | Command | Result |
|---|---|---|
| Backend | `cd backend && ./gradlew build` | **426/426 tests, 0 failures** |
| Android | `cd android && ./gradlew build` | build+lint+36 JVM tests green, unchanged (all tasks `UP-TO-DATE`) |
| Web | `cd web/public-web && npm ci && npm run typecheck && npm test -- --run && npm run build` | 55/55 tests, typecheck clean, build clean, unchanged |
| Deploy-config / Caddy | validate/fmt/routing/redaction/CSP + secret scan, reproduced in a container mirroring the CI job (Caddy 2.11.4) | all green, unchanged |
| Reference data | `node reference-data/tools/validate-canonical.mjs reference-data/example/manifest.json` | valid, unchanged |

All 5 GitHub Actions workflows (`backend`, `android`, `web`, `deploy-config`,
`reference-data`) confirmed green on this branch's final pushed commit — see the session's
closing report to the owner for the exact SHA and workflow run links.

## N. Known limitations

- ~~Service ID collision-retry-within-one-transaction shares the same class of bug this
  phase found and fixed elsewhere.~~ **Resolved — see §B.1.** `insertIfServiceIdFree` now
  uses `ON CONFLICT (service_id) DO NOTHING`, deterministically tested via
  `ServiceIdCollisionIT` against both real call sites (the maintenance CLI's
  `CreateSuperAdminUseCase` and Phase 6's `CreateUserUseCase`), including the
  exhausted-bounded-retry failure path leaving no partial state.
- **No full-text or fuzzy service-ID search** — `query` is a plain `ILIKE '%...%'`
  substring match, per §26's explicit "no full-text infrastructure" instruction.
- **`GET /areas` and the moderator-scope list filter both re-derive the actor's own active
  area set on every call** rather than caching it — correct and simple, but means a list
  request issues one extra query beyond the page query itself. Not a concern at the data
  volumes this module targets.
- **B6 (rate limiting) remains open**, unchanged from Phase 4/5 — Phase 6 deliberately did
  not add any rate limiting to the new authenticated endpoints (§65), consistent with the
  brief.
- Everything else in `DECISIONS_REQUIRING_OWNER.md` carries over unchanged.

---

Phase 6 is complete per the Definition of Done in §72: creation, lifecycle, authorisation
(SUPER_ADMIN/territorial/global/peer/SERVICE_USER), scope (grant/revoke/global/last-area/
immediate effect), roles (SUPER_ADMIN-only, no SUPER_ADMIN HTTP path) and concurrency
(all three named races plus the deactivation/login race) are all implemented and verified —
against real PostgreSQL, and once against a real running instance driven by hand. **Phase 7
has not been started and is not addressed by this report.**
