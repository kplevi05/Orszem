# Railway territory mapping — OSM review, verification-tier model & promotion gate

**Engineering report.** Starting `main` SHA `b4cf33cf2736d6da671dcddbb5599801a771ea0b` (PR #32
merge). Final branch HEAD of this phase: `feature/osm-railway-review-policy-and-promotion`
(see `git log` on that branch for the exact final commit at merge time). Supersedes and
consolidates `docs/RAILWAY_REVIEW_POLICY_MILESTONE_REPORT.md` and
`docs/RAILWAY_TRIAGE_AND_VERIFICATION_TIER_MILESTONE_REPORT.md`, which remain in the tree as
the dated intermediate record.

## 1. What this phase is, and is not

An OSM/Geofabrik-derived candidate railway reference dataset (`reference-data/osm-review/`)
already existed on `main` before this phase (built and merged as part of PR #32), with 121
railway lines / 921 settlement-line mappings, `verificationStatus: UNVERIFIED`,
`automaticImportAllowed: false`. This phase:

1. **Reproduced** that snapshot's exact source input and confirmed the pipeline was
   deterministic (§2).
2. **Hardened the pipeline** to correctly quarantine non-operational infrastructure
   (sidings/yards/industrial/military) that was previously being silently accepted (§3).
3. **Introduced a four-tier verification model** making explicit that OSM-internal evidence
   alone (`OSM_EVIDENCE_ACCEPTED`) can never authorize import — only a human decision backed
   by a genuine, licence-checked independent source (`INDEPENDENTLY_VERIFIED`) can (§4).
4. **Built and ran a machine triage** across the resulting 919 candidates (§5).
5. **Did not** perform that independent verification — no legally-cleared external source was
   identified or approved in this phase, and none was invented to close the gap (§6). This is
   the deliberate, correct, final state of this phase, not an omission.

Nothing here touches backend, Android or Web source; nothing was deployed; nothing was
imported into any database, local or production.

## 2. Snapshot reproduction

- Confirmed `origin/main` HEAD `b4cf33cf2736d6da671dcddbb5599801a771ea0b`, clean working tree.
- Resolved `https://download.geofabrik.de/europe/hungary-latest.osm.pbf`'s redirect at the
  time of this work to `hungary-260922.osm.pbf` (325,320,804 bytes); its SHA-256
  (`0b66459603d0cdbdd284d81296e78659f56207e447aa43d104f27295176604aa`) is an **exact match** to
  the manifest already committed on `main` — a genuine reproduction, not a substitution.
- Re-ran the CI workflow's own steps locally (Docker, Ubuntu 24.04, `osmium-tool` + Python)
  against that exact input; pre-hardening output was byte-identical to all 5 committed files
  and all manifest counts (121/921/803/838).
- Found and fixed a real reproducibility flaw: the workflow recorded wall-clock
  `date -u +%Y-%m-%d` instead of a fact about the fetched file, and used the unpinned `latest`
  pointer. Fixed in `.github/workflows/build-osm-railway-reference.yml` to resolve and record
  the actual dated URL it fetched.

## 3. Pipeline hardening — non-operational infrastructure quarantine

`scripts/build-osm-railway-reference.py` now classifies every way `ordinary`,
`non_operational` (`service` ∈ {siding, yard, spur, crossover} or `usage` ∈ {industrial,
military}), or `excluded` (`railway` ∈ {construction, abandoned, disused, proposed} — enforced
a second time, defensively, inside the script itself, not only by the upstream `osmium
tags-filter`). A station's ordinary and non-operational nearby evidence are computed
independently, so a station accepted via one code still has non-operational evidence on a
*different* code recorded rather than silently dropped (a real bug in an early version of this
change, caught and fixed before commit).

**Effect on the real dataset:** 121 → **119** railway lines, 921 → **919** settlement-line
mappings. `153K` at Kalocsa (`service=spur`) and `20L` at Várpalota (`service=siding`,
`usage=industrial`) had zero remaining ordinary evidence and are now correctly quarantined
under `NON_OPERATIONAL_INFRASTRUCTURE_ONLY` with full evidence retained
(`non-operational-evidence.csv`), instead of the silent acceptance the pre-hardening pipeline
gave them. Line `40`'s one siding-tagged instance at Tolnanémedi is excluded the same way while
its other, ordinary evidence keeps it accepted elsewhere. `settlementsWithVerifiedRelations`
unchanged at 803.

Every candidate now carries a stable `candidateId` (`pair:<ksh>:<line>` / `osm:<osm_id>`) and a
deterministic `evidenceHash` (`candidates.json`), covering exactly the fields a reviewer sees —
the basis for the stale-decision protection in §4.

## 4. The four-tier verification model

`reference-data/osm-review/decisions/README.md` defines:

| Tier | Set by | Unlocks promotion? |
|---|---|---|
| `REJECTED` | Human | No |
| `QUARANTINED` | Human, or implicitly by the deterministic build's quarantine categories | No |
| `OSM_EVIDENCE_ACCEPTED` | The deterministic build rule only — this is `candidates.json`'s own `category` field, never a decision *file* | **No, never, regardless of count** |
| `INDEPENDENTLY_VERIFIED` | Human only, against a documented, licence-checked external source | **Yes — the only tier that ever promotes** |

Why this exists: an OSM station name matching KSH, a nearby referenced way, and consistent
tags are strong internal-consistency evidence, but they prove only that one source agrees with
itself, never that a line is genuinely national network-statement infrastructure. Collapsing
"the rule found qualifying evidence" and "a human independently confirmed this" into one
accept/reject flag — which is what the previous version of this promotion gate did — would
have let a large batch of internally-consistent-but-unverified rows become importable on
volume alone.

`scripts/promote-osm-railway-reference.py` enforces this structurally, not just by convention:
a decision file asserting `decisionTier: OSM_EVIDENCE_ACCEPTED`, or `reviewerType:
AUTOMATED_RULE`, or `humanApproved: true` without the `INDEPENDENTLY_VERIFIED` tier, is a hard
`PromotionBlocked` failure, not a warning. Promotion produces **no manifest at all** when zero
candidates reach `INDEPENDENTLY_VERIFIED` — never a `VERIFIED` dataset with zero rows, and
never one built from `OSM_EVIDENCE_ACCEPTED` alone. A non-empty partial verification promotes
only that verified subset; every other candidate, including every other
`OSM_EVIDENCE_ACCEPTED` one, is left exactly where it was.

## 5. Machine triage

`scripts/triage-osm-railway-candidates.py` (offline, read-only) produced
`reference-data/osm-review/triage-report.json`:

| Dimension | Result |
|---|---|
| Ordinary vs. unusual line code | 106 ordinary, **13 unusual** |
| Non-operational `service` tags | siding: 2, spur: 1 |
| Non-operational `usage` tags | industrial: 1 |
| Distance band | 0–10m: 749, 10–25m: 138, 25–50m: 22, 50–100m: 14 |
| Stations with >1 accepted line | 92 |
| Pairs with >1 evidence row | 4 |
| Lines crossing >1 ServiceArea | 50 |
| Evidence farther than 50m | 14, individually listed |
| Budapest/composite/multi-word station names | 377 (all correctly in `needs-review.csv`, none auto-accepted) |
| `needs-review.csv` reasons | `NO_UNIQUE_KSH_NAME`: 764, `NO_REFERENCED_LINE_WITHIN_LIMIT`: 74 |

All 13 line codes the review brief named by number — `70AX, 262e, 268, 284, 300b, 306, 342,
351, 353, 371, 372, 392, 400` — are present in the dataset and individually documented in
`triage-report.json`'s `namedUnusualLineCodeDossier`, with every supporting OSM object id,
station name, distance and tag already extracted. All 13 currently sit at
`OSM_EVIDENCE_ACCEPTED`.

## 6. The independent-verification blocker — this phase's actual stopping point

No `INDEPENDENTLY_VERIFIED` decision was recorded for any candidate, including the 13 named
codes. **This is the deliberate, correct result, not an unfinished task.**

Verifying a candidate against something other than this OSM extraction and the KSH roster used
to build it requires a specific external source, with its licence/terms actually confirmed to
permit this factual, non-bulk reuse — recorded per-candidate in
`independentSourcesChecked` (URL, retrieval date, usage basis). No such source was named,
checked, or approved during this phase. Examples that *might* eventually qualify — each
needing its own licence confirmation, not assumed here — include a MÁV- or GYSEV-published
line/timetable listing, or Hungarian Wikipedia's railway-line articles under their own terms.
Picking one and using it without that check would be exactly the "creatively work around the
blocker" outcome the brief explicitly ruled out, and would risk quietly reconstructing
HÜSZ-derived facts through a back door if the chosen source itself derived them from the same
PENDING annexes.

**This is an owner/legal decision, not an engineering one**, and is the one concrete item
blocking further progress on this dataset:

> Which external source(s), if any, may be used to independently verify these candidates, and
> has its licence been confirmed to permit this reuse?

Until that is answered, the correct and complete state of this dataset is exactly what it is:
**919 well-structured, fully-triaged `OSM_EVIDENCE_ACCEPTED` candidates; 0
`INDEPENDENTLY_VERIFIED`; `verificationStatus: UNVERIFIED`; `automaticImportAllowed: false`;
`runtimeCompatible: false`; not importable anywhere, including production.**

## 7. Test matrix and results

- `scripts/tests/test_osm_railway_reference.py` — 12 tests (5 pre-existing, unchanged and
  passing; 7 new: non-operational quarantine, mixed ordinary/non-operational evidence,
  defense-in-depth exclusion of abandoned/disused ways, stable candidate IDs, deterministic
  rebuild, evidence-hash sensitivity to source changes).
- `scripts/tests/test_promote_osm_railway_reference.py` — 13 tests: unreviewed candidates
  block promotion; `OSM_EVIDENCE_ACCEPTED` alone can never produce a `VERIFIED` dataset; a
  decision file can never assert the machine tier; AI/automated-rule review is never human
  approval; independently-verified decisions promote; partial verification promotes only the
  verified subset; rejected candidates never reach the promoted CSV; stale/wrong-policy-version
  decisions block; unknown `candidateId` blocks; a blank `{"approved": true}` file is refused;
  `INDEPENDENTLY_VERIFIED` without a documented source is refused; unrelated settlements keep
  `UNCLASSIFIED` fallback eligibility; promoted output is deterministic.
- `scripts/tests/test_territory_plan.py` — 15 pre-existing tests, unmodified, still passing
  (confirms this phase's changes did not regress the territory-plan generator).
- **Total: 40/40 passing.**
- `node reference-data/tools/validate-canonical.mjs reference-data/osm-review/manifest.json` —
  passes (`UNVERIFIED / PARTIAL / reuse:CLEARED`, 3,178 settlements / 119 railway lines / 919
  relations / 803 settlements with a relation).
- Backend, Android, Web, deployment/backup-restore CI: unaffected by this phase (no source in
  those trees changed) and passing on this branch's HEAD per the GitHub check-runs recorded at
  the time this report was written.

## 8. Concurrency, authorization and architecture — no change in scope

This phase touches only reference-data tooling and documentation. It does not modify
`ReportWorkflowPolicy`, `AreaScopePolicy`, `RoutingService`, `service_area_settlement_lines`,
or any authorization/workflow code. `docs/architecture/adr/0011-pair-level-settlement-railway-line-service-area-routing.md`
(added this phase, but documenting PR #32's already-shipped behaviour, not new behaviour)
records that model's own concurrency/authorization guarantees, verified directly against
`RoutingService.kt`.

## 9. Data provenance statement

- **Used:** `reference-data/cleared/settlements.csv` (KSH Helységnévtár, CC BY 4.0, already
  cleared and imported in an earlier phase); Geofabrik's Hungary OSM extract (ODbL 1.0,
  `reuseStatus: CLEARED` per its licence, `sourceSha256` recorded and independently
  re-verified in this phase).
- **Not used, at any point, in any form:** the PENDING VPE/KTI/GYSEV HÜSZ-derived dataset
  (`reference-data/local-research/`) — no row-level data, line-code list, settlement relation,
  or comparison result from it was read, cited, or reconstructed. The VPE HÜSZ network
  statement's *concept* (annexes 5.2-4/5.2-5) is cited once, in `review-policy.json`'s domain
  scope rationale, purely to explain *why* a category of infrastructure is out of scope —
  never as comparison data, per the owner's own explicit instruction.

## 10. Known limitations

- The dataset remains genuinely unverified for production purposes; this phase does not change
  that, by design.
- The "unusual line code" triage rule (2+ trailing letters, or leading numeric part > 160) is a
  **review-priority heuristic**, documented as such in `triage-osm-railway-candidates.py`'s own
  docstring — it is not an acceptance or rejection rule, and review-policy.json's actual
  eligibility rule does not depend on it.
- `reference-data/osm-review/candidates.json` is a large generated file (curr. ~650KB); this is
  an accepted tradeoff for having one deterministic, hashable, git-diffable review queue rather
  than scattered per-candidate files for the machine tier.

## 11. Confirmations

- No PENDING VPE/KTI/GYSEV dataset was used, compared against, or reconstructed.
- No production data was mutated; no deployment occurred.
- No `INDEPENDENTLY_VERIFIED` decision was fabricated or approximated to make this report look
  more complete than the underlying verification work actually is.

## 12. Owner decisions requested

1. Approve or redirect the domain-scope decision already recorded in `review-policy.json`
   (national VPE HÜSZ network-statement infrastructure, passenger and freight alike) — carried
   over from the previous milestone, unchanged.
2. **Name a specific, legally-checked independent source** (or confirm none is currently
   available) for `INDEPENDENTLY_VERIFIED` review — see §6. This is the one blocker preventing
   any candidate, including the 13 named unusual codes, from ever becoming importable.
3. Confirm this phase may conclude, as delivered, with the dataset at 0 independently verified
   candidates — i.e., that "correctly and honestly still blocked" is an acceptable phase
   outcome rather than a failure to close out.
