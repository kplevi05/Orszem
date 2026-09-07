# AGENTS.md — Őrszem V2

Contract for any agent or contributor working in this repository. Self-contained: it
inherits nothing by reference from `docs/archive/`.

## Repository layout

| Path | Contents |
|---|---|
| `backend/` | Kotlin + Spring Boot backend, single Gradle module |
| `android/` | one Gradle build: `public-app`, `service-app` |
| `web/public-web/` | Public Web — React + TypeScript + Vite, static output |
| `reference-data/` | machine-readable datasets (empty until Phase 3) |
| `deploy/` | Caddyfile, systemd unit, environment template |
| `docs/` | active architecture, product and deployment documentation |
| `docs/archive/` | **Demo v1.1 historical record — never a source of truth** |
| `scripts/` | developer scripts |
| `.github/workflows/` | CI: backend, android, web |

## Source of truth

`docs/ENGINEERING_RULES.md` §1 defines the priority order when sources disagree:
HTTP contract → migrations → product scope → UX → reference data. Follow the higher
source and document the discrepancy; do not invent a third answer.

## Build and test commands

```bash
# Backend — unit tests plus Testcontainers integration tests (needs Docker)
cd backend && ./gradlew build
cd backend && ./gradlew bootRun          # needs ORSZEM_DB_USERNAME / ORSZEM_DB_PASSWORD

# Android — debug builds and lint. Never required to sign a release in CI.
cd android && ./gradlew :public-app:assembleDebug :service-app:assembleDebug
cd android && ./gradlew lint

# Public Web
cd web/public-web && npm ci && npm run typecheck && npm run build
```

## Rules for agents

1. **Do not expand the current phase's scope.** Phase 1 is the technical baseline; Phase 2
   is authentication. If a task appears to need a later-phase feature, stop and raise it.
2. **Schema changes only through new Flyway migrations.** Applied migrations are immutable.
3. **Keep HTTP shapes conformant to the generated OpenAPI contract**, and grow the contract
   with the features that are actually implemented — never document endpoints in advance.
4. **Enforce authorization in the backend**, not in the UI.
5. **Never commit real secrets, and never log credentials or tokens.** See
   `docs/ENGINEERING_RULES.md` §5 and §6 for the exact prohibitions.
6. **Respect the layering.** `api → application → domain → infrastructure`, inwards only;
   controllers stay mapping-only; the domain stays framework-free.
7. **Test state changes on all three paths** — positive, negative, failure — against real
   PostgreSQL, not H2.
8. **Do not reuse Demo v1 code, schema, identities or assumptions.** V1 is preserved by the
   `demo-v1.1-final` tag. Neutral infrastructure may be reused only after inspection shows
   it genuinely fits V2.
9. **Keep the repository buildable at every commit**, and never commit build output,
   `local.properties`, `keystore.properties` or `.env`.

## Version and identity facts

- Application IDs: `hu.orszembejelento.app`, `hu.orszembejelento.service`
- Backend package root: `hu.orszembejelento.backend`
- HTTP contract: `/api/v1` — the product version does not renumber the API
- Android `versionCode` increases monotonically for every distributed build
- There is no Service Web, and no web login
