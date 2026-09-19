# V2.0.1 patch candidate — engineering report

**Status: candidate, not tagged, not released, not deployed.** `v2.0.0` is unchanged and immutable.
Base: `origin/main` = `ab984bb90adb55ac203a57a6895679b0b6fb3b68`. Branch: `release/v2.0.1`.

Scope (owner-approved, and nothing else): B6 anonymous Public submission rate limit, the Public 429
message, the KSH CC BY 4.0 attribution, the supporting B9 work, and their tests and documentation.
No signing, DNS, V1, secrets or deployment change.

## 1. Commits

| Commit | Change |
|---|---|
| `aa0c99f`, `4a0a4ae` | `docs/PRODUCTION_READINESS.md` brought into the patch branch (no PR tooling to merge it separately) |
| `ae7d999` | B6: per-source token bucket in the backend |
| `8c2c831` | Android: 429 handling and KSH attribution (Public and Service) |
| `c2c7329` | Web: 429 handling and KSH attribution |
| `011ae96` | ADR 0010, B9 reuse memo, register and readiness updates |
| `cf47161` | version `2.0.1` (Android `versionCode` 2); a separate commit so it is trivial to adjust |
| `ef7508b` | fix found by the full regression: the limiter tolerates a backwards clock step |

## 2. The rate limit

Exactly the approved policy. Full design and constraints: [ADR 0010](architecture/adr/0010-public-submission-rate-limit.md).

| Aspect | Implementation |
|---|---|
| Bucket | capacity 10, one token per 20 s (about 3/min), a GCRA (two numbers per source) inside Caffeine's per-key `compute` |
| Source | existing `ClientIpResolver`; IPv4 exact; IPv6 first 64 bits; IPv4-mapped IPv6 as IPv4; a non-IP value is never a key |
| Replay ordering | `SubmitReportUseCase`: `clientSubmissionId` lookup **first**; a replay or a 409 returns before the limiter. A token is taken only on the creation path and **refunded** if a concurrent identical submission wins the insert |
| Refusal | 429, existing `RATE_LIMITED`, `Retry-After` (whole seconds, rounded up, ≥ 1), `Cache-Control: no-store`; OpenAPI documents it |
| Memory | entries expire once the bucket would be full again, capped at 100,000 keys (eviction gives an evicted source a full bucket), eviction on the calling thread |
| Logging | at most one WARN a minute, a count only; no address or key anywhere |
| Scaling | in-memory, per instance. **Do not run more than one backend instance without a shared rate-limit design** (ADR 0010, ARCHITECTURE, runbook, env template) |

**Defect found by the regression, fixed (`ef7508b`).** The first full backend build failed 201 of
835 tests with 429: the shared test `MutableClock` is advanced by days and reset backwards, leaving
the loopback source's stored time in the future. The trigger is a test artefact, but the defect is
real: an NTP step backwards would throttle a source for the size of the step. Each entry now also
remembers when it was last touched; a clock reading earlier than that resets the bucket to full
(failing open briefly is far safer than throttling people because of a clock step). Three new tests
fail without the check. The focused tests had passed before; only the full suite exposed it.

## 3. Clients

| | Behaviour |
|---|---|
| 429 handling (Public Android and Web) | explicit branch: the record stays `PENDING` under the **same** `clientSubmissionId` and credential, code `RATE_LIMITED`; recognised even without a body (a proxy). **No automatic retry**: one request per manual action |
| Message | "Túl sok bejelentés érkezett rövid időn belül. Kérjük, várjon egy kicsit, majd próbálja újra." on the New Report screen and on the History card, above the existing Retry button. It does not blame the person |
| KSH attribution | "Településadatok forrása: KSH (CC BY 4.0)": Public Android (New Report step 1 under the settlement field; History), Public Web (under the settlement field; History), Service Android (Új/Folyamatban and Archívum headers; both report detail screens). Plain text; no new screen or navigation. The Web app has no footer, so none was added |

Not covered by the attribution line: the Service moderation *list* of deleted reports, the Home
screens' history previews, and the Service filter sheet. The licence's "working link when online" is
not met by plain text; that is left as an owner decision.

## 4. Results (exact final code, uncached builds)

| Suite | Result |
|---|---|
| Backend `clean build --no-build-cache` | **840 tests, 90 suites, 0 failures, 0 errors, 0 skipped**, all 9 tasks executed (789 before + 31 limiter unit + 20 limiter integration) |
| Public Android unit | **48** (39 + 9), 0 failures |
| Service Android unit | **169**, 0 failures |
| Public Android instrumented (cold-booted emulator) | **26** (21 + 5), 0 failures |
| Service Android instrumented | **90** (89 + 1), 0 failures |
| Public Web | **69** in 9 files (55 + 14), typecheck and production build green, `npm ci` 0 vulnerabilities |
| Android lint | 0 errors; Public 11 and Service 3 warnings, unchanged (dependency-update notices) |
| Android builds | `assembleDebug` and `assembleRelease`, both apps; release APKs are **unsigned** |
| Caddy | validate valid, fmt clean; routing, redaction and CSP scripts exit 0 |
| Restore drill | PASS, 25 assertions, no leftover containers |
| Script checks, reference-data validator | pass |

**Negative proofs.** Taking the token before the replay check fails 4 tests; removing the refund fails
the race test; disabling the backwards-clock check fails 3 tests.

**Live check at the production defaults (real 2.0.1 JAR).** The Phase 17 probe of 60 rapid anonymous
submissions, which all succeeded on 2.0.0, now gives **10 × 201 then 50 × 429**, `Retry-After: 20`, and only
`code`, `message`, `correlationId` in the body. In a real browser (Public Web against that backend): a
throttled submit shows the approved message and stays on the form; the report is saved `PENDING` with the
explanation and a Retry button; over 28 s with a token available the database count did not move (no
automatic retry); the manual retry created exactly one report (0 duplicate submission ids) and the card became
"Beérkezett". The backend log held two throttle notices, counts only. The Public Android app was launched on the
emulator and shows the attribution on New Report and History.

## 5. B9 (not cleared)

Backup outside Git and outside OneDrive, working manifest upgraded with `reuseStatus: PENDING`, validator and the
backend's `reference-validate`/`reference-diff` pass, and `reference-import` is refused
(`REFERENCE_DATASET_REUSE_NOT_CLEARED`, 0 rows written). Details and the owner's options:
[REFERENCE_DATA_REUSE_MEMO.md](deployment/REFERENCE_DATA_REUSE_MEMO.md). **Awaiting the owner's clearance decision.**

## 6. Limitations

- Per-source limits do not stop a distributed flood (CAPTCHA and reputation services are out of scope by decision).
- Shared carrier addresses can legitimately hit the burst; tune `ORSZEM_PUBLIC_SUBMISSION_RATE_LIMIT_BURST`, no release needed.
- A throttled caller still costs one indexed lookup (the price of never blocking a replay).
- The Service Android attribution was verified by compilation, the pinned-copy test and code review, not by a logged-in
  visual pass (that needs a Service account).
- Physical-device testing was not done; everything ran on one emulator.

## 7. Remaining owner gates (unchanged by this patch)

V1 backup and restore; V1 retirement; DNS/HTTPS; the Android signing key and its backup; production configuration and
secrets; the B9 clearance; merging this PR and approving the `v2.0.1` tag. **Production must deploy `2.0.1`, not `2.0.0`.**
