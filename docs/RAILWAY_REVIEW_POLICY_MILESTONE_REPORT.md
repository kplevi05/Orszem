# Railway territory mapping — review policy + promotion milestone

Intermediate report, not the final phase report. Covers exactly the milestone requested:
snapshot reproduction, formal review policy, decision schema, ADR, pipeline hardening, and
their tests. The full manual candidate review (§4 of the original brief) has **not** started —
it is explicitly the next milestone, gated on this one.

## 1. Snapshot reproduction — verified, genuinely reproduced

- Confirmed `origin/main` HEAD `b4cf33cf2736d6da671dcddbb5599801a771ea0b` (PR #32 merge),
  local working tree fast-forwarded and clean.
- Resolved `https://download.geofabrik.de/europe/hungary-latest.osm.pbf`'s redirect at the
  time of this work: `hungary-260922.osm.pbf` (325,320,804 bytes). Downloaded it and computed
  its SHA-256: **`0b66459603d0cdbdd284d81296e78659f56207e447aa43d104f27295176604aa`** — an
  **exact match** to the `sourceSha256` already recorded in the committed manifest. This is a
  genuine reproduction of the original input, not a substitution.
- Re-ran the CI workflow's own steps locally (Docker, Ubuntu 24.04 container, matching
  `osmium-tool` + `python3`): `osmium tags-filter` → `osmium export` →
  `build-osm-railway-reference.py`. Before any hardening changes, the output was
  **byte-identical** to the committed files (`evidence.csv`, `needs-review.csv`,
  `railway-lines.csv`, `settlement-railway-lines.csv`, `settlements.csv` — all five SHA-256
  matched exactly), and the manifest's counts matched exactly: 121 railway lines / 921
  mappings / 803 settlements with a relation / 838 quarantined stations.
- **Real reproducibility risk found and fixed**: the workflow fetched `hungary-latest.osm.pbf`
  (a moving pointer) and recorded `SOURCE_DATE="$(date -u +%Y-%m-%d)"` — wall-clock time, not
  a property of the actual file. It happened to work today only because Geofabrik's daily
  rotation lag lined up. Fixed in `.github/workflows/build-osm-railway-reference.yml`: the
  workflow now resolves the redirect itself, downloads that exact dated URL, and derives
  `SOURCE_DATE` from the resolved filename — so `sourceVersionOrDate` is now a fact about the
  file that was actually fetched, not about when CI happened to run. No reproducibility
  blocker was hit this time (the exact dated file was still live), so nothing needed the
  "archive the verified source" fallback described in `review-policy.json` — but the fix
  above is what makes that fallback meaningful the next time the exact file has rotated out.

## 2. Formal review policy — `reference-data/osm-review/review-policy.json`

Versioned (`policyVersion: 2026-09-23.1`), covers exactly the required sections: domain scope
(with the owner-approved rationale linking to `reference-data/README.md`'s existing VPE HÜSZ
framing, used only conceptually — no PENDING row-level data referenced), candidate
eligibility (ordinary vs. non-operational vs. excluded railway/service/usage tags), the
"unusual line codes" handling (no invented numeric heuristic — flagged for mandatory review
instead, with the two real examples this hardening pass actually found), duplicate-evidence
handling, settlement name normalization and why exact matching is safe / fuzzy matching is
forbidden, Budapest/district handling, border/composite station names, evidence requirements
for acceptance vs. promotion, the default-to-quarantine rule, stale-decision protection, and
an explicit list of what still needs a human decision.

## 3. New ADR — `docs/architecture/adr/0011-pair-level-settlement-railway-line-service-area-routing.md`

Fills the gap found during inspection: PR #32 shipped `V007`/pair routing with no ADR. Records
(verified directly against `RoutingService.kt`, not assumed): a line is either whole-line- or
pair-routed, never mixed, enforced at the admin write path; a missing pair means
`UNCLASSIFIED`, never inference from a neighbour; historical reports are immune to mode
switches; only `SUPER_ADMIN` may preview/apply, atomically and audited; `ON DELETE RESTRICT`
protects an in-use pair mapping from reference-import deactivation (extending ADR 0006).

## 4. Review-decision schema — `reference-data/osm-review/decisions/README.md`

One JSON file per candidate, keyed by a stable `candidateId` (`pair:<ksh>:<line>` for an
accepted-by-rule candidate, `osm:<osm_id>` for a quarantined one). Required fields:
`candidateId`, `decision` (`ACCEPTED`/`REJECTED` only), `reasonCode`, a non-empty `note`,
`sourceUrl`, `evidenceHash`, `policyVersion`, `reviewer`, `decidedAt`. A blank
`{"approved": true}`-style file is rejected outright by the promoter (see §6) — the schema has
no such field and every required field is checked. The directory is currently empty — that is
the correct, honest state before the manual review milestone begins.

## 5. Pipeline hardening — `scripts/build-osm-railway-reference.py`

- Reads each way's `service`/`usage`/`railway` tags and classifies it `ordinary`,
  `non_operational` (`service` ∈ {siding, yard, spur, crossover} or `usage` ∈ {industrial,
  military}), or `excluded` (`railway` ∈ {construction, abandoned, disused, proposed} — a
  defense-in-depth check on top of the upstream `osmium tags-filter`, which already excludes
  these `railway` values but must not be the *only* place this is enforced).
- A station's ordinary and non-operational nearby evidence are now computed **independently**
  — a station accepted via one code still has its non-operational evidence on a *different*
  code recorded, never silently dropped (a real bug caught and fixed during this work: an
  earlier version of this change did drop it whenever the station also had an ordinary match).
- New quarantine reason `NON_OPERATIONAL_INFRASTRUCTURE_ONLY`, with full evidence
  (`non-operational-evidence.csv`) — never silently discarded.
- Stable `candidateId` for every candidate (`pair:<ksh>:<line>` / `osm:<osm_id>`), and a
  deterministic `evidenceHash` per candidate (`candidates.json`) covering every field a
  reviewer actually saw, so a later change to the underlying evidence invalidates any decision
  recorded against the old hash.
- `manifest.json` now explicitly carries `runtimeCompatible: false` and
  `status: "OFFLINE_UNVERIFIED_SOURCE_REVIEW"` (previously implied only by
  `automaticImportAllowed: false`).

**Effect on the real dataset, re-run through the hardened pipeline against the exact same
reproduced input:** 121 → **119** railway lines, 921 → **919** settlement-line mappings.
Two codes dropped entirely because their *only* supporting evidence was non-operational
infrastructure — concretely: `153K` at **Kalocsa** (`service=spur`) and `20L` at
**Várpalota** (`service=siding`, `usage=industrial`). Both are exactly the kind of "unusual
suffix" code the original brief flagged as needing investigation, and both were being silently
accepted before this hardening. A third instance — line `40` at Tolnanémedi
(`service=siding`, operator `MÁV`) — lost only that one siding-tagged station as evidence;
line `40` itself remains accepted via its other, ordinary evidence elsewhere. All three are now
visible with full tag evidence in `non-operational-evidence.csv` rather than either silently
accepted or silently dropped. `settlementsWithVerifiedRelations` is unchanged at 803 (no
station lost *all* of its accepted codes).

The committed `reference-data/osm-review/` snapshot and
`operational-data/county-v1/osm-review-generated/` preview have been regenerated with this
hardened pipeline and the same reproduced input, and both pass their existing validators
(`node reference-data/tools/validate-canonical.mjs`, `build-territory-plan.py`'s own checks).
`operational-data/county-v1/osm-review-generated/summary.json` now reports 119 lines / 919
mappings / 50 cross-area lines / 20 areas (unchanged) / 2,375 settlements without a relation
(unchanged - the two dropped candidates both belonged to settlements that keep other accepted
relations).

## 6. Promotion tooling — `scripts/promote-osm-railway-reference.py`

New, separate, deliberate command — never a side effect of the build script, CI push, or an
OSM download. For every `ORDINARY_ACCEPTED_BY_RULE` candidate, requires a decision file whose
`candidateId` matches, `decision == "ACCEPTED"`, `policyVersion` matches the dataset's, and
`evidenceHash` matches the candidate's *current* hash. Any candidate missing a decision, with a
stale or wrong-policy-version decision, or an explicit `REJECTED` decision blocks promotion
entirely — the script always writes `promotion-status.json` (what's ready / missing / stale /
rejected) but only writes an importable `manifest.json` (`verificationStatus: VERIFIED`,
`automaticImportAllowed/runtimeCompatible: true`) when literally everything is satisfied. A
decision file for a `candidateId` the current build never produced is also a hard block, not a
silent no-op. Run against the current (still entirely unreviewed) dataset, it correctly
reports **0 of 919 pair candidates ready, 919 missing decisions** and refuses to promote —
exactly the expected state before the manual review milestone.

## 7. Tests

- `scripts/tests/test_osm_railway_reference.py`: 5 pre-existing tests unchanged and still
  passing (schema/output-shape backward compatible for the accepted-candidate path); 8 new
  tests added — siding-only and industrial-usage-only quarantine, mixed
  ordinary/non-operational evidence on one station, defense-in-depth exclusion of
  abandoned/disused ways even when present in the input, stable candidate IDs, deterministic
  rebuild (byte-identical output across two runs), and changed evidence changes the hash.
- `scripts/tests/test_promote_osm_railway_reference.py` (new): unreviewed candidate blocks
  promotion; accepted+current decision promotes; rejected candidate never reaches the
  promoted CSV; stale evidenceHash blocks; unknown `candidateId` blocks; a blank
  `{"approved": true}` file is refused outright; wrong `policyVersion` blocks; an
  `--expect-policy-version` mismatch blocks before any decision file is even read; promoted
  output is deterministic across repeated runs.
- **Result: 34/34 tests pass**, including the pre-existing, untouched
  `scripts/tests/test_territory_plan.py` suite (15 tests) — confirms this milestone's changes
  did not regress the territory-plan generator.

## 8. What this milestone deliberately does not include

- The manual review of unusual codes, cross-area lines, far-evidence rows, Budapest, a
  per-county sample, and every `needs-review.csv` reason category (original brief §4) — not
  started. `decisions/` is still empty by design.
- Any actual promotion — `promote-osm-railway-reference.py` correctly refuses, since no
  decisions exist yet.
- The full repository regression suite (backend/Android/Web Gradle+npm builds) — not run this
  round, because nothing in this milestone touched backend, Android or Web source; only
  `scripts/`, `reference-data/osm-review/`, `operational-data/`, and `docs/` changed. It will
  be run before the PR is opened, per the brief's §14/final-verification requirement.
- Security/architecture review (original brief §7) and the rollout/rollback plan (§8) — next
  milestones.

## 9. No blockers found this round

Reproducibility succeeded; no PENDING VPE/KTI/GYSEV data was read, referenced, or compared
against at any point; `automaticImportAllowed`/`runtimeCompatible`/`verificationStatus`
remain exactly as restrictive as before (`false`/`false`/`UNVERIFIED`) until promotion
actually happens; nothing was deployed or imported into any database.
