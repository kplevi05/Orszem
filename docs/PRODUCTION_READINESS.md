# Production readiness and deployment — V2.0.0

**Status: NOT READY — BLOCKERS REMAIN.** Nothing in this document has been executed against
production. It is the checklist and runbook that closes the remaining gates and then drives a
controlled deployment. It does not repeat the phase reports; it links to them.

| | |
|---|---|
| Software release | `v2.0.0`, annotated tag `da1beeae81ae5fe396d9c53d9a26e20e8e4149d1` → `cc4f67443e2f6baf2dcdd15c90449ba5d173de84`. **Immutable.** |
| `origin/main` when this was written | `ab984bb90adb55ac203a57a6895679b0b6fb3b68` (Phase 17 closure docs merged, PR #22). Only documentation changed after the tag. |
| Production hosts (unchanged) | `https://orszembejelento.hu`, `https://api.orszembejelento.hu`; `www` redirects to the apex |
| Deployment rule | Nothing is deployed, tagged, merged or changed on production infrastructure without a separate, explicit owner instruction |
| **V2.0.1 patch candidate** | Branch `release/v2.0.1`: B6 rate limit, the Public 429 message and the KSH attribution (owner-approved). **Not tagged, not released.** `v2.0.0` does **not** contain these; production must deploy **`2.0.2`** (the KSH-attribution compliance patch, `release/v2.0.2`, once the owner has merged and tagged it), not `2.0.1` or `2.0.0`. See §1a |

## 1. Gate summary

| Gate | Status | Blocker | Next action | Owner/Claude |
|---|---|---|---|---|
| 1 — B6 Public submission rate limit | **Implemented in the 2.0.1 candidate; awaiting merge, tag and deployment** | Not in `v2.0.0`; needs the `2.0.1` release and the go/no-go. Single backend instance only (ADR 0010) | Owner reviews and merges the patch PR, then approves the `v2.0.1` tag | **Owner** |
| 2 — B9 production reference dataset | **Split into B9-A (licence) and B9-B (quality), see `deployment/B9_DECISION_PACKAGE.md`. KSH and taxonomy CLEARED; railway roster and relations `PENDING`, written VPE confirmation required; backed up, validated; NOT CLEARED** | The importer refuses a `PENDING` dataset with no bypass (verified). VPE reuse rights are unconfirmed; see `deployment/REFERENCE_DATA_REUSE_MEMO.md` | **Owner decides E1** (memo options A–D): written VPE confirmation, an owner-declared basis, or launch without VPE data | **Owner** |
| 3 — Android signing | **Instructions ready** | No V2 release key exists (owner-only by design) | Generate and back up the key on your own machine (§4) | **Owner** |
| 4 — V1 backup and rollback evidence | **Runbook ready** | No production access here; V1 must not be touched | Run §5 on the server and workstation; report the checksum and restore result | **Owner** |
| 5 — Production configuration inventory | **Complete** (§6) | Owner must supply the secrets and host facts listed | Provide the "owner must provide" items; generate the secrets on the server | **Owner** (secrets); Claude (no action) |
| 6 — Deployment runbook | **Written, not executed** (§7) | Depends on Gates 1–5 and the go/no-go | Rehearse steps 1–13 on a disposable host if desired; wait for the go/no-go | **Owner** go/no-go; Claude on instruction |

**Production deployment is currently: NOT READY — BLOCKERS REMAIN.** The blocking items are
Gate 1 (decision + patch), Gate 2 (reuse basis), Gate 3 (key), Gate 4 (verified V1 backup) and the
secrets and host facts in Gate 5.

### 1a. What changed since the first pass (V2.0.1 candidate)

| Item | State |
|---|---|
| B6 policy | **Approved** and implemented exactly: burst 10, one token per 20 s, IPv4 exact, IPv6 /64, trusted-proxy resolver unchanged, replays and 409s never throttled, 429 + `RATE_LIMITED` + `Retry-After`. Design and constraints: [ADR 0010](architecture/adr/0010-public-submission-rate-limit.md) |
| Public 429 UX | **Approved** and implemented: the dedicated Hungarian message on both Public clients, the report stays `PENDING` with the same identity, manual retry only |
| KSH attribution | **Approved** and implemented as one caption line in the Public Web, Public Android and Service Android |
| B9 | **Not cleared.** Backup made, working manifest upgraded with `reuseStatus: PENDING`, validation and the import refusal verified. The owner's decision is still needed |
| Single-instance constraint | Documented in ADR 0010, ARCHITECTURE, the operations runbook and the env template: **do not run more than one backend instance without a shared rate-limit design** |
| B9 in the release decision | **Does not block the V2.0.1 software release; it blocks production deployment** (owner, final). The recovered working copies were removed from OneDrive after the backup was re-verified |
| Still owner-only | signing key, V1 backup and restore, production configuration and secrets, DNS/HTTPS, the go/no-go, the `v2.0.1` tag, the B9 clearance |

## 2. Gate 1 — B6: anonymous Public submission rate limiting

> **Update:** approved and implemented in the V2.0.1 candidate (see §1a and ADR 0010). §2.1–§2.6 below are the
> design record from the first pass and are kept for traceability.


### 2.1 Finding

`POST /api/v1/public/reports` has no abuse control. Phase 17 measured 60 rapid anonymous
submissions, all `201`. ADR 0008 Decision 8, the Phase 4 report §L and decision B6 all record this as
a known, deliberately deferred limitation "before any public deployment".

**No active document specifies numbers.** The only figure in the repository is a Demo v1
architecture target ("20 reports / 5 minutes / source IP, without persisting the IP") in the
archive. Under `CLAUDE.md` the archive is never a requirement, so it is offered below only as one
option the owner may adopt. Per your instruction, the numeric policy is therefore **not implemented
and needs your decision**.

### 2.2 What the current architecture already gives us

| Building block | Where | Why it matters |
|---|---|---|
| Caddy overwrites `X-Forwarded-For` with the real TCP peer (`header_up X-Forwarded-For {remote_host}`) | `deploy/caddy/Caddyfile` | A client cannot inject a forwarded address through the edge |
| `ClientIpResolver` trusts the header only when the immediate peer is in `orszem.auth.trusted-proxies` (default loopback) and then uses the **last** entry; validates the value before it becomes a key | `auth/infrastructure/ClientIpResolver.kt` | Spoofing is already defended and unit-tested for login |
| `LoginRateLimiter`: Caffeine, bounded key count, time expiry, in-memory | `auth/infrastructure` | Proven storage pattern, no new infrastructure |
| `RATE_LIMITED` error code, HTTP 429, `Retry-After` | `ApiExceptionHandler` | No new public error code is needed |
| Replay/idempotency check runs **first** in `SubmitReportUseCase.submit` (`findByClientSubmissionId` → replay or 409) | `reports/application` | A limiter placed after it can never block a legitimate replay |

### 2.3 Layer choice

| Layer | Verdict |
|---|---|
| **Backend, in the submit path** | **Recommended.** Can exempt replays (it sees `clientSubmissionId`), reuses `ClientIpResolver`, needs no new component |
| Caddy `rate_limit` | Not stock Caddy: it needs a third-party plugin and a custom build. It also cannot distinguish a replay from a new report. Rejected |
| PostgreSQL-backed counters | Survives restarts and multiple instances but needs a migration and adds a write per request. Not needed for one instance; keep as the upgrade path |
| Redis | Excluded unless the owner approves |

### 2.4 Client impact (verified in the code, no change needed)

Neither Public client mentions 429. A 429 lands in their existing "ambiguous / retryable" branch
(`ReportRepository.kt` `else ->`; Web `reportRepository.ts` "Any other/ambiguous status: stays
PENDING, retryable"): the local record stays `PENDING` under the **same `clientSubmissionId`**, and
the user retries with a button. There is **no automatic retry loop** in either client, so a
limiter cannot be hammered by them. A specific Hungarian "too many attempts, wait a moment"
message would be a visible UI change and needs owner approval (D4).

### 2.5 Proposed policy (a proposal, not a decision)

| Aspect | Proposal | Rationale |
|---|---|---|
| Scope | Attempts to **create** a report on `POST /api/v1/public/reports` only | Lookups (`GET`) are existence-safe and brute-forcing a 256-bit capability is infeasible |
| Key | Client IP from the existing `ClientIpResolver`; IPv4 exact, **IPv6 aggregated to its /64** | A single IPv6 subscriber controls a whole /64 and can rotate addresses inside it; per-/128 limiting would be trivially bypassed |
| Algorithm | Token bucket per key | Expresses "burst" and "sustained" separately and is easy to reason about |
| **Option A (recommended)** | **Burst 10, refill 1 token per 20 s** (≈3/min, ≤180/hour, ≈4,300/day per source) | One incident is one report and a person files 1–3; 10 covers a household, a shared mobile-carrier address (CGNAT), or several passengers reporting one incident, and correcting mistakes. The sustained rate is far above human use but caps a single-source flood |
| Option B | 20 per rolling 5 min (≈4/min), the non-authoritative Demo v1 target | More permissive sustained rate |
| Option C | Burst 5, refill 1 per 60 s | Stricter; higher risk of blocking legitimate shared-IP users |
| Replay/idempotency | A request whose `clientSubmissionId` already exists (replay **or** 409 mismatch) consumes **no** token and is never throttled. If a concurrent identical submission converges on a replay after a token was taken, the token is refunded | Preserves ADR 0008 semantics exactly |
| Response | `429`, existing `RATE_LIMITED` code, stable generic body, `Retry-After` = seconds until the next token (min 1), `Cache-Control: no-store` | Reuses the public error contract; no new code |
| Existence leak | The decision depends only on source and count, never on the capability or on whether a report exists. Residual note: a throttled caller could tell "unknown ID" (429) from "existing ID" (200/409), which needs guessing a random 122-bit UUID | Not exploitable in practice; documented for completeness |
| Storage | Caffeine, bounded to 100,000 keys (≈ ≤15 MB), idle entries expire after the refill horizon | Same pattern as login throttling; bounded growth |
| Restart | State resets on restart | Acceptable for one instance; matches login throttling |
| Multiple instances | Counters are per instance, so the effective limit is N× | Documented. A shared store needs owner approval (Postgres table = new migration; Redis excluded) |
| Global cap | **Not proposed** | A global ceiling lets one attacker exhaust it for everyone. Distributed floods need CAPTCHA/reputation, which is out of scope by your constraints |
| Logging | **No IP, no key, no per-client data in logs.** One aggregated WARN at most once a minute with only a count of throttled requests | Data minimisation |
| Configuration | `ORSZEM_PUBLIC_SUBMISSION_RATE_LIMIT_ENABLED` (default true), `_BURST` (10), `_REFILL_PERIOD` (20s), `_MAX_KEYS` (100000); trusted proxies reuse `ORSZEM_TRUSTED_PROXIES` | Tunable without a release once real traffic is seen |
| Version | **`2.0.1`**, additive (`429` documented on the operation). This is a post-2.0.0 patch and does **not** modify `v2.0.0` | Owner approves the version and tag separately |

**Owner decisions needed (D1–D4):**
- **D1** — Policy: Option A, B, C, or your own numbers.
- **D2** — Confirm IPv6 aggregation to /64.
- **D3** — Confirm scope: creation attempts only.
- **D4** — Client copy: leave the generic retry message (recommended), or approve a dedicated
  Hungarian message (a visible change to both Public clients).

### 2.6 Implementation and test plan (after D1–D4)

Focused tests: token-bucket arithmetic with a fake `Clock` (burst, refill, cap, sub-token
accrual, `Retry-After` value); the N+1-th request from one source → 429; a replay of an accepted
submission **after** exhaustion → 200, not 429; a 409 mismatch → 409, not 429; independent sources
do not affect each other; IPv6 addresses in one /64 share a bucket; an `X-Forwarded-For` sent by an
untrusted peer is ignored, and from a trusted peer only the last entry is used; forged
`X-Forwarded-For` values cannot evade or transfer a limit; **concurrency**: many parallel new
submissions from one source create at most `burst` reports, parallel identical replays never
throttle, and the refund path never over-credits; the key cap evicts rather than grows; the 429
body passes the existing error-shape and no-leak assertions; the OpenAPI document lists the 429.
Then: full backend regression (`clean build --no-build-cache`), a live re-run of the 60-request
probe, a security review of the diff, and all six GitHub Actions checks green.

## 3. Gate 2 — B9: production reference dataset

### 3.1 What V2 needs

Three components, per ADR 0006: **settlements** (identity), **railway lines** and
**settlement↔line relations**. **Service areas and their line assignments are not reference
data.** They are operational data the SUPER_ADMIN creates in the Service app after go-live (open
question B2). Nothing in any dataset provides them.

### 3.2 What exists

| Location | Contents | Status |
|---|---|---|
| `reference-data/cleared/settlements.csv` (tracked) | 3,178 KSH settlements, CC BY 4.0 | Redistributable; matches the manifest hash |
| `reference-data/example/` (tracked) | Fictional: 3 settlements, 2 lines, 4 relations | CI and demos only; **not** production data |
| `reference-data/local-research/` (gitignored) | Only a README was here | The documented home of the real data; was empty |
| **Unreachable git objects in this clone** | The pre-rewrite VPE-derived dataset, from commit `87b4878` ("VERIFIED/PARTIAL canonical dataset from KSH and VPE HÜSZ"), orphaned by the Phase 3B history rewrite (B10) | **Recoverable now; will be lost at the next `git gc`** |
| V1 / pilot data | The V1 database holds the demonstration reports, not a V2 reference dataset. V1 data is deliberately not migrated | Not a source |

### 3.3 Provenance and integrity (from the recovered manifest)

| Item | Value |
|---|---|
| Dataset version | `2026.09.07-1`, `verificationStatus: VERIFIED`, `coverageStatus: PARTIAL` |
| Settlements | KSH *Magyarország helységnévtára* (2025-01-01), CC BY 4.0, source workbook SHA-256 `d059a148…437991` |
| Railway lines / relations | VPE (KTI VPE Igazgatóság) *Hálózati Üzletszabályzat 2026/2027*, annexes 5.2-4 (MÁV) and 5.2-5 (GYSEV), retrieved 2026-09-07. **No explicit reuse licence.** Basis recorded: mandatory regulatory publication, public-body data (Act LXIII of 2012), factual rows only, source not redistributed; "owner confirmation recommended" |
| GYSEV note | The data comes from VPE's regulatory annex, **not** from gysev.hu, whose legal notice forbids putting site content in a database without written permission |
| Counts | 3,178 settlements; 231 lines; 967 relations |
| Coverage | 824 settlements (25.9%) have a verified relation; 147 lines have relations; 1,122 candidates quarantined (`NEEDS_REVIEW`); source extraction coverage 99.76% |
| **Integrity (verified this session)** | The three canonical files' SHA-256 **match the manifest exactly**; the recovered `settlements.csv` is byte-identical to the tracked `cleared/settlements.csv` |
| Stable identifiers | `ksh_code` (five digits) and the canonical HÜSZ `line_code`. Imports match on them; they must not change |

### 3.4 Compatibility with the V2 tooling — conversion needed (manifest only)

`validate-canonical.mjs` rejects the recovered manifest with four errors: it predates ADR 0006, so
it lacks `reuseStatus` and the per-component `coverage`. The **data files need no conversion**.
The manifest needs exactly:

- `reuseStatus: "PENDING"`: the honest current status. The importer **refuses `PENDING`
  unconditionally, with no bypass** (ADR 0006).
- `coverage: { settlements: "COMPLETE", railwayLines: "PARTIAL", settlementRailwayLines: "PARTIAL" }`,
  as recorded in `reference-data/README.md` and ADR 0006.

The canonical way to produce a current-schema manifest is `build-canonical.mjs`, which needs the
**original sources** (the KSH workbook and the MÁV/GYSEV annex text). They are **not on this
machine**. Editing the manifest by hand is the alternative; the data hashes stay valid.

### 3.5 What I did, and what was stopped

- Extracted the five files (`manifest.json`, `settlements.csv`, `railway-lines.csv`,
  `settlement-railway-lines.csv`, `needs-review.csv`) from the orphaned commit into the
  **gitignored** `reference-data/local-research/` (the documented home), then verified the hashes
  in §3.3. Git status is unchanged (only your untracked files). Nothing was committed, imported or
  distributed, and no git ref was created (a ref would make the exposed data reachable and
  pushable).
- **Not done — the manifest upgrade, `reference-validate`/`reference-diff`, and the disposable-DB
  dry-run.** The tool-permission layer refused the manifest edit because the dataset's reuse status
  is `PENDING`. I did not work around it. It is an owner decision (§3.7).

### 3.6 Owner decisions (B9)

- **E1 — Reuse basis.** The importer needs `reuseStatus: CLEARED` to load the real data, and a
  `CLEARED` label is a **legal assertion only the owner can make**. Options: (a) obtain written VPE
  confirmation (the cleanest); (b) decide that the recorded internal-use basis is sufficient for
  running the service (B9's own text says it concerns *external distribution*, "not the running
  service", yet the importer gate does not distinguish) and set `CLEARED` yourself, recording the
  decision; (c) launch without real railway data. In that case every report routes **UNCLASSIFIED**
  (an empty relation set means "no verified reference", per ADR 0006) and only global
  MODERATOR/SUPER_ADMIN can see it.
- **E2 — Back the recovered files up now, securely.** They exist only as unreachable objects (plus
  the gitignored copies above). Store them off-repository with the manifest and its hashes.
- **E3 — KSH attribution (CC BY 4.0).** The licence requires "Forrás: KSH — https://www.ksh.hu" to
  be shown with the data. **`v2.0.0` shows it nowhere**, although the Public apps display KSH-derived
  settlement names. **Approved and implemented in the V2.0.1 candidate** as one caption line: Public Web
  (form and History), Public Android (New Report and History) and Service Android (queue and archive
  headers, both detail screens). The Web app has no footer, so none was added. The line **links to
  `https://www.ksh.hu`** (the address in the licence's own attribution string), so the "working link when
  online" requirement is met.
- **E4 — Budapest (B8).** The dataset contains the city and its 23 districts as separate settlements;
  decide how a Budapest report should route.
- **E5 — Coverage expectation.** Only 25.9% of settlements have a verified relation, so a
  production instance will route most reports UNCLASSIFIED until the review worklist is worked.

### 3.7 Dry-run procedure (to run once you authorise it, against a disposable DB only)

```bash
# 0. Optional: the manifest upgrade (adds reuseStatus PENDING + coverage). Data files untouched.
# 1. Structure and hashes
node reference-data/tools/validate-canonical.mjs reference-data/local-research/manifest.json
# 2. Backend's own validation and read-only diff against an EMPTY disposable database
./scripts/orszem-admin reference-validate reference-data/local-research
./scripts/orszem-admin reference-diff     reference-data/local-research
# 3. Prove the gate: this MUST be refused while the dataset is PENDING
./scripts/orszem-admin reference-import   reference-data/local-research
```

Expected: the validator passes with `PENDING`; validate and diff report 3,178 / 231 / 967; the
import is **refused**. The real import is only run at deployment step 7 (§7), and only if
`reuseStatus` is genuinely `CLEARED`. The database is destroyed afterwards.

## 4. Gate 3 — Android production signing (owner-only)

**Current state.** Both apps sign the release build only when all four inputs are supplied and
otherwise leave the APK **unsigned**. `*.jks`, `*.keystore`, `*.p12` and `keystore.properties`
are gitignored. The release `API_BASE_URL` is `https://api.orszembejelento.hu/`, cleartext is
forbidden, and `versionName` is `2.0.0`, `versionCode` `1`. The APKs built from `v2.0.0` are
unsigned and are **not** production-distributable. Existing background: `docs/deployment/ANDROID_SIGNING.md`.

**Rules.** Do not generate the key in a cloud session, a CI runner or this environment. Do not
reuse the V1 identity. Never paste a password into a repository, chat, ticket or shell history.

### 4.1 Generate (owner's secure machine, once, offline)

```bash
keytool -genkeypair -v \
  -keystore orszem-v2-release.jks \
  -alias orszem-v2 \
  -keyalg RSA -keysize 4096 \
  -sigalg SHA256withRSA \
  -validity 10000 \
  -storetype PKCS12 \
  -dname "CN=<publisher name>, O=<organisation>, C=HU"
```

| Parameter | Value | Why |
|---|---|---|
| Algorithm | RSA 4096, `SHA256withRSA` | Supported by APK Signature Scheme v2/v3 on `minSdk 26`; large margin |
| Alias | `orszem-v2` | Matches the documented `ORSZEM_RELEASE_KEY_ALIAS` |
| Validity | 10000 days (≈27 years) | A key that expires before the app is retired makes updates impossible |
| Store type | PKCS12 | The default; store and key password are the same |
| Passwords | Two prompts by `keytool`; long random passphrase from a password manager | **Never** on the command line |
| `-dname` | Real publisher details | It is embedded in every APK |

Record the certificate fingerprint (this is public and safe to write down):

```bash
keytool -list -v -keystore orszem-v2-release.jks -alias orszem-v2 | grep "SHA256:"
```

### 4.2 Back it up before any build is distributed

- The `.jks` in **at least two durable places you control, one offline**.
- The passwords in a password manager, **not** next to the keystore.
- The SHA-256 fingerprint recorded where you can find it.
- Losing the key makes every installed V2 app permanently un-updatable (it would have to be
  reinstalled under a new application ID).
- If you publish via Google Play, enrol in **Play App Signing**: the keystore then becomes the
  *upload* key, which Google can reset. Recommended. The publication channel (Play vs direct APK)
  is still an open owner decision.

### 4.3 Where the key must NOT be

Not in the repository, not in `android/`, not in CI secrets, not in any chat, not in `backups/`.
The repository ignores `*.jks`, `*.keystore`, `*.p12`, `keystore.properties`; that is a safety
net, not permission.

### 4.4 Inputs the build expects

Either a gitignored `android/keystore.properties` (`storeFile`, `storePassword`, `keyAlias`,
`keyPassword`) **or** four environment variables, all four required:
`ORSZEM_RELEASE_STORE_FILE`, `ORSZEM_RELEASE_STORE_PASSWORD`, `ORSZEM_RELEASE_KEY_ALIAS`,
`ORSZEM_RELEASE_KEY_PASSWORD`. Set them for the one shell session only and clear them afterwards
(PowerShell: `Remove-Item Env:ORSZEM_RELEASE_*`).

### 4.5 Build both signed release APKs

From a clean checkout of the exact release tag, with the same toolchain as CI (Temurin JDK 21,
Android platform 37.0, build-tools left to AGP):

```bash
git checkout v2.0.0            # or the approved patch tag
cd android
./gradlew clean :public-app:assembleRelease :service-app:assembleRelease --no-build-cache
# outputs: public-app/build/outputs/apk/release/public-app-release.apk
#          service-app/build/outputs/apk/release/service-app-release.apk
sha256sum public-app/build/outputs/apk/release/*.apk service-app/build/outputs/apk/release/*.apk
```

Without the four inputs the same command yields `*-release-unsigned.apk`. That is the safe
default and proves nothing was signed by accident.

**Reproducibility (measured, not assumed).** The two unsigned release APKs built for the
`v2.0.0` release (Phase 17) and rebuilt in this session from a clean tree with `--no-build-cache`
are **byte-identical**: Public `6acf141b2431f183bc2e2a7f41fa46bde638bf54ff8a2ab6db804fa152927d58`,
Service `1ff52215a108fa2a9ecfc147b8e2071ebad0bb7694bc4e4bebf9198f8f01bcaa` (same workstation,
same toolchain). A signed APK is expected to be identical for identical input and key (RSA PKCS#1
v1.5 signing is deterministic and the release uses APK Signature Scheme v2/v3 only), but that is
**not yet verified**. Confirm it once: build twice with the real key and compare the SHA-256s.

### 4.6 Verify a signed APK

```bash
APKSIGNER="<sdk>/build-tools/<version>/apksigner"     # or: java -jar <sdk>/build-tools/<v>/lib/apksigner.jar
"$APKSIGNER" verify --verbose --print-certs public-app-release.apk
```

Acceptance: `Verifies`; scheme **v2 and v3 = true**; exactly one signer; the certificate SHA-256
**equals the fingerprint you recorded in §4.1**, and the subject is your publisher, **not**
"Android Debug". Also confirm identity and version:

```bash
"<sdk>/build-tools/<version>/aapt2" dump badging public-app-release.apk | head -1
# package: name='hu.orszembejelento.app' versionCode='1' versionName='2.0.0'
# (service: name='hu.orszembejelento.service')
```

Check both apps, record each APK's SHA-256, and install each on a clean device before any
distribution. A debug- or test-signed APK is never described as a production artefact.

## 5. Gate 4 — V1 backup and rollback evidence (owner-executed)

This environment has no V1 host access and none was guessed. **V1 is not modified.** The
existing procedures are `docs/deployment/V1_DATABASE_ARCHIVE.md` (dump) and
`docs/deployment/V1_DECOMMISSION.md` (retirement); this section adds the missing verification and
rollback. Placeholders in `<angle brackets>` are yours to fill. **Do not commit the dump or any
value below**: it holds the demo user's password hash.

### 5.1 Dump on the V1 server

```bash
ssh opc@<server-ip>
cd /home/opc/apps/orszem
STAMP=$(date +%F-%H%M)
docker compose --env-file .env -f infra/compose/docker-compose.prod.yml \
  exec -T db pg_dump -Fc -U orszem orszem > ~/orszem-v1-$STAMP.dump
ls -l ~/orszem-v1-$STAMP.dump                       # must be clearly non-zero
sha256sum ~/orszem-v1-$STAMP.dump | tee ~/orszem-v1-$STAMP.dump.sha256
# source row counts, for comparison after the restore
docker compose --env-file .env -f infra/compose/docker-compose.prod.yml exec -T db \
  psql -U orszem -d orszem -At -c "select 'select '''||relname||''', count(*) from '||quote_ident(relname)||';' from pg_stat_user_tables order by 1" \
  | docker compose --env-file .env -f infra/compose/docker-compose.prod.yml exec -T db psql -U orszem -d orszem -At \
  > ~/orszem-v1-$STAMP.source-counts.txt
```

`pg_dump` is read-only; V1 can keep running. Custom format (`-Fc`) is compressed and lets
`pg_restore` list or selectively restore.

### 5.2 Verify the file, off the server

```bash
scp opc@<server-ip>:'~/orszem-v1-<STAMP>.dump*' <local-backup-dir>/
sha256sum -c orszem-v1-<STAMP>.dump.sha256          # must say OK
pg_restore --list orszem-v1-<STAMP>.dump | head     # must list real objects, not error
```

### 5.3 Restore into a disposable PostgreSQL and sanity-check

Use the same major version as V1 (PostgreSQL 16) on your workstation:

```bash
docker run -d --name v1_restore_check -e POSTGRES_PASSWORD=<throwaway> -p 127.0.0.1:55700:5432 postgres:16-alpine
docker exec v1_restore_check psql -U postgres -c "create database v1_check"
docker exec -i v1_restore_check pg_restore -U postgres -d v1_check --no-owner --exit-on-error < orszem-v1-<STAMP>.dump
```

Sanity checks (all must pass):

| Check | How |
|---|---|
| Restore ran clean | `pg_restore` exit code 0 with `--exit-on-error` |
| Same objects | table count equals `pg_restore --list` table entries |
| Same data | per-table `count(*)` equals `orszem-v1-<STAMP>.source-counts.txt` (exact if V1 was frozen; small drift is expected while it was live) |
| Schema history | the V1 Flyway history shows its single migration `V1__init_demo_schema` |
| Not empty | the reports table has rows; note the newest timestamp |

Then destroy the disposable instance (`docker rm -f v1_restore_check`).

### 5.4 Preserve it

- At least two copies, one offline. **Encrypt before it leaves your machine** (for example with
  `age` or `gpg`); it contains a credential hash.
- Record outside the repository: file name, date, SHA-256, where the copies are, and the
  encryption key location.
- Decide the retention period. V1 data is not consumed by V2; keep the dump at least until V2 is
  stable.
- **Take a second, final dump after V1 is frozen** (deployment step 3) and verify it the same way.

### 5.5 Gate criterion

V1 must not be retired until §5.2 and §5.3 pass. Send back the file name, SHA-256 and the result
of each check (no file, no secret).

## 6. Gate 5 — Production configuration inventory

Legend: **S** already safe/defaulted · **O** owner must provide · **G** must be generated ·
**H** production-hostname dependent · **X** secret.

### 6.1 Backend (`/etc/orszem/backend.env`, mode 640 `root:orszem`)

| Input | Class | Value/default | Notes |
|---|---|---|---|
| `ORSZEM_DB_URL` | S / O | `jdbc:postgresql://127.0.0.1:5432/orszem_v2` | The dev default points at `localhost:5432/orszem_v2`; confirm host and DB name |
| `ORSZEM_DB_USERNAME` | O | `orszem_v2` | A new role; never a V1 role |
| `ORSZEM_DB_PASSWORD` | **G, X** | none, on purpose | Generate with a password manager or `openssl rand -base64 32`. Startup fails without it |
| `ORSZEM_BIND_ADDRESS` | S | `127.0.0.1` | Never a public address |
| `ORSZEM_PORT` | S | `8080` in code; **`8081` in the template** | Set 8081 so it never collides with V1 on 8080 |
| `JAVA_TOOL_OPTIONS` | S | `-Duser.timezone=UTC` | |
| `ORSZEM_TRUSTED_PROXIES` | S | `127.0.0.1/32,::1/128` | Correct while Caddy is on the same host; **must never** include a client range |
| `ORSZEM_ACCESS_TOKEN_LIFETIME` / `_SESSION_LIFETIME` | S | `15m` / `30d` | No signing secret exists (opaque server-side tokens) |
| `ORSZEM_RATE_LIMIT_*` (login) | S | 10 per service ID, 40 per IP, 15 m window | Provisional pilot values |
| Public-submission limit settings | **pending B6** | proposed in §2.5 | Not yet in the code |

### 6.2 PostgreSQL

| Item | Class | Notes |
|---|---|---|
| Version | S | 16 (matches the Testcontainers and drill baseline) |
| Database `orszem_v2`, role `orszem_v2` | O / G | Fresh; loopback only; never in the OCI security list; `ss -lntp | grep 5432` must show `127.0.0.1` only |
| Backups | O | Scripts exist (`scripts/orszem-backup.sh`); the backup **timer is owner-gated and inactive** (`OPERATIONS_RUNBOOK.md` §16) |

### 6.3 Public Web

| Item | Class | Notes |
|---|---|---|
| Build | S | `npm ci && npm run build`; API base is the fixed same-origin `/api/v1`, so **no hostname is baked in** |
| Deployed path | S | `/home/opc/apps/orszem-v2/web`, served by Caddy |
| Source map | O | The build emits a 1.3 MB `.map`. Recommended: exclude `*.map` from the rsync (a deployment choice) |
| KSH attribution | O | Missing (§3.6 E3) |

### 6.4 Android apps

| Item | Class | Notes |
|---|---|---|
| Release API base URL | S, **H** | `https://api.orszembejelento.hu/`, baked in at build time; the apps cannot reach V2 until that host resolves |
| Cleartext, backup, permissions | S | Cleartext forbidden in release; `allowBackup=false`; Service = `INTERNET` only; Public also uses location |
| Release signing | **O, X** | §4 |
| Distribution channel | O | Play (recommended, with Play App Signing) or direct APK: undecided |

### 6.5 Caddy and TLS

| Item | Class | Notes |
|---|---|---|
| `deploy/caddy/Caddyfile` | S, **H** | Hosts `orszembejelento.hu`, `www.` (redirect to apex), `api.`; backend `127.0.0.1:8081`; validated and policy-tested in CI |
| Certificates | S (automatic) | ACME **HTTP-01**: requires the three A records to resolve to this VM **and** inbound TCP/80 (and 443) open in both `firewalld` and the OCI security list. No paid service |
| HTTP/3 | S | Disabled (UDP/443 is not opened) |
| Log directory | O | `/var/log/caddy`, owned by `caddy`; access logs redact `Authorization` and `X-Orszem-Report-Access` |
| Caddy version | O | Install a current stable Caddy on the host (validated with 2.11.x) |

### 6.6 Reference-data import and first administrator

| Item | Class | Notes |
|---|---|---|
| Dataset | O | §3; needs the reuse decision |
| First SUPER_ADMIN | **G, X** | `./scripts/orszem-admin create-super-admin`, run on the server. The Service ID and a one-time credential are printed **once to the console**; there is no HTTP route and no seeded account |
| Service areas, users | O | Created afterwards in the Service app (B2) |

### 6.7 Backup and restore

| Item | Class | Notes |
|---|---|---|
| `scripts/orszem-backup.sh`, `-restore.sh`, `-preflight.sh`, `-backup-age-check.sh` | S | Explicit targets only; the restore refuses a non-empty database |
| Backup destination, retention, off-host copy | O | Owner decision (`OPERATIONS_RUNBOOK.md` §5) |

### 6.8 Hostname and network facts to confirm

`https://orszembejelento.hu`, `https://api.orszembejelento.hu`, and `www` → apex are unchanged. DNS
has **no** records yet and the zone was undelegated as of Phase 1 (re-check with `dig`). Re-verify
the VM's current public IPv4 in the OCI console. The `129.159.31.175` in the V1 archive is history,
not a guarantee. Keep the existing VM and public IP; nothing found requires new infrastructure.

## 7. Gate 6 — Deployment runbook (prepared, not executed)

**Design points that shape the order**

1. There is no DNS to "switch": the names have no records today and V1 is served on its `sslip.io`
   name. Creating the records **is** the go-live, and nobody can reach V2 before it.
2. V1's Caddy container owns ports 80/443. The host-level V2 Caddy cannot bind them while V1's
   edge is up, so V1's edge and V2 go-live are coupled (the alternative is a single Caddy serving
   both, `SERVER_RUNBOOK.md` §0).
3. ACME HTTP-01 needs DNS to already point at the VM, so the honest "pre-DNS" checks are loopback
   and `curl --resolve` checks, and HTTPS is verified only after the records exist.
4. Keep the VM and the public IP throughout.

### 7.1 Sequence

| # | Step | Owner gate | Detail / command |
|---|---|---|---|
| 1 | **Final go/no-go** | ✅ Owner | All of §1 green: B6 patch shipped (or explicitly waived in writing), reuse decision taken, signing key generated, V1 backup verified, secrets prepared |
| 2 | **Verified V1 backup** | ✅ Owner | §5.1–§5.4 passed and recorded |
| 3 | **V1 write/service-access shutdown** | ✅ Owner | Freeze writes: `docker compose … stop api` (keeps the database), then take the **final dump** (§5.4) and verify it. Only then `docker compose … down` (**no `-v`**: volumes kept). This frees 80/443. `V1_DECOMMISSION.md` Option 1 |
| 4 | **Create the V2 database and role** | ✅ Owner | `SERVER_RUNBOOK.md` §2; password from a password manager; confirm loopback-only |
| 5 | **Install the backend artefact and config** | ✅ Owner | Build the JAR from the approved **`v2.0.2`** tag (never `v2.0.0`, which lacks the rate limit, nor `v2.0.1`, which lacks the KSH attribution on six screens) (`./gradlew clean bootJar`), record its SHA-256, copy it up, install as `orszem`. Create `/etc/orszem/backend.env` (mode 640) from the template; run `scripts/orszem-preflight.sh` |
| 6 | **Start the backend; run migrations** | ✅ Owner | `systemctl enable --now orszem-backend`. Flyway applies **V001–V006** to the empty DB. Check `journalctl -u orszem-backend` for a clean start, and `curl http://127.0.0.1:8081/actuator/health` → `UP`. Confirm the startup log has **no** generated-password line |
| 7 | **First administrator, then reference import** | ✅ Owner | `scripts/orszem-admin create-super-admin` (store the printed credential securely; it is one-time). Then `reference-validate`, `reference-diff`, `reference-import` of the **approved production dataset** (only if `CLEARED`, §3) |
| 8 | **Deploy the Public Web build** | ✅ Owner | `npm ci && npm run build` from the approved tag; rsync `dist/` (excluding `*.map`) to `/home/opc/apps/orszem-v2/web` |
| 9 | **Install Caddy configuration** | ✅ Owner | Install Caddy; copy `deploy/caddy/Caddyfile`; `mkdir /var/log/caddy`; `caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile`. **Do not start it on 80/443 until V1's edge is down** (step 3) |
| 10 | **Open the network** | ✅ Owner | TCP 80 and 443 open in `firewalld` **and** the OCI security list. 5432 and 8081 must **not** be open |
| 11 | **Signed Public/Service APK verification** | Owner | §4.5–§4.6 on both APKs; fingerprint matches §4.1; the release base URL is `https://api.orszembejelento.hu/` |
| 12 | **Pre-DNS health and smoke** | Owner (Claude can script) | On the VM: backend health and `/api/v1/meta` on loopback; `scripts/verify-caddy-routing.sh`, `-header-redaction.sh`, `-csp.sh`; `curl --resolve orszembejelento.hu:80:127.0.0.1 -I http://orszembejelento.hu/` (expect the HTTPS redirect); Public catalog and settlement search via loopback; login as the new SUPER_ADMIN via loopback |
| 13 | **Pre-DNS backup of the empty-but-migrated V2 DB** | Owner | `scripts/orszem-backup.sh` → verify. It becomes the "just after migrations" rollback point |
| 14 | **DNS switch (creating the records)** | ✅ **Owner only** | At the DNS host: `A` records for `orszembejelento.hu`, `www` and `api`, all → the VM's public IPv4, **TTL 300** (`DNS.md`). Requires the zone to be delegated first |
| 15 | **Post-DNS smoke** | Owner (Claude can script) | `dig` shows the VM address; Caddy obtains certificates (check its log); `curl -sI https://orszembejelento.hu/` → 200; `https://www…` → 308 to the apex; `https://api…/api/v1/meta` → 200. All internal paths return **404** on both hosts (`SERVER_RUNBOOK.md` §5 loop). Browser check: no CSP violation. Service login from a signed APK on a real device. A throwaway Public report **only with your explicit approval**, then archive/moderate it |
| 16 | **Monitoring and log inspection** | Owner | For the first 24–48 h: `journalctl -u orszem-backend --since …` (count `ERROR`; expect none), Caddy access/error logs, `ss -lntp`, disk space, `scripts/orszem-backup-age-check.sh`. Confirm no secret in logs (no `Bearer`, no capability). Activating the backup timer is a separate owner-gated action |
| 17 | **Rollback if criteria are met** | ✅ Owner | §7.2 |

### 7.2 Rollback

**Criteria** (any one; decided by the owner):

| Trigger | Threshold |
|---|---|
| Backend will not start or Flyway fails | not healthy within 10 minutes of step 6 |
| Pre-DNS smoke fails | any failure in step 12 that cannot be fixed within the window |
| TLS not issued | no valid certificate within 30 minutes of step 14 |
| Post-DNS smoke fails | any failure in step 15 |
| Data integrity doubt | wrong counts after import, or an unexpected `ERROR` pattern in the log |
| Security | a secret in a log, an internal path reachable, or the database/backend reachable from outside |

**Steps — before any real V2 report exists (the expected first-day case):**

1. Remove or repoint the three DNS records (TTL 300 keeps the window short).
2. `systemctl stop caddy orszem-backend` (leave the V2 database in place for diagnosis; **do not drop it**).
3. Restart V1: `cd /home/opc/apps/orszem && docker compose --env-file .env -f infra/compose/docker-compose.prod.yml up -d`. The volumes were never removed. Confirm the health of V1.
4. If the V1 database looks damaged, restore the **final dump** (§5.4) into a **fresh** database and point V1 at it; never restore over a live one.
5. Record what failed, when, and the evidence, before any retry.

**Steps — after V2 has accepted real reports (point of no return):** do not roll back to V1 by
discarding V2 data. First take and verify a V2 backup (`scripts/orszem-backup.sh`), then prefer
fixing forward. If a database-impacting rollback is unavoidable, follow `OPERATIONS_RUNBOOK.md`
§10 B (restore a known-good backup into a **fresh** database, never in place). Application-only
rollback keeps the previous JAR named by its git SHA.

## 8. Other findings to keep visible

- `docs/deployment/SERVER_RUNBOOK.md` is partly stale ("Phase 1 has no migrations", no first
  administrator, reference import or backup steps). This document's §7 is the current sequence;
  fold the two together when convenient.
- Open product decisions that interact with go-live but are not technical blockers: **B5**
  retention (also fixes the lifetime of a report's access capability), B3, B4, B7, B8, and the
  distribution channel.
- The Android apps are unusable against production until `api.orszembejelento.hu` resolves and
  the APKs are signed.
- `ORSZEM_PORT` defaults to `8080` in code; the template sets `8081`. A missing env value would
  start the backend on 8080 (V1's port), so run the preflight.

## 9. Change log of this document

| Date | Change |
|---|---|
| 2026-09-19 | First pass: state verified (`main` = `ab984bb`, `v2.0.0` intact); B6 design; B9 data recovered and integrity-verified, manifest upgrade pending authorisation; signing, V1 backup, configuration and deployment runbooks written. Nothing executed against production |
