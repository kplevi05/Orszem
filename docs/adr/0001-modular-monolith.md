# ADR 0001 — Modular monolith backend

**Status:** Accepted (Demo v1)
**Date:** 2026-09-01

## Context

`docs/architecture/ARCHITECTURE.md` fixes the Demo v1 backend as a single
deployment unit with clear internal module boundaries, one PostgreSQL database
and one versioned REST API. Microservices, message brokers and distributed
transactions are explicitly out of scope.

## Decision

The backend `services/api` is a Spring Boot modular monolith. Business modules
live under `hu.orszem.<module>`:

- `auth` — demo login, JWT issuance and validation
- `identity` — demo service user and profile / capabilities
- `catalog` — event categories and event types
- `reporting` — anonymous public report creation, idempotency
- `servicecase` — active list, detail, accept, archive, archive list
- `analytics` — deterministic read-only aggregations
- `audit` — security / workflow audit events
- `shared` — cross-cutting technical concerns (errors, time, ids, request context)

Each module keeps the dependency direction
`api/controller -> application/usecase -> domain <- infrastructure/adapter`.
Domain code must not depend on Spring MVC, JPA or HTTP types. A module's
controller must not touch another module's persistence directly; modules
integrate through application ports.

## Consequences

- Simple to build, run and demonstrate; one Docker image.
- Module boundaries are enforced by package structure and review, not by the
  build system in Demo v1. A later split into services is possible without a
  domain rewrite.
