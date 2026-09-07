> **ARCHIVED — Demo v1.1 historical record.**
> This document describes the Demo v1 / v1.1 product as it stood at tag `demo-v1.1-final`
> (commit `38540f6`). It does **not** govern Őrszem V2 and must never be cited as a V2
> requirement or source of truth. See `docs/archive/DEMO_V1_1.md` for the archive record.
>
> *Archivált Demo v1.1 dokumentum — kizárólag történeti referencia. A V2-re nem érvényes.*

# ADR 0003 — OpenAPI as the canonical HTTP contract

**Status:** Accepted (Demo v1)
**Date:** 2026-09-01

## Context

`contracts/openapi/orszem-v1.yaml` is the canonical public HTTP contract for
both Android clients. The backend and clients must not diverge from it.

## Decision

- DTOs on the backend and the network models on Android are hand-written to
  match the OpenAPI schemas one-to-one (single representation per shape, no
  code generation in Demo v1 to keep the dependency count low).
- Errors use `application/problem+json` with a stable machine-readable `code`
  field, exactly as enumerated in the contract
  (`VALIDATION_ERROR`, `EVENT_TYPE_INVALID`, `OCCURRED_AT_IN_FUTURE`,
  `REPORT_ID_CONFLICT`, `INVALID_CREDENTIALS`, `UNAUTHORIZED`,
  `REPORT_NOT_FOUND`, `REPORT_NOT_ACCEPTABLE`, `REPORT_NOT_ARCHIVABLE`,
  `RATE_LIMITED`, `INTERNAL_ERROR`).
- The contract is linted in CI (`@redocly/cli`). A CI check compares it against
  the committed baseline to catch unintended breaking changes.
- If an implementation reveals a genuine contract error, the implementation is
  fixed first; the YAML is changed only when the specification itself requires
  it, and an externally visible change is escalated to the owner.

## Consequences

- One source of truth for request/response shapes.
- Slightly more manual DTO code, but no generator lock-in and full control over
  Kotlin nullability and serialization.
