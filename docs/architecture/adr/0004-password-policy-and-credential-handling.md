# ADR 0004 — password policy, blocklist and credential handling

**Status:** accepted · 2026-09-07 · Phase 2

## Password hashing

**Argon2id**, via Spring Security's `Argon2PasswordEncoder` (BouncyCastle underneath), with
explicit parameters at the OWASP baseline:

| Parameter | Value |
|---|---|
| memory | 19 MiB |
| iterations | 2 |
| parallelism | 1 |
| salt | 16 bytes |
| hash | 32 bytes |

Written out rather than left to defaults, because a silently changed default is how cost
regressions happen unnoticed. The parameters are not raised beyond the baseline without
benchmark evidence: memory cost is paid on every login, and an unmeasured increase buys
latency rather than security. A test asserts the stored hash really is `$argon2id$` at these
parameters, so a fallback to bcrypt or Argon2i would fail the build.

Hashing sits behind a small `PasswordHasher` boundary so the cost can be raised deliberately
later without touching any use case.

## Password policy: length only

- **15 to 128 Unicode code points**
- no composition rules
- no expiry

Composition rules ("one uppercase, one digit, one symbol") measurably push people toward
predictable patterns like `Password1!` while excluding strong passphrases. Forced periodic
rotation produces incremental, guessable variations of the previous password. Current
guidance (NIST SP 800-63B) recommends length plus a blocklist instead, which is what this is.

Length is counted in **code points**, not UTF-16 units, so an emoji or astral-plane character
counts once rather than twice.

The 128 upper bound exists only so unbounded input cannot be fed into a memory-hard hash as a
cheap denial of service. The API additionally caps the raw field length.

## Normalization

Passwords are normalized to **Unicode NFC** and nothing else.

The same normalization runs when a password is set and when it is verified — that is the
entire point. If the two differed, a user could set a password they could never type again:
`á` entered as one code point and as `a` + combining accent are the same password after NFC,
and different without it.

Passwords are **not trimmed**. A leading or trailing space is a character the user chose, and
removing it silently changes their password. The Android password fields likewise do not trim
and do allow paste.

## Blocklist

Offline, local, no network call — no Have I Been Pwned lookup. Authentication must not depend
on a third party being reachable, and sending anything derived from a user's password to an
external service is its own risk.

The list is curated for this project rather than imported, so there is no upstream licence or
attribution to carry.

**Why it is small, and why its contents look unusual.** The 15-character minimum already
rejects almost every classic weak password — `password`, `123456`, `qwerty`, `admin` are all
far too short to reach the blocklist at all. A conventional "top 10 000" list would therefore
be almost entirely dead weight. What actually threatens a 15-character minimum is the small
set of ways people reach that length without adding entropy:

- repeating a weak word (`passwordpassword`)
- long keyboard walks (`qwertyuiopasdfgh`)
- long digit runs (`123456789012345`)
- one repeated character
- famous passphrases (`correcthorsebatterystaple`)
- project, product and domain names (`orszembejelento`)

Comparison folds to NFC, lower case and trimmed — but **only for the comparison**. What gets
hashed and stored is always the user's NFC-normalized original, untrimmed and with its
original case.

## Temporary credentials

16 characters from a 32-symbol unambiguous alphabet (`23456789ABCDEFGHJKLMNPQRSTUVWXYZ` — no
`0`/`O`, no `1`/`I`), which is exactly 5 bits per character and **80 bits** of entropy.

Displayed grouped as `XXXX-XXXX-XXXX-XXXX` purely so a person can transcribe it. The hyphens
are formatting: the credential is accepted with or without them and in any case. That
tolerance applies **only** to temporary credentials, never to user-chosen passwords.

A temporary credential stays valid until the password change actually succeeds. A login
attempt does not consume it — so if the app dies between the login response and the change,
the user can start again rather than being locked out of an account nobody can reach.

## Enumeration resistance

Unknown service ID, wrong password and deactivated account all return the same
`401 INVALID_CREDENTIALS`, with byte-identical bodies apart from the correlation ID.

Timing is levelled too: when no user exists, the password is still verified against a dummy
Argon2 hash generated once at startup. Without it, an unknown account would return in
microseconds while a real one paid the full Argon2 cost, and that difference alone would
enumerate valid service IDs.

## Rate limiting, and its accepted limitation

Two independent buckets — per service ID and per source IP — counted separately rather than
as one `(ip, serviceId)` pair, which would hand an attacker a fresh budget for every address
they control or every ID they try.

**No account lockout.** A permanent lock turns a guessing attempt into a denial of service
against the real user: anyone knowing a service ID could keep that person signed out
indefinitely. Attempts decay instead.

Backed by Caffeine with bounded size and time expiry, so the limiter cannot grow without
limit under attack — the failure mode of the obvious `ConcurrentHashMap`.

> **Accepted limitation:** the counters are in memory and reset when the backend restarts,
> and each instance would count independently. That is acceptable for a single-server pilot
> and avoids introducing Redis for this alone. It must be revisited before running more than
> one backend instance.

## Retention obligation

Expired sessions and consumed refresh tokens are deliberately kept: the consumed rows are
what make replay detectable. Indexes on `session_expires_at` and `expires_at` exist so a
future cleanup can find old rows cheaply.

No scheduler is introduced in Phase 2. Pilot-scale accumulation is fine, but **a bounded
retention policy is required before production scale** and is recorded as an open item.
