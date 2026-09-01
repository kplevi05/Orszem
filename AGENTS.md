# AGENTS.md — Őrszem Demo v1

This repository implements the approved **Őrszem Demo v1**. The canonical
requirements live in `MASTER_PROMPT_DEMO_V1_FINAL.md` and the documents it
references. Treat those documents as the single source of truth.

## Layout

| Path | Contents |
| --- | --- |
| `contracts/openapi/orszem-v1.yaml` | Canonical HTTP contract (OpenAPI 3.1) |
| `contracts/catalog/event-types.demo-v1.json` | Machine-readable event catalog |
| `services/api/` | Kotlin / Spring Boot modular-monolith backend |
| `apps/android/` | Two separate Android apps (`public-app`, `service-app`) + shared modules |
| `infra/` | Docker Compose + Dockerfile for local/demo runtime |
| `scripts/` | Demo reset / verification scripts |
| `docs/` | Product, UX, architecture, testing, implementation docs + ADRs |

## Build & test

Backend (needs Docker; uses a JDK 21 toolchain):

```bash
cd services/api
./gradlew build            # compile + unit + integration (Testcontainers) tests
./gradlew bootRun          # run locally (expects Postgres from infra/compose)
```

Local runtime:

```bash
docker compose -f infra/compose/docker-compose.yml up -d
```

Contract lint:

```bash
npx --yes @redocly/cli@latest lint contracts/openapi/orszem-v1.yaml
```

Android:

```bash
cd apps/android
./gradlew :public-app:assembleRelease :service-app:assembleRelease
./gradlew testDebugUnitTest
```

## Rules for agents

- Do not expand Demo v1 scope or implement Production-only features.
- Change the DB schema only through new Flyway migrations; never rewrite an applied one.
- Keep public HTTP shapes conformant to `contracts/openapi/orszem-v1.yaml`.
- Enforce authorization / workflow rules in the backend, not only the UI.
- Never commit real secrets. Never log passwords, JWTs, or Authorization headers.
- Keep `docs/implementation/BUILD_STATUS.md` current after each milestone.
