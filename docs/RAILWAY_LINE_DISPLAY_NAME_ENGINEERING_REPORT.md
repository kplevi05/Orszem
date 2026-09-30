# Railway line display names — Phase A engineering report

**Status: draft, awaiting owner review.** No production data was touched, no `reference-import`
ran, no territory apply ran, no `v2.0.4` tag/Release/artifact was modified, nothing was deployed.
The `v2.0.5` batch endpoint / Web / Android work (Phase B) is not implemented here.

## 1. What this is

Phase A of `docs/RAILWAY_LINE_DISPLAY_NAME_AND_DETAIL_REVIEW.md`: the owner approved a specific,
exact display name for all 38 promoted railway lines (see that document's review sheets, and the
owner's verbatim approval message this PR's decisions file transcribes). This report documents
the resulting decisions file, the promoter change that applies it, its protections, and the
proof that the regenerated dataset changes only what it should.

## 2. New files

- **`reference-data/osm-review/railway-line-display-name-decisions.json`** — the versioned,
  owner-approved decision record. `schemaVersion: 1`, `policyVersion: "RAILWAY-LINE-NAME-V1"`,
  38 entries, every one `humanApproved: true`, each with `source` (URL, retrieval date, source
  type, license) and a paraphrased `evidence` field. Every `approvedDisplayName` matches the
  owner's approval message exactly (machine-checked, see §5).
- **`reference-data/LICENSES/Wikipedia-CC-BY-SA.md`** — CC BY-SA 4.0 attribution, following the
  existing `KSH-helysegnevtar.md` / `OpenStreetMap-ODbL.md` pattern, with a retrieved-artefacts
  table (6 rows: the master line-list table plus the 5 dedicated line articles fetched during
  the deeper research pass) and a note on why short place-name pairs do not trigger ShareAlike
  propagation.
- **`scripts/tests/test_railway_line_display_name_decisions.py`** — 16 new tests (see §4).
- **This report.**

## 3. Promoter change

`scripts/promote-osm-railway-reference.py`:

- New `load_display_name_decisions(path, used_codes, expect_policy_version=None)`: loads and
  validates the decisions file, returns `{lineCode: approvedDisplayName}` **only** for entries
  with `humanApproved: true`. Never invents or infers a name.
- `promote()` gained two optional parameters, `display_name_decisions_path` and
  `expect_name_policy_version`; `main()` gained the matching `--display-name-decisions` and
  `--expect-name-policy-version` CLI flags. Omitting them reproduces the exact prior behaviour
  (every line keeps the `"<code>. számú vasútvonal"` placeholder) — this is not a breaking
  change to the existing 919-candidate OSM review/promotion flow.
- `railway-lines.csv` generation changed from
  `(code, f"{code}. számú vasútvonal")` to
  `(code, display_name_overrides.get(code, f"{code}. számú vasútvonal"))` — a line without an
  approved override is unaffected, byte for byte.
- The manifest gains a `displayNameSource` block (decisions file path, its own SHA-256,
  overridden/total line counts, attribution string) whenever a decisions file was supplied.

### 3.1 Stale / missing / duplicate protection

| Condition | Result |
|---|---|
| Decisions file does not exist | `PromotionBlocked` |
| Decisions file is not valid JSON | `PromotionBlocked` |
| `schemaVersion` is not `1` | `PromotionBlocked` |
| File's top-level `policyVersion` missing, or mismatches `--expect-name-policy-version` | `PromotionBlocked` |
| A decision's own `policyVersion` differs from the file's top-level one | `PromotionBlocked` |
| A decision is missing a required field (`lineCode`, `approvedDisplayName`, `source`, `evidence`, `decisionStatus`, `humanApproved`, `policyVersion`) or a required `source` subfield (`url`, `retrievedAt`, `sourceType`, `license`) | `PromotionBlocked` |
| `approvedDisplayName` or `evidence` is blank/whitespace-only | `PromotionBlocked` |
| Two decisions name the same `lineCode` (**duplicate**) | `PromotionBlocked` |
| A decision names a `lineCode` absent from this run's own promoted `used_codes` (**stale** — the closest structural equivalent here to the OSM decisions' evidence-hash staleness, since a line name has no per-candidate hash to go stale against) | `PromotionBlocked` |
| `humanApproved` is present but not a boolean | `PromotionBlocked` |
| `humanApproved: false` | Entry is simply not applied — not an error, the safe placeholder is kept |
| No `--display-name-decisions` at all | Every line keeps its safe placeholder — unchanged prior behaviour |

### 3.2 `datasetVersion` collision — found and fixed during this work

The promoter originally derived `datasetVersion` purely from the underlying OSM review
manifest's own version (`OSM-HU-RAIL-REVIEW-… → OSM-HU-RAIL-VERIFIED-…`), which does not change
when only display names change. Re-promoting with the same review dataset would therefore have
produced **the exact same `datasetVersion` string** (`OSM-HU-RAIL-VERIFIED-2026-09-23`) as the
already-imported production dataset. `ReferenceImportUseCase.import()`
([`ReferenceImportUseCase.kt:116`](../backend/src/main/kotlin/hu/orszembejelento/backend/reference/application/ReferenceImportUseCase.kt))
looks up an existing import **by `datasetVersion`**, and when the manifest content differs
under an already-used version, it throws `ReferenceDatasetVersionConflictException` rather than
upserting — this name-only release would have been **unimportable** as originally generated.

**Fix:** when a decisions file is supplied and at least one line is actually overridden, the
promoter appends a deterministic suffix derived from the decisions file's own SHA-256:
`-NAMES-<first 8 hex chars>`. This produces
`OSM-HU-RAIL-VERIFIED-2026-09-23-NAMES-d895e061` for the current decisions file — a genuinely
new, importable version, while staying fully deterministic (an unchanged decisions file always
regenerates the identical suffix; a changed one changes it automatically, so a future name
correction can never silently collide with this one).

## 4. Test suite

Full suite run: `python3 -m unittest discover -s scripts/tests -v` (via a `python:3.12-slim`
Docker container, since the local Windows Python install is a non-functional Store stub — same
workaround used throughout this project's sessions).

```
Ran 71 tests in 1.087s
OK
```

71 = the pre-existing 55 (county territory preview, OSM railway reference, promote-osm-railway-
reference, territory plan) **+ 16 new** in `test_railway_line_display_name_decisions.py`:
happy path (override applied; untouched lines keep the placeholder), `humanApproved: false`
never applied, `--expect-name-policy-version` match, and one test per hard-failure condition in
§3.1 (missing file, invalid JSON, wrong schema version, policy version mismatch at both the
file and per-decision level, missing required field, missing `source` subfield, blank name,
duplicate `lineCode`, stale `lineCode`, non-boolean `humanApproved`), plus a dedicated test that
loads the **real, committed** decisions file and asserts: `schemaVersion`/`policyVersion` as
expected, exactly 38 entries, no duplicate `lineCode`, every entry `humanApproved`, every
`approvedDisplayName`/`evidence` non-blank, every decision's `policyVersion` matching the file's
own.

## 5. Regenerated dataset — proof that only 38 `display_name` values changed

```
python3 scripts/promote-osm-railway-reference.py \
  --review-dir reference-data/osm-review \
  --out work/promote-release-names \
  --expect-policy-version 2026-09-23.1 \
  --display-name-decisions reference-data/osm-review/railway-line-display-name-decisions.json \
  --expect-name-policy-version RAILWAY-LINE-NAME-V1

PROMOTED OSM-HU-RAIL-VERIFIED-2026-09-23-NAMES-d895e061: {'settlements': 3178, 'railwayLines': 38, 'settlementRailwayLineMappings': 146} (146 of 919 candidates)
```

Not committed to Git (same convention as the existing, untracked `work/promote-release/` used
for the v2.0.4 rollout — this is a generated build artifact, not source).

| File | Result |
|---|---|
| `settlements.csv` | **Byte-identical** to `work/promote-release/settlements.csv` — SHA-256 `70b34b3a…9cd` both sides |
| `settlement-railway-lines.csv` | **Byte-identical** — SHA-256 `7fa589c0…d8e` both sides (still 146 mappings) |
| `railway-lines.csv` — `line_code` column | **Byte-identical** — `diff <(cut -d, -f1 …) <(cut -d, -f1 …)` produces no output; still exactly 38 lines |
| `railway-lines.csv` — `display_name` column | **All 38 changed**, from `"<code>. számú vasútvonal"` to the owner-approved name; row count unchanged (39 lines including header, both files) |

Manifest diff: `datasetVersion` changed (§3.2, required for importability),
`counts`/`coverage`/`sources`/`review` block unchanged, new `displayNameSource` block added
(`overriddenLineCount: 38`, `totalLineCount: 38`, the decisions file's own SHA-256, the
attribution string).

Independent verification, run directly against both generated directories in this session:

```
diff work/promote-release/settlements.csv work/promote-release-names/settlements.csv            # empty
diff work/promote-release/settlement-railway-lines.csv work/promote-release-names/settlement-railway-lines.csv   # empty
diff <(cut -d, -f1 work/promote-release/railway-lines.csv) <(cut -d, -f1 work/promote-release-names/railway-lines.csv)  # empty
```

## 6. What was deliberately NOT done in this PR

- No `reference-validate` / `reference-diff` / `reference-import` was run against production or
  any database — the regenerated dataset was only ever checked at the file level (§5). Those
  three steps are the next, separately-approved action once this PR is reviewed (per the
  two-phase plan in `docs/RAILWAY_LINE_DISPLAY_NAME_AND_DETAIL_REVIEW.md` §8), and per Phase A
  step 5's requirement for its own distinct owner approval sentence for the actual import.
- No territory apply, no `v2.0.4` tag/Release change, no `v2.0.5` API/Web/Android code, no
  deployment.
- `work/` (including the two generated dataset directories) stays untracked, per this project's
  standing convention and instruction.

## 7. Open item carried forward (not blocking this PR)

Per the owner's disclaimer in the approval message, several approved names (6, 13, 15, 23, 29,
43, 89, 109, 115, 117, 151, and the border-framing ones 1/81/105) do not assert current
passenger service, complete route coverage, or reproduction of a legal infrastructure endpoint.
Two of those (6 and 151) surfaced a genuine "might not currently run" signal during the deeper
per-line research (an infobox `0 km/h` max-speed segment for line 6; a "last of the trains"
photo caption for line 151) — captured in the decisions file's own `evidence` fields, not acted
on here. If Phase B's UI work ever needs to represent operational status, that is new scope
requiring its own owner decision, not something this display-name-only release resolves.
