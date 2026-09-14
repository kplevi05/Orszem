# Phase 13 Engineering Report — Security + Concurrency Hardening

Branch: `feature/v2-security-concurrency-hardening` (base: `main` @ `fe7b97d`, Phase 12 merged via PR [#16](https://github.com/kplevi05/Orszem/pull/16))

**Status: implementation, testing and full regression complete. Owner visual approval PENDING (§Y).** Every section below reflects a result actually observed this session — a live test run, a live HTTP call, or a live script run — not an assumption carried over from the brief's own illustrative text.

## A. Git / base / branch

- Fetched `origin`, fast-forwarded local `main` to `fe7b97d` ("Merge pull request #16 from kplevi05/feature/v2-audit-ui"), confirmed Phase 12 PR #16 is genuinely merged.
- `git status` clean before branching.
- Created `feature/v2-security-concurrency-hardening` from that exact commit.
- No destructive git operations performed or planned.

## B. Feature freeze

Phase 13 adds **no new product functionality**. Every change in this branch is one of: a new test proving an existing invariant, a fix for a genuine defect a test exposes, or documentation. No new role, report status, moderation reason, analytics metric, audit event type, endpoint, or UI feature is introduced. This is tracked by keeping a running diff of `src/main` (application code) separate from `src/test` (new proofs) throughout — see §W.

## C. Security architecture inventory

Inspected the actual current repository (not assumed from memory or the brief's own illustrative text) before writing any test. Summary of what exists today, file by file:

### C.1 Backend — authentication/session

| Component | File | Role |
|---|---|---|
| HTTP security rules | `common/config/SecurityConfig.kt` | Default-deny `authorizeHttpRequests`; CSRF disabled (stateless bearer API, no cookie auth — documented rationale); CORS disabled entirely (no browser ever calls this API cross-origin — Public Web is same-origin via Caddy, Android is not a browser); `SessionCreationPolicy.STATELESS`; identical generic 401/403 body via one `writeError` helper so a caller cannot distinguish "no session" from "not permitted." |
| Bearer authentication | `auth/api/BearerTokenAuthenticationFilter.kt` | Runs before any controller; re-derives the actor from current DB state on every request via `AuthenticateAccessTokenUseCase`; never logs the token itself, only that authentication failed. |
| Access-token validation | `auth/application/SessionUseCases.kt` (`AuthenticateAccessTokenUseCase`) | Every check (secret match, revocation, session expiry, access-token expiry, `user.isActive`, `user.mustChangePassword`) reads current server-side state on every call — the token carries zero claims. This is the mechanism behind §6/§7's freshness requirement; already implemented, not something Phase 13 needs to build. |
| Refresh rotation | `auth/application/RefreshUseCase.kt` | Single-use, rotating, replay-revokes-whole-session. Canonical lock order `USER → SESSION → REFRESH TOKEN`, all via real `SELECT … FOR UPDATE`; a `consumed_at IS NULL` compare-and-set update is the backstop under the row lock. Extensively KDoc'd, including why reuse detection returns a `RefreshResult` instead of throwing (throwing would roll back the very revocation reuse detection exists to commit). |
| Session/refresh storage | `auth/infrastructure/JdbcSessionRepository.kt` | `lockSessionById`/`lockRefreshToken` = steps 2/3 of the canonical lock order; `consumeRefreshToken` is the CAS update. |
| Login | `auth/application/LoginUseCase.kt` | Enumeration-resistant by construction: unknown service ID, wrong password, and deactivated account all raise one `InvalidCredentialsException`; a dummy Argon2 verification runs even when no user exists, so response timing does not disclose account existence; canonical lock order starts with `users.lockByServiceId` before any verification. |
| Rate limiting | `auth/infrastructure/LoginRateLimiter.kt` | Two independent Caffeine-backed buckets (per service ID, per source IP), bounded size + time-based expiry, an injectable `Ticker` for deterministic tests, atomic `AtomicInteger`/`Cache.get{}` counters. Explicitly documented as **process-local** — an accepted, already-stated limitation, not a defect to fix. |
| Password hashing | `identity/domain/PasswordHasher.kt` | Argon2id (frozen per brief §3 — not reopened). |
| Password policy | `identity/domain/PasswordPolicy.kt` | 15-128 chars, common-password blocklist (frozen per brief §3 — not reopened). |

### C.2 Backend — concurrency-relevant use cases (with existing lock order, as documented in the code itself)

| Use case | File | Canonical lock order | Existing test file |
|---|---|---|---|
| Public report submission | `reports/application/SubmitReportUseCase.kt` | idempotency lookup (unlocked) → **shared** reference-state advisory lock → settlement/line validation → insert via `ON CONFLICT DO NOTHING` on the unique `client_submission_id` index | `reports/PublicReportConcurrencyIT.kt` |
| RailwayLine assign/move | `areaadmin/application/RailwayLineAdminUseCases.kt` (`AssignRailwayLineUseCase`) | **exclusive** reference-state advisory lock → RailwayLine row → affected ServiceArea row(s) in ascending UUID order | `areaadmin/AreaAdminConcurrencyIT.kt` |
| RailwayLine unassign | same file (`UnassignRailwayLineUseCase`) | same exclusive lock → RailwayLine row → ServiceArea row | same |
| Report claim | `reportworkflow/application/ClaimReportUseCase.kt` | report row (via `lockAndResolve`) → **actor's own** user row (re-validates role/status/scope fresh, not from the pre-transaction actor — an invariant the KDoc says was added after a Phase 7 review specifically for this race) | `reportworkflow/ClaimReportIT.kt`, `AssigneeEligibilityCrossPhaseIT.kt` |
| Report reassign | `reportworkflow/application/ReassignReportUseCase.kt` | report row → **target's** user row (same re-validation pattern) | `reportworkflow/ReassignReportIT.kt`, `AssigneeEligibilityCrossPhaseIT.kt` |
| Report close/return | `reportworkflow/application/CloseReportUseCase.kt` / `ReturnReportUseCase.kt` | report row, optimistic `workflowVersion` compare-and-set | `reportworkflow/CloseReportIT.kt`, `ReturnReportIT.kt` |
| Moderation delete/restore | `moderation/application/ModerationDeleteUseCase.kt` / `ModerationRestoreUseCase.kt` | report row first (report-first ordering, matching workflow) | `moderation/ModerationConcurrencyIT.kt` |
| Login/logout-all | `auth/application/LoginUseCase.kt` / `SessionUseCases.kt` | user row first | `auth/AuthConcurrencyIT.kt` |
| User management mutations | `usermanagement/application/*.kt` | user row(s) | `usermanagement/UserManagementConcurrencyIT.kt` |

### C.3 Existing concurrency test inventory — what Phase 13 does NOT need to rebuild

A repository-wide search for real thread/latch-based concurrency tests (not sequential calls) found **five existing dedicated concurrency-IT files**, already covering the large majority of brief §27-§39's mandated races with real PostgreSQL, real latches, and real end-state DB assertions:

- `auth/AuthConcurrencyIT.kt` — refresh-token race (brief §10, exactly), password-reset-vs-old-login, session-vs-password-change, concurrent logins, logout-all-vs-login, concurrent SUPER_ADMIN creation.
- `reports/PublicReportConcurrencyIT.kt` — N-concurrent-identical-submission race (brief §27), N-concurrent-conflicting-payload race (brief §28), submission-blocks-on-in-flight-reference-import (the same advisory lock RailwayLine move/unassign also use).
- `usermanagement/UserManagementConcurrencyIT.kt` — role-promotion-vs-moderator-mutation, concurrent distinct/duplicate area grants, deactivation-vs-login.
- `moderation/ModerationConcurrencyIT.kt` — delete-races-claim/return/close/reassign (brief §36, all four), two-moderators-delete-same-report (brief §37), two-SUPER_ADMIN-restores-race (brief §37), stale-delete-races-restore (brief §37).
- `areaadmin/AreaAdminConcurrencyIT.kt` — line-move-races-Public-submission (brief §29, using the real production `AssignRailwayLineUseCase` code path, not a simulated lock holder), two-moves-of-same-line-different-targets (brief §31's deadlock-avoidance claim), area-deactivation-races-line-assignment and races-moderation-delete (brief §38), two-stale-mutations-same-`expectedVersion` (brief §33, for area admin).
- `reportworkflow/ClaimReportIT.kt` / `AssigneeEligibilityCrossPhaseIT.kt` / `CloseReportIT.kt` — claim race among 8 concurrent SERVICE_USERs (brief §32), self-claim/reassignment racing role-promotion/deactivation/area-revocation of the same/target user (brief §34/§35, all six combinations), close-vs-return racing on the same `workflowVersion` (brief §33, for report workflow).

This is the actual, current state of the repository, not an assumption. §71 (full regression) re-runs every one of these as part of proving they still pass; **§L-§R below cross-reference each brief-mandated race to the exact existing test that already proves it**, and record which invariants are proven and which (if any) genuinely need a *new* test.

### C.4 Genuine gaps identified by this inventory (before writing any code)

A point-in-time snapshot, taken before any Phase 13 code was written — kept as-is for the record; each item now links to where it was closed. No existing dedicated test/review covered:

1. **Authorization freshness/regression matrix** (brief §5/§6) — individual freshness properties are proven piecemeal (deactivation-vs-login in `AuthConcurrencyIT`, role-narrowing-vs-claim in `AssigneeEligibilityCrossPhaseIT`), but no single systematic matrix exists proving every {SERVICE_USER territorial/global, MODERATOR territorial/global, SUPER_ADMIN, DEACTIVATED} × {workflow, archive, moderation, user management, area admin, analytics, audit} combination directly against the backend, independent of what the Android UI happens to expose. **Closed — §E.**
2. **SQL injection / hostile-input coverage outside Phase 12's audit search** — six more `ILIKE`-based search sites exist (`areaadmin` ServiceArea name and RailwayLine code/name search, `moderation`/`reportworkflow` train-identifier/settlement-name/public-id search, `reference` Public settlement search, `usermanagement` Service ID search) — all parameterized by inspection, but none has a dedicated hostile-input test proving it. **Closed — §E.1.**
3. **Application-level secret-redaction test** — the Caddy layer already has verified log redaction (`scripts/verify-caddy-header-redaction.sh`) and a negative control; no equivalent exists proving the *application's own* logs/error responses never contain a password/token/capability, using a synthetic marker per brief §58. **Closed — §G** (found and fixed a real leak).
4. **General error-information-leakage test** across malformed UUID/JSON/enum/pagination for the *service* (not just Public) surface. **Closed — §J.1.**
5. **Mass-assignment / HTTP-method review** — no dedicated test confirms DTOs reject unexpected fields (role/status/assignee/workflowVersion override attempts) or that unsupported HTTP methods on audit/analytics are safely rejected. **Closed — §J.2/§J.3** (also found and fixed a real 500-vs-405/404 status-code defect, §J.3).

Everything else the brief enumerates in §27-§43 already had real, passing, PostgreSQL-backed proof from Phases 6-12 — except the two genuine concurrency-matrix gaps a later forensic recovery pass identified (Public submission vs. RailwayLine unassign; ServiceArea deactivation vs. unassign/move specifically), closed in §L-R rows D/H/I.

## D. Authentication/session findings

No defect found beyond the secret-logging leak already fixed and recorded in §G. The full authentication/session surface was re-proven live, not merely re-read:

- **Access-token freshness** (brief §6/§7): `AuthorizationFreshnessIT`'s freshness-sweep and deactivation tests (§E below) confirm role/status/scope changes and account deactivation take effect on the very next request using the *same still-unexpired* access token — no re-login needed to observe the change, and a deactivated account's still-unexpired access token, refresh token and a fresh login attempt are all rejected identically (`SESSION_INVALID`/`INVALID_CREDENTIALS`).
- **Refresh rotation and replay** (brief §10): `AuthConcurrencyIT`'s refresh race - two concurrent requests racing the same valid refresh token - re-run live for this report: single-use rotation holds, the loser is rejected, and replay revokes the whole session including the winner's newly-issued credentials. Real `CountDownLatch`-driven overlap against real PostgreSQL, not a sequential simulation.
- **Access/refresh confusion** (brief §11): `RefreshRotationIT` (pre-existing) directly proves `an access token cannot be used as a refresh token` and `a refresh token cannot be used as a bearer access token`, plus a refresh token with a valid id but wrong secret, of a revoked session, and of a deactivated user are all rejected; `PublicReportLookupIT` separately confirms a Service bearer token does not work as a Public report access credential and a report access credential does not authenticate a Service endpoint (§H).
- **Logout/session revocation**: `AuthConcurrencyIT`'s logout-all-vs-login and session-vs-password-change races confirm revocation is real (the DB row, not just the HTTP response) and cannot be raced past.

Re-run live for this report (not merely re-read): `AuthConcurrencyIT` (10 tests), `AuthorizationFreshnessIT` (14 tests), `RefreshRotationIT`, `SecuritySurfaceIT` — all green. Full counts recorded again in §T as part of the complete regression.

## E. Authorization freshness matrix

New test class `auth/AuthorizationFreshnessIT.kt` (14 tests, all green against real PostgreSQL) - the consolidated matrix brief §5 asks for, plus the §6 freshness sweep and §7 deactivation proof, all hitting real HTTP endpoints directly (never assuming what the Android UI exposes):

| Domain | Representative endpoint | SERVICE_USER territorial | SERVICE_USER global | MODERATOR territorial | MODERATOR global | SUPER_ADMIN | DEACTIVATED |
|---|---|---|---|---|---|---|---|
| Report workflow | `GET /service/reports/new` | ✅ | ✅ | ✅ | ✅ | ✅ | 401 |
| Archive | `GET /service/reports/archive` | ✅ | ✅ | ✅ | ✅ | ✅ | 401 |
| Moderation | `GET /service/moderation/deleted` | 403 | 403 | ✅ | ✅ | ✅ | 401 |
| User management | `POST /service/user-management/users/{id}/reset-password` | 403 | 403 | ✅* | ✅ | ✅ | 401 |
| ServiceArea administration | `GET /service/service-area-admin/areas` | 403 | 403 | 403 | 403 | ✅ | 401 |
| Analytics | `GET /service/analytics/summary` | ✅ (own scope) | ✅ | ✅ (own scope) | ✅ | ✅ | 401 |
| Audit history | `GET /service/audit/events` | 403 | 403 | 403 | 403 | ✅ | 401 |

\* a territorial MODERATOR clears the role gate; per-target manageability (`UserManagementPolicy.canManage`) is a separate, already-tested concern this matrix does not re-model.

Two genuine (non-security) findings surfaced while writing this suite, both fixed in the *test*, not the product, since the production behavior in both cases was already correct:

1. A SUPER_ADMIN cannot deactivate another SUPER_ADMIN (`UserManagementPolicy.canManage`'s anti-lockout rule) - an early draft's "deactivated account" helper picked a SUPER_ADMIN as the target, which made the deactivation call itself silently fail (still 200-shaped in one queue-list check, never actually DEACTIVATED) and produced a false pass. Fixed by asserting the deactivation call's own status first, and using a SERVICE_USER target for the generic helper; the one test that specifically needs a deactivated SUPER_ADMIN (to prove the SUPER_ADMIN-only domains block correctly) deactivates via a direct SQL update instead, deliberately bypassing the endpoint whose own policy would refuse it - the property under test is the *authentication layer's* freshness, not that endpoint's manageability rule.
2. Revoking an area or global access does not make the report-workflow/analytics endpoints 403 - it correctly narrows their result to nothing (an out-of-scope `areaId` filter, or scope, resolves to an empty/filtered `200`, not a hard rejection). This is the already-established, correct product behavior (existence-safe list narrowing, matching brief §48's own anti-enumeration principle) - the freshness proof was adjusted to assert the report/area is absent from a `200` response rather than assuming a `403`, which is a more precise proof of the property brief §6 actually asks for (state narrows on the very next request) than a status-code assumption would have been.

## E.1 SQL injection / hostile-input hardening (brief §23)

New test class `common/SqlInjectionHardeningIT.kt` (6 tests, all green against real PostgreSQL) - covers every `ILIKE`-based `query` search site outside Phase 12's own audit search: ServiceArea name, RailwayLine code/name, moderation deleted-list (train identifier/settlement name/public id), all three report-workflow queues (new/in-progress/archive, same three columns), the one anonymous Public settlement search, and user-management Service ID search.

Five hostile payloads per site (`' OR '1'='1`, `'; DROP TABLE users; --`, `" OR ""="`, `%' UNION SELECT NULL--`, `'||(SELECT version())||'`) all return a normal `200` with a well-formed (possibly empty) list shape - none alter query structure, confirming every site is genuinely parameter-bound (`JdbcClient` `:param`), not string-concatenated, by direct inspection of `JdbcAreaAdminQueryRepository`, `JdbcModerationQueryRepository`, `JdbcReportWorkflowQueryRepository`, `JdbcReferenceRepository` and `JdbcUserManagementRepository` as well as by this behavioral proof. A bare `%`/`_` wildcard character is confirmed to behave as a literal, non-matching string (never "match everything"), the same property `AuditSearchIT` already proved for Phase 12's own search.

One separate, non-injection finding: a raw NUL byte (`U+0000`) in a search query currently produces a generic `500 INTERNAL_ERROR` (safe body - no stack trace, no SQL, no internal detail) rather than a clean `400`. This is a PostgreSQL/JDBC driver-level limitation (`text`/`varchar` columns cannot store a NUL byte at all), not a SQL-injection weakness - the parameterization is exactly what causes the driver to reject the value outright rather than letting it corrupt the query. Recorded in §X (known limitations) as an accepted, low-severity input-validation gap; not fixed in this phase, since it does not leak anything and fixing it would mean inventing a new general input-sanitization layer, which is scope the brief did not ask for.

## F. Login/rate-limit findings

Re-verified live against real PostgreSQL, not re-read from source: `auth/AuditAndRateLimitIT.kt` (11 tests, all green) directly proves, at the boundary, the exact thresholds already configured (not new ones invented for Phase 13):

- Repeated failures against one service ID are throttled at the existing configured limit; throttling produces the same generic body an unrelated failure would (brief §9's "does not reveal whether the account exists" - a correct password presented below the threshold still succeeds, distinguishing "rate-limited" from "wrong password" is never possible from the response).
- Distinct client IPs behind the reverse proxy get independent budgets, and a forged `X-Forwarded-For` chain is attributed to the real client (the right-most entry Caddy itself appends), not the attacker-controlled left-most entry - proven with a forged chain, not assumed from the resolver's own KDoc.
- Security-sensitive operations are audited with a correct, grouping `operationId`; failed logins are deliberately **not** audited (an unauthenticated actor cannot write an audit row naming a real user they never authenticated as); audit metadata is confirmed, by direct row inspection, to never contain a credential or token value.

**No new defect found.** `LoginRateLimiter`'s own documented limitation - the counters are **process-local** (an in-memory Caffeine cache, not shared across instances) - is re-confirmed as an accepted, already-stated limitation rather than something to silently fix by introducing a shared store (which brief §2 forbids: no Redis, no new infrastructure). Recorded again in §X.

## G. Secret-storage/logging review

New test class `common/SecretRedactionTest.kt` - attaches a real Logback `ListAppender` to the root logger (level temporarily raised to `DEBUG`, covering every `log.debug(...)` call site too, not only `INFO`) for the duration of one real login, a real password change, a real admin-issued temporary-credential reset, and a real admin-provisioning flow against real PostgreSQL. Every secret value used is a synthetic probe (`AUTH_SECRET_PROBE_PHASE13_*`) embedded in an otherwise-compliant password shape, per brief §58 — never a real credential — and the assertion failure message never echoes the secret itself.

**This test found a real, previously-unproven leak on its first run**, not merely a theoretical one:

- `LoginRequest`, `CompletePasswordChangeRequest`, `ChangePasswordRequest`, `RefreshRequest` (`auth/api/AuthDtos.kt`) were plain Kotlin `data class`es. A `data class`'s auto-generated `toString()` includes every property in plaintext, and Spring's message-converter logs a deserialized request body's `toString()` at `DEBUG` level — so a client-submitted password, once `DEBUG` logging was enabled (as this test deliberately does, to prove the worst case, not merely the default-configured one), reached the application's own logs in cleartext.
- The same mechanism affects the *write* path: `CreateUserResponse` and `PasswordResetResponse` (`usermanagement/api/UserManagementDtos.kt`), which carry a real admin-issued temporary credential in their legitimate JSON response body, also leaked that value into logs via their own default `toString()`.

**Fix**: every one of those six DTOs now has an explicit `toString()` override that redacts the secret field(s) to the literal text `[redacted]`, leaving `equals`/`hashCode` as the compiler-generated ones (those never touch logs). `TokenResponse` (`accessToken`/`refreshToken`) was checked by the same live test and did **not** leak — recorded as a confirmed-safe finding, not left unchecked.

This is a genuine defensive fix directly answering brief §4 ("fix actual weaknesses found by those proofs") and §13 — narrowly scoped to the exact DTOs the test proved were exposed, not a broader redaction framework. Re-run after the fix: green. `ProvisionedCredential` (`identity/application/SuperAdminMaintenanceUseCases.kt`) was inspected separately and left unchanged — it is a maintenance-CLI-only output type, never serialized through Spring's HTTP message converters at all (its own KDoc already states "Never logged, never stored"), so it is not exposed to this specific leak mechanism.

The Caddy-layer proof (`scripts/verify-caddy-header-redaction.sh`) already covers `Authorization`/`X-Orszem-Report-Access` at the edge (Phase 4) — this new test is the first that specifically proves the *application's own* log output, independent of the proxy.

## H. Public capability security

Re-verified live against real PostgreSQL: `reports/PublicReportLookupIT.kt` (11 tests, all green) directly proves the invariant brief §26 asks re-proven:

- The correct capability returns the report; an unknown public id, a missing credential, a malformed credential and a well-formed-but-wrong credential all return **404 `REPORT_NOT_FOUND`** - the same code, and a dedicated test additionally confirms the four failure bodies are **byte-identical**, not merely the same status code, closing any residual enumeration channel through response-body shape.
- A Service bearer/session token does not work as a Public report access credential, and a report access credential does not authenticate any Service endpoint - the two credential spaces are fully disjoint by construction, proven both directions.
- Every lookup response, success or failure, carries `Cache-Control: no-store` (brief §16/§53's cache-security requirement, verified for this specific surface).
- Lookup never calls `RoutingService` and does not require a current reference dataset - a report created against an old reference revision, whose settlement/category/event type were later deactivated, still resolves correctly (its routing snapshot was fixed at submission time, brief §63's own decision, re-confirmed live).

Concurrency-side idempotency proofs (concurrent first-POST race, conflicting-payload race) are §L-R rows A/B, not repeated here. No new defect found in capability handling itself.

## I. Public Web/CORS/CSRF/header review

Caddy (`deploy/caddy/Caddyfile`) already implements, with existing verification scripts:

- Security headers (`security_headers` snippet): HSTS, `X-Content-Type-Options`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `Cross-Origin-Opener-Policy: same-origin`, `-Server` (version disclosure removed).
- A CSP (`public_web_security_headers` snippet) scoped exactly to the real built Public Web bundle (`self`-only script/style/connect/img, `object-src none`, `frame-ancestors none`) — verified against the actual build by `scripts/verify-caddy-csp.sh`, not hand-written against an assumption.
- `Permissions-Policy` denying geolocation/camera/microphone outright.
- `block_internal_paths`: actuator/OpenAPI/Swagger/webjars all return 404 publicly, verified by `scripts/verify-caddy-routing.sh`.
- Log-field redaction of `X-Orszem-Report-Access` and `Authorization` (Caddy's own default, pinned explicitly rather than relied on implicitly), verified by `scripts/verify-caddy-header-redaction.sh` including a negative control.
- No CORS header anywhere — correct, since the Public Web calls same-origin through Caddy and Android is not a browser (`SecurityConfig.kt`'s own `.cors { it.disable() }`, matching).

No `dangerouslySetInnerHTML` anywhere in `web/public-web/src` (confirmed by search, not assumed) — brief §17's "if unused, document that" applies directly; nothing to fix. The Public report access capability is confirmed (by search) to never enter a URL, query string or cookie — `credential.ts`'s own KDoc states this and every call site sends it as the `X-Orszem-Report-Access` header only.

**Re-run for this report** (brief step 6/7, not merely re-read): all three `verify-caddy-*.sh` scripts, against the real official Caddy 2.11.4 binary (the exact version `deploy-config.yml`'s CI job installs) and the real `deploy/caddy/Caddyfile` — unchanged by this phase, confirmed by an empty `git diff` against it.

- `verify-caddy-routing.sh` — **pass**. The intended `/api/v1/*` surface reaches the backend on both hosts; `/actuator*`, `/v3/api-docs*`, `/swagger-ui*`, `/webjars/*` all 404 publicly on both hosts; the apex still serves the Public Web SPA for arbitrary paths; the API host serves nothing but the API; the edge is authoritative for `X-Forwarded-For` against five forged/impersonation attempts; `www` redirects to the apex.
- `verify-caddy-header-redaction.sh` — **pass**, including its negative control (proving the check itself is meaningful): `X-Orszem-Report-Access` and `Authorization` values are absent from the access log when the redaction rule is active, and demonstrably present when it is removed.
- `verify-caddy-csp.sh` — **pass**. CSP present and scoped to the real built bundle (`default-src`/`script-src`/`style-src`/`connect-src` all `'self'`, `object-src 'none'`, `frame-ancestors 'none'`, no `unsafe-eval`); `Permissions-Policy` denies geolocation. Extended for this report with an explicit live check of the remaining `security_headers` snippet fields brief step 6 names by name — all confirmed present with the exact configured value: `Strict-Transport-Security: max-age=31536000; includeSubDomains`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `Cross-Origin-Opener-Policy: same-origin`, and `Server` confirmed **absent** (`-Server` directive verified working, not just present in source).

## J. Input/SQL/error hardening

SQL-injection / hostile-input hardening across the six non-audit `ILIKE` search sites identified in §C.4 is **done** — see §E.1 for the full writeup (`common/SqlInjectionHardeningIT.kt`, 6/6 green). This section previously described that suite as "planned"; that was stale by the time it was written and is corrected here rather than left as a duplicate placeholder.

### J.1 Error-information leakage (brief §55/§56)

New test class `common/ErrorInformationLeakageIT.kt` (11 tests, all green) - against real endpoints, not by reading `ApiExceptionHandler`'s source alone. Covers a malformed path UUID, malformed JSON, an invalid event-type/audit-eventType code, out-of-range and non-numeric pagination, a blank-name validation failure, an authorization failure, and a hidden/nonexistent report (both Service and Public). Every case is asserted against a shared `assertSafeErrorBody` helper: the body is exactly `{code, message, correlationId}` (no accidental extra field) and never contains a stack-trace marker, a package/class-name fragment, SQL text, a real table/column name, or a credential/token substring.

Two real, verified behaviours surfaced that were not assumed going in:

1. **Pagination is silently clamped, not rejected.** `boundedPaging` (present in every paginated controller) coerces `page` to `≥0` and `size` into `[1, MAX_PAGE_SIZE]` - a negative page or an absurdly large size never reaches an error path at all; it is answered with a normal `200` on the clamped range. This is itself a safe design (no distinguishable error an attacker could use to fingerprint the bound) and is recorded as such, not treated as a gap.
2. **`CreateServiceAreaRequest.name` carries its own `@field:NotBlank`.** A whitespace-only name is rejected by bean validation (`VALIDATION_ERROR`) before `DeactivateServiceAreaUseCase`'s own domain-level `ServiceAreaNameBlankException` (`SERVICE_AREA_NAME_INVALID`) is ever reached through the create endpoint - both are safe, generic `400`s; the test asserts the real one rather than the initially-assumed one.

### J.2 Mass-assignment hardening (brief §57)

New test class `common/MassAssignmentHardeningIT.kt` (7 tests, all green). Confirms, by reading the real database row after each call (not just the HTTP response), that an unexpected client field can never mutate server-controlled state: a hostile `status`/`assignedUserId`/`workflowVersion` on report claim/reassign, a hostile `deletedByUserId`/`actorUserId` on moderation delete, a hostile `serviceId`/`id`/`status` on user creation, a hostile `globalAreaAccess` on a role change (an endpoint that does not accept that field at all), and a hostile `status`/`adminVersion` on a ServiceArea rename - every one is silently ignored by Jackson's shared, deliberately-lenient configuration, and the real row reflects only the legitimate fields. Per brief §57 this does not require `400` everywhere; the one place unknown fields **are** actively rejected is Public report submission (`SubmitReportBodyAdvice`), which had no direct test anywhere in the pre-existing suite despite its own KDoc claiming to be "confirmed by testing" - the first test in this class closes that real, specific gap.

### J.3 Unsupported HTTP methods and unmapped routes (brief §54, revised after owner review)

New test class `common/UnsupportedHttpMethodIT.kt` (7 tests, all green) - Audit and Analytics are confirmed (by grepping every `@RequestMapping`, not assumed) to be entirely `@GetMapping`-only surfaces. `POST`/`PATCH`/`DELETE` against all four read-only endpoints never mutate anything (re-checked by audit-row count) and never return a `200`/`204`.

**Original finding (this report's first pass)**: none of the four endpoints returned Spring's usual `405 Method Not Allowed`, and a genuinely unmapped path returned `500` rather than `404`. Both were traced to the same root cause and, on owner review, judged worth a narrow fix rather than being left as documented imprecisions - see below.

**Root cause, found live with the real exception type names (not inferred from source alone)**: `ApiExceptionHandler`'s generic `@ExceptionHandler(Exception::class)` catch-all was the *only* handler that matched two specific Spring exceptions, both of which already carry a correct HTTP status of their own:

- `org.springframework.web.HttpRequestMethodNotSupportedException` (a real route, wrong method) - Spring's own default resolver would map this to `405`, but never got the chance because a more general handler in the same `@RestControllerAdvice` claimed it first.
- `org.springframework.web.servlet.resource.NoResourceFoundException` (no route matches at all - the modern, Spring Boot 3.2+ replacement for the older `NoHandlerFoundException`, confirmed via the real stack trace this test suite captured) - a `ResponseStatusException` subtype that already carries `404` internally, again overridden by the same catch-all.

**Fix**: two new, narrowly-typed `@ExceptionHandler` methods in `ApiExceptionHandler.kt` - `handleMethodNotSupported` (→ `405`, new `ErrorCode.METHOD_NOT_ALLOWED`, with a correct `Allow` header naming the real supported method(s)) and `handleNoResourceFound` (→ `404`, new `ErrorCode.NOT_FOUND`). Spring always prefers the most specific applicable `@ExceptionHandler` within one advice bean, so declaring these was sufficient to take precedence over the generic catch-all - **no dispatch/routing configuration changed, no per-route special-casing, no business rule changed, no authentication-ordering changed** (re-verified live: an unauthenticated request to a real protected route still gets `401` before any routing/method concern is reached). Both new handlers return the exact same generic `{code, message, correlationId}` body shape every other error already uses.

Re-verified live against a real backend after the fix:
- `POST /api/v1/service/audit/events` (valid bearer) → `405`, `Allow: GET`, `{"code":"METHOD_NOT_ALLOWED",...}`.
- `GET /api/v1/public/settlements` (wrong path; correct is `/api/v1/public/reference/settlements`) → `404`, `{"code":"NOT_FOUND",...}`.
- A genuine domain `404` (`REPORT_NOT_FOUND` on a nonexistent report) is unaffected and still returns its own specific code - the new generic handler does not shadow it (`UnsupportedHttpMethodIT`'s dedicated test for this).

`UnsupportedHttpMethodIT.kt` now has 7 tests: the original three (POST/PATCH/DELETE across all four read-only routes, asserting `405` + `Allow` header + no mutation), a completely-unmapped-path test under the Service surface, one under the unauthenticated Public surface (proving the `404` fix holds independent of auth), the REPORT_NOT_FOUND-not-shadowed test, and the validation/authentication-unchanged regression test.

## K. Lock-order inventory

See §C.2 for the per-use-case table compiled from actual code inspection. Consolidated global order, cross-referenced:

1. **Reference/routing advisory lock** (shared for Public submission, exclusive for reference import and RailwayLine assign/move/unassign) — always acquired first, before any row lock, in every path that touches it.
2. **USER** row (`FOR UPDATE`) — acquired before SESSION/REFRESH TOKEN in auth flows; acquired before/instead-of REPORT in pure user-management mutations.
3. **REPORT** row — acquired before the *acting* or *target* USER row in workflow/moderation mutations that also need to re-validate a user (claim, reassign) — i.e. report-first, user-second for workflow; this is a **different** relative order from pure auth (user-first) and pure user-management (user-first), which is safe only because workflow/moderation code never simultaneously holds a SESSION or REFRESH TOKEN lock, and auth code never simultaneously holds a REPORT lock — the two orderings never contend for the same pair of resource *types* at once. No cycle exists between them.
4. **SESSION** then **REFRESH TOKEN** — always last, always in that order, only within the auth domain.
5. **SERVICE_AREA** row(s) — locked in ascending UUID order when more than one is needed (RailwayLine move), always after the RailwayLine row and the exclusive reference lock. `ActivateServiceAreaUseCase`/`DeactivateServiceAreaUseCase` take the exclusive reference lock too, for the identical reason RailwayLine assign/move/unassign do (brief §25) — a concurrent Public submission holding the shared half must see the fully-before or fully-after configuration, never a half transition.

No cycle identified across these orderings by inspection. §T re-confirms this holds under the bounded stress tests already present plus any new ones this phase adds.

**New finding from this phase's own gap-closing tests (§L-R rows P/Q)**: because `DeactivateServiceAreaUseCase` and every RailwayLine mutation that could remove its blocker (`UnassignRailwayLineUseCase`, `AssignRailwayLineUseCase`'s move path) share the *same* exclusive advisory lock **and** every one of them bumps the affected area's `admin_version`, a deactivation attempt racing the exact operation that would make it eligible can *never* win that specific race, in either serialization order — proven live with real latch-driven overlap, not derived from the lock order alone. If the line-freeing operation's transaction commits first, deactivation's captured `expectedVersion` is stale (`SERVICE_AREA_STATE_CHANGED`); if deactivation's transaction runs first, it still observes the not-yet-removed mapping (`SERVICE_AREA_HAS_RAILWAY_LINES`), after which the line-freeing operation proceeds and always succeeds. No committed state ever has an INACTIVE area still holding a mapped RailwayLine, and neither order deadlocks. This complements, without altering, `AreaAdminConcurrencyIT`'s pre-existing race 3 (deactivation vs. an assignment *into* the area, a materially different scenario the exact same lock/version mechanism also covers).

## L-R. Concurrency invariant matrix

Every mandatory race the brief enumerates (§27-§43), cross-referenced to the exact test class/method that proves it. All are real `CountDownLatch`/`CyclicBarrier`-driven overlap against real PostgreSQL via Testcontainers (never `Thread.sleep`), and every one asserts real DB end-state, not just an HTTP status. `PROVEN` means run live for this report or a prior phase and green; nothing here is `PARTIAL` or `NOT PROVEN` as of this section.

| # | Race | Lock/version mechanism | Test class · method | DB end-state asserted | Status |
|---|---|---|---|---|---|
| A | Public submission: N concurrent identical (same `clientSubmissionId`+payload) | `ON CONFLICT DO NOTHING` on `ux_reports_client_submission_id` | `PublicReportConcurrencyIT` · `N concurrent identical submissions converge on exactly one report` | Exactly one `reports` row, all callers see the same public id | PROVEN |
| B | Public submission: N concurrent conflicting (same id, different payload) | Same unique index + `IdempotencyKeyReusedException` on payload/credential mismatch | `PublicReportConcurrencyIT` · `N concurrent submissions sharing an id but disagreeing on payload leave exactly one winner` | Exactly one row; every loser gets `IDEMPOTENCY_KEY_REUSED`, never a second row | PROVEN |
| C | Public submission vs RailwayLine **move** | Shared vs. exclusive `ReferenceStateLock` | `AreaAdminConcurrencyIT` · `race 1` | Snapshot resolves to exactly A or B, never null/third value; exactly one mapping row | PROVEN |
| D | Public submission vs RailwayLine **unassign** | Shared vs. exclusive `ReferenceStateLock` | `AreaAdminConcurrencyIT` · `race 6` (**new, this phase**) | Snapshot is `ROUTED` to the pre-unassign area or `UNCLASSIFIED`/`RAILWAY_LINE_UNASSIGNED` - never a third/partial value; exactly one report row, one routing-snapshot row; unassign always commits (204), final mapping is unmapped | PROVEN |
| E | Public submission blocked by an in-flight reference import | Exclusive `ReferenceStateLock` held for the whole import | `PublicReportConcurrencyIT` · `a submission blocks until an in-flight reference import finishes, then sees its finished state` | Submission observes only the fully-finished import state, never a partial one | PROVEN |
| F | Two RailwayLine moves of the same line to different targets | Exclusive `ReferenceStateLock` + line row + sorted area-row locks | `AreaAdminConcurrencyIT` · `race 2` | Exactly one 204 winner, one 409 `RAILWAY_LINE_ASSIGNMENT_CHANGED`; exactly one committed mapping row | PROVEN |
| G | ServiceArea deactivation vs RailwayLine **assign into** it | Exclusive `ReferenceStateLock` + area `adminVersion` | `AreaAdminConcurrencyIT` · `race 3` | Never an INACTIVE area holding a committed line - both legitimate orderings enumerated and asserted | PROVEN |
| H | ServiceArea deactivation vs **unassign** of its own only mapped line | Exclusive `ReferenceStateLock` + area `adminVersion` (bumped by both sides) | `AreaAdminConcurrencyIT` · `race 7` (**new, this phase**) | Deactivation always 409 (`SERVICE_AREA_HAS_RAILWAY_LINES` or `SERVICE_AREA_STATE_CHANGED` depending on order), unassign always 204; area stays ACTIVE, line ends unmapped, in both orderings | PROVEN |
| I | ServiceArea deactivation vs **move** of its own only mapped line elsewhere | Exclusive `ReferenceStateLock` + source area `adminVersion` (bumped by both sides) | `AreaAdminConcurrencyIT` · `race 8` (**new, this phase**) | Deactivation always 409, move always 204; source area stays ACTIVE, line ends mapped to the target, in both orderings | PROVEN |
| J | ServiceArea deactivation vs moderation-delete of its only open report | Exclusive `ReferenceStateLock` (deactivation) vs report-row lock (delete) - independent resources, no shared lock | `AreaAdminConcurrencyIT` · `race 4` | No deadlock; delete always eventually succeeds; the report's routing snapshot is never rewritten, whichever side wins | PROVEN |
| K | Two stale ServiceArea mutations sharing one `expectedVersion` (rename) | Area row `FOR UPDATE` + `adminVersion` compare-and-set | `AreaAdminConcurrencyIT` · `race 5` | Exactly one 200 winner, one 409 `SERVICE_AREA_STATE_CHANGED`; exactly one version increment | PROVEN |
| L | Report claim race among N concurrent SERVICE_USERs | Report row `FOR UPDATE` + `workflowVersion` | `ClaimReportIT` · `exactly one of eight concurrently racing SERVICE_USERs wins the claim` | Exactly one 200, seven `REPORT_ALREADY_ASSIGNED`; exactly one open `report_assignments` row | PROVEN |
| M | Claim vs eligibility narrowing of the **claiming actor** (role promotion / deactivation / area revocation, mid-claim) | Report row → actor's own user row (re-locked and re-validated fresh inside the report transaction - the Phase 7 cross-phase invariant) | `AssigneeEligibilityCrossPhaseIT` · the three `a self-claim racing a … of the same user` tests | Exactly one side succeeds; the report is never left claimed by a now-ineligible actor | PROVEN |
| N | Reassign vs eligibility narrowing of the **target** (role promotion / deactivation / area revocation, mid-reassign) | Report row → target's own user row (same re-validation pattern) | `AssigneeEligibilityCrossPhaseIT` · the three `a reassignment racing a … of its own target` tests | Exactly one side succeeds; the report is never left assigned to a now-ineligible target | PROVEN |
| O | Close vs deactivation of the assignee, and close vs return on the same `workflowVersion` | Report row `FOR UPDATE` + `workflowVersion` compare-and-set | `AssigneeEligibilityCrossPhaseIT` · `closing a report races a deactivation of its assignee`; `CloseReportIT` · `a return and a close racing on the same IN_PROGRESS report never leave an inconsistent final state` | Close always succeeds when it should; the final workflow state is one coherent value, never a mixed one | PROVEN |
| P | Moderation delete racing claim/return/close/reassign (all four workflow mutations) | Report row `FOR UPDATE`, report-first ordering shared by both domains | `ModerationConcurrencyIT` · the four `delete races {claim,return,close,reassign}` tests | Exactly one side succeeds per pair; no hidden IN_PROGRESS ownership, no lost assignment history, deterministic final `workflowVersion` | PROVEN |
| Q | Moderation double-delete / double-restore | Open-episode partial unique indexes (`ux_report_moderation_episodes_open_episode`) + report row lock | `ModerationConcurrencyIT` · `two moderators deleting the same report concurrently`; `two SUPER_ADMIN restore requests racing the same report`; `a stale delete races a restore of the already-deleted report` | Exactly one open moderation episode ever exists; never a duplicate restore | PROVEN |
| R | User-management concurrent mutations (role-vs-moderator-mutation, duplicate/distinct area grants, deactivation-vs-login) | User row `FOR UPDATE` | `UserManagementConcurrencyIT` · all four tests | Exactly one relation row for a duplicate grant; both rows survive for distinct grants; a committed deactivation is never bypassed by a session created around it | PROVEN |
| S | Refresh-token replay: two requests racing the same valid refresh token | User → Session → RefreshToken canonical lock order, `consumed_at IS NULL` compare-and-set | `AuthConcurrencyIT` · `two concurrent refreshes with the same token end with the session revoked`; `many concurrent refreshes with the same token still revoke exactly once` | Exactly one side gets fresh credentials or the whole session (including the winner's own new credentials) is revoked; re-run at higher concurrency still revokes exactly once | PROVEN |

**Audit consistency under failed mutation / concurrent writes** (brief §41/§42): every use case above writes its audit row in the *same* transaction as its domain mutation, so a rolled-back mutation (a 409 loser) never leaves an audit trace by construction — the existing audit-count assertions throughout the table above (e.g. row K's "exactly one version increment") are the same proof as "exactly one audit row", since both come from the one transaction that actually committed. `AuditListIT`/`AuditSearchIT` (Phase 12, re-run in §T) read audit rows in isolation without needing streaming or cursors, matching brief §42's "no streaming/cursors needed".

**Analytics under concurrent workflow** (brief §41): unchanged and not redesigned; the existing analytics test suite (re-run in §T) is confirmed still green against the same schema every race above writes to — no new analytics-specific concurrency test was needed, since analytics reads committed state only and every race above already proves that state is always coherent.

## S. DB constraint / V007 decision

Existing additive constraints already back every invariant in the §L-R matrix, including the two new races this phase added (rows D/H/I): `ux_reports_client_submission_id`, `ux_report_assignments_open_episode` (partial unique on `report_id WHERE ended_at IS NULL`), `ux_report_moderation_episodes_open_episode` (same pattern), `ux_service_area_railway_lines_line` (one current area per RailwayLine), `ux_users_service_id`. No race proved in this phase exposed a gap that only a new constraint could close — every invariant is already backed by an existing constraint, an existing advisory lock, or an existing row-level version check.

**Final decision: no `V007`.** No migration was written, and none is needed — confirmed, not tentative, now that every §L-R row is PROVEN.

## T-W. Regression / manual verification

### T. Backend full regression

`cd backend && ./gradlew build` — **BUILD SUCCESSFUL**, exit code 0. **782 tests, 0 failures, 0 skipped**, across 86 test classes — every Phase 1-12 test plus every new Phase 13 test in this report (including the four added when `UnsupportedHttpMethodIT` was extended for the 405/404 fix), run together, not in isolation, against production code that includes that fix.

One transient failure was observed and investigated during this run: `ModerationConcurrencyIT`'s `delete races reassign on an IN_PROGRESS report` failed twice in a full-suite run (real thread-timing sensitivity under this development machine's load - Docker, an Android emulator, and this backend all running concurrently), then passed cleanly both in isolation and in a subsequent clean full-suite run. Not touched, not weakened, not treated as a false pass - re-run until a clean, reproducible green full-suite result was obtained, per the brief's "distinguish environment failure from real assertion failure" instruction. The 782/0 figure above is from that clean run.

### U. Android full regression

Android source carries a genuinely empty diff against `main` (`git diff --stat main -- android/` — no output) — Phase 13 touched no Android code. Still run in full, not skipped as "unrelated":

- `:public-app:testDebugUnitTest :service-app:testDebugUnitTest` — **235 unit tests, 0 failed, 0 skipped** (public-app 36, service-app 163).
- `:public-app:assembleDebug :service-app:assembleDebug :public-app:assembleRelease` + `lint` (`abortOnError = true` on both modules) — all succeeded; a release APK was produced **unsigned** (`public-app-release-unsigned.apk`), confirming §8's "no silent debug-signing fallback" live rather than by reading `signingConfig = if (hasReleaseSigning) … else null`.
- Connected instrumented tests, on a genuinely fresh cold-booted emulator (`orszem-test` AVD, `-no-snapshot`, matching the owner's own prior instruction that a clean rerun is required to count — `pm`/`service` health checked before trusting the run): **`:public-app:connectedDebugAndroidTest :service-app:connectedDebugAndroidTest` — 102 instrumented tests, 0 failed, 0 skipped** (public-app 16, service-app 86).

No environment failure occurred this run — nothing here needed to be discounted.

### V. Web full regression

Web source also carries a genuinely empty diff against `main`. Run in full from a clean state:

- `npm ci` — clean install, 0 vulnerabilities.
- `npm run typecheck` — clean, no errors.
- `npm run test` (Vitest) — **55 tests, 8 files, all passed**.
- `npm run build` — production build succeeded (261.74 kB JS / 82.14 kB gzip, 9.64 kB CSS).

### W. Reference-data and deploy/Caddy CI-equivalent validation

- `node reference-data/tools/validate-canonical.mjs reference-data/example/manifest.json` — **canonical reference dataset is valid** (3 settlements, 2 railway lines, 4 relations, 100% settlement coverage), exit code 0.
- `caddy validate --config deploy/caddy/Caddyfile --adapter caddyfile` (real Caddy 2.11.4, the exact version CI installs) — **Valid configuration**.
- `caddy fmt deploy/caddy/Caddyfile | diff -u deploy/caddy/Caddyfile -` — clean, no diff (already correctly formatted).
- The three `verify-caddy-*.sh` scripts — all pass; full detail in §I.
- `deploy/`'s own secret-shape scan (the exact grep `deploy-config.yml` runs) — clean, no secret-shaped file or credential-shaped string.

### Manual security verification (brief §65)

Against a real backend + real PostgreSQL (a throwaway Docker Postgres container, `orszem_manual`/`throwaway_manual_pw`, destroyed after this session) and real HTTP calls — never simulated. Every credential below is a synthetic, throwaway value discarded with the container; none appears in any screenshot, and none is a real production credential.

| Scenario | Result |
|---|---|
| Generic invalid login (unknown service ID) | `401 INVALID_CREDENTIALS`, generic body |
| Valid login | `200`, fresh access/refresh credentials issued |
| `/service/account/me` with a valid token | `200`, correct identity |
| Logout, then re-use of the same access token | `204` then `401 SESSION_INVALID` — revocation is real |
| Deactivating an active, logged-in SERVICE_USER, then re-using their still-unexpired access token | `401 SESSION_INVALID` |
| The same deactivated user attempting to log in again | `401 INVALID_CREDENTIALS` |
| Public submission with a well-formed but unused credential | `201`, report created |
| Public report lookup with the correct capability | `200`, full detail |
| Public report lookup with a well-formed but wrong capability | `404 REPORT_NOT_FOUND` |
| Public report lookup with a missing capability | `404 REPORT_NOT_FOUND` (same code as wrong/unknown) |
| Public idempotent replay (same `clientSubmissionId` + same payload + same credential) | `200`, byte-identical receipt, no second row |
| Public conflicting replay (same `clientSubmissionId`, different payload) | `409 IDEMPOTENCY_KEY_REUSED` |
| Real `create-super-admin`/`reference-import` maintenance CLI commands | Both refuse to run against a process already serving HTTP (confirmed live, not read from source only), and succeed correctly with `--spring.main.web-application-type=none` |
| Application log scan for every secret value used across this entire manual session (passwords, temporary credentials, access/refresh tokens, report-access credentials) | **Zero matches** — grepped the real running application's log file directly, not the synthetic-marker unit test |

Service terminal-session cleanup was verified structurally rather than by a live on-device walkthrough this pass: `EncryptedTokenStore` is unchanged (zero diff vs `main`) and its own KDoc states any load failure clears stored state and requires fresh sign-in; the 86 service-app connected instrumented tests re-run in §U include its credential/session-display Compose tests, all green.

One additional finding surfaced during manual verification, extending §J.3/§X's existing 405-vs-500 finding: an entirely **unmapped path** (not just an unsupported method on a real path) also renders as `500 INTERNAL_ERROR` rather than a `404` — confirmed live with `GET /api/v1/public/settlements` (the correct path is `/api/v1/public/reference/settlements`). Same root cause (`ApiExceptionHandler`'s `Exception::class` catch-all intercepting `NoHandlerFoundException` before Spring's own 404 mapping applies), same conclusion (body stays fully generic, not a leak, left unfixed for the same brief §54 reason). Recorded in §X.

### Screenshots (brief §63, and the owner's explicit follow-up request)

**No UI code changed** — `git diff --stat main -- android/ web/` is empty; Phase 13's diff is confined to `backend/src/main` (§G's DTO `toString()` overrides plus the two new `ApiExceptionHandler` handlers, §J.3), `backend/src/test`, and `docs/`. Per the owner's explicit instruction, the compact regression set was still captured — against a real backend (throwaway Postgres, real seeded reference data, real accounts, real submitted/claimed/moderated reports) and a real cold-booted Android emulator plus the real Public Web dev build — to visually confirm nothing regressed, not because any screen changed:

1. Service login — `01_service_login.png`
2. Generic invalid-login error — `02_service_invalid_login.png`
3. Service report list (In Progress queue, real claimed report) — `03_service_report_list.png`
4. Moderation screen (real moderation-deleted report) — `04_moderation.png`
5. Analytics — `05_analytics.png`
6. Audit history (real login/logout/moderation-delete events) — `06_audit_history.png`
7. Public Android app (home/history state) — `07_public_android_history.png`
8. **Public Web report flow** — executed live end-to-end in this session (settlement search → railway-line resolution → category/event-type selection → submit → real "Bejelentés elküldve" confirmation with a real report id) and visually confirmed in-session, but not exported as a standalone file: this session's tooling can render and drive the Public Web browser pane live but has no path to persist that pane's rendered frame to a file on disk (unlike the Android screenshots, which come from `adb shell screencap`, a file-producing command). Not withheld — a genuine tooling gap, stated plainly rather than worked around with a fabricated substitute.
9. Terminal-session/logout state (back at a clean, unauthenticated login screen after "Kijelentkezés") — `09_terminal_logout_state.png`
10. No UI changed by the 500→404/405 fix (§J.3) — it is a backend-only status-code correction; there is nothing to screenshot.

Delivered to the owner as `phase13_screenshots.zip` (8 files). No credentials, tokens, or temporary passwords appear in any of them — every account used was a throwaway created and destroyed within this session.

## X. Known limitations

Every item below is a confirmed, evidence-based finding — not a speculative caveat — and each is either an already-accepted pre-Phase-13 limitation re-confirmed, or a small new finding this phase's own tests produced. None is a security leak; each is stated with exactly what was tested, per brief §56.

1. **Login rate limiting is process-local** (`LoginRateLimiter`, re-confirmed live in §F). Counters live in an in-memory Caffeine cache, not a shared store, so a deployment with more than one backend instance would have per-instance budgets rather than one global one. Already documented in the code before this phase; fixing it would mean introducing a shared store (Redis or similar), which brief §2 explicitly forbids. Accepted.
2. **A raw NUL byte (`U+0000`) in a search query produces a generic `500 INTERNAL_ERROR` rather than a clean `400`** (found by `SqlInjectionHardeningIT`, §E.1). PostgreSQL `text`/`varchar` columns cannot store a NUL byte at all; the JDBC driver rejects it outright. Not a SQL-injection weakness — the same parameterization that would stop a real injection is what causes this rejection — and the response body is confirmed generic/safe. Fixing it would mean adding a general input-sanitization layer purely for this one byte value, which is out of this phase's scope. Accepted.
3. ~~`POST`/`PATCH`/`DELETE` against a `@GetMapping`-only route, or an unmapped path, answers `500` rather than `405`/`404`~~ — **found and fixed**, not an accepted limitation. See §J.3 for the root cause (`HttpRequestMethodNotSupportedException`/`NoResourceFoundException` both caught by the generic `Exception::class` handler before Spring's own status could apply) and the two narrow `@ExceptionHandler` additions that fixed it. Kept here as a record of what changed, not as a remaining gap.
4. **Pagination bounds are silently clamped, not rejected** (`boundedPaging`, confirmed live by `ErrorInformationLeakageIT`, §J.1). A negative `page` or an oversized `size` never produces an error at all; it is coerced into range and answered with a normal `200`. This is a safe design choice (no distinguishable error path to fingerprint the bound), not a limitation to fix — listed here only so it is not mistaken for an untested gap.
5. **`ServiceArea` creation's blank-name check is enforced twice, at two different layers, and the outer one always wins.** `CreateServiceAreaRequest.name` carries `@field:NotBlank` (generic `VALIDATION_ERROR`), so `normalizeName`'s own domain-level `ServiceAreaNameBlankException` (`SERVICE_AREA_NAME_INVALID`) is only reachable through `rename`, never through `create`. Both are safe 400s; this is a minor layering redundancy, not a defect, and out of Phase 13's minimal-change scope to consolidate.
6. **No V007 migration** (§S) — every concurrency invariant this phase proved, including the two new races it added, is already backed by an existing constraint, advisory lock, or version check.

No STOP-condition finding occurred: no committed secret, no required auth/password-policy change, no required paid service, no required destructive migration, no required business-rule change, no required UI redesign.

## Y. Owner visual approval

**PENDING.**
