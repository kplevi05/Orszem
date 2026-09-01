# CLAUDE.md — Őrszem Demo v1

## Mission

Implement the approved Őrszem Demo v1. Do not redesign the product and do not implement Production-only features.

## Read first, in this order

1. `docs/product/DEMO_V1_SCOPE.md`
2. `docs/ux/DEMO_V1_SCREENS.md`
3. `docs/architecture/ARCHITECTURE.md`
4. `docs/architecture/DATABASE_SCHEMA.md`
5. `docs/architecture/BUSINESS_RULES.md`
6. `docs/product/EVENT_CATALOG.md`
7. `contracts/openapi/orszem-v1.yaml`
8. `docs/testing/DEMO_V1_ACCEPTANCE_TESTS.md`
9. `docs/implementation/IMPLEMENTATION_PLAN.md`

## Non-negotiable boundaries

- Two separate Android applications: Public App and Service App.
- Public App is anonymous.
- No free-text incident description in Demo v1.
- Event type comes from the server-side catalog.
- Report state machine is exactly `NEW -> IN_PROGRESS -> ARCHIVED`.
- No report editing or reopening.
- No Super Admin, Moderator, area routing, password reset, user management, refresh-token subsystem, LLM, NLP, photo upload, push notification, WebSocket, or offline outbox in Demo v1.
- Service authorization is enforced by the backend.
- Public/service Android apps never connect directly to PostgreSQL.
- Database changes only through new Flyway migrations.
- Public HTTP shapes must conform to the OpenAPI contract.
- Never log passwords, access tokens, Authorization headers, or raw GPS coordinates.

## Implementation policy

- Follow `IMPLEMENTATION_PLAN.md` milestone order.
- Keep the repository buildable after each milestone.
- Prefer the smallest maintainable implementation satisfying the specification.
- Do not introduce Redis, Kafka, Kubernetes, GraphQL, message brokers, or separate AI services.
- Do not create generic `utils` dumping grounds for business logic.
- Domain code must not depend on Spring MVC/JPA/HTTP types.
- Add positive, negative, and failure-path tests for state-changing use cases.
- If a reversible technical detail is unspecified, choose a conventional solution and record it in an ADR or code comment where appropriate.
- If a decision would change product scope, stop and mark it for owner review instead of inventing behavior.

## Demo baseline

Demo reset must produce:
- total reports: 120
- today: 16
- NEW: 8
- IN_PROGRESS: 6
- ARCHIVED: 106
- active: 14

Demo service login:
- username: `demo.service`
- password: `OrszemDemo!2026`

Credentials are demo-only.

## Definition of Done

Demo v1 is done only when every P0 acceptance test in `DEMO_V1_ACCEPTANCE_TESTS.md` passes and the complete presentation smoke test is reproducible after a demo reset.
