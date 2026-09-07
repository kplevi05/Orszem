# ADR 0003 — opaque tokens and server-side sessions

**Status:** accepted · 2026-09-07 · Phase 2

## Context

The Service Android app needs to authenticate against the backend and stay signed in across
app restarts, without asking for a password every time.

The reflexive choice is JWT. It is worth examining why it is the wrong one here.

## Decision

**Opaque bearer tokens backed by server-side session rows. No JWT anywhere.**

```
at_<session-uuid>.<secret>       access token,  15 minutes
rt_<refresh-token-uuid>.<secret> refresh token, rotates on every use
```

The secret is 256 bits of CSPRNG output, Base64url-encoded. Only its SHA-256 digest is
stored, as `BYTEA`, and comparison is constant-time.

### Why not JWT

A JWT earns its complexity when a resource server must validate a token *without* asking the
issuer — many services, or a separate authorization server. None of that applies:

- one backend
- one database
- one client type

Every request already touches PostgreSQL, so a self-contained token saves nothing. What it
would cost is the property that matters most here: **immediate revocation**. A signed JWT is
valid until it expires, so logout, deactivation and role changes would all take effect
"eventually". With an opaque token, the server reads current state on every request, and a
deactivation applied one second ago is enforced on the next call. That is tested.

JWT would also have added key management, algorithm-confusion risk, and the standing
temptation to trust claims embedded in the token. Role is deliberately **not** in the token:
it is read from the `users` row every time.

### Why SHA-256 for token secrets but Argon2id for passwords

They defend against different things. A password is low-entropy and human-chosen, so a
stolen hash must be made expensive to attack — hence Argon2id with a deliberate memory cost.
A token secret is 256 uniformly random bits: there is no guessable distribution to attack, so
a slow hash would add latency to every authenticated request while defending against nothing.

### Refresh rotation and reuse detection

Each refresh consumes the presented token and issues a new one. The consumed row is kept for
the life of the session, because that record is what makes replay detectable.

Presenting an already-consumed token revokes the **entire session**, not just that token.
Replay means either the token was stolen and the thief is using it after the real client
rotated, or the real client is using it after a thief did. Nothing distinguishes those cases,
so the safe response is to end the session.

### Lock order

Every mutating flow takes row locks in one order:

```
USER  ->  SESSION  ->  REFRESH TOKEN
```

Two flows taking these in opposite orders would deadlock. Locking the user row *first* also
closes a real credential race: a password reset and a login using the old password serialise
on the same row, so the login cannot slip a new session in between the reset's verification
and its commit. Both are covered by concurrency tests using real threads.

## Consequences

- Every authenticated request costs a database read. At pilot scale this is nothing, and it
  buys immediate revocation.
- Sessions and consumed refresh tokens accumulate. Indexes exist so cleanup is cheap when it
  is added; see the retention note in ADR 0004.
- Horizontal scaling is unaffected — session state is in PostgreSQL, not in memory. The one
  in-memory component is the rate limiter, which is documented as single-instance.

## Two bugs this design produced, and how they were fixed

Recorded because both were subtle and both were caught by tests rather than review.

1. **Reuse detection revoked inside the transaction it then aborted.** Signalling failure by
   throwing rolled back the revocation, so a replayed token failed the request but left the
   session alive. Refresh now returns a result instead of throwing, and the transaction
   commits.

2. **The first attempt to fix that deadlocked against itself.** Revoking in a `REQUIRES_NEW`
   transaction meant the inner transaction blocked trying to update a session row that its
   own suspended parent already held a `FOR UPDATE` lock on. Returning a result removes the
   second transaction entirely.
