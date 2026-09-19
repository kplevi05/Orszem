# Phase 17 Engineering Report — V2.0.0 Final Release

Scope: release freeze, version metadata, final release regression, clean-environment smoke test,
final security/privacy gate, artefact inventory and owner-gate assessment. **No feature, redesign,
architecture change or business-rule change.** No production code changed in this phase; no visible
UI changed, so no owner visual approval was needed.

**Conclusion, up front**

| | |
|---|---|
| Őrszem V2.0.0 **software** | **READY.** Every gate below passed on the exact final code. |
| **Production deployment** | **NOT READY — pending owner gates** (§H). Nothing was deployed. |
| Android release APKs | built, but **unsigned**: not distributable until the owner generates the signing key |

## A. Git / base / branch

| | |
|---|---|
| Phase 16 merge (verified by fetching, not assumed) | PR #20, merge commit `9dfc16a9ab1bb21ed9102e6c91d976389db753e6` on `origin/main` (Phase 16's last commit `c395742` is its ancestor) |
| Branch | `release/v2.0.0`, created from exactly that commit |
| Tags | only `demo-v1.1-final` before this phase; **`v2.0.0` is not created yet** (§K) |
| Untracked owner files | `.claude/`, `dump1.xml`–`dump6.xml` untouched and uncommitted |

Commits (all on top of `9dfc16a`):

| Commit | Change |
|---|---|
| `9fe3631` | `release: set the V2 release version to 2.0.0` (the only code-tree change) |
| (docs) | this report; `CLAUDE.md`/`AGENTS.md` phase status; B6 note in the decision register |

## B. Versioning

Inspected first; the repository matches the Phase 16 finding exactly. There is no second version
source and no release automation.

| Location | Before | After |
|---|---|---|
| `backend/build.gradle.kts` `version` | `2.0.0-dev` | `2.0.0` (JAR manifest `Implementation-Version: 2.0.0`) |
| `android/public-app` `versionName` / `versionCode` | `2.0.0-dev` / `1` | `2.0.0` / `1` |
| `android/service-app` `versionName` / `versionCode` | `2.0.0-dev` / `1` | `2.0.0` / `1` |
| `web/public-web/package.json` + `package-lock.json` (2 lines) | `2.0.0-dev` | `2.0.0` |

- **versionCode stays 1.** `git log -G versionCode` shows it was set once, in the baseline commit.
  These application IDs (`hu.orszembejelento.app`/`.service`) have never been distributed (no
  signing key exists), so `1` is the correct first-release value. No scheme was invented; the
  natural convention is a monotonic integer, +1 per release.
- **`orszem.api.version: v1` is deliberately unchanged.** It is the HTTP contract version
  (`/api/v1`), separate from the product version; CLAUDE.md says V2 does not renumber the API.
- The built artefacts carry the version (§G): APK badging reports `versionName='2.0.0'`.
- No workflow is tag-triggered and no GitHub Release exists. The workflows use plain `push` /
  `pull_request`, so pushing a tag will simply re-run CI on that commit (nothing is published).

## C. Release regression matrix (exact final code, `9fe3631`)

Everything was re-run, with the Gradle build cache **disabled** where results could otherwise be
restored rather than executed.

| Suite | Result |
|---|---|
| Backend `clean build --no-build-cache` | **789 tests, 88 suites, 0 failures, 0 errors, 0 skipped**; all 9 tasks executed |
| Public Android unit | **39 / 0 failures** |
| Service Android unit | **169 / 0 failures** (`AuthRepositoryTest` 22) |
| Public Android instrumented (emulator) | **21 / 0 failures** (run twice, both green) |
| Service Android instrumented (emulator) | **89 / 0 failures** (includes `SessionSurvivesRecreationInstrumentedTest`) — see §D |
| Android lint | Public 0 errors, 11 warnings; Service 0 errors, 3 warnings — see below |
| Android builds | `assembleDebug` and `assembleRelease` for both apps: success |
| Public Web | clean `npm ci` (0 vulnerabilities), `typecheck`, **55 tests / 8 files**, production build |
| Reference data | `validate-canonical` valid |
| Caddy | `caddy validate` valid, `caddy fmt` clean; routing, header-redaction and CSP scripts each exit 0 with 0 `FAIL` lines |
| Backup/restore script tests | executable bit, syntax, no production-shaped hostname, safe missing-argument behaviour: all OK |
| Full restore drill | **PASS**, 25 assertions, no leftover containers |

**Critical regression re-run on this exact code (81 backend tests, 0 failures, 0 skipped):**
`AreaAdminConcurrencyIT` 8, `AuthConcurrencyIT` 7, `ModerationConcurrencyIT` 7,
`ModerationRollbackInvariantIT` 2, `PublicReportConcurrencyIT` 3, `PublicReportIdempotencyIT` 9,
`ReportWorkflowInvariantsIT` 5, `UserManagementConcurrencyIT` 4, `ErrorInformationLeakageIT` 11,
`FrameworkExceptionMappingIT` 6, `NoDefaultCredentialIT` 1, `MassAssignmentHardeningIT` 7,
`SqlInjectionHardeningIT` 6, `PublicSurfaceIT` 4, `SecretRedactionTest` 1; plus the Phase 16
session regression `AuthRepositoryTest` (22, JVM) and `SessionSurvivesRecreationInstrumentedTest` (2, device).

**Public lint 10 → 11 warnings — explained.** All 11 are dependency-update notices (AGP 9.4.1,
Compose BOM 2026.09.00, Room 2.8.5 ×4, navigation-compose 2.10.1, Kotlin plugins 2.4.20) plus the
known `AppBundleLocaleChanges` and one unused string. A newer library release appeared upstream;
this phase changed only `versionName`. Upgrading dependencies during a freeze is out of scope.

**Caching was caught, not assumed.** The first backend build reported `:test FROM-CACHE`, which is
not a run. It was repeated with `--no-build-cache` (789 tests, all tasks executed).

## D. The emulator failure during the Service instrumented run (evidence, not a "flake")

The first Service run reported **61 tests and 1 failure** (89 expected). Evidence that this was the
emulator, not the code:

- the Gradle log shows `Transport endpoint is not connected` and `cmd: Can't find service:
  package` / `activity` — the emulator's system services were gone mid-run;
- the last recorded test (`AuditComposeTest…`) had an **empty** failure body, i.e. the
  instrumentation process was killed;
- the Public suite that followed on the same device passed (21/21); a retry immediately after
  reported a bogus `BUILD SUCCESSFUL in 11s` with no tests run (the device was dead), which was
  discarded.

The emulator was cold-booted (same AVD, same flags). The whole Service suite then passed **89/89
in 2m56s**, versus 10–11 min on the long-running instance. Same code, same tests, different
emulator uptime. The AVD has 2 GB RAM. Working hypothesis: a long-lived 2 GB emulator degrades
and eventually loses its system server; this also fits Phase 16's one `ComposeNotIdleException`
in the same test class. That is a hypothesis; it is not proven. Practical rule recorded: cold-boot
the emulator before the final instrumented run. Instrumented suites are not among the six CI
checks.

## E. Clean-environment smoke test (synthetic data only)

A brand-new PostgreSQL 16 container (0 tables), the freshly built `2.0.0` JAR, the maintenance CLI
and the fictional example dataset. `smoke.mjs`: **24 checks before and 6 after the restore, 0 failures.**

| # | Step | Result |
|---|---|---|
| 1 | Clean DB → Flyway | 6 migrations (V001–V006), 0 failed, health UP |
| 2 | Reference dataset | `reference-import` of `EXAMPLE-1` imported |
| 3 | Public submission | `201`; the response carries no access secret |
| 4 | Public lookup by capability | `RECEIVED` (device-local history rendering is covered by the Public instrumented suite, not by this API script) |
| 5 | Service login / session restoration | login OK; the refresh token yields a working access token; replaying the consumed token is refused |
| 6 | Visible in NEW | yes |
| 7–8 | Claim → Public status | `PROCESSING` |
| 9 | Return, claim again, MODERATOR reassigns | Public status back to `RECEIVED` after the return; reassign OK |
| 10–12 | Close → Archive → Public status | in Archive; `CLOSED` |
| 13 | Assignment history | 3 episodes: `RETURNED`, `REASSIGNED` (by the moderator), `ARCHIVED` — complete |
| 14 | Audit | claim, return, reassign and archive entries present; never contains the capability |
| 15 | Moderation and admin | MODERATOR deletes, only SUPER_ADMIN restores; SUPER_ADMIN renames a service area |
| 16 | Backup + restore | `pg_dump -Fc` (60,772 bytes) → `pg_restore` into an empty DB: row counts match for reports, sessions, audit, users and Flyway history; a backend started on the restored DB re-applied 0 migrations; the report is still `CLOSED`, history and audit intact, the capability still resolves, a wrong capability is still a generic 404, and a session revoked before the backup stays revoked |

Honest note: the first smoke run had 2 failures, both wrong expectations in my script (I expected
two history episodes; the correct record is three, and the area's version field is `adminVersion`).
The data was right. The script was corrected and the **whole flow was re-run on a rebuilt database**,
so the evidence above is one clean run. Step 16 used `pg_dump`/`pg_restore` directly; the
`orszem-backup.sh` / `orszem-restore.sh` scripts themselves are exercised by the full drill (§C).

## F. Final security/privacy release gate

| Requirement | Evidence |
|---|---|
| No default/generated credential logged | 0 occurrences in both backend runtime logs; `NoDefaultCredentialIT` |
| No access/refresh/capability/password secret logged | 0 hits for Bearer/Authorization, JWT-shaped strings, the actual capability, the temporary credential and the smoke password across 71 log lines (two runs); 0 ERROR lines, 0 stack traces |
| Capability not in URL, query or cookie | clients send it only in the `X-Orszem-Report-Access` header (Android `PublicApi`, Web `publicApi.ts`); no `Set-Cookie` on Public responses; Web tests assert nothing reaches `localStorage`/`sessionStorage`; the Caddy redaction script keeps it out of access logs |
| Release Android does not permit cleartext | both **release** merged manifests use a network-security-config with `cleartextTrafficPermitted="false"` and no `usesCleartextTraffic`; the only cleartext allowance is in the *debug* config, for `10.0.2.2`/`localhost`/`127.0.0.1`; release `API_BASE_URL` is `https://api.orszembejelento.hu/` |
| Refresh token KeyStore-protected | `KeystoreCryptoBox`: `AndroidKeyStore` provider, AES/GCM/NoPadding via `KeyGenParameterSpec`; the token file never holds the access token (`AuthRepositoryTest`); `allowBackup="false"` |
| One-time temporary credentials | a second `complete-password-change` with the same credential is refused; after the change the temporary credential no longer logs in |
| Caddy redaction active | `verify-caddy-header-redaction.sh` passes, including its negative control that shows the leak without the rule |
| Public Web CSP valid | `verify-caddy-csp.sh` passes against the real built bundle (`default-src 'self'`, `object-src 'none'`, `frame-ancestors 'none'`, no `unsafe-eval`, Permissions-Policy denies geolocation) |
| No private data via Public endpoints | Public report keys are `reportId, occurredAt, submittedAt, trainIdentifier, settlement, category, eventType, status`; no service ID, assignee, area, audit or moderation data; catalog has none; `PublicSurfaceIT` |
| No stack traces in errors | live bodies are the stable `{code,message,correlationId}` shape; `ErrorInformationLeakageIT` (11) and `FrameworkExceptionMappingIT` (6) |
| Swagger/OpenAPI not reachable at the public edge | `verify-caddy-routing.sh` asserts `/v3/api-docs*` and `/swagger-ui*` are blocked on every host. Spring still serves them on the loopback-only backend (it logs a startup WARN); that is the documented design |

No external paid scanner or service was used; `npm audit` reports 0 vulnerabilities.

**Finding reconfirmed, not new: no rate limit on Public submission (B6).** 60 rapid anonymous
`POST /api/v1/public/reports` calls all returned `201`. This is the documented, still-open owner
decision B6 ("concrete answer needed before any public deployment"); it was carried unchanged since
Phase 4. It is not a defect against the frozen specification and needs product decisions (limits,
CAPTCHA-equivalent, what a throttled client sees), so it was **not** changed here. It is a
production-deployment gate (§H).

## G. Artefact inventory

Built today without violating any owner gate. **No binary is committed**; the repository has no
release-artefact convention and none was invented.

| Artefact | Path (build output) | Size | SHA-256 |
|---|---|---|---|
| Backend JAR | `backend/build/libs/backend.jar` (`Implementation-Version: 2.0.0`) | 55,005,713 B | `0f9d82dc…24507fe` (full: `0f9d82dcc5c7355c2e33f4f415c8c5aa948326b45b9166454ed53301b24507fe`) |
| Public Web production build | `web/public-web/dist/` (`index.html`, `assets/index-CMdx9-mU.js` 261,747 B, `assets/index-DpYcTIPN.css`, manifest, icons; source map is emitted) | ~1.66 MB incl. map | JS `1ab5e975…` |
| Public Android release | `android/public-app/build/outputs/apk/release/public-app-release-unsigned.apk` (`hu.orszembejelento.app`, `2.0.0`, code 1) | 1,983,760 B | `6acf141b…927d58` |
| Service Android release | `android/service-app/build/outputs/apk/release/service-app-release-unsigned.apk` (`hu.orszembejelento.service`, `2.0.0`, code 1) | 2,081,195 B | `1ff52215…f01bcaa` |
| Reference data | `reference-data/example/` (fictional, `EXAMPLE-1`, validated). The real VPE-derived dataset is **not** in the repository | — | — |
| Deployment config/scripts | `deploy/caddy/Caddyfile`, `deploy/systemd/orszem-backend.service`, `deploy/env/backend.env.example`, `scripts/*` | — | — |

**The Android release APKs are UNSIGNED** (`apksigner verify`: `DOES NOT VERIFY`, no signature
entries). They are build products that prove the release build works; they are **not**
production-distributable and must not be described as such. Production signing needs the owner's
key (§H). The debug APKs are debug-signed and are test builds only. The web source map (1.3 MB)
is a build output; whether to serve it publicly is a deployment choice.

## H. Owner gates — software release vs production deployment

**Software release readiness (V2.0.0): READY.** Code, tests, smoke, security gate and artefacts
are complete and verified; the version is set.

**Production deployment readiness: NOT READY.** None of these was performed, and none may be done
by this session:

1. Verified V1 `pg_dump -Fc` backup and a verified restore (`docs/deployment/V1_DATABASE_ARCHIVE.md`).
2. V1 retirement / service-access plan (`docs/deployment/V1_DECOMMISSION.md`); the V1 public
   exposure and its published demo credential remain until then.
3. Production DNS and HTTPS (`docs/deployment/DNS.md`); it was undelegated as of Phase 1 and has not been re-checked in this phase.
4. V2 production **signing key** generated and safely backed up (`docs/deployment/ANDROID_SIGNING.md`).
   This is also what makes the Android artefacts distributable.
5. Production configuration and secrets (`/etc/orszem/backend.env`, `deploy/env/backend.env.example`),
   the first production SUPER_ADMIN via the maintenance CLI, and production-host access.
6. **B6 — a Public submission rate-limit / abuse-defence decision** before exposing the endpoint (§F).
7. **The production reference dataset:** the repository ships only the fictional example. The real
   VPE-derived dataset stays local and its reuse is still unconfirmed (B9); it must be imported
   through the maintenance CLI once the owner is satisfied. Without it a production instance routes
   nothing.
8. Open product questions that were never decided and interact with go-live: B5 data retention
   (also settles the lifetime of the report capability), and B3/B4/B7/B8 as recorded.
9. If the Public app is ever shipped as an App Bundle: disable language splits (Phase 15 §Z).

Decided and closed: **B11** (owner, Phase 16) — a lost/ambiguous refresh response may require a new
sign-in; not blindly retried; a V2.0.0 tradeoff.

## I. Known limitations

- Release APKs are unsigned (§G).
- No submission rate limit (B6). No production reference data in the repository (B9).
- Android instrumented suites run on one long-lived 2 GB emulator and are not CI checks; the
  emulator can degrade over time (§D). No physical-device testing was done.
- Public "local history" was verified by the instrumented suite, not by the smoke script (which is API-level).
- Caddy scripts were run inside the `caddy:2-alpine` container because no `caddy` binary is
  installed locally; CI runs them natively.
- 11 lint warnings on the Public app are dependency-update notices (§C).
- The web source map is part of the production build output.
- `deploy/caddy/Caddyfile;C` is an empty, untracked directory (a Windows bind-mount artefact from
  an earlier phase); Git ignores it and it was left in place.
- V002 was edited in place once (Phase 3C) before any deployment; V001–V006 are immutable from now on.

## J. CI

Recorded for the exact final HEAD in the pull request and in the conclusion message, because a
commit cannot contain its own CI result. The post-merge closure update (§K) records the merge
commit's CI and the tag SHA here.

## K. RC tag decision and the final tag

**No `v2.0.0-rc.1` tag was created.** Phase 16 was the validated release candidate: its branch had
all six checks green and it was reviewed and merged as PR #20 (`9dfc16a`). A tag now would only
name a commit that is about to be superseded by `v2.0.0`; no consumer, workflow or artefact
depends on an RC tag, and there is no release workflow to feed. It would be ceremony, not evidence.

**Final tag (not yet created):** `v2.0.0`, annotated (matching the existing annotated
`demo-v1.1-final` convention), created only after the owner confirms the Phase 17 PR is merged and
after re-verifying the merge commit, its `2.0.0` version state and its CI. No GitHub Release
mechanism exists and none will be invented.

| Closure fields (filled after the tag) | |
|---|---|
| Phase 17 merge commit | _pending owner merge_ |
| `v2.0.0` tag object / target SHA | _pending_ |
| CI on the merge commit | _pending_ |
