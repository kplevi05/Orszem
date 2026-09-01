# Őrszem Demo v1 — Build status

_Updated per milestone. Newest milestone on top._

## Environment notes

| Tool | Status |
| --- | --- |
| JDK 21 | Not installed system-wide. A Temurin 21 build is bootstrapped into `.toolchain/` (git-ignored) and used as the Gradle toolchain / `JAVA_HOME`. |
| Gradle | Via the committed wrapper (`./gradlew`, Gradle 8.14). |
| Docker | Docker Desktop present. The docker-java client bundled with Testcontainers cannot talk to this Docker Desktop's API gateway directly, so backend integration tests run through a Docker-in-Docker sidecar — see `scripts/dev-backend-test.sh`. Plain Linux CI runs `./gradlew build` directly. |
| Android SDK | `platforms/android-37`, `build-tools/36.0.0`, `platform-tools` present. No `cmdline-tools`. APK assembly uses the committed Android Gradle wrapper + this SDK. |
| Node | v24 — used for `@redocly/cli` contract lint. |

---

## M0 — Repository skeleton & reproducible build ✅

**Completed**

- Root config: `.editorconfig`, `AGENTS.md`, `.gitignore` updates, `.env.example`.
- ADRs `0001`–`0004` (`docs/adr/`).
- Backend Gradle project `services/api` (Kotlin 2.1.20 / Spring Boot 3.4.5 / Java 21):
  build script, wrapper, `application.yml` + `application-local.yml` + `application-demo.yml`,
  `OrszemApiApplication`.
- Flyway wiring: production location `classpath:db/migration` (schema only);
  `local`/`demo` profiles additionally load `classpath:db/demo` (repeatable event-catalog
  migration). `R__demo_event_catalog.sql` moved from `db/migration` to `db/demo` to match
  `EVENT_CATALOG.md` §5 and `DATABASE_SCHEMA.md` §12.
- `infra/docker/api.Dockerfile` (multi-stage) + `infra/compose/docker-compose.yml`
  (PostgreSQL + API).
- Contract lint: `contracts/redocly.yaml`, `contracts/package.json`; `orszem-v1.yaml` lints
  clean (2 style warnings, exit 0).
- `scripts/dev-backend-test.sh` — hermetic backend test runner.

**Tests executed**

- `./gradlew classes` — backend compiles. ✅
- `FlywayMigrationIT` (Testcontainers PostgreSQL 16, via dind):
  - schema migrates cleanly on an empty DB (all tables + `flyway_schema_history`). ✅
  - demo event catalog seeds exactly **7 categories / 61 active event types**. ✅
  - `ck_reports_state_consistency` rejects an inconsistent row. ✅
- `@redocly/cli lint` — OpenAPI contract valid. ✅

**Known non-blocking limitations**

- Android skeleton modules not yet created (tracked for completion within M0/M1 boundary
  before Android features in M2).
- `MANIFEST.sha256` / `README.md` still reference the old `db/migration/R__demo_event_catalog.sql`
  path; to be refreshed in M7 documentation pass.

**Next:** M1 — backend domain model + `GET /public/event-types` + problem+json error handling.
