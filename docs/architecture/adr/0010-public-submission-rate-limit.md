# ADR 0010 — Rate limiting for anonymous Public report creation

**Status:** accepted (owner decision B6, V2.0.1). Supersedes ADR 0008 Decision 8 (which deferred it).

## Context

`POST /api/v1/public/reports` is anonymous by design: no account, no login, no CAPTCHA. Phase 4
deferred abuse control, and Phase 17 measured that 60 rapid anonymous submissions were all
accepted. Public deployment needs a defence that does not weaken anonymous reporting, does not
reveal whether a report or credential exists, never blocks the legitimate retry of an already
accepted submission, and needs no paid service, Redis, CAPTCHA, fingerprinting or third-party
abuse tooling.

## Decision

A **token bucket per source**, enforced in the backend on the report-creation path.

| Aspect | Decision |
|---|---|
| Capacity (burst) | 10 |
| Refill | 1 token every 20 seconds (about 3 a minute, at most 180 an hour, per source) |
| Source key | the address resolved by the existing `ClientIpResolver`; IPv4 exact, IPv6 by its first 64 bits, IPv4-mapped IPv6 as IPv4 |
| Scope | attempts that would **create** a report. Lookups, replays and idempotency conflicts are never limited |
| Refusal | HTTP 429, the existing `RATE_LIMITED` code, `Retry-After` in whole seconds (rounded up, at least 1), `Cache-Control: no-store` |
| Storage | in process memory, bounded, per instance |
| Logging | at most one WARN a minute, carrying only a count; **no address or key is ever logged or stored** |
| Configuration | `orszem.public-submission-rate-limit.*` (`ORSZEM_PUBLIC_SUBMISSION_RATE_LIMIT_ENABLED`, `_BURST`, `_REFILL_PERIOD`, `_MAX_KEYS`) |

### Ordering, and why replay is safe

In `SubmitReportUseCase` the `clientSubmissionId` lookup runs **first**. A request whose id already
exists (an identical replay, or a 409 mismatch) returns before the limiter is consulted, so it can
never be throttled, however exhausted its source is. Only a request that reaches the creation path
takes a token. If a concurrent identical submission wins the insert and this call therefore becomes
a replay, the token is **refunded**, so a race never costs a legitimate client capacity.

The price is one indexed lookup per throttled request. Acquiring the token first would avoid it, but
would make a replay from an exhausted source fail, which the owner ruled out.

### Trusted proxy and spoofing

Nothing new is trusted. Caddy overwrites `X-Forwarded-For` with the real TCP peer; the backend
believes that header only from a configured trusted proxy (loopback by default) and then uses only
its **last** entry; an unparseable value falls back to the peer. Consequently a client cannot mint a
fresh bucket by inventing addresses, nor throttle somebody else by naming them. These properties are
tested, through the real filter chain, in `PublicSubmissionRateLimitIT`.

### Algorithm and memory

A virtual-scheduling bucket (GCRA): one number per source (its "theoretical arrival time"), updated
atomically inside Caffeine's per-key `compute`, so concurrent requests from one source cannot
overspend it. Time comes from the injected `Clock`. Memory is bounded twice: an entry expires once
its bucket would be full again, and the cache is capped at `maximum-tracked-keys` (default 100,000).
Beyond the cap the least valuable entries are evicted and an evicted source simply starts again with
a full bucket, so memory never grows with traffic. Eviction runs on the calling thread.

## Consequences

- **Single instance only.** The counters are per process. That is correct for the current single
  backend instance and a restart resets them (acceptable for a brief window, as with login throttling).
- **Horizontal scaling requires a design first.** With N backend instances behind a load balancer each
  instance keeps its own counters, so the effective limit becomes roughly N times the configured one
  and a source can be spread across instances. **Do not add a second backend instance before a
  shared rate-limit design exists.** The natural options are a small PostgreSQL-backed counter (a new
  forward migration and one write per creation attempt), sticky routing by source at the load
  balancer, or a shared store. Redis is excluded unless the owner approves it. This ADR does not
  choose among them.
- **Shared addresses.** Many mobile subscribers can share one IPv4 address (carrier-grade NAT), so a
  burst of 10 was chosen to allow a household, a train carriage of passengers reporting one incident,
  and corrections. If real traffic shows legitimate blocking, tune the burst; that needs no release.
- **Not a defence against a distributed flood.** A botnet using many sources is not stopped by a
  per-source limit. Doing so needs CAPTCHA or reputation services, which are out of scope by decision.
- A throttled report is **not lost**: both Public clients keep it `PENDING` under the same
  `clientSubmissionId` and credential, show the approved Hungarian message, and require a manual retry.
  Neither client retries automatically.
- The public error-code set is unchanged; OpenAPI documents the 429 on report creation.

## Verification

`PublicSubmissionRateLimiterTest` (26, fake clock) and `PublicSubmissionRateLimitIT` (20, real HTTP and
PostgreSQL) cover burst, refill, `Retry-After`, IPv4/IPv6/IPv4-mapped keys, forged and garbage
forwarding headers, replay and 409 bypass, refund correctness, bounded memory and expiry, races, log
hygiene and the OpenAPI contract. Two negative proofs were run: acquiring the token before the replay
check fails four tests, and removing the refund fails the race test.
