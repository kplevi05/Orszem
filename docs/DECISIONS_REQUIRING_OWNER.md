# Decisions requiring the owner

Active V2 list. Items are added when implementation reaches a point where a technical
choice would silently decide a product question.

Nothing here blocks Phase 1, which is complete without any of these answers.

---

## A. Outstanding actions (not decisions)

These are already decided; they simply have not been carried out, and they need the
owner's access.

| # | Action | Where | Blocking |
|---|---|---|---|
| A1 | Take and verify the V1 database dump | `deployment/V1_DATABASE_ARCHIVE.md` | blocks A2 and any server change |
| A2 | Retire the public V1 deployment | `deployment/V1_DECOMMISSION.md` | security: the demo credential is public |
| A3 | Delegate `orszembejelento.hu` and create the A records | `deployment/DNS.md` | blocks HTTPS and any V2 deployment |
| A4 | Generate and back up the V2 release keystore | `deployment/ANDROID_SIGNING.md` | blocks any V2 distribution |
| A5 | Confirm which keystore holds the V1 pilot signing identity `745f5fa8…a51a`, then back it up | `archive/DEMO_V1_1.md` §4 | blocks any in-place update to installed V1 pilot builds |
| ~~A6~~ | ~~Remediate the VPE-derived reference data being public~~ — **done**, `feature/v2-reference-routing` history rewritten 2026-09-08 | `PHASE_3B_DECISION_GATE.md` §11, `DECISIONS_REQUIRING_OWNER.md` B10 | resolved; B9 (VPE confirmation) remains open separately |

A5 is a one-command check. `orszem-pilot.jks` is only a *candidate* until its certificate
fingerprint is compared against the measured pilot APK certificate; if it does not match,
the signing key for the distributed pilot builds is somewhere else and needs locating.

---

## B. Open product questions

### B1. Free-text description
Demo v1 forbade free text entirely; the reporter chose from the catalogue. Does V2 keep
that, or allow a limited optional note?

*Affects:* report schema, moderation load, privacy exposure, and whether any text
processing is ever needed. **Answer before the report feature is designed.**

**Provisionally settled by Phase 4 (2026-09-08):** the Public reporting backend accepts no
free text anywhere — a report is `occurredAt` + an optional structured `trainIdentifier`
(a code, length-capped, never validated against a catalogue) + `settlementId` + an optional
`railwayLineId` + `eventTypeCode` from the fixed taxonomy. No GPS, no photo/media, no notes
field. This matches Demo v1's model. It is "provisional" in the sense that nothing prevents
a later phase from adding an optional note; Phase 4 itself does not.

### B2. Service areas
How is a service area defined, and can one user belong to more than one?

*Affects:* the user model, authorization, and report routing. **Answer before authentication
is implemented**, because it shapes the permission model.

### B3. Train identification
Demo v1 accepted free-text train identifiers. Does V2 use an authoritative catalogue or a
railway API, and if so which?

*Affects:* reference data, validation, and an external dependency. Relevant to Phase 3.

**Still open after Phase 4.** `trainIdentifier` on a report is an optional, trimmed,
length-capped (64 Unicode code points, `orszem.reports.train-identifier-max-code-points`)
string with no further validation and no catalogue or timetable lookup - deliberately the
simplest thing that satisfies "no free text of unbounded length", not an answer to this
question. No paid railway API was introduced (§53 forbids it without owner approval).

### B4. Settlement list
A controlled list of settlements, or free text as in Demo v1?

*Affects:* reference data and analytics quality. Relevant to Phase 3.

### B5. Data retention
How long is a report kept, and what happens at the end of that period — deletion or
anonymisation?

*Affects:* the schema, a scheduled job, and the privacy notice. **Answer before the first
production deployment**, because retention cannot be applied retroactively to data already
discarded or already kept.

**Widened by Phase 4 (2026-09-08):** a report's client-generated access credential
(`X-Orszem-Report-Access`, ADR 0008) also has no expiry - it remains valid for as long as
the report row exists, with no rotation and no revocation mechanism. This was a deliberate
non-decision, not an oversight: inventing an expiry window now would be exactly the kind of
arbitrary, unjustified product threshold the phase brief asked not to invent. Whatever
retention policy answers B5 also settles how long the credential itself remains meaningful
- they are the same question asked from two angles, not two separate ones.

### B6. Spam and rate limiting
The Public app is anonymous, so what are the concrete abuse limits and what happens when
one is exceeded?

*Affects:* the public report endpoint and its infrastructure.

**Now concrete and still open (Phase 4, 2026-09-08):** the Public report submission
endpoint (`POST /api/v1/public/reports`) shipped with **no rate limiting or abuse defence
at all** - this is the endpoint B6 asks about, now real. `LoginRateLimiter` (Phase 2) is
coupled to service-ID/IP dual-bucket semantics built for credential-guessing defence over
an authenticated flow; reusing it here would mean touching auth semantics or inventing a
materially different, unjustified threshold for an unrelated (anonymous, high-volume,
legitimately-bursty) traffic shape - exactly what the phase brief said not to do (ADR 0008
Decision 8). **Concrete answer needed before any public deployment**: per-IP/per-window
limits, a CAPTCHA-equivalent (no paid CAPTCHA service without owner approval, §53), and
what a limited client sees when throttled. See `PHASE_4_ENGINEERING_REPORT.md` §L for the
full account of what is and is not defended today.

### B7. Moderation
Who moderates, on what basis, and what states does moderation produce?

*Affects:* the report state machine. Demo v1's `NEW → IN_PROGRESS → ARCHIVED` was a
demonstration simplification and is **not** automatically the V2 model.

**Reused provisionally by Phase 4 (2026-09-08), still unanswered.** `reports.status` uses
exactly `NEW`/`IN_PROGRESS`/`ARCHIVED` because Phase 4 needed *some* internal vocabulary to
give a freshly-created report a value, and every report Phase 4 creates is `NEW` by
construction - nothing in Phase 4 transitions a report to either other state, and no
moderation workflow exists yet. The Public-facing mapping (`RECEIVED`/`PROCESSING`/
`CLOSED`, ADR 0008 Decision 6) was deliberately kept distinct from this internal vocabulary
specifically so this question staying open does not leak into the Public API contract -
changing the internal state machine's shape later needs no Public-facing migration.

### B8. Budapest — city or districts?

The KSH Helységnévtár lists **both** `Budapest` (KSH code 13578) and its 23 districts as
separate settlements, and the imported reference data therefore contains all 24. A report
in Budapest can be attributed to either. The choice determines what a service area covers
and how a Budapest report routes.

*Affects:* reference data, routing and service-area configuration. Relevant to Phase 3.
See [`PHASE_3B_DECISION_GATE.md`](PHASE_3B_DECISION_GATE.md) §3.

### B9. VPE reuse confirmation

VPE publishes no explicit reuse licence for the HÜSZ annexes the railway reference data is
derived from. The current basis — mandatory regulatory publication by a body performing a
public task, factual rows only, source document not redistributed — is sound for internal
use, but is not a written grant.

*Affects:* external distribution of the derived dataset only, not the running service. See
[`PHASE_3B_DECISION_GATE.md`](PHASE_3B_DECISION_GATE.md) §6.

### B10. The VPE-derived dataset was public — git-history remediation performed

**Resolved by an owner-approved history rewrite of `feature/v2-reference-routing`, 2026-09-08.**

`github.com/kplevi05/Orszem` is a **public** repository (confirmed via the GitHub API on
2026-09-08). `reference-data/current/settlements.csv`, `railway-lines.csv`,
`settlement-railway-lines.csv`, `needs-review.csv` and `manifest.json` — the VPE-derived
canonical dataset — had been committed to that branch's history and were visible to anyone.

The backend importer's `reuseStatus: PENDING` gate (ADR 0006) stops the dataset from
reaching a *running service* without written clearance. It did **nothing** about the public
git repository: a gate inside the application cannot retroactively un-expose files already
sitting in history. **PENDING must not be read as "the running service is safe, so this is
fine" — the two are unrelated.**

**What was done, on explicit owner authorisation, conservatively and without a paid
service:** a read-only exposure audit first confirmed the blast radius (see
[`PHASE_3B_DECISION_GATE.md`](PHASE_3B_DECISION_GATE.md) §11 for the full account) — the
exposure was exactly one branch, `feature/v2-reference-routing`, with no PR ref, no fork,
and no reachability from `main`, `demo-v1.1-final`, or any Phase 0–2 ref. That branch's
history (six commits from its base at `a773d7a`) was then reconstructed from that same
base: the row-level VPE-derived files no longer appear in **any** commit reachable from the
rewritten branch, verified before the branch was force-pushed with `--force-with-lease`,
scoped to that one branch only. `main`, `demo-v1.1-final`, and every other ref were left
untouched — confirmed unchanged before and after the push.

The repository was **not** made private, and VPE confirmation was **not** awaited, per
explicit instruction — this remediation stands on its own regardless of either.

**What this does and does not resolve:**
- Resolved: the row-level dataset is no longer reachable from any commit in this
  repository. A fresh clone of `feature/v2-reference-routing` cannot recover it via `git
  log`.
- **Not resolved, and not resolvable by a git operation:** the files were public for
  roughly two hours before this rewrite. GitHub's own caching, search indexing, or any
  crawler that happened to fetch the branch in that window may have already retained a
  copy. Zero forks and zero stars were confirmed at the time (§4/§5 of the audit), which
  bounds the realistic risk but does not eliminate it.
- Still open, unchanged by this action: **B9** — written VPE confirmation is still required
  before any VPE-derived row-level data is committed to a public repository again, or
  distributed externally in any other form. The real research data continues locally under
  `reference-data/local-research/` (gitignored — see that directory's README).

*Affects:* whether the derived data may be recommitted or the repository's visibility
changed. Those remain the owner's decisions; nothing here assumes either has happened.

---

## C. Explicitly deferred

- Any AI, LLM or NLP component — no provider, model or use case has been chosen, and none
  is assumed anywhere in the codebase.
- Push notifications, real-time updates, offline support and media upload.
- Publication channel for the Android apps (Google Play vs. direct APK), which interacts
  with A4.
