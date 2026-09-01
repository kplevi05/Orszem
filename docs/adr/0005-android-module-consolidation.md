# ADR 0005 — Android module granularity

**Status:** Accepted (Demo v1) — reversible
**Date:** 2026-09-01

## Context

`ARCHITECTURE.md` §4 sketches a fine-grained Android module layout with eight
`feature/*` modules and split `designsystem-public` / `designsystem-service`
modules. Module count is a structural, reversible choice; it does not affect
product scope, the API contract, database semantics, privacy or security.

## Decision

For Demo v1 the Android build under `apps/android/` uses:

- `:core:common`, `:core:model`, `:core:network`, `:core:designsystem`, `:core:testing`
- `:public-app`, `:service-app`

The two design systems live as separate `OrszemPublicTheme` / `OrszemServiceTheme`
entry points inside one `:core:designsystem` module (they are token sets, not
independent components). Feature code lives in `feature/*` **packages** inside
each application module, keeping the layering intact
(`ui -> viewModel/UiState -> repository -> network`).

## Consequences

- Fewer Gradle modules to configure and build; faster iteration for a demo.
- The two apps stay genuinely separate (ADR 0002 unchanged); shared code is still
  isolated in `core/*`.
- Splitting a feature into its own module later is mechanical (move the package,
  add a `build.gradle.kts`) and needs no rewrite.
