# Őrszem V2 — engineering rules

Active, binding rules for V2 work. This document is self-contained: it does not inherit
anything by reference from `docs/archive/`, which is historical record only.

## 1. Source priority

When two sources disagree, the higher one wins. Do not improvise a third answer; follow the
higher source and write down the discrepancy.

1. **HTTP contract** — the generated OpenAPI document for `/api/v1`
2. **Persistence** — the applied Flyway migrations, which are the truth about the schema
3. **Product scope** — the agreed scope for the current phase
4. **UX** — screen and interaction specifications
5. **Reference data** — `docs/product/EVENT_CATALOG_V2.md` and `reference-data/`

## 2. Database changes

- **Forward only.** A migration that has been applied to any persistent environment is
  immutable. Never edit, renumber or delete it; correct it with a new migration.
- No schema change outside a Flyway migration.
- No seed, demo or fixture data inside migrations. Reference data is loaded explicitly.
- `flyway.clean` stays disabled everywhere.
- Nothing is hard-deleted that a record may reference. Deactivate instead.

## 3. Backend layering

`api → application → domain → infrastructure`, and never the other way round.

- Controllers map HTTP to use cases and back. They hold no business logic.
- Domain code must not depend on Spring MVC, JPA or any HTTP type.
- Authorization is enforced in the backend, never in a client. A client may hide a control
  for usability, but the server decides.
- No generic `utils` package as a home for business logic.

## 4. API evolution

- The HTTP generation is `/api/v1`. The product being "V2" does not renumber the API.
- Breaking changes get a new generation alongside the old one; they do not mutate `v1`.
- The Public Web reaches the API same-origin at `/api/v1`; Android clients use
  `https://api.orszembejelento.hu/api/v1`. Both hit the same backend and the same use
  cases. The API is never implemented twice.
- No permissive `Access-Control-Allow-Origin: *`. Same-origin proxying is the design.

## 5. Secrets

Never committed: database passwords, signing passwords, keystores, private keys, access
tokens, API secrets. Configuration comes from the environment; `*.example` files carry
placeholders only.

## 6. Logging

Never log, at any level, in any environment: passwords, password hashes, access or refresh
tokens, `Authorization` header values, the future `X-Report-Access-Token` header, signing
secrets, values read from the environment, or raw GPS coordinates.

Global request/response body logging stays off. A future feature needing diagnostics adds
a targeted, redacting logger — never a blanket body dump.

## 7. Tests

- Positive, negative and failure-path tests for every state-changing use case.
- Database behaviour is tested against **real PostgreSQL** via Testcontainers. H2 is not a
  stand-in for PostgreSQL and is not used.
- Tests must not depend on the developer's machine or on a shared environment.

## 8. Dependencies

- Stable releases only. No snapshots, no milestones, no release candidates.
- Do not add Redis, Kafka, Kubernetes, GraphQL, message brokers or separate AI services.
- Prefer the smallest maintainable implementation that satisfies the specification.
- Do not abstract because two small pieces of code look similar. Shared code is extracted
  when duplication is real, not anticipated.

## 9. Scope discipline

- If a reversible technical detail is unspecified, choose a conventional solution and
  record it in an ADR or a code comment.
- If a decision would change product scope, stop and raise it for owner review rather than
  inventing behaviour. Open items live in `DECISIONS_REQUIRING_OWNER.md`.
- Keep the repository buildable at every commit.
