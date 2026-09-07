# Őrszem V2

Reporting platform for railway incidents: a public reporting channel and a service-side
handling application, sharing one backend.

**Status: Phase 1 — technical baseline.** The foundation is in place and verified. No
business feature is implemented yet: no authentication, no report submission, no
taxonomy, no analytics.

Demo v1.1 is archived at the tag `demo-v1.1-final` and documented in
`docs/archive/DEMO_V1_1.md`. Its data, schema, identities and API are not carried into V2.

## Platform

```
Public Android  ─┐
                 │
Public Web ──────┼──►  Spring Boot backend  ──►  PostgreSQL
                 │
Service Android ─┘
```

Three clients, one backend. There is no Service Web.

| Component | Identity |
|---|---|
| Public Android | `hu.orszembejelento.app` |
| Service Android | `hu.orszembejelento.service` |
| Backend | `hu.orszembejelento.backend` |
| HTTP contract | `/api/v1` |

## Layout

```
backend/            Kotlin + Spring Boot + PostgreSQL + Flyway
android/            public-app, service-app (one Gradle build)
web/public-web/     React + TypeScript + Vite, static output
reference-data/     datasets (Phase 3)
deploy/             Caddyfile, systemd unit, env template
docs/               architecture, product, deployment; archive/ is historical only
scripts/
```

## Requirements

| Tool | Version |
|---|---|
| JDK | 21 (Temurin) |
| Node | 24 |
| Android SDK | platform `android-37.0`; build tools chosen by AGP, not pinned |
| Docker | needed only for backend integration tests |

## Backend

```bash
cd backend
./gradlew build          # unit + Testcontainers integration tests
./gradlew bootRun
```

Database credentials have no defaults, so a misconfigured run fails loudly rather than
reaching the wrong database:

```bash
export ORSZEM_DB_URL=jdbc:postgresql://localhost:5432/orszem_v2
export ORSZEM_DB_USERNAME=...
export ORSZEM_DB_PASSWORD=...
```

Then:

```bash
curl http://127.0.0.1:8080/actuator/health   # {"status":"UP"}
curl http://127.0.0.1:8080/api/v1/meta
```

The server binds to `127.0.0.1` by default — Caddy is the only public edge. Flyway is
configured and runs, but Phase 1 has no migrations by design; see
`docs/architecture/adr/0002-meta-endpoint-and-empty-flyway.md`.

## Android

```bash
cd android
./gradlew :public-app:assembleDebug :service-app:assembleDebug
./gradlew lint
```

Both apps are Compose shells. The API base URL is a per-build-type `BuildConfig` field —
debug points at the emulator loopback, release at `https://api.orszembejelento.hu/` — and
can be overridden with `-PORSZEM_API_BASE_URL=…`.

Release builds are left **unsigned** unless signing material is supplied; see
`docs/deployment/ANDROID_SIGNING.md`. No keystore is committed.

## Public Web

```bash
cd web/public-web
npm ci
npm run dev        # dev server, proxies /api to localhost:8080
npm run typecheck
npm run build      # static output in dist/
```

The browser always calls the API same-origin at `/api/v1`, so no CORS configuration is
needed anywhere.

## Deployment

Prepared, not yet executed. See `docs/deployment/`:

- `SERVER_RUNBOOK.md` — Caddy → Spring Boot → PostgreSQL on the existing VM
- `DNS.md` — the records required before HTTPS can be issued
- `V1_DATABASE_ARCHIVE.md` and `V1_DECOMMISSION.md` — outstanding V1 wind-down
- `ANDROID_SIGNING.md` — the V2 release key, to be generated on a secure machine

## Contributing

Read `AGENTS.md` and `docs/ENGINEERING_RULES.md` first. In short: forward-only migrations,
authorization in the backend, framework-free domain, no committed secrets, real PostgreSQL
in tests, and nothing from `docs/archive/` treated as a requirement.
