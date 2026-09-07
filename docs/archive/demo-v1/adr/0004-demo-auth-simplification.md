> **ARCHIVED — Demo v1.1 historical record.**
> This document describes the Demo v1 / v1.1 product as it stood at tag `demo-v1.1-final`
> (commit `38540f6`). It does **not** govern Őrszem V2 and must never be cited as a V2
> requirement or source of truth. See `docs/archive/DEMO_V1_1.md` for the archive record.
>
> *Archivált Demo v1.1 dokumentum — kizárólag történeti referencia. A V2-re nem érvényes.*

# ADR 0004 — Demo authentication simplification

**Status:** Accepted (Demo v1)
**Date:** 2026-09-01

## Context

Production identity (Super Admin, Moderator, hierarchical password reset,
refresh tokens, sessions, area scoping) is explicitly out of Demo v1 scope.
The service app still needs a real, working login.

## Decision

- One seeded `SERVICE_USER` (`demo.service`). Password verified with **Argon2id**
  (`org.springframework.security.crypto.argon2.Argon2PasswordEncoder`, m=65536,
  t=3, p=1). Only the hash is stored in PostgreSQL.
- On success the backend issues a signed **JWT** access token (HS256), default
  TTL **8 hours**, `sub` = user id, `capabilities` claim carrying the fixed
  Demo v1 capability set. No refresh token, no server-side session, no logout
  endpoint (the client discards the token).
- All `/service/**` endpoints except `/service/auth/login` require a valid
  Bearer token; capability checks are enforced on the backend.
- The JWT signing secret is supplied via configuration / environment
  (`ORSZEM_JWT_SECRET`), never committed. `local`/`demo` profiles carry a
  clearly-marked non-production development secret.
- The documented plaintext demo password is a Demo v1 fixture and appears only
  in demo docs and demo seed comments.

## Consequences

- Minimal but genuine auth suitable for a live demo.
- The `identity` / `auth` module boundary leaves room for the Production
  hierarchy without a rewrite.
