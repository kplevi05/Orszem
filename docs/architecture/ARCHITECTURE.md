# Őrszem V2 — architecture

Version 2.0 · baseline established 2026-09-07

## 1. Platform

V2 has exactly **three** clients and one backend.

```
Public Android  ─┐
                 │
Public Web ──────┼──►  Spring Boot backend  ──►  PostgreSQL
                 │
Service Android ─┘
```

The backend is the single source of business truth. Clients render and collect; they never
decide authorization, and they never talk to PostgreSQL directly.

**There is no Service Web.** The Service client is Android only. Consequently there is no
`service.orszembejelento.hu` host, and none is configured anywhere.

## 2. Identities

| Component | Identity |
|---|---|
| Public Android | `hu.orszembejelento.app` |
| Service Android | `hu.orszembejelento.service` |
| Backend package root | `hu.orszembejelento.backend` |
| HTTP contract | `/api/v1` |

The API generation is `v1` — the first published Őrszem contract. The product being "V2"
does not renumber the API.

## 3. Domains and routing

| Host | Serves |
|---|---|
| `orszembejelento.hu` | static Public Web, plus `/api/*` proxied to the backend |
| `www.orszembejelento.hu` | permanent redirect to the apex |
| `api.orszembejelento.hu` | the same backend, for the Android clients |

The browser therefore always calls the API **same-origin**, which is why no CORS header is
needed and none is configured. A permissive `Access-Control-Allow-Origin` would be a
regression, not a convenience.

Both routes reach the same backend and the same use cases. The API is never implemented
twice.

## 4. Runtime topology

```
            internet
               │  443
        ┌──────▼──────┐
        │    Caddy    │   TLS, static files, /api/* proxy
        └──────┬──────┘
               │  127.0.0.1:8081
        ┌──────▼──────┐
        │ Spring Boot │   systemd unit, own user, loopback only
        └──────┬──────┘
               │  127.0.0.1:5432
        ┌──────▼──────┐
        │ PostgreSQL  │   never published, never proxied
        └─────────────┘
```

Caddy is the only process reachable from the internet. Spring Boot binds `127.0.0.1` by
default; PostgreSQL is never exposed. Actuator, the OpenAPI document and Swagger UI are
served by the backend but are **not** routed publicly by Caddy.

Deliberately absent: Docker as a requirement, Kubernetes, microservices, message brokers,
Redis, a Node application server in production.

## 5. Backend structure

A modular monolith. Each business module is a vertical slice with the layering described in
`BACKEND_LAYERING.md`.

Phase 1 contains only what proves the platform works:

```
hu.orszembejelento.backend
├── BackendApplication.kt
├── common
│   ├── config    ApiProperties, OpenApiConfig
│   └── web       ApiPaths — the single /api/v1 constant
└── meta
    ├── api           MetaController
    └── application   GetApiMetaUseCase
```

Future modules — `auth`, `users`, `reports`, `routing`, `moderation`, `analytics`, `audit` —
are named here as intent. They are **not** present as empty packages; each appears when it
has real behaviour.

## 6. Persistence

PostgreSQL, schema owned by Flyway, forward-only.

Phase 1 has no domain model and therefore **no migrations**. Flyway is fully configured and
runs at startup: it connects, discovers the migration location, finds nothing, and creates
`flyway_schema_history`. That is asserted by `DatabaseBaselineIT`, so connectivity and
migration discovery are verified rather than assumed. No placeholder table is invented to
make a `V001` exist.

## 7. Clients

**Public Android** — Kotlin, Jetpack Compose, Material 3, `minSdk 26`,
`compileSdk`/`targetSdk` 37. Light, civilian-facing identity.

**Service Android** — same technology baseline. Dark navy ground with a restrained gold
accent; an operational tool meant to be readable trackside and at night.

**Public Web** — React, TypeScript, Vite. Static output served by Caddy. No SSR, no Node
server in production, no web login: the Public Web is anonymous by design.

Shared Android code is introduced only where duplication is real. Phase 1 has none, so
there is no `core` module. The two apps have deliberately different product identities and
are not forced to share a design system.

## 8. What Phase 1 deliberately excludes

Authentication, users, reports, taxonomy implementation, routing, moderation, analytics,
audit UI, GPS, report submission, media upload, free text, AI/LLM/NLP, push notifications,
real-time transport, offline support, device binding, MFA, multi-tenancy — and a Service
Web, which is out of scope permanently rather than deferred.

## 9. Relationship to Demo v1.1

Demo v1.1 is archived at the `demo-v1.1-final` tag and documented in
`docs/archive/DEMO_V1_1.md`. Its data, authentication model, database schema, application
IDs and API are **not** carried into V2. Material that survived was re-validated and
restated in active V2 documents; the archive itself is historical record and is never cited
as a requirement.
