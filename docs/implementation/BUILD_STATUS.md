# Őrszem Demo v1 — Build status

_Newest milestone on top._

## Environment notes

| Tool | Status |
| --- | --- |
| JDK 21 | Bootstrapped into `.toolchain/` (git-ignored); used as the Gradle toolchain. The machine's only other JVM is Android Studio's JBR 25, which AGP/Gradle do not support. |
| Gradle | Committed wrappers — 8.14 (backend), 8.11.1 (Android). |
| Docker | Docker Desktop present but its docker-java gateway is incompatible with the bundled Testcontainers client. Backend integration tests run through a **Docker-in-Docker** sidecar — `scripts/dev-backend-test.sh`. Plain Linux CI runs `./gradlew build` directly. |
| Android SDK | The machine only had platform **API 37** (Android 17) installed, which current stable AGP does not support. Platform **35** + build-tools **35.0.0** were added; the build targets `compileSdk/targetSdk = 35`, `minSdk = 26`. |
| Node | v24 — `@redocly/cli` contract lint. |

---

## M7 — Hardening & presentation ✅ (backend)

- `RequestLoggingFilter` — one structured line per request (method, path, status,
  duration, correlationId); **no** headers/bodies. `logback-spring.xml` keeps
  Spring's own request logging off.
- Actuator `health` + liveness/readiness probes exposed and permitted.
- `LogSanitizationIT` (**AT-044**) — password, access token and `Authorization`
  header never appear in the log across login-success / login-failure / `me`.
- `DemoResetIT` (**AT-001**) — reset restores the exact baseline; the
  `/admin/demo/reset` endpoint enforces its shared secret.
- `docs/DEMO_PRESENTATION.md` — AT-050 walkthrough; README rewritten.

**Backend tests: 58 / 58 green** (`./gradlew test`), covering every P0 acceptance
test and the AT-050 end-to-end flow (`PresentationFlowIT`).

## M2–M6 — Backend feature set ✅

| Milestone | Delivered |
| --- | --- |
| M2 Public report | `POST /public/reports`: validation, whitespace normalization, +5min skew, idempotency + `REPORT_ID_CONFLICT`, in-memory rate limit (`429 RATE_LIMITED` + `Retry-After`). ATs 011–015, 040, 043. |
| M3 Service auth | Argon2id verify, HS256 JWT (8h), `GET /service/me` capabilities, JWT filter + problem+json 401, `SERVICE_LOGIN_*` audit. ATs 002, 003, 029, 041, 042. |
| M4 Workflow | Active list (NEW-first, keyset pagination), detail, **atomic** accept/archive (`WRONG_STATE` vs `NOT_FOUND`), archive list, `REPORT_ACCEPTED`/`REPORT_ARCHIVED` audit, concurrent-accept test. ATs 020–028. |
| M5 Analytics | Deterministic summary + event-type / category / settlement / train stats, `Europe/Budapest` "today". ATs 030–034. |
| M6 Demo seed | `DemoDataService` (`local`/`demo` only) + secret-guarded `POST /admin/demo/reset`; `scripts/reset-demo.sh` / `seed-demo.sh`. Baseline 120 / 8 / 6 / 106, today 16. AT-001. |

## M1 — Backend domain + catalog ✅

- `shared`: `ErrorCode`/`ApiException`, `GlobalExceptionHandler` (problem+json),
  `CorrelationIdFilter`, `OrszemProperties`, stateless `SecurityConfig`, Argon2id encoder.
- `catalog`: `EventCatalogPort` + JDBC adapter, `GET /public/event-types`
  (7 categories / 61 active types, canonical order). ATs 010.
- `R__demo_event_catalog.sql` moved from `db/migration` to `db/demo` to match
  `EVENT_CATALOG.md` §5 / `DATABASE_SCHEMA.md` §12.

## M0 — Repository skeleton ✅

- Root config, ADRs 0001–0004, backend Gradle project, Flyway wiring, Docker
  Compose + Dockerfile, contract lint config, `dev-backend-test.sh`.
- `FlywayMigrationIT` — schema migrates on an empty DB, catalog seeds 7/61, the
  state-consistency check rejects an inconsistent row.

---

## Android (`apps/android/`) — implemented; build verification in progress

**Structure** (ADR 0005): `:core:{common,model,network,designsystem,testing}` +
`:public-app` + `:service-app` (feature code as packages inside each app).

| App | Screens / logic | Unit tests |
| --- | --- | --- |
| public-app | Home → Form → Success; searchable grouped event picker; date-time picker; GPS→settlement (coordinates never sent); idempotent submit + retry; loading/error/empty states. | `ReportFormViewModelTest` (validation, submit, network-retry with stable id, conflict→fresh id, GPS fallback, catalog grouping). |
| service-app | DataStore session + `AuthInterceptor` (401→login); login; active list (NEW-first, pull-to-refresh, keyset paging); detail with accept/archive + stale-409 dialog; archive; deterministic statistics (`Intelligens statisztika`). | `LoginViewModelTest`, `ActiveReportsViewModelTest`, `ReportDetailViewModelTest` (accept/archive/409). |

**Network layer** conforms to `orszem-v1.yaml`: `OrszemApi` Retrofit interface,
hand-written DTOs, `safeApiCall` mapping problem+json → the contract error codes.

**Build:** two release APKs via
`./gradlew :public-app:assembleRelease :service-app:assembleRelease`;
unit tests via `./gradlew testDebugUnitTest`. Building on the reference machine
required adding SDK platform 35 + a JDK 21 toolchain and building from a short
path (`C:\oa` junction) to avoid a Windows/OneDrive path issue in AGP.
Verification status is tracked in the completion report.

## Known non-blocking limitations

- `MANIFEST.sha256` still lists the pre-implementation file set / the old
  `db/migration/R__demo_event_catalog.sql` path.
- Android instrumented (Compose UI) tests are written against the modules but not
  executed here (no emulator/device in this environment).
