# Reference data — reuse and provenance memo (decision B9)

> **Superseded in part (2026-09-19):** the owner's decisions and the split into B9-A / B9-B are in
> [B9_DECISION_PACKAGE.md](B9_DECISION_PACKAGE.md). Option B below is rejected; written VPE confirmation is required for the
> railway components. This memo is otherwise unchanged.

**Status: `reuseStatus: PENDING`. Production import is blocked, and stays blocked, until the owner
decides.** This memo records what evidence exists for each source, what it does and does not
support, and exactly what the owner must decide. It is an engineering record, **not legal advice**;
nothing here asserts that any source may or may not lawfully be reused.

It contains no row-level data. The dataset itself is kept outside Git (see §6).

## 1. One-page summary

| Source | What we take | Evidence in hand | Supports production reuse? |
|---|---|---|---|
| **KSH** *Magyarország helységnévtára* | Settlement identity: `ksh_code`, name, county (3,178 rows) | KSH's own terms: CC BY 4.0, with a required attribution. Recorded in `reference-data/LICENSES/KSH-helysegnevtar.md` | **Yes, with attribution.** Attribution is now shown in the Public Web, Public Android and Service Android (this patch). Already `CLEARED` in `reference-data/cleared/` |
| **VPE HÜSZ 2026/2027, annex 5.2-4 (MÁV)** | Railway line codes and their service points → 231 lines, relations | Recorded *basis*, no licence: mandatory network statement (Directive 2012/34/EU Art. 27); public-body data (Hungarian Act LXIII of 2012 on the reuse of public data); factual rows only; source not redistributed | **Not established.** No written grant; the basis is an argument, not a permission |
| **VPE HÜSZ 2026/2027, annex 5.2-5 (GYSEV)** | GYSEV line codes and their service points | Same basis as above, plus a caution: gysev.hu's own legal notice forbids putting its site content in a database without written permission. The data came from VPE's annex, not from gysev.hu | **Not established**, and slightly weaker (a second rights-holder is plausible) |

**Bottom line.** Settlement identity is fine to use. The railway-line roster and the
settlement↔line relations are the part with no written reuse permission. Because the Public API
answers "which railway lines serve this settlement?" to any anonymous caller, running the service
with this data in production **is** publishing those relations (§4).

## 2. Source by source

### 2.1 KSH — settlements (cleared)

| | |
|---|---|
| Source | Központi Statisztikai Hivatal, *Magyarország helységnévtára*, workbook `hnt_letoltes_2025.xlsx` |
| Version / retrieved | source date 2025-01-01, retrieved 2026-09-07 |
| Source SHA-256 | `d059a14883a27963daa234d9f3954eb68b4cbf967e796710a4bcdb4f03437991` |
| Licence | CC BY 4.0 (KSH terms of use, `https://www.ksh.hu/copyright`); required attribution "Forrás: KSH — https://www.ksh.hu", not hidden or separated from the data, with a working link when online |
| Derived file | `settlements.csv` (3,178 rows), tracked in Git, SHA-256 `70b34b3a…359cd` |
| Attribution status | **Closed in this patch.** The owner-approved line "Településadatok forrása: KSH (CC BY 4.0)" is shown where settlement names are chosen or listed: Public Web (form, History), Public Android (New Report, History), Service Android (Új/Folyamatban and Archívum headers, both report detail screens). It **links to `https://www.ksh.hu`**, the address named in the licence's own required attribution string ("Forrás: KSH — https://www.ksh.hu"), so the "working link when online" requirement is met. Only an underline was added; the wording is unchanged |

### 2.2 VPE HÜSZ annex 5.2-4 (MÁV) — lines and relations (not cleared)

| | |
|---|---|
| Source | VPE (KTI VPE Igazgatóság), *Hálózati Üzletszabályzat 2026/2027*, 5.2-4 sz. melléklet (MÁV szolgálati hely és vonalkategóriák), "AE. sz. módosítás" |
| URL / retrieved | `https://vpe.kti.hu/wp-content/uploads/2026/09/husz-2026-2027-ae-sz-modositas.zip`, 2026-09-07 |
| Source hash | **`null` in the manifest: never recorded** (see §3) |
| Licence | **None published.** Recorded basis: mandatory regulatory publication; public-body data; only factual rows derived; the source document is not redistributed; "owner confirmation recommended before external distribution" |
| Derived files | `railway-lines.csv` (231 lines, SHA-256 `efba4fd3…457c6`), `settlement-railway-lines.csv` (967 relations, SHA-256 `3ce18969…6ed6695`) |

### 2.3 VPE HÜSZ annex 5.2-5 (GYSEV) — lines and relations (not cleared)

As 2.2 with annex 5.2-5 ("D. sz. módosítás"). Additional recorded caution: gysev.hu's legal notice
forbids placing site content in a database without written permission. The manifest states the
data is the VPE-published regulatory annex and not content taken from gysev.hu. Whether GYSEV
itself has any claim over its own service-point data inside VPE's annex was **not** investigated.

## 3. Provenance gaps (things we cannot currently prove)

1. **The VPE source archive was never hashed** (`sha256: null` for both annexes), and **the
   source files are not on this machine**. The derived rows cannot be re-derived or re-verified
   against the source today; only the derived files' own hashes are checkable.
2. **The dataset is a snapshot of one amendment** (HÜSZ 2026/2027, amendments AE and D, retrieved
   2026-09-07). Network statements are amended over time. No refresh procedure or owner is defined.
3. **Quality gaps recorded in the manifest itself:** only 824 of 3,178 settlements (25.9%) have a
   verified relation; 1,122 candidate relations are quarantined for review; the manual review file
   contains no decisions yet.
4. **The dataset exists only as orphaned Git objects plus copies** (recovered this session, §6).
   It was previously exposed publicly for about two hours (decision B10).

## 4. What "reuse" means for the running service

The importer gate treats `PENDING` as "do not load into production". Phase 3B described the basis
as "sound for internal use and for running the service", but the service exposes the data:

- `GET /api/v1/public/reference/settlements/{id}/railway-lines` returns the railway lines
  associated with a settlement to **any anonymous caller**.
- Enumerating the 3,178 settlements through that endpoint reconstructs the whole relation table.

So loading the dataset into production and running the Public API **publishes the derived
relations and the line roster**, in effect if not as a file. The "internal use only" reading is
therefore weak for this dataset, and the earlier recommendation ("confirm with VPE before
distribution") should be read as applying to the running service too. This is the owner's
judgement to make; it is stated here so it is made knowingly.

## 5. Decision required (E1) — options

| Option | What it means | Effect |
|---|---|---|
| **A. Written confirmation from VPE** (recommended) | Ask KTI VPE Igazgatóság for written confirmation that the factual rows may be used and published by an operator-run service. Ask specifically about the GYSEV annex. Keep the reply with the backup. Then set `reuseStatus: CLEARED` in the manifest and record the basis | Cleanest. Removes the gate legitimately. Costs nothing but time |
| **B. Owner-declared basis** | The owner decides the recorded public-body / regulatory-publication basis suffices, and sets `CLEARED` herself, recording the decision and date | Fastest. The legal risk is the owner's, and §4 applies: the relations become publicly enumerable |
| **C. Launch without VPE data** | Import settlements only (KSH, already cleared). No line data | Legal question avoided. Every report routes **UNCLASSIFIED** (empty relations mean "no verified reference", ADR 0006), so only global MODERATOR/SUPER_ADMIN see them. The Public app's line question disappears |
| **D. Partial** | Owner authors the settlement↔line mapping herself for the lines she cares about | Owner-created data, no third-party rights question. Substantial manual work; not verified against an authority |

**Nothing in this memo, or in the repository, sets `CLEARED`. That is your decision.**

If you choose A or B, the change is one field in the local manifest and the dataset is then
imported at deployment step 7 (`docs/PRODUCTION_READINESS.md` §7). If the answer is no or "not yet",
production can still launch under option C.

## 6. What was done under the owner's approval (this session)

| Action | Result |
|---|---|
| Durable backup outside the Git store | `C:\Users\ottva\.orszem\reference-data\2026.09.07-1\`: 9 files (original manifest, three canonical files, review files, `SHA256SUMS.txt`, `README.txt`), read-only, all checksums verified (re-verified before the working copies were removed). **Outside OneDrive** on purpose |
| Working-copy manifest upgraded to the current schema | Only two things added: `reuseStatus: "PENDING"` and the component `coverage` (`settlements: COMPLETE`, `railwayLines: PARTIAL`, `settlementRailwayLines: PARTIAL`). **All sources, URLs, dates, hashes, licence references and the original coverage statistics are unchanged.** The original is kept beside it (`manifest.v0-original.json`) |
| Data files | Untouched. All three canonical files still match the manifest's recorded SHA-256 |
| `validate-canonical.mjs` | valid: 3,178 settlements, 231 lines, 967 relations, `VERIFIED / PARTIAL / PENDING` |
| Backend `reference-validate` (disposable empty DB) | `VALID`, with the explicit note that import will refuse the dataset |
| Backend `reference-diff` (read-only) | +3,178 settlements, +231 lines, +967 relations, 0 removals |
| Backend `reference-import` | **Refused**: `REFERENCE_DATASET_REUSE_NOT_CLEARED` (exit 1). **0 rows written**. The gate was not bypassed |

Stable identifiers (unchanged, and they must stay that way): `ksh_code` (five digits) for
settlements and the canonical HÜSZ `line_code` for lines. Imports match on them.

## 7. Two things worth knowing

- **The repository lives inside OneDrive, so the recovered working copies were removed from it** (owner
  authorisation, after re-verifying the backup): the five data/manifest files and the manifest original in
  `reference-data/local-research/` were each confirmed byte-identical to the backup and then deleted. Only the
  tracked `README.md` remains there. The upgraded working manifest is the original plus `reuseStatus` and
  `coverage` and is regenerable. **OneDrive keeps deleted files in its recycle bin and version history for a
  while**, so the cloud copies are not purged instantly; empty the OneDrive recycle bin if that matters. The
  backup in `.orszem\` is outside OneDrive and untouched.
- **Do not create a Git ref to the orphaned commit** (for example a "backup" branch): that would
  make the previously exposed data reachable and pushable again.

## 8. What would close B9

1. Choose option A, B, C or D (§5).
2. For A or B: record the clearance basis (the reply, or your dated decision) alongside the backup, set
   `reuseStatus: "CLEARED"` in the local manifest, re-run the checks in §6, and import at go-live.
3. Decide whether the VPE archive should be re-downloaded and hashed so the provenance gap in §3.1
   is closed (recommended if you choose A or B).
