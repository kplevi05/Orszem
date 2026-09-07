# Reference data

This directory holds Phase 3B's reference-data tooling and findings, split into three
parts with different rules for each — see [Layout](#layout). The real VPE-derived
settlement↔line dataset is **not committed here**; this README explains what that means
and why.

## Layout

| Location | What it holds | Tracked in Git? |
|---|---|---|
| `cleared/` | KSH settlement data, **CC BY 4.0**, safe to redistribute | **Yes** |
| `example/` | A small, entirely fictional dataset for CI and format documentation | **Yes** |
| `review/` | Aggregate build statistics and the (currently empty) manual-review worklist template | **Yes** |
| `local-research/` | The real VPE HÜSZ-derived research: railway lines, relations, quarantined candidates | **No — gitignored** |
| `tools/` | The offline builder and validator (generic; contain no data) | **Yes** |
| `LICENSES/` | Licence text for cleared material | **Yes** |

## Findings, `2026.09.07-1` — VERIFIED / PARTIAL / reuseStatus: PENDING

The research behind this reference dataset is complete and its results are recorded here
in aggregate, even though the row-level output currently lives only in
`reference-data/local-research/` (see [Why the data isn't here](#why-the-real-dataset-is-not-committed-here)).
The full breakdown, methodology and licence table are in
[`docs/PHASE_3B_DECISION_GATE.md`](../docs/PHASE_3B_DECISION_GATE.md); this is the summary.

Three axes are tracked independently, because they answer three different questions:

- **verificationStatus (VERIFIED)** — every relation that *is* in the dataset was derived
  from an authoritative source by an exact, deterministic rule, or admitted by a recorded
  human decision.
- **coverageStatus (PARTIAL)**, tracked **per component** (see
  [ADR 0006](../docs/architecture/adr/0006-reference-import-gates-and-partial-coverage-routing.md)):
  settlement identity is `COMPLETE` (KSH publishes the full registry); the railway-line
  roster and the settlement↔line relations are `PARTIAL` (824 of 3,178 settlements have a
  verified railway relation; the HÜSZ annexes enumerate service points, not every
  settlement a line crosses). Absence never means *"no railway exists here"* — only
  *"no verified reference for this yet"*. The backend importer treats a `PARTIAL`
  component's absence as something to preserve, never to delete.
- **reuseStatus (PENDING)** — correctness is not the same question as redistribution
  rights. See below.

| | Count |
|---|---|
| Settlements (KSH) | 3,178 |
| Railway lines (VPE HÜSZ-derived) | 231 |
| Settlement↔line relations (VPE HÜSZ-derived) | 967 |
| Settlements with a verified relation | 824 (25.9%) |
| Quarantined candidates (`NEEDS_REVIEW`) | 1,122 |

## Why the real dataset is not committed here

VPE has published no explicit reuse licence for the HÜSZ annexes the railway-line and
relation data is derived from (`reuseStatus: PENDING`). The reuse basis used — mandatory
regulatory publication by a body performing a public task, factual rows only, source
document never redistributed (§5–6 of the decision gate report) — is sound for running the
service internally, but it is **not** a written grant, and it says nothing about whether
this repository may publish the derived rows to the world.

**This repository is public.** A `reuseStatus: PENDING` gate inside the backend importer
(ADR 0006) stops the dataset from reaching a *running service* without written clearance —
it does nothing about a public Git repository, which is a separate exposure surface
entirely outside the importer's control. The row-level VPE-derived files (the 231-row
railway-line roster, the 967 settlement↔line relations, and the 1,122-row quarantine
candidate list) were therefore removed from this repository's tracked history — not just
its current tree — and now live only in `reference-data/local-research/` (gitignored, never
committed, packaged, or deployed — see that directory's own README). This was a deliberate,
owner-approved history rewrite of the branch that had introduced them; see
[`docs/DECISIONS_REQUIRING_OWNER.md`](../docs/DECISIONS_REQUIRING_OWNER.md) B10 for the full
account.

**What stayed, and why it's safe:**
- `cleared/settlements.csv` — KSH data under **CC BY 4.0**, an actual public licence, not a
  reuse basis awaiting confirmation.
- `review/build-summary.json` — aggregate counts and statistics only (how many rows, what
  fraction matched, what fraction was quarantined). No station names, no line codes, no
  row-level content.
- This document, the decision gate report, and every ADR — prose, aggregate findings, and
  provenance descriptions, not the dataset itself.
- `example/` — a small dataset that is entirely invented, containing nothing derived from
  any real source (see `reference-data/example/README.md`).

## Sources

| Source | Version | Licence / reuse basis |
|---|---|---|
| **KSH Helységnévtár** | state 2025-01-01 | **CC BY 4.0**, attribution required |
| **VPE HÜSZ 2026/2027, annex 5.2-4** (MÁV service points and line categories) | AE. módosítás | mandatory network statement; see below |
| **VPE HÜSZ 2026/2027, annex 5.2-5** (GYSEV service points and line categories) | D. módosítás | as above |

Both railway annexes come from the **VPE-published regulatory document**, which is why the
research covers MÁV and GYSEV from one consistent, current source.

### Reuse basis for the HÜSZ annexes

The Hálózati Üzletszabályzat is a *mandatory* publication under Directive 2012/34/EU
Art. 27 and Hungarian rail law, published so that railway undertakings can use it
operationally. VPE (KTI VPE Igazgatóság) is a body performing a public task, bringing it
within Hungarian Act LXIII of 2012 on the reuse of public data.

Only **factual rows** were derived — line code, service-point name, and the settlement each
maps to. The source PDF was never redistributed and was never committed here.

> **Owner action, still open:** VPE publishes no explicit reuse licence. The basis above is
> sound for internal use, but written confirmation from VPE is required before the derived
> row-level dataset is committed to this (or any public) repository again, or distributed
> externally in any other form.

### Why gysev.hu was *not* used

GYSEV station pages do carry a section headed *"A települést érintő GYSEV vasútvonalak"*,
which would be directly useful. They were nonetheless excluded, because the GYSEV legal
notice states:

> *"Előzetes írásos engedély nélkül a honlap tartalmi elemei nem helyezhetők el sem
> nyilvános, sem zárt adatbázisban."*
> (Without prior written permission, site content may not be placed in a public **or
> private** database.)

Building a canonical dataset is exactly that. The terms were checked *before* fetching
anything in bulk, and no bulk retrieval was performed. The VPE annexes made it unnecessary,
since they already cover the GYSEV network.

### Considered and rejected — paid

No paid dataset, API, geocoder, subscription or licence is used or assumed anywhere in this
pipeline, including in the cleanup that removed the row-level data from this repository.

## How relations were derived

Deterministic exact matching, then quarantine — the method, recorded here even though the
row-level output now lives only locally:

1. Parse `line code → service point name` from the HÜSZ annexes.
2. Strip a **whitelisted** set of pure station-type suffixes (`mh.`, `pvh.`, `ipvk.`, …)
   that describe a facility rather than a different place.
3. Accept only when the result is **exactly equal** to a KSH settlement name, and that
   name identifies exactly one settlement.
4. Everything else becomes `NEEDS_REVIEW` and is excluded.

There is no similarity score, edit distance or threshold anywhere. A fuzzy match cannot
create a canonical relation, because routing built on "probably Tata" inherits the guess.

The quarantine catches precisely the cases that defeat naive matching — composite names
(`Budapest-Kelenföld`), `alsó`/`felső` variants (`Almásfüzitő felső`), junctions
(`Tatabánya elágazás`), chainage markers, industrial sidings and border points. These are
illustrative examples from the method, not a reproduction of the quarantine dataset itself.

### Manual review

`review/manual-review.csv` admits or rejects individual relations. Each row records the
KSH code, line code, decision, the authoritative source consulted, and a short reason —
reviewable data, never conditions in code. The file is currently empty of decisions; the
824 covered settlements come entirely from exact automatic matches. The worklist it draws
from (`needs-review.csv`) now lives in `reference-data/local-research/`.

## Rebuilding

The tools are offline and take local copies of the sources, which are **not committed**.
Output now goes to `reference-data/local-research/` instead of a tracked path — see that
directory's README for the exact commands.

```bash
# 1. KSH workbook (CC BY 4.0)
curl -o hnt.xlsx https://www.ksh.hu/docs/helysegnevtar/hnt_letoltes_2025.xlsx
mkdir ksh && cd ksh && unzip -q ../hnt.xlsx && cd ..

# 2. HÜSZ annexes, then extract the two service-point tables as UTF-8 text
curl -o husz.zip https://vpe.kti.hu/wp-content/uploads/2026/09/husz-2026-2027-ae-sz-modositas.zip
unzip -j husz.zip '*Szolg.hely és vonalkategóriák*'
pdftotext -layout -enc UTF-8 '5.2-4 sz. melléklet_MÁV - Szolg.hely és vonalkategóriák_O.pdf' mav.txt
pdftotext -layout -enc UTF-8 '5.2-5 sz. melléklet _GYSEV - Szolg.hely és vonalkategóriák_D.pdf' gysev.txt

# 3. Build - to the local, gitignored directory, not a tracked path
node reference-data/tools/build-canonical.mjs \
  --ksh ksh --mav mav.txt --gysev gysev.txt \
  --review reference-data/local-research/manual-review.csv \
  --out reference-data/local-research --version <version> --reuse-status PENDING
```

The builder refuses to produce output if it extracts less than 98% of the rows the source
contains. That gate exists because an earlier, narrower parser silently dropped 22% of the
data — including all of line 17 — and a partial parse fails quietly rather than loudly.

## Validating

```bash
# The real research dataset (local only):
node reference-data/tools/validate-canonical.mjs reference-data/local-research/manifest.json

# The tracked example dataset (what CI actually runs):
node reference-data/tools/validate-canonical.mjs reference-data/example/manifest.json
```

Checks UTF-8 and headers, checksums against the manifest, duplicate external keys, dangling
references, deterministic ordering, and that the counts and coverage claims match the
files. Coverage is tracked per component (`coverage.settlements` / `.railwayLines` /
`.settlementRailwayLines`, see ADR 0006) rather than as one blanket flag, and the validator
refuses a manifest whose overall `coverageStatus: COMPLETE` contradicts a component that is
still `PARTIAL`.

CI (`reference-data` workflow) validates only `reference-data/example/manifest.json` — the
fictional dataset — entirely offline, on every push and pull request. It never depends on
KSH, VPE, MÁV or GYSEV being reachable, and it never touches the real research data.

## Importing

The backend maintenance CLI (`scripts/orszem-admin reference-validate` /
`reference-diff` / `reference-import`) refuses to import any dataset that is not
`verificationStatus: VERIFIED` **and** `reuseStatus: CLEARED` — no bypass. The real research
dataset is `PENDING` today, so it cannot be imported anywhere, including production, until
VPE confirms in writing. See `docs/deployment/MAINTENANCE_CLI.md` and ADR 0006.

## Known limitations

- **Coverage is partial by construction.** 2,354 settlements have no verified relation.
  Most genuinely have no railway; some have one that only the quarantined candidates would
  reveal. The dataset does not distinguish these, and does not pretend to.
- **Budapest has no relations at all** in the current research — all 24 KSH rows (23
  districts plus the city row) are uncovered, because every Budapest service point is
  composite-named (`Budapest-Keleti`, `Budapest-Nyugati`, …) and quarantined. Only **8
  rows** stand between this and full Budapest coverage; it is the first manual-review batch
  to do.
- **1,122 quarantined candidates** remain unreviewed. Working through them — particularly
  the `alsó`/`felső` and composite-name cases — is the highest-value way to raise coverage,
  and needs a person with an authoritative source, not an algorithm.
- **Line display names** are derived from the first and last service point on the line. A
  few read awkwardly where the terminus is a chainage marker rather than a station.
- The HÜSZ annexes describe **service points**, so a settlement whose station lies outside
  its own boundary, or is named for a neighbour, will not match automatically.

## Attribution

Any future distribution containing the derived data (once `reuseStatus: CLEARED`) must
carry:

> Forrás: KSH — https://www.ksh.hu
> Vasúti vonal- és szolgálatihely-adatok: VPE Hálózati Üzletszabályzat 2026/2027
