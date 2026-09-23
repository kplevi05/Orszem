# Railway review — verification-tier model, triage, and the independent-source blocker

Second intermediate report, continuing directly from
`docs/RAILWAY_REVIEW_POLICY_MILESTONE_REPORT.md`. Covers: the four-tier decision model, the
rewritten promotion gate, the mechanical triage report, and the documented investigation of
every named unusual line code. **Stops at a real legal/owner blocker before attempting any
`INDEPENDENTLY_VERIFIED` decision** — see §5.

## 1. OSM cannot verify itself — the decision-tier model

`reference-data/osm-review/decisions/README.md` now defines four tiers: `REJECTED`,
`QUARANTINED`, `OSM_EVIDENCE_ACCEPTED`, `INDEPENDENTLY_VERIFIED`. Key structural choice: the
`OSM_EVIDENCE_ACCEPTED` tier is **not** something a reviewer files a decision for — it is
exactly `candidates.json`'s own `category` field, produced once by
`build-osm-railway-reference.py`, always machine-labelled. A decision *file* only ever exists
for `REJECTED`, a human-confirmed `QUARANTINED`, or `INDEPENDENTLY_VERIFIED` — so there is no
code path that could fabricate 919 per-candidate "review" files for a rule result that already
had one shared, clearly-machine-generated record. `promote-osm-railway-reference.py` rejects
outright any decision file that claims `decisionTier: OSM_EVIDENCE_ACCEPTED`, or
`reviewerType: AUTOMATED_RULE`, or `humanApproved: true` without `decisionTier:
INDEPENDENTLY_VERIFIED` — these are hard validation failures (`PromotionBlocked`), not
warnings.

`candidates.json`'s machine category was renamed `ORDINARY_ACCEPTED_BY_RULE` →
`OSM_EVIDENCE_ACCEPTED` for terminology consistency with the tier model (one real bug caught
in the process: the committed `candidates.json` briefly went stale relative to the renamed
script after the rename — caught by the triage script reporting 0 accepted candidates against
an unchanged 919-mapping manifest, fixed by regenerating from the same verified input before
anything else used it).

## 2. Promotion gate — rewritten around the tier, not a binary accept/reject

`scripts/promote-osm-railway-reference.py` no longer treats "has any decision file" as
sufficient. It now:

- Only ever includes a candidate in the promoted output when its current decision file has
  `decisionTier: INDEPENDENTLY_VERIFIED`, `reviewerType: HUMAN`, `humanApproved: true`,
  `decisionMethod: INDEPENDENT_SOURCE_VERIFICATION`, a non-empty `independentSourcesChecked`
  list (each entry requiring `url`/`retrievedAt`/`usageBasis`), and a still-current
  `evidenceHash`.
- Produces **no manifest at all** when zero candidates reach that tier — never a `VERIFIED`
  dataset with zero rows, and never one built from `OSM_EVIDENCE_ACCEPTED` alone, however many
  of those exist.
- Promotes **only the verified subset** when some (not all) candidates are independently
  verified — every other candidate, including every other `OSM_EVIDENCE_ACCEPTED` one, stays
  exactly where it was; nothing about a partial verification broadens what gets imported.
- Still separately tracks `rejected`, `quarantined` (human), `staleDecision`,
  `wrongPolicyVersion`, and `osmEvidenceOnlyNoHumanDecision` in `promotion-status.json` for
  full auditability.

## 3. Tests — 13 new promotion tests (40 total, all passing)

Covers exactly the required properties: `OSM_EVIDENCE_ACCEPTED` alone can never produce a
`VERIFIED` dataset; a decision file may never assert the `OSM_EVIDENCE_ACCEPTED` tier;
AI/automated-rule review is never treated as human approval (`reviewerType: AUTOMATED_RULE`
claiming `INDEPENDENTLY_VERIFIED` is rejected); a stale `evidenceHash` blocks reuse; partial
independent verification promotes only the proven rows; rejected candidates never reach the
promoted CSV; `INDEPENDENTLY_VERIFIED` without a documented independent source is rejected;
and other settlements keep their `UNCLASSIFIED` fallback eligibility regardless of promotion
state (`scripts/tests/test_promote_osm_railway_reference.py`, 13 tests;
`scripts/tests/test_osm_railway_reference.py` unchanged at 12; `scripts/tests/test_territory_plan.py`
unchanged at 15 — **40/40 passing**).

## 4. Machine triage — `scripts/triage-osm-railway-candidates.py`

New, read-only, offline script; output committed as
`reference-data/osm-review/triage-report.json`. Breaks the 919 `OSM_EVIDENCE_ACCEPTED`
candidates and 838 quarantined ones down by exactly the dimensions requested:

| Dimension | Result |
|---|---|
| Ordinary vs. unusual line code | 106 ordinary, **13 unusual** (2+ trailing letters, or leading numeric part > 160, or on the explicitly named list) |
| Non-operational `service` tags | `siding`: 2, `spur`: 1 |
| Non-operational `usage` tags | `industrial`: 1 |
| Distance band | 0–10m: 749, 10–25m: 138, 25–50m: 22, 50–100m: 14 |
| Stations with >1 accepted line | 92 |
| Pairs with >1 evidence row | 4 |
| Lines crossing >1 ServiceArea | 50 (cross-referenced against `operational-data/.../line-routing-assessment.csv`) |
| Evidence farther than 50m | 14 rows, all listed individually in the report |
| Budapest/composite/multi-word station names | 377 (needs-review only — none of these were ever auto-accepted, per `review-policy.json`'s Budapest/composite rules) |
| `needs-review.csv` reason breakdown | `NO_UNIQUE_KSH_NAME`: 764, `NO_REFERENCED_LINE_WITHIN_LIMIT`: 74 |

## 5. All 13 named unusual line codes — investigated, and where this phase stops

Every code the brief named — **`70AX`, `262e`, `268`, `284`, `300b`, `306`, `342`, `351`,
`353`, `371`, `372`, `392`, `400`** — is present in this dataset and appears in
`triage-report.json`'s `namedUnusualLineCodeDossier`, with every supporting OSM object id,
station name, distance, and (where applicable) the disqualifying `service`/`usage` tag that
already quarantined it. None of these 13 named codes are the same as `153K`/`20L` from the
previous milestone (those were found by tag-based hardening, not named in the brief) — all 13
currently sit at `OSM_EVIDENCE_ACCEPTED`: internally
consistent (exact KSH name match, ordinary `railway=rail` tagging, within the distance limit)
but, as the brief's own point 1 states correctly, that only proves the OSM extraction is
internally consistent with itself, never that the line is genuinely national network-statement
infrastructure.

**This phase does not attempt to mark any of them `INDEPENDENTLY_VERIFIED`.** Doing so
requires a real, legally-checked external source distinct from this OSM extraction and from
the KSH roster — and per the review brief's own explicit instruction, inventing or guessing at
one rather than stopping and asking is exactly what must not happen here.

**The concrete blocker: no independent source for this verification step has been identified
or legally cleared in this phase.** Before any `INDEPENDENTLY_VERIFIED` decision can be
recorded — for these 13 codes or for any of the other 906 `OSM_EVIDENCE_ACCEPTED` candidates —
someone needs to name a specific candidate source (examples that *might* qualify, each
needing its own licence check before use, not assumed here: a MÁV-published timetable/line
list, a GYSEV-published equivalent, Wikipedia's Hungarian railway line articles under their
own CC BY-SA terms, a Hungarian Wikipedia infobox citing an official line number) and confirm
its terms actually permit this factual, non-bulk reuse. That is an owner/legal decision, not
one this phase should make unilaterally by picking a website.

**Correct result for this milestone, per the brief's own §6: 919 well-structured
`OSM_EVIDENCE_ACCEPTED` candidates, properly triaged and quarantine-separated, 0
`INDEPENDENTLY_VERIFIED`, dataset still `UNVERIFIED`, still not importable.** Running
`promote-osm-railway-reference.py` against the real committed dataset right now correctly
produces no manifest and reports 0 promotable candidates.

## 6. What is still outstanding for the next milestone

- An owner decision on which (if any) independently-checked external source to use, and
  confirmation of its licence terms — the named blocker above.
- Per-candidate `INDEPENDENTLY_VERIFIED` review of whichever subset that source can actually
  corroborate (very possibly a small subset of 919, honestly reflecting real corroboration
  coverage rather than completeness).
- Full backend/Android/Web/deploy CI (nothing in this milestone touched those trees).
- The full engineering report and a draft PR — both deferred until either a verified subset
  exists or the owner confirms the milestone should conclude with 0 verified and the gate
  closed, which is itself a legitimate, honest outcome this phase is prepared to document as
  final if that is the owner's call.

No PENDING VPE/KTI/GYSEV data was read, compared against, or reconstructed at any point.
Nothing was deployed or imported.
