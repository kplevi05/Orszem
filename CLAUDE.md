# CLAUDE.md — Őrszem V2

## Mission

Build Őrszem V2, the first long-term baseline. Not a demo: V2 evolves
`2.0.0 → 2.0.1 → 2.1.0 → … → production` without resetting itself. Migrations go forward,
application identities stay stable, the API evolves deliberately, and releases are
reproducible.

Do not redesign the product. Do not implement features belonging to a later phase.

## Current phase

**The roadmap (Phases 1–17) is complete. Phase 17 is the V2.0.0 final release**, the last
roadmap phase. The version is `2.0.0`; it is tagged `v2.0.0` only after the Phase 17 PR is
merged and verified. Each phase's evidence is in `docs/PHASE_<n>_ENGINEERING_REPORT.md`; the
release state, artefacts and remaining owner gates are in `docs/PHASE_17_ENGINEERING_REPORT.md`.

Production **deployment** is a separate step from the software release and stays gated on
owner actions (see `docs/DECISIONS_REQUIRING_OWNER.md` §A and the Phase 17 report).

Work after 2.0.0 is maintenance and deliberate evolution (2.0.1, 2.1.0, …). If a task
seems to require a new feature or a product decision nobody has taken, stop and raise it
rather than inventing behaviour.

## Read first

1. `docs/architecture/ARCHITECTURE.md`
2. `docs/architecture/BACKEND_LAYERING.md`
3. `docs/ENGINEERING_RULES.md`
4. `docs/DECISIONS_REQUIRING_OWNER.md`
5. `docs/architecture/adr/`

`docs/archive/` is the Demo v1.1 historical record. **Never cite it as a requirement or a
source of truth.** Anything from V1 that still applies has been restated in the active
documents above.

## Platform

Three clients, one backend:

- Public Android — `hu.orszembejelento.app`
- Public Web (React/TypeScript/Vite, static) — `orszembejelento.hu`
- Service Android — `hu.orszembejelento.service`
- Backend (Kotlin/Spring Boot/PostgreSQL) — `hu.orszembejelento.backend`

**There is no Service Web, permanently.** Do not create one, and do not add a
`service.orszembejelento.hu` host.

The HTTP contract is `/api/v1`. The product being "V2" does not renumber the API.

## Non-negotiable rules

- **Database changes only through new Flyway migrations.** A migration applied to a
  persistent environment is immutable — never edit, renumber or delete it.
- **Authorization is enforced by the backend**, never by a client.
- **Domain code must not depend on Spring MVC, JPA or HTTP types.** Layering is
  `api → application → domain → infrastructure`, inwards only.
- **Controllers hold no business logic.** They map requests to use cases and back.
- **Android and web clients never connect to PostgreSQL directly.**
- **Never commit secrets**: database passwords, signing passwords, keystores, private keys,
  tokens, API secrets.
- **Never log** passwords, hashes, access or refresh tokens, `Authorization` headers, the
  future `X-Report-Access-Token`, or raw GPS coordinates. No global request-body logging.
- **No permissive CORS.** The Public Web calls the API same-origin through the Caddy proxy.
- **No** Redis, Kafka, Kubernetes, GraphQL, message brokers, or separate AI services.
- **No generic `utils` package** as a home for business logic.

## Working style

- Prefer the smallest maintainable implementation that satisfies the specification.
- Keep the repository buildable at every commit.
- Positive, negative and failure-path tests for every state-changing use case.
- Database behaviour is tested against real PostgreSQL via Testcontainers, never H2.
- Do not abstract because two small pieces of code look similar.
- Stable dependency releases only — no snapshots, milestones or release candidates.
- If a reversible technical detail is unspecified, choose a conventional solution and
  record it in an ADR or a code comment.
- If a decision would change product scope, stop and mark it for owner review.

## Build and test

```bash
cd backend       && ./gradlew build
cd android       && ./gradlew :public-app:assembleDebug :service-app:assembleDebug lint
cd web/public-web && npm ci && npm run typecheck && npm run build
```

Backend integration tests require Docker (Testcontainers). Android builds require the
Android SDK with platform `android-37.0`; build tools are left to AGP's default and must
not be pinned.
