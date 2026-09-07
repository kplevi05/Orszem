# Reference data

**Dataset status: `UNVERIFIED` — no canonical dataset has been produced.**

Phase 3A (schema, domain model, authorisation policy) is complete and merged into the
backend. Phase 3B stopped deliberately at the source-verification gate, and Phase 3C
(routing, public reference API) has not been started, because it is gated on a `VERIFIED`
dataset.

This document is the record of what was investigated, what was resolved, and what blocks
the rest. It is written so the next attempt does not have to repeat the search.

---

## Summary

| Source | Needed for | Outcome |
|---|---|---|
| KSH Helységnévtár | settlements | **RESOLVED** — authoritative, current, licensed |
| ERA RINF | railway lines, operational points | **BLOCKED** — no verified public bulk access, reuse terms not established |
| VPE / MÁV network statement | line list, validation | **BLOCKED** — published as PDF only, not machine-readable |
| GYSEV network statement | line list, validation | **CHANGE CONFIRMED, not reconciled** |
| *settlement ↔ railway line* | the core relation | **NO AUTHORITATIVE SOURCE EXISTS** |

The last row is the decisive one. Everything else is a difficulty; that one is a wall.

---

## 1. Settlements — RESOLVED

**Source.** Központi Statisztikai Hivatal, *Magyarország helységnévtára*, download page
`https://www.ksh.hu/apps/hntr.egyeb?p_lang=HU&p_sablon=LETOLTES`.

| | |
|---|---|
| File | `https://www.ksh.hu/docs/helysegnevtar/hnt_letoltes_2025.xlsx` |
| Retrieved | 2026-09-07 |
| Source version | State as of **2025-01-01** (sheet: `Helységek 2025.01.01.`) |
| Size | 1,370,790 bytes |
| SHA-256 | `d059a14883a27963daa234d9f3954eb68b4cbf967e796710a4bcdb4f03437991` |

**Content verified by parsing, not assumed.** The settlement sheet provides exactly the
fields the schema needs:

- `A` — *Helység megnevezése* (settlement name)
- `B` — *Helység KSH kódja* (the stable external identifier)
- `D` — *Vármegye megnevezése* (county)

Measured: **3,178 settlements, 3,178 distinct KSH codes, 0 codes that are not exactly five
digits.** Sample: `17376 Aba (Fejér)`, `12441 Abádszalók (Jász-Nagykun-Szolnok)`.

This confirms the §5 design assumption: the KSH code is a stable five-digit external key
suitable for preserving internal UUIDs across imports, and it must be stored as text so
leading zeroes survive.

**Licence — established, not assumed.** From the KSH terms of use
(`https://www.ksh.hu/copyright`):

> A KSH a Creative Commons Attribution 4.0 International (CC BY 4.0) szabványosított
> nemzetközi licencet használja, amely szerint a Honlapon található összes tartalom –
> beleértve a táblázatokat, az ábrákat és az infografikákat is – szabadon másolhatók,
> sokszorosíthatók és terjeszthetők.

So published website content, tables included, is **CC BY 4.0** and may be redistributed.
Attribution is mandatory (§3.2): the source must be shown as `Forrás: KSH` or
`www.ksh.hu`, must not be hidden or separated, and an online citation must be a working
link.

One carve-out was checked and does **not** apply here: §3.3 places datasets extracted from
KSH internal databases *on individual request* under CC BY-NC 4.0. The Helységnévtár is a
standard published download, not a custom extract.

**Conclusion: settlements could be imported and redistributed today, with attribution.**

## 2. Railway lines — BLOCKED

### ERA RINF

The intended machine-readable source. Not usable as things stand:

- `https://rinf.data.era.europa.eu/sparql` returns the application's JavaScript shell, not
  a query endpoint. No public bulk-download path for the Hungarian dataset was verified.
- ERA's RINF FAQ describes account registration for infrastructure managers and railway
  undertakings; it does not state public download terms.
- No Terms of Use or licence statement for the Hungarian data was found on the pages
  checked.

§18 is explicit that the licence of RINF *documentation* must not be assumed to license the
underlying infrastructure-manager datasets, and that unclear redistribution rights mean
stop rather than copy. Both conditions apply.

### VPE / MÁV network statement

The Hálózati Üzletszabályzat is the authoritative national line register. It is published
as **PDF only** — a scan of the VPE page found zero `.xlsx`/`.csv` annexes, and
`opendata.vpe.hu` did not resolve. Extracting a line list would mean transcribing PDF
tables by hand, which is not a verifiable import path.

### GYSEV and the 2025 transfer

The transfer the brief asks about is real and material: GYSEV operated a 434.7 km network
and took on a further **752.3 km from 1 July**, including Gyékényes–Hegyeshalom and
Székesfehérvár–Szentgotthárd.

This was **not** reconciled line-by-line against the codes listed in the brief
(10, 14, 17, 20, 23, 24, 25, 30, 60), because there is no machine-readable line register to
reconcile against. It is recorded here as an open item, not as a completed check.

Worth noting: the V002 schema is already insulated from this class of change. A railway
line's identity deliberately excludes its infrastructure manager, precisely because
operation can transfer between MÁV and GYSEV without the line becoming a different line.
The 2025 transfer therefore affects *validation of the line list*, not the data model.

## 3. Settlement ↔ railway line — the blocking problem

**No authoritative published dataset maps Hungarian settlements to national railway line
numbers.** This was searched for directly and is not a gap in effort.

Every available derivation route is one the brief explicitly forbids:

| Route | Why it is not available |
|---|---|
| Match RINF operational-point names to settlement names | §22 — no similarity threshold may promote a candidate to production truth. Real station names defeat naive matching anyway: `Budapest-Keleti` is not a settlement, and stations frequently sit in or are named after a neighbouring settlement. |
| Spatial join of station coordinates to settlement boundaries | §1 and §19 — no geocoding, no geographic services. |
| OpenStreetMap, Wikipedia, community line/station lists | §17 — explicitly not authoritative. |
| Manual review | §57 permits reviewed exceptions, not a from-scratch national mapping. Roughly 3,178 settlements against ~200 lines cannot be validated authoritatively in this session, and unvalidated rows must not enter canonical data. |

§74 lists "settlement-line relations require guessing" as a stop condition. That is exactly
the situation, so the work stopped here rather than producing a plausible-looking mapping.

---

## What was deliberately *not* done

- No canonical CSV files were written. Empty or partial files would imply a dataset exists.
- No `manifest.json` was created. A manifest describes a dataset; there is none, and one
  marked `UNVERIFIED` with nothing behind it is worse than its absence.
- No raw source file was committed. The KSH workbook is CC BY 4.0 and *could* be, but there
  is no importer to consume it yet, and committing it now would suggest a pipeline that
  does not exist.
- **No mapping was invented, inferred or approximated.**

## Planned layout, once a dataset exists

```
reference-data/
  current/
    settlements.csv
    railway-lines.csv
    settlement-railway-lines.csv
    manifest.json
  LICENSES/
  README.md
```

Canonical files will be UTF-8, deterministically ordered by their external key, so Git
diffs stay reviewable.

## What would unblock this

1. **A machine-readable, licensed Hungarian line register.** Either RINF access with
   written reuse terms for the Hungarian dataset, or a VPE/MÁV/GYSEV line list in CSV or
   XLSX with stated terms.
2. **A decision on the settlement↔line relation**, which is a product question rather than
   a technical one. Options the owner may wish to weigh:
   - obtain or commission an authoritative mapping;
   - narrow the initial scope to a small set of lines whose settlements can be validated by
     hand and reviewed, accepting that the rest routes to UNCLASSIFIED;
   - change the reporting model so the reporter selects a line directly rather than a
     settlement, removing the need for the relation.

These are recorded as owner actions. Nothing here should be resolved by guessing.

## Attribution

Any future distribution containing KSH-derived settlement data must carry:

> Forrás: KSH — https://www.ksh.hu
