# ADR 0001 — V2 technical baseline

**Status:** accepted · 2026-09-07

## Context

Demo v1.1 was a throwaway demonstration product: a hand-written OpenAPI contract, demo
fixtures delivered through Flyway, release APKs signed with whatever key was to hand, a
public deployment carrying a published credential, and application IDs chosen for a demo.

V2 is the first long-term baseline. It must survive many versions: `2.0.0 → 2.0.1 → 2.1.0
→ … → production`, with forward-only migrations, stable application identities, deliberate
API evolution and reproducible releases.

Continuing from the V1 tree would have carried its assumptions forward silently.

## Decision

Start V2 from a clean tree, preserving V1 through Git rather than through duplication.

- **V1 is archived at the annotated tag `demo-v1.1-final`** and its source removed from the
  V2 tree. Its documentation is kept under `docs/archive/`, banner-marked as historical
  record, and is never cited as a V2 requirement. Material that survives was re-validated
  into new active documents.
- **New identities** (`hu.orszembejelento.*`), so V1 and V2 can coexist on a device and
  neither can masquerade as an upgrade of the other.
- **Monorepo**, with one tree per deliverable: `backend/`, `android/`, `web/public-web/`,
  `reference-data/`, `deploy/`, `docs/`, `scripts/`.
- **Stable releases only**: Kotlin 2.3.21, Spring Boot 4.1.1, Gradle 9.7.1, AGP 9.4.0,
  React 19, TypeScript 7, Vite 8, Java 21.
- **No business feature in Phase 1.** No authentication, reports, taxonomy implementation,
  routing, moderation or analytics.

## Deviations from the requested structure, and why

- **`deploy/` instead of putting deployment config under `scripts/`.** `infra/` was removed
  with V1, and `scripts/` is for scripts. Deployable configuration is a different kind of
  artifact and gets its own directory.
- **No `android/core` module.** The brief allowed one "only if genuinely useful". In
  Phase 1 nothing is shared: there is no network layer, no model and no common UI beyond a
  per-app theme. Extracting a module now would be abstraction ahead of duplication. A
  shared version catalogue covers version alignment. A module appears when duplication is
  real.
- **No `buildSrc` or convention plugins.** Two Android modules do not justify the
  indirection.

## Consequences

- A V1 hotfix means checking out the tag. This is accepted: the demonstration is over.
- The V1 signing identity is not reused, so there is no upgrade path from V1 to V2. That is
  intended — the application IDs differ anyway.
- Each phase adds only what it needs, so the tree always reflects real behaviour.
