# OSM railway review — decision files

One JSON file per reviewed candidate, named `<candidateId with `:`/`/` replaced by `_`>.json`
(for example `pair_20127_1.json` for candidate `pair:20127:1`, or `osm_n411752399.json` for
candidate `osm:n411752399`). Every candidate `build-osm-railway-reference.py` produces is
listed in `../candidates.json`; a decision file is how a reviewer or a deterministic rule
records a permanent, version-controlled verdict on exactly one of them.

This directory is empty until the manual review (review-policy.json's
"whatStillRequiresExplicitHumanApproval") actually happens — an empty directory here is the
correct, honest state of an unreviewed dataset, not a bug.

## Decision tiers — OSM cannot verify itself

An OSM station name matching a KSH settlement, a nearby referenced way, and consistent
infrastructure tags are strong **candidate** evidence — but they only prove the *internal
consistency of one source*, never the independent, authoritative fact that a line is national
network-statement infrastructure. A candidate therefore carries an explicit `decisionTier`,
and only the strongest tier may ever unlock promotion:

| Tier | Meaning | Set by | Unlocks promotion? |
|---|---|---|---|
| `REJECTED` | A reviewer determined the candidate does not belong in the canonical dataset. | Human | No — and it can never appear in a promoted file. |
| `QUARANTINED` | Evidence is insufficient or ambiguous; needs more review or a different source. | Human or the deterministic build (implicitly, via `candidates.json`'s quarantine categories) | No. |
| `OSM_EVIDENCE_ACCEPTED` | The deterministic rule found qualifying OSM evidence (exact KSH name match, ordinary infrastructure tags, within the distance limit). Internally consistent, **not independently corroborated**. | The deterministic build rule (`decisionMethod: DETERMINISTIC_RULE`, `reviewerType: AUTOMATED_RULE`) — this tier is the only one a machine may assign on its own, and it may only assign this exact tier, never a stronger one. | **No.** Never sufficient for `VERIFIED`, never sets `humanApproved`, never removes the import block. |
| `INDEPENDENTLY_VERIFIED` | A human reviewer corroborated the candidate against a source *other than* this OSM extraction and the KSH roster used to build it — see "Independent sources" below. | Human only (`decisionMethod: INDEPENDENT_SOURCE_VERIFICATION`, `reviewerType: HUMAN`, `humanApproved: true`). | **Yes** — this is the only tier `promote-osm-railway-reference.py` will ever include in an importable dataset. |

`OSM_EVIDENCE_ACCEPTED` is not something a reviewer writes a file for — it is exactly what
`candidates.json`'s `category: "OSM_EVIDENCE_ACCEPTED"` already records for every
deterministically-accepted candidate, produced once by `build-osm-railway-reference.py`,
never regenerated per-candidate and never claiming `humanApproved`. No decision file is
created or required to hold a candidate at this tier — it is the honest default for every
candidate the rule accepted and nobody has reviewed further yet. A decision file only ever
exists when a human has actually looked at a specific candidate and moved it to
`REJECTED`, an explicit human-confirmed `QUARANTINED`, or `INDEPENDENTLY_VERIFIED`. This
keeps "what the machine found" (919 entries in one committed `candidates.json`) and "what a
human has actually reviewed" (individual files in this directory) structurally impossible to
confuse — there is no path that fabricates 919 per-candidate "review" files for a rule result
that already had one shared, clearly-machine-labelled record.

## Independent sources

"Independent" means a source that is not this OSM extraction and not the KSH settlement
roster used to build it — the point is corroboration from outside the one dataset being
reviewed. It does **not** mean the PENDING VPE/KTI/GYSEV dataset, under any framing —
comparison, cross-checking, or "just to see" is still use, and remains forbidden regardless of
decision tier.

Before citing any other source for `INDEPENDENTLY_VERIFIED`:

1. Confirm its licence/terms actually permit this reuse — do not assume.
2. Record the exact URL, the exact retrieval date, and the licence/usage basis in
   `independentSourcesChecked` (see schema below) — a bare "I checked a website" is not
   sufficient provenance.
3. Never bulk-extract from an uncertain-licence source — one candidate reviewed against one
   cited source at a time.
4. Never reconstruct HÜSZ-derived facts indirectly through another source that itself derived
   them from the same PENDING annexes.

If no legally usable independent source exists for a candidate, it stays at
`OSM_EVIDENCE_ACCEPTED` (or `QUARANTINED`/`REJECTED`) — that is a correct, final answer for
this phase, not a gap to be closed by inventing a workaround.

## Schema

```json
{
  "candidateId": "pair:20127:1",
  "category": "OSM_EVIDENCE_ACCEPTED",
  "kshCode": "20127",
  "lineCode": "1",
  "osmObjectIds": ["n123456789"],
  "decisionTier": "INDEPENDENTLY_VERIFIED",
  "decisionMethod": "INDEPENDENT_SOURCE_VERIFICATION",
  "reviewerType": "HUMAN",
  "humanApproved": true,
  "reasonCode": "MATCHES_INDEPENDENTLY_CONFIRMED_LINE",
  "note": "Human-readable justification, in Hungarian or English.",
  "independentSourcesChecked": [
    {
      "url": "https://example.org/exact-source-consulted",
      "retrievedAt": "2026-09-24",
      "usageBasis": "Publicly published factual line/station listing; no redistribution of the source document itself.",
      "licenceNote": "Confirmed permits this factual, non-bulk reuse before citing it here."
    }
  ],
  "evidenceHash": "sha256 hex string, copied exactly from candidates.json at decision time",
  "policyVersion": "2026-09-23.1",
  "reviewer": "kplevi05",
  "decidedAt": "2026-09-24T12:00:00Z"
}
```

| Field | Rule |
|---|---|
| `candidateId` | Must exactly equal one entry's `candidateId` in `../candidates.json`. A decision for one OSM object or line code can never be reused for a different `candidateId`, even a superficially similar one — `promote-osm-railway-reference.py` verifies this by filename and by field, not by inference. |
| `decisionTier` | `REJECTED` \| `QUARANTINED` \| `OSM_EVIDENCE_ACCEPTED` \| `INDEPENDENTLY_VERIFIED`. Only `INDEPENDENTLY_VERIFIED` unlocks promotion. |
| `decisionMethod` | `HUMAN_REVIEW` or `INDEPENDENT_SOURCE_VERIFICATION` for any file in this directory (files here are always human — see above). `DETERMINISTIC_RULE` is a valid schema value in the abstract, but it only ever appears as `candidates.json`'s own record, never as a file here. |
| `reviewerType` | Always `HUMAN` for a file in this directory. `promote-osm-railway-reference.py` rejects any file here claiming `AUTOMATED_RULE` — that value belongs to `candidates.json`, not to a reviewer's own decision record. |
| `humanApproved` | Boolean. Must be `true` for `INDEPENDENTLY_VERIFIED`. A file with `reviewerType: HUMAN` but `humanApproved: false` is accepted for `REJECTED`/`QUARANTINED` (a human looked and declined/deferred), but `INDEPENDENTLY_VERIFIED` always requires it `true`. |
| `reasonCode` | Short, stable, machine-checkable (upper snake case). Never a routing/API exception name — a review decision reason and a runtime error code are different vocabularies. |
| `note` | Free text for a human reader. Required, non-empty. |
| `independentSourcesChecked` | Required and non-empty **only** for `INDEPENDENTLY_VERIFIED`; each entry needs `url`, `retrievedAt`, `usageBasis`. Never cites the PENDING VPE/KTI/GYSEV dataset. |
| `evidenceHash` | Copied verbatim from `candidates.json` at the moment of decision. `promote-osm-railway-reference.py` recomputes the candidate's current hash from the live evidence and refuses promotion if it no longer matches (review-policy.json's stale-decision protection) — the decision is not deleted, just no longer usable for promotion until re-reviewed. |
| `policyVersion` | The `review-policy.json` version this decision was made under. |
| `reviewer` / `decidedAt` | Who (or, for a machine record, the generating script's identity) and when, for audit purposes. |

## What a decision can never do

- Apply to a different candidate than its own `candidateId`.
- Survive a change to its candidate's `evidenceHash` (source geometry, tags, or the KSH
  settlement roster moved since the decision — see `promote-osm-railway-reference.py`).
- Let `decisionTier: OSM_EVIDENCE_ACCEPTED` (however many files, however "unanimous")
  substitute for `INDEPENDENTLY_VERIFIED` — the promoter checks the tier value itself, not a
  count or a consensus.
- Claim `humanApproved: true` while `reviewerType: AUTOMATED_RULE` — rejected as an invalid
  file, not silently downgraded.
- Cause `verificationStatus` to become `VERIFIED` by itself — only a full, successful
  `promote-osm-railway-reference.py` run, with at least one `INDEPENDENTLY_VERIFIED` decision
  present, current and consistent, produces an importable snapshot (containing only that
  verified subset). A blank or placeholder `"approved": true` file is not a valid decision —
  the schema has no such field, and the promoter validates every field above, not just
  presence of a file.
