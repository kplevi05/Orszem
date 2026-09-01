# ADR 0002 — Two separate Android applications

**Status:** Accepted (Demo v1)
**Date:** 2026-09-01

## Context

The product requires an anonymous public reporting app (**Őrszem**) and a
login-gated service app (**Őrszem Szolgálat**). They have different users,
different threat models and different visual identities.

## Decision

One Gradle build under `apps/android/` produces two application modules and two
APKs:

- `:public-app` — anonymous, no auth, report creation only
- `:service-app` — demo login, workflow, archive, analytics

Genuinely shared code lives in `core/*` modules
(`common`, `model`, `network`, `designsystem-public`, `designsystem-service`,
`testing`). Feature modules under `feature/*` are consumed by exactly one app.
The two apps are never merged into a single role-switching app.

## Consequences

- Clear separation of the anonymous and authenticated surfaces.
- Each app can be installed independently.
- Shared networking / model code avoids duplication without coupling the apps.
