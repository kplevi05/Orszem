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

### B2. Service areas
How is a service area defined, and can one user belong to more than one?

*Affects:* the user model, authorization, and report routing. **Answer before authentication
is implemented**, because it shapes the permission model.

### B3. Train identification
Demo v1 accepted free-text train identifiers. Does V2 use an authoritative catalogue or a
railway API, and if so which?

*Affects:* reference data, validation, and an external dependency. Relevant to Phase 3.

### B4. Settlement list
A controlled list of settlements, or free text as in Demo v1?

*Affects:* reference data and analytics quality. Relevant to Phase 3.

### B5. Data retention
How long is a report kept, and what happens at the end of that period — deletion or
anonymisation?

*Affects:* the schema, a scheduled job, and the privacy notice. **Answer before the first
production deployment**, because retention cannot be applied retroactively to data already
discarded or already kept.

### B6. Spam and rate limiting
The Public app is anonymous, so what are the concrete abuse limits and what happens when
one is exceeded?

*Affects:* the public report endpoint and its infrastructure.

### B7. Moderation
Who moderates, on what basis, and what states does moderation produce?

*Affects:* the report state machine. Demo v1's `NEW → IN_PROGRESS → ARCHIVED` was a
demonstration simplification and is **not** automatically the V2 model.

---

## C. Explicitly deferred

- Any AI, LLM or NLP component — no provider, model or use case has been chosen, and none
  is assumed anywhere in the codebase.
- Push notifications, real-time updates, offline support and media upload.
- Publication channel for the Android apps (Google Play vs. direct APK), which interacts
  with A4.
