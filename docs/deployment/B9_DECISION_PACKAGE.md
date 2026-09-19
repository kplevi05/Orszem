# B9 — owner decision package (production reference dataset)

Engineering evidence review, **not legal advice**. It builds on
[REFERENCE_DATA_REUSE_MEMO.md](REFERENCE_DATA_REUSE_MEMO.md) and contains **aggregate figures only**: no
row-level VPE-derived data. First issued 2026-09-19; updated the same day with the owner's decisions.

B9 is split in two, because they have different owners of the answer and different exit conditions:

| | Question | Exit condition |
|---|---|---|
| **B9-A — reuse / licence clearance** | May each component lawfully be used in the public production service? | Owner decision per component; for the railway data, **written VPE confirmation** |
| **B9-B — reference-data quality / completeness** | Is the data good and complete enough to be useful in routing? | A worked review plan ([B9B_REFERENCE_DATA_QUALITY_PLAN.md](B9B_REFERENCE_DATA_QUALITY_PLAN.md)); independent of B9-A |

Neither is done by the other: a cleared dataset can still be too sparse, and a complete one can still be unusable
without permission. `reuseStatus` in the manifest stays **`PENDING`** while any component is uncleared; the
importer's dataset-level gate is unchanged. **No import, no deployment, `v2.0.1` unmodified.**

## 0. Owner decisions recorded (2026-09-19)

| Component | Owner decision | State |
|---|---|---|
| KSH settlements | **CLEARED**, conditional on the provenance showing the public Helységnévtár download and not a custom extract; KSH's current terms independently checked (CC BY 4.0, mandatory source attribution with a working link) | Provenance condition **verified below (§A1)**. Six surfaces still lack the attribution line in `v2.0.1` (§A1.2) |
| Event taxonomy | **CLEARED** | First-party; no action |
| Service areas | No reuse decision needed | Operational data (B2) |
| Quarantined candidates | **DO NOT REUSE** | Backup only |
| Railway-line roster | **PENDING** | Needs written VPE confirmation |
| Settlement ↔ line relations | **PENDING** | Needs written VPE confirmation |

The owner **rejected** the option "declare the basis sufficient ourselves" (memo option B). Written confirmation from the
current KTI VPE Igazgatóság is required before either railway component may be marked `CLEARED`. The request is drafted,
**not sent**: [VPE_PERMISSION_REQUEST_DRAFT.md](VPE_PERMISSION_REQUEST_DRAFT.md).

---

# B9-A — reuse and licence clearance

## A1. KSH settlements — CLEARED by owner (evidence)

### A1.1 Provenance check: public download, not a custom extract

| Check | Result |
|---|---|
| Source recorded | `https://www.ksh.hu/docs/helysegnevtar/hnt_letoltes_2025.xlsx`, a `/docs/` path of the public site, state 2025-01-01, retrieved 2026-09-07 (manifest `sources.KSH`; `reference-data/LICENSES/KSH-helysegnevtar.md`) |
| Anything indicating an individual request | **None.** No request, correspondence, order number or restricted-use notice is recorded anywhere in the manifest, licence file, builder or docs. The licence file states the CC BY-NC exception (data extracted from KSH's databases *on individual request*) and records that it does **not** apply to this download |
| Content matches a standard published table | The builder reads a plain worksheet (`sheet1`: name, 5-digit KSH code, county) from the unzipped `.xlsx`, the shape of a published registry, not a bespoke extract |
| Derived file integrity | `reference-data/cleared/settlements.csv` (tracked, public) hashes to `70b34b3a…359cd`, equal to the manifest; 3,178 rows |
| Not verifiable | The source workbook is **not retained**, so its recorded hash `d059a148…37991` cannot be re-checked from here. Re-downloading it (a download, needing your approval) and comparing the hash would close this fully |

**Conclusion:** the preserved provenance is consistent with the public download and contains nothing pointing to a custom extract.
The condition is met on the recorded evidence; the one residual is that the workbook itself was not kept.

### A1.2 Client surfaces that show KSH-derived settlement names

Audited every place in the three clients (code sweep for settlement names, then traced which screens render each card and
list). The earlier package said "three minor Service surfaces" (deleted list, Home history previews, filter sheet). **That was
incomplete and is corrected here: six surfaces lack the line in `v2.0.1`**: it missed the two submission-success screens, and
the Home previews are two surfaces (Public Android and Public Web).

| Client | Surface | In `v2.0.1` | In the patch candidate |
|---|---|---|---|
| Public Android | New Report step 1; History | shown | unchanged |
| Public Android | **Home** (last three history cards) | **missing** | added |
| Public Android | **Submission-success screen** ("Település: …") | **missing** | added |
| Public Web | Step 1 form; History | shown | unchanged |
| Public Web | **Home** (history preview) | **missing** | added |
| Public Web | **Submission-success view** | **missing** | added |
| Service Android | Reports (new/in progress) and Archive headers; report detail; deleted-report detail | shown | unchanged |
| Service Android | **Deleted-reports list** | **missing** | added |
| Service Android | **Report filter sheet** (settlement search and chip) | **missing** | added |

No other screen presents settlement names (analytics, audit, area administration and login were checked).

**Smallest compliance patch, prepared and not tagged:** branch `release/v2.0.2`, one commit `8a24b3c` on `main` (`a80f6e5`,
= `v2.0.1`). It reuses the existing `SettlementDataSourceNote` (same approved wording and `https://www.ksh.hu` link) with one
line per surface: 9 files, +134/−7 including tests. **No version bump** (a release step, not part of this fix), **no tag**.

| Verification | Result |
|---|---|
| Web | typecheck and production build green; **74** tests (71 + 3 new); the two behavioural new tests fail without the change |
| Android | `assembleDebug` both apps, unit tests, `lint` all green |
| Instrumented (emulator) | Public **30** (29 + 1), Service **94** (93 + 1), 0 failures |
| Limits | The filter sheet is verified by compilation and code review only (no test seam: it needs a live catalogue repository); the Web Home test also asserts the note is absent when no settlement name is shown |

## A2. Railway-line roster and settlement ↔ line relations — PENDING

Assessment unchanged by the owner's decision, which now fixes the path: **written confirmation is required**.

| | Roster (`railway-lines.csv`, 231) | Relations (`settlement-railway-lines.csv`, 967) |
|---|---|---|
| Source | VPE *Hálózati Üzletszabályzat 2026/2027*, annex 5.2-4 (MÁV, "AE. sz. módosítás") and 5.2-5 (GYSEV, "D. sz. módosítás"), retrieved 2026-09-07 from `https://vpe.kti.hu/wp-content/uploads/2026/09/husz-2026-2027-ae-sz-modositas.zip` | same, joined to KSH settlements by exact normalised name |
| Hash | `efba4fd3…457c6` | `3ce18969…6ed6695` |
| Licence evidence | **None.** Only a recorded basis (Directive 2012/34/EU Art. 27; Act LXIII of 2012). GYSEV: gysev.hu's legal notice forbids database use without permission; whether it has a claim over its data inside the VPE annex is uninvestigated | as roster, plus our derivation over it |
| Evidence label | **INSUFFICIENT EVIDENCE** | **INSUFFICIENT EVIDENCE** |
| Publication in production | Yes in effect: 147 of 231 lines are reconstructable by enumerating the Public API (id, `code`, `displayName`); the 84 lines with no relation are never returned | Yes, completely: all 967 pairs are reconstructable (settlement search ≥ 2 chars, ≤ 20 per call, then one lookup per settlement) |
| `display_name` | Our string: first and last service point of the line (`displayNameFor`), not a source field | n/a |
| Missing evidence | Written VPE grant (use, publication via API, modification/normalisation, commercial use, attribution wording, GYSEV coverage); VPE source hash and retained annex copy | same |

**What the written confirmation must cover** (all in the drafted request): the exact annexes; derivation of a line roster and relations
database; public production service; public API return making the relations reconstructable; derived names and normalisation;
redistribution, publication and modification; commercial use; attribution and its exact wording/link; whether GYSEV material
needs separate permission.

**Before sending, the owner should:** confirm `vpe@kti.hu` is the current official contact (not verified from here); optionally
re-download and hash the annex archive so the provenance gap (`sha256: null`) is closed and the email cites a verifiable file.

## A3. Other components

| Component | Decision / label |
|---|---|
| Quarantined candidates (1,122 rows) and the empty manual-review file | **DO NOT REUSE** (owner). Unverified, unimported, exposed by no API |
| Event taxonomy (7 categories, 61 types, migration V003) | **CLEARED** (owner); first-party |
| Service areas and line→area assignments | No reuse question; SUPER_ADMIN configuration after import (B2) |
| Import provenance row | Written by the importer from the manifest; no separate rights question |

## A4. Consequence for production while B9-A is open

The importer gate is **dataset-level**: the recovered manifest carries one `reuseStatus`. Two consequences the owner should know:

- With the two railway components PENDING, the recovered dataset **cannot be imported in any form**; this is the intended state.
- KSH being `CLEARED` does not by itself allow a settlements-only launch (memo option C). That needs a **separate KSH-only dataset**
  (settlements `COMPLETE`, empty line and relation files, `reuseStatus: CLEARED`). Whether the validator and importer accept an
  empty roster/relation file is **not verified** here; it is a small, testable question to settle if option C is chosen. Nothing was built.

---

# B9-B — reference-data quality and completeness

Full plan and numbers: [B9B_REFERENCE_DATA_QUALITY_PLAN.md](B9B_REFERENCE_DATA_QUALITY_PLAN.md). No quarantined row was classified and no
relation inferred. Headlines:

| Question | Answer |
|---|---|
| The 227 vs 231 line difference | **Explained, no missing data.** 207 (MÁV) and 32 (GYSEV) with 12 shared are the lines that have **at least one quarantined row** (227 distinct). The other **4** lines have relations and no quarantined row (7 relations). 227 + 4 = 231, and the union of "lines with a relation" and "lines with a quarantined row" is exactly the 231-line roster. The Phase 3B §4 heading "lines seen" therefore over-states what those figures are |
| The 1,122 quarantined rows | 973 MÁV + 149 GYSEV; 606 flagged `AMBIGUOUS_NAME` by a **lexical rule** (a marker character/word in the name) and 516 `NO_SETTLEMENT_MATCH`; 935 distinct names on 227 lines. The flag is not proof of ambiguity |
| Zero manual decisions | The review file was created as an **empty template** in the same commit as the pipeline (2026-09-07) and no decision was ever recorded. No document records a reason beyond Phase 3B listing the work as future manual effort needing an authoritative source per decision |
| Budapest 0 / 24 | 8 quarantined rows (4 distinct names, on 7 lines), all flagged by the `Budapest` marker rule; the exact settlement name `Budapest` is not matched because station names are composites. Attribution to a district needs a person and B8 (city vs district) |
| Routing impact | **84 lines** (of 231) have only quarantined rows: 193 rows, 155 names. They cannot be selected by anyone until reviewed. Whether that matters depends on which lines the owner assigns to service areas (B2), which is not yet configured |
| PARTIAL semantics | Acceptable as a *safe* state (never a false claim); it does make many reports `UNCLASSIFIED` (see the plan §5). It does not become `COMPLETE` however many rows are reviewed |

---

# Decision table (remaining owner items)

| Component | Evidence | Missing evidence | Recommended owner decision choices |
|---|---|---|---|
| KSH settlements | CLEARED by owner; public-download provenance consistent; CC BY 4.0 verified; attribution on 8 of 14 surfaces in `v2.0.1` | Retained source workbook (optional re-check); attribution on 6 surfaces | Approve/merge the `release/v2.0.2` candidate and its version bump and tag (separate step), or accept the six gaps knowingly until then. *Recommended: patch before production* |
| Railway-line roster | Recorded basis only; 147 of 231 publicly reconstructable | Written VPE confirmation; VPE source hash | (A) Send the drafted request (after checking the address); (C) plan go-live without it; (D) author your own. B is excluded |
| Settlement ↔ line relations | Same; 967 exact pairs fully reconstructable | Same | Decide together with the roster; go-live fallback is C, which also needs a KSH-only dataset to be built and validated |
| B9-B quality | Numbers and plan ready; nothing classified | A decision to start review, who reviews, which authoritative source per row | Approve the plan's tiers; start with Budapest (8 rows) and the 84 zero-relation lines; or defer and launch knowing most reports route `UNCLASSIFIED` |
| Quarantine, taxonomy, service areas | Owner decisions recorded | none | none |

**STOP.** Railway data is not `CLEARED`, nothing was imported, no deployment, `v2.0.1` unmodified.
