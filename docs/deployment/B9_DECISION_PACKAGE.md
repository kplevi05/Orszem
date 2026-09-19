# B9 — owner decision package (production reference dataset)

**Status: `reuseStatus: PENDING`. Nothing is `CLEARED`, nothing was imported, and this document decides
nothing.** It is an engineering evidence review for the owner, **not legal advice**. It builds on
[REFERENCE_DATA_REUSE_MEMO.md](REFERENCE_DATA_REUSE_MEMO.md) and does not contradict it.

Every statement below was checked against the actual files on 2026-09-19: the durable backup
(`C:\Users\ottva\.orszem\reference-data\2026.09.07-1\`, 8 of 8 checksums OK), the tracked repository files,
the licence file, ADR 0005/0006/0007, `docs/PHASE_3B_DECISION_GATE.md`, the builder/validator tools and the
backend code. It contains **aggregate figures only**: no row-level VPE-derived data.

`v2.0.1` is untouched. Deployment, DNS, V1, signing keys and production secrets are out of scope.

## 1. Per-component evidence

Labels are the requested three: `CLEAR EVIDENCE FOR REUSE`, `INSUFFICIENT EVIDENCE`, `DO NOT REUSE`. A
label is an evidence assessment, **not** a clearance.

### 1.1 KSH settlements — **CLEAR EVIDENCE FOR REUSE**

| | |
|---|---|
| Exact source | KSH, *Magyarország helységnévtára*, `https://www.ksh.hu/docs/helysegnevtar/hnt_letoltes_2025.xlsx`, state 2025-01-01, retrieved 2026-09-07, SHA-256 `d059a148…37991` (recorded in the licence file and the manifest) |
| Provenance | Derived to `settlements.csv` (`ksh_code`, `name`, `county_name`; 3,178 rows). The tracked copy `reference-data/cleared/settlements.csv` hashes to `70b34b3a…359cd`, identical to the manifest |
| Licence evidence present | `reference-data/LICENSES/KSH-helysegnevtar.md`: CC BY 4.0 under KSH's terms; the CC BY-NC restriction applies only to individual-request extracts, not to this download |
| Attribution | Required: "Forrás: KSH — https://www.ksh.hu", visible, with a working link when online. Implemented in `2.0.1`: "Településadatok forrása: KSH (CC BY 4.0)" linking to `https://www.ksh.hu` on Public Web, Public Android and Service Android (not on three minor screens, see the patch report §3) |
| Production use = redistribution/publication? | Yes, and that is permitted: CC BY allows it with attribution. The file is already public in this repository under that licence |
| Public API reconstructs it? | Yes (name search, ≥ 2 characters, 20 results per call, no login). Irrelevant for the verdict: the licence permits it |
| Evidence supporting reuse | Explicit public licence, licence file with retrieval hash, tracked file matches the manifest hash, attribution shipped |
| Evidence missing | The KSH terms page was **not re-read online** for this package (the licence file records what was read at retrieval). Attribution is absent from the Service moderation deleted-list, Home history previews and the Service filter sheet |

### 1.2 Railway-line roster — **INSUFFICIENT EVIDENCE**

| | |
|---|---|
| Exact source | VPE (KTI VPE Igazgatóság), *Hálózati Üzletszabályzat 2026/2027*: annex 5.2-4 (MÁV, "AE. sz. módosítás") and annex 5.2-5 (GYSEV, "D. sz. módosítás"), archive `https://vpe.kti.hu/wp-content/uploads/2026/09/husz-2026-2027-ae-sz-modositas.zip`, retrieved 2026-09-07 |
| Provenance | `railway-lines.csv`: 231 lines (`line_code`, `display_name`), SHA-256 `efba4fd3…457c6`. `display_name` is **our** string, built by the builder from the first and last service point of the line (`displayNameFor`), not a source field. The source archive was **never hashed** (`sha256: null` for both annexes) and the source files are **not on this machine**, so rows cannot be re-derived today. Documented per-annex line counts (207 MÁV + 20 GYSEV-only = 227 distinct) do **not** add up to 231; the four-line difference is not explained in any recorded document |
| Licence evidence present | **None.** Recorded basis only: mandatory network statement (Directive 2012/34/EU Art. 27); public-body data (Act LXIII of 2012 on reuse of public data); factual rows only; source not redistributed. The manifest itself says "owner confirmation recommended before external distribution". GYSEV rows: gysev.hu's legal notice forbids putting site content in a database without written permission; the data came from the VPE annex, but whether GYSEV holds any right in its own service-point data was not investigated |
| Attribution | None required or specified. If reuse were granted in writing, any attribution term would come with that grant |
| Production use = publication? | **Yes in effect.** The Public API returns each settlement's lines to anonymous callers |
| Public API reconstructs it? | **Partly.** Exposed per line: internal id, `code`, `displayName`. Enumerating settlements (substring search, ≥ 2 chars, ≤ 20 results, no rate limit on lookups) and calling `GET /public/reference/settlements/{id}/railway-lines` for each yields the **147 lines that have a relation** completely. The **84 lines with no relation are never returned**. MÁV/GYSEV origin and service-point names are not exposed. Read from the code (`PublicReferenceController`, `PublicReferenceQueryUseCase`, `JdbcReferenceRepository`); not exercised against the real data |
| Evidence supporting reuse | Recorded regulatory/public-body basis; only factual identifiers derived; VPE is the source for both networks; no evidence of any restriction on the VPE annexes was found either (absence of a licence, not a prohibition) |
| Evidence missing | Written grant or licence statement from VPE; VPE source hash and a retained copy of the annexes; resolution of the 227/231 difference; any GYSEV position; legal view that "factual rows" cover a line roster with our derived display names |

### 1.3 Settlement ↔ railway-line relations — **INSUFFICIENT EVIDENCE**

| | |
|---|---|
| Exact source | Same VPE annexes, joined to KSH settlements by exact normalised name |
| Provenance | `settlement-railway-lines.csv`: 967 pairs (`ksh_code`, `line_code`), SHA-256 `3ce18969…6ed6695`, from 989 exact deterministic candidates (22 duplicates collapse). No similarity score anywhere; 0 manual decisions. Source hash never recorded; sources absent (as 1.2) |
| Licence evidence present | As 1.2, plus a **compilation** element: the pairing is our derivation (annex service point → KSH settlement), so it is derived data over a source with no stated licence |
| Attribution | As 1.2 |
| Production use = publication? | **Yes in effect, and most directly.** This is exactly what the settlement→lines endpoint returns |
| Public API reconstructs it? | **Yes, completely.** All 967 pairs are recoverable by enumeration (3,178 settlement lookups after enumerating settlements). Verified from code; the API returns only active lines and the settlement's coverage flag |
| Evidence supporting reuse | Same as 1.2; rows are exact matches only, so correctness is strong for what is included |
| Evidence missing | Same as 1.2. Additionally: no completeness evidence (§2) |

### 1.4 Other derived / reference components

| Component | Evidence and label |
|---|---|
| **Quarantined candidates** (`needs-review.csv`, 1,122 rows: 606 `AMBIGUOUS_NAME`, 516 `NO_SETTLEMENT_MATCH`) | Same VPE origin, **not** verified, not part of the canonical dataset, never imported, not exposed by any API. **DO NOT REUSE** in production: unverified and outside the manifest. Remain backup/research material |
| **Manual review file** (`manual-review.csv`) | Header only, 0 decisions. Nothing to reuse |
| **Event-type taxonomy** (7 categories, 61 types) | First-party, carried from the owner's own Demo v1.1 taxonomy into migration V003 and `docs/product/EVENT_CATALOG_V2.md`. Not third-party data, not part of the reference dataset, not gated by `reuseStatus`. **CLEAR EVIDENCE FOR REUSE** (owner-authored; no third-party rights in evidence) |
| **Service areas and line→area assignments** | Operational data created by a SUPER_ADMIN after import (B2 open). Not dataset content. Not applicable to the reuse question |
| **Import provenance row** (`reference_dataset_imports`, audit event) | Written by the importer from the manifest, including licence references. No independent rights question |

## 2. Coverage: what is the 25.9 %?

Verified figures (manifest, `build-summary.json`, Phase 3B §2; all consistent):

| | |
|---|---|
| Settlements / lines / relations | 3,178 / 231 / 967 |
| Settlements with ≥ 1 relation | 824 (25.9 %); 2,354 without |
| Lines with ≥ 1 relation | 147 (63.6 %); 84 without |
| Relations per settlement | 716 have 1, 80 have 2, 23 have 3, 4 have 4, 1 has 6 |
| Source extraction | 2,111 of 2,116 annex data rows parsed (99.76 %; 5 rows not extracted, not itemised) |
| Extracted rows → outcome | 989 exact (→ 967 relations) + 606 ambiguous + 516 no match = 2,111 |

**Determination: (c) not determinable from the available evidence. Both (a) and (b) contribute and the
documents do not split them.**

- **Partly by design (a).** Phase 3B §7 records that the annexes list *service points* (stations, stops,
  junctions, sidings), not every settlement a line crosses. A settlement the track passes without a
  service point cannot appear, "no matter how much manual review is done". Some of the 2,354 settlements
  therefore legitimately have no relation *in this source*.
- **Partly incomplete processing (b).** 1,122 of 2,111 extracted station rows (53 %) were **quarantined**,
  not matched, by the deliberately strict exact-name rule, with **0** manual decisions made. Phase 3B §3
  states an "unknown minority" of uncovered settlements have a railway "that only the quarantine would
  reveal". Budapest is at 0 of 24 for this reason (8 quarantined rows). The 25.9 % is therefore a floor
  produced by conservative matching, not a completed result.
- **Why (c).** Phase 3B §3 says most uncovered settlements have no railway, but that is an assertion: no
  recorded measurement separates railway-served settlements from the rest, and the source cannot show it
  (§7). Quantifying it would require inferring or resolving the quarantined rows, which this package does
  not do. The dataset is honest about this: ADR 0006 marks the relation component `PARTIAL`, and an empty
  result means "no verified reference", never "no railway".

## 3. Production suitability (separate from reuse rights)

| Check | Result |
|---|---|
| Schema compatibility | Compatible. The three canonical files use the importer's headers (`ksh_code,name,county_name`; `line_code,display_name`; `ksh_code,line_code`); `reference-validate` returned VALID and `reference-diff` showed +3,178 / +231 / +967, 0 removals (earlier this session, disposable DB) |
| Manifest compatibility | The recovered manifest is the **original** (v0): it lacks `reuseStatus` and per-component `coverage`. An upgraded working manifest (adding only those two, everything else unchanged) validated. **That working copy was removed from OneDrive**; it must be rebuilt from the backup before any import. Regenerable, not automatic |
| Hashes / integrity | The three canonical file hashes match the manifest; the backup's 8 checksums verify; the tracked KSH file matches its hash. **Source integrity is unproven** for VPE: no source hash |
| Validation result | `validate-canonical.mjs`: valid, `VERIFIED / PARTIAL / PENDING`; backend `reference-validate`: VALID; `reference-import`: **refused** with `REFERENCE_DATASET_REUSE_NOT_CLEARED` (0 rows written) |
| Reference completeness | Settlements `COMPLETE`. Lines and relations `PARTIAL` (§2). Budapest city vs 23 districts undecided (B8/E4) |
| Import requirements | Rebuild working dir from backup; set `reuseStatus` only per the owner's decision; import is transactional, advisory-locked, idempotent by `ksh_code`/`line_code`; needs a first administrator; runs at deployment step 7 |
| PARTIAL vs COMPLETE | `PARTIAL` (current): the importer never deletes on absence, and routing **never auto-infers**: even a settlement with exactly one verified line yields `UNCLASSIFIED / RAILWAY_LINE_NOT_SELECTED` unless the reporter selects it; a settlement with no relation yields `NO_VERIFIED_RAILWAY_LINE_REFERENCE`. `COMPLETE` would allow inferring a single verified line. The recovered data **cannot honestly be declared COMPLETE** (it would need paid boundary data, Phase 3B §7) |
| Service-area mappings | **Must be configured manually after import.** The import creates no service area and no line→area assignment. Without them an active line routes `UNCLASSIFIED / RAILWAY_LINE_UNASSIGNED`. That is B2 work by a SUPER_ADMIN, needed under every option |

**Suitability verdict:** technically importable and validated *if* the owner authorises it. What it delivers
is limited by design: most reports will be `UNCLASSIFIED` (visible only to global MODERATOR/SUPER_ADMIN)
until relations and service areas are extended (E5).

## 4. Owner decision table

| Component | Evidence | Missing evidence | Recommended owner decision choices |
|---|---|---|---|
| KSH settlements | CC BY 4.0 on file, hash-matched, attribution shipped with link | Online re-read of KSH terms; attribution on 3 minor Service screens | (1) Accept as reuse-cleared and record it; (2) first re-read the KSH terms page. *Recommended: 1, optionally after 2* |
| Railway-line roster | Recorded public-body basis only; 147 of 231 lines publicly reconstructable through the API | Written VPE grant; VPE source hash and copy; GYSEV position; 227 vs 231 line-count difference | (A) Obtain written VPE confirmation, then decide; (B) declare the basis sufficient yourself and record it; (C) launch without the roster; (D) author your own roster. *Memo recommendation: A* |
| Settlement ↔ line relations | Same basis; 967 exact pairs, fully reconstructable through the API | Same as roster; no completeness evidence (25.9 % not determinable) | Same A / B / C / D, decided **together with the roster** since neither is usable alone. *Memo recommendation: A; C is the safe fallback for go-live* |
| Other: quarantined candidates, manual-review file | Unverified, not imported | Any verification | Keep as backup only; do not import. Optionally start manual review later |
| Other: event taxonomy, service areas | First-party / operational | none for reuse | No decision needed (service areas are B2 work after import) |

**STOP.** Nothing is `CLEARED`; no import will be run until you decide.
