# ADR 0002 — a single meta endpoint, and Flyway with no migrations

**Status:** accepted · 2026-09-07

## Context

Phase 1 must establish the `/api/v1` baseline, make OpenAPI infrastructure available, and
prove database connectivity and migration discovery — while implementing no business
feature. Two tensions follow:

1. An API prefix with no endpoint behind it cannot be exercised. OpenAPI would describe an
   empty document, no integration test could hit a request path, and the Caddy `/api/*`
   proxy could not be verified end to end.
2. Flyway proves nothing if it is never given a database — but Phase 1 owns no domain
   model, and inventing a table to justify a `V001` file would create schema that no code
   needs and that a later migration would have to undo.

## Decision

**One non-business endpoint: `GET /api/v1/meta`,** returning only `{name, apiVersion}`.

It carries no business data, no secrets and no environment detail. It exists to make the
platform verifiable: the `/api/v1` prefix, the springdoc document, the request path through
Caddy, and the `api → application` layering all become testable through a real request.
It doubles as the worked example in `BACKEND_LAYERING.md`.

**Flyway is fully configured with an empty migration directory.**

At startup Flyway connects, discovers `classpath:db/migration`, finds no migrations, and
creates `flyway_schema_history`. `DatabaseBaselineIT` asserts exactly that, plus that the
public schema contains *nothing else* — a guard against a placeholder table creeping in.
Connectivity and migration discovery are therefore genuinely verified, and the first real
migration arrives in Phase 2 with the first persistent feature.

## Alternatives rejected

- **No endpoint at all.** Leaves the API prefix unexercised, OpenAPI empty and the proxy
  path unverifiable. The acceptance criteria ask for an established `/api/v1` baseline;
  configuration alone does not establish one.
- **A health endpoint under `/api/v1`.** Duplicates Actuator and blurs operational
  concerns into the product contract.
- **A placeholder `V001__init.sql`.** Explicitly ruled out: it creates schema nothing
  needs, and once applied to a persistent environment it is immutable and must be undone
  by a further migration.

## Consequences

- `/api/v1/meta` is public and unauthenticated. It is safe: nothing it returns is
  sensitive, and `ApiProperties` is documented as carrying no secrets.
- It is bootstrap infrastructure, not a product feature. If the API later grows a genuine
  version or capability endpoint, this may be folded into it.
- The first migration will be `V001`, created in Phase 2.
