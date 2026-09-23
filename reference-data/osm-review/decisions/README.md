# OSM railway review — decision files

One JSON file per reviewed candidate, named `<candidateId with `:`/`/` replaced by `_`>.json`
(for example `pair_20127_1.json` for candidate `pair:20127:1`, or `osm_n411752399.json` for
candidate `osm:n411752399`). Every candidate `build-osm-railway-reference.py` produces is
listed in `../candidates.json`; a decision file is how a human reviewer records a permanent,
version-controlled verdict on exactly one of them.

This directory is empty until the manual review (review-policy.json's
"whatStillRequiresExplicitHumanApproval") actually happens — an empty directory here is the
correct, honest state of an unreviewed dataset, not a bug.

## Schema

```json
{
  "candidateId": "pair:20127:1",
  "category": "ORDINARY_ACCEPTED_BY_RULE",
  "kshCode": "20127",
  "lineCode": "1",
  "osmObjectIds": ["n123456789"],
  "decision": "ACCEPTED",
  "reasonCode": "MATCHES_KNOWN_NATIONAL_LINE",
  "note": "Human-readable justification, in Hungarian or English.",
  "sourceUrl": "https://www.openstreetmap.org/node/123456789",
  "evidenceHash": "sha256 hex string, copied exactly from candidates.json at decision time",
  "policyVersion": "2026-09-23.1",
  "reviewer": "kplevi05",
  "decidedAt": "2026-09-23T12:00:00Z"
}
```

| Field | Rule |
|---|---|
| `candidateId` | Must exactly equal one entry's `candidateId` in `../candidates.json`. A decision for one OSM object or line code can never be reused for a different `candidateId`, even a superficially similar one — `promote-osm-railway-reference.py` verifies this by filename and by field, not by inference. |
| `decision` | `ACCEPTED` or `REJECTED`. Nothing else. |
| `reasonCode` | Short, stable, machine-checkable (upper snake case). Never a routing/API exception name — a review decision reason and a runtime error code are different vocabularies. |
| `note` | Free text for a human reader. Required, non-empty. |
| `sourceUrl` | The OSM object permalink, or another legitimately reusable public source consulted (e.g. a Wikipedia article on the specific line) — never a citation of the PENDING VPE/KTI/GYSEV dataset. |
| `evidenceHash` | Copied verbatim from `candidates.json` at the moment of decision. `promote-osm-railway-reference.py` recomputes the candidate's current hash from the live evidence and refuses promotion if it no longer matches (review-policy.json's stale-decision protection) — the decision is not deleted, just no longer usable for promotion until re-reviewed. |
| `policyVersion` | The `review-policy.json` version this decision was made under. |
| `reviewer` / `decidedAt` | Who and when, for audit purposes. |

## What a decision can never do

- Apply to a different candidate than its own `candidateId`.
- Survive a change to its candidate's `evidenceHash` (source geometry, tags, or the KSH
  settlement roster moved since the decision — see `promote-osm-railway-reference.py`).
- Cause `verificationStatus` to become `VERIFIED` by itself — only a full, successful
  `promote-osm-railway-reference.py` run, with every required decision present, current and
  `ACCEPTED`, produces an importable snapshot. A blank or placeholder `"approved": true` file
  is not a valid decision — the schema has no such field, and the promoter validates every
  field above, not just presence of a file.
