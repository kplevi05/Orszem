# Phase 3B — decision gate report

**Dataset `2026.09.07-1` · `verificationStatus: VERIFIED` · `coverageStatus: PARTIAL`**
**Zero external cost incurred. Nothing paid was used, and nothing paid is assumed.**

This is the report required before Phase 3B continues and before Phase 3C may start.
Phase 3C has **not** been started.

---

## 1. Verdict

| Question | Answer |
|---|---|
| Is a `VERIFIED + COMPLETE` dataset achievable at zero cost? | **No.** See §7. |
| Is a useful `VERIFIED + PARTIAL` dataset achievable at zero cost? | **Yes — and it is built, validated, committed and green in CI.** |
| Did any step cost money, or assume a paid source? | **No.** |
| Is the canonical-data path settled? | **Yes**, with one open owner item (§6). |

---

## 2. Counts

| Metric | Value |
|---|---|
| Verified settlements (identity rows) | **3,178** |
| Verified railway lines | **231** |
| Verified settlement ↔ line relations | **967** |
| Settlements with ≥ 1 verified relation | **824** |
| Settlements with no verified relation | **2,354** |
| Railway lines with ≥ 1 verified relation | **147** |
| Railway lines with no verified relation | **84** |
| Exact deterministic candidate matches produced | **989** |
| — collapsing to distinct relations | 967 (22 were the same settlement reached twice on one line) |
| Unresolved / quarantined candidates (`NEEDS_REVIEW`) | **1,122** |
| — `AMBIGUOUS_NAME` | 606 |
| — `NO_SETTLEMENT_MATCH` | 516 |
| Duplicate-name collisions (two settlements, one name) | **0** |
| Manual decisions recorded so far | **0** accepted, **0** rejected |

Nothing in the canonical files came from a similarity score. Every one of the 967
relations is an exact normalised name equality against a KSH settlement that the name
identifies uniquely.

### Source extraction

| Metric | Value |
|---|---|
| Data rows present in the two HÜSZ annexes | 2,116 |
| Rows the parser extracted | 2,111 |
| **Extraction coverage** | **99.76 %** |

The builder refuses to emit anything below 98 %. That gate exists because an earlier,
narrower parser silently dropped 22 % of the source — including the whole of line 17 —
and a partial parse fails quietly rather than loudly.

---

## 3. Estimated coverage

| Dimension | Covered | Total | Share |
|---|---|---|---|
| Settlements | 824 | 3,178 | **25.9 %** |
| Railway lines | 147 | 231 | **63.6 %** |

25.9 % is not the share of Hungarian settlements that have a railway — it is the share
for which we hold a *verified* relation. Most of the 2,354 uncovered settlements
genuinely have no railway; an unknown minority have one that only the quarantine would
reveal. **The dataset does not distinguish the two cases and does not pretend to.**

### Coverage by county

| County | Covered / total | County | Covered / total |
|---|---|---|---|
| Békés | 35 / 75 (47 %) | Tolna | 32 / 109 (29 %) |
| Hajdú-Bihar | 35 / 82 (43 %) | Veszprém | 61 / 217 (28 %) |
| Pest | 77 / 187 (41 %) | Somogy | 62 / 246 (25 %) |
| Jász-Nagykun-Szolnok | 31 / 78 (40 %) | Nógrád | 31 / 131 (24 %) |
| Csongrád-Csanád | 23 / 60 (38 %) | Heves | 28 / 121 (23 %) |
| Fejér | 40 / 108 (37 %) | Szabolcs-Szatmár-Bereg | 52 / 229 (23 %) |
| Bács-Kiskun | 36 / 119 (30 %) | Borsod-Abaúj-Zemplén | 75 / 358 (21 %) |
| Győr-Moson-Sopron | 54 / 183 (30 %) | Vas | 38 / 216 (18 %) |
| Komárom-Esztergom | 23 / 76 (30 %) | Baranya | 52 / 301 (17 %) |
| | | Zala | 39 / 258 (15 %) |
| | | **Budapest** | **0 / 24 (0 %)** |

### The one gap that matters most

**Budapest has zero verified relations.** All 24 KSH rows — the 23 districts plus the
city row itself — are uncovered, because every Budapest service point in the annexes is
composite-named (`Budapest-Keleti`, `Budapest-Nyugati`, `Budapest-Kelenföld`,
`Budapest-Józsefváros`, …) and therefore quarantined by design. The rule is behaving
correctly: `Budapest-Keleti` is not the settlement `Budapest`, and it is certainly not
`Budapest 08. ker.`, which is the district the station actually stands in.

For a report-routing product this is the single highest-value manual-review batch, and
it is small: **8 quarantined rows**. It is the first item in §8.

A related shape question the owner should settle: KSH lists **both** `Budapest`
(code 13578) and the 23 districts as separate settlements. Routing needs to know which
one a report is attributed to.

---

## 4. MÁV / GYSEV coverage assessment

Both networks come from the **same** VPE-published regulatory document, so they are
covered from one consistent, current source rather than from two sources of differing
quality.

| | Lines seen | With ≥1 relation | Relations | Settlements |
|---|---|---|---|---|
| Lines in the GYSEV annex (5.2-5) | 32 | 27 | 278 | 249 |
| — of those, GYSEV-only (absent from the MÁV annex) | 20 | — | 87 | 83 |
| Lines in the MÁV annex (5.2-4) | 207 | — | — | — |
| Line codes appearing in **both** annexes | 12 | — | — | — |

The 12 shared codes are the sections where the two networks meet. They are single rows in
`railway-lines.csv`, which is correct — a line code is a line code regardless of which
undertaking's annex lists it.

**Assessment: GYSEV is not under-covered relative to MÁV.** 84 % of GYSEV-annex lines
carry at least one verified relation, against 64 % across all lines. That is the expected
result of a smaller, denser, more consistently named network.

---

## 5. Source and licence table

| Source | Version | Retrieved | Licence / reuse basis | Used for | Cost |
|---|---|---|---|---|---|
| **KSH Helységnévtár** (`hnt_letoltes_2025.xlsx`, SHA-256 `d059a148…37991`) | state 2025-01-01 | 2026-09-07 | **CC BY 4.0** — attribution required | settlement identity: KSH code, name, county | **free** |
| **VPE HÜSZ 2026/2027, annex 5.2-4** — MÁV service points and line categories | AE. sz. módosítás | 2026-09-07 | Mandatory network statement under Directive 2012/34/EU Art. 27; public-body data under Act LXIII of 2012 on the reuse of public data. Factual rows only; source PDF not redistributed. | line codes and their service points | **free** |
| **VPE HÜSZ 2026/2027, annex 5.2-5** — GYSEV service points and line categories | D. sz. módosítás | 2026-09-07 | as above | GYSEV line codes and their service points | **free** |

### Checked and deliberately **not** used

| Source | Why not |
|---|---|
| **gysev.hu station pages** | Directly useful — they carry a *"A települést érintő GYSEV vasútvonalak"* section — but the site's legal notice forbids placing its content in a public **or private** database without prior written permission. The terms were read **before** any bulk retrieval, and **no bulk retrieval was performed.** The VPE annexes cover the GYSEV network anyway. |
| **MÁV GTFS** | Secondary at best: it describes *timetabled service*, not infrastructure, so a line with no passenger service disappears. Not needed given the annexes. |
| **Wikipedia, OSM, community datasets, AI-generated tables** | Excluded as canonical truth by the phase rules. OSM has a possible role as a *review aid* only — see §8. |
| **Lechner boundary and address datasets** | **Not used — paid.** |
| **Paid geocoders, paid APIs, new SaaS, new servers, commercial data licences** | **Not used — paid.** |

**No paid dataset, API, service, subscription or infrastructure was introduced or
assumed. Nothing here carries a cost.**

---

## 6. Open owner item

**VPE publishes no explicit reuse licence.** The basis in §5 — mandatory regulatory
publication by a body performing a public task, from which only factual rows are derived,
without redistributing the document — is sound for internal use and for running the
service. It is not a written grant.

**Recommendation:** obtain written confirmation from VPE (KTI VPE Igazgatóság) before the
derived dataset is distributed externally, for example published as an open file or
shipped inside a downloadable client build.

This is now enforced, not just documented: the manifest carries a third, independent
status field, `reuseStatus: PENDING | CLEARED` (see
[ADR 0006](architecture/adr/0006-reference-import-gates-and-partial-coverage-routing.md)),
separate from `verificationStatus` and `coverageStatus`. The real dataset is
`reuseStatus: PENDING`, and **the backend reference importer refuses to import a `PENDING`
dataset into a production database.** The dataset therefore stays research/candidate
material — the running service does not depend on it — until VPE confirms in writing and
that one field changes to `CLEARED`. This does not block Phase 3B or 3C, because neither
requires the real dataset to be imported yet.

This is the only unresolved licensing question. It is an owner action, at no cost.

---

## 7. Why `VERIFIED + COMPLETE` is not reachable at zero cost

The blocker is structural, not effort.

The HÜSZ annexes enumerate **service points** — stations, stops, junctions, sidings. They
do **not** enumerate the settlements a line passes through. A settlement whose territory
the track crosses without stopping is invisible to a name-based derivation *no matter how
much manual review is done*, because the fact is simply not in the source.

Closing that gap needs geometry: line alignments intersected with settlement boundaries.
Authoritative Hungarian settlement boundary polygons come from Lechner, and they are
**paid**. Therefore:

> A `VERIFIED + COMPLETE` dataset requires a paid boundary dataset. **Rejected — paid.**
> It would require explicit owner approval and is not pursued.

`VERIFIED + PARTIAL` is the correct target for Demo V2, which is exactly why the two
statuses were separated. The dataset is honest about what it is.

### The runtime consequence, restated

A settlement with no relation means **"no verified railway reference for this"**, never
"no railway exists here". Routing must return `NO_VERIFIED_RAILWAY_LINE_REFERENCE` →
`UNCLASSIFIED`, never a physical-absence claim. This is a Phase 3C requirement, recorded
here so it cannot be lost.

---

## 8. The canonical-data construction process — proposed and implemented

This is the process to keep. It already runs in CI.

**1. Acquire, offline.** The KSH workbook and the two HÜSZ annexes are fetched by hand,
checksummed, and **not committed**. The tools take local paths. Runtime never contacts
KSH, VPE, MÁV, GYSEV, GTFS or a geocoder.

**2. Parse with a coverage gate.** `build-canonical.mjs` extracts `line code → service
point` and **refuses to emit output below 98 % extraction coverage.**

**3. Normalise conservatively.** Strip only a **whitelisted** set of station-type
suffixes (`mh.`, `pvh.`, `ipvk.`, `oh.`, `vá.`, `áll.`, …) that describe a facility rather
than a different place.

**4. Accept only exact, unique equality.** The normalised name must equal a KSH settlement
name exactly, and that name must identify exactly one settlement. **There is no edit
distance, similarity score or threshold anywhere in the pipeline.** A fuzzy match cannot
create a canonical relation, because routing built on "probably Tata" inherits the guess.

**5. Quarantine everything else** to `review/needs-review.csv`, excluded from canonical
output. The quarantine catches precisely the cases that defeat naive matching — composite
names, `alsó`/`felső` variants, junctions, chainage markers, industrial sidings, border
points.

**6. Admit or reject by recorded human decision.** `review/manual-review.csv` holds
`ksh_code, line_code, decision, source, reason` — **transparent preprocessing data, never
conditions in Kotlin.** Each row names the authoritative source consulted. The builder
reads it as input, so a decision is reproducible and auditable.

**7. Emit a manifest** with per-file SHA-256, provenance per source, counts, and the two
independent status fields.

**8. Validate offline.** `validate-canonical.mjs` checks UTF-8 with no BOM, headers,
checksums, duplicate keys, dangling references, deterministic ordering, count and coverage
cross-checks, and **refuses a `COMPLETE` claim the data does not support.** It has been
negative-tested against a mutated checksum, a dangling reference, a duplicate and a
disordered file.

**9. Run it in CI on every push and pull request**, entirely offline, against the
committed snapshot — so validation never depends on KSH or VPE being reachable.

**10. Import transactionally** through the backend maintenance CLI — the remaining Phase
3B work item (§9).

### Raising coverage from here, at zero cost

In priority order. All of it is manual-review work under step 6; none of it changes the
rules above.

1. **Budapest — 8 rows.** Attribute each terminus to the district it stands in. The
   highest value per unit of effort in the entire backlog, and it removes a 0 % region.
2. **`alsó` / `felső` and directional variants** (`Almásfüzitő felső`, …). Mechanical to
   confirm against the annex itself; a large share of the 606 `AMBIGUOUS_NAME` rows.
3. **Composite and hyphenated names** outside Budapest.
4. **The 516 `NO_SETTLEMENT_MATCH` rows** — stations named for a neighbouring settlement,
   or lying outside their own settlement's boundary. Slowest, needs a person with a map.

**A possible future aid, not proposed for now:** ERA RINF publishes operational points
with coordinates and is free and official. It could *propose* settlement attributions for
quarantined rows. It is not adopted here because turning a coordinate into a settlement
still needs boundary polygons, and the free ones are OSM-derived — which the phase rules
bar as canonical truth. It could only ever feed `manual-review.csv` as a suggestion a
human confirms against an authoritative source. Recorded as an option; **not part of the
current path, and not required.**

---

## 9. What happens next

| | State |
|---|---|
| Phase 3A — schema, scope, routing model | **complete** (V002; tests green) |
| Phase 3B — canonical dataset | **complete and validated** |
| Phase 3B — backend reference importer (§24 / §25 / §50) | **complete** (185 backend tests green) |
| Phase 3C — routing service and Public reference API | **not started, correctly gated** |

The importer (`ReferenceImportUseCase`, wired into `scripts/orszem-admin
reference-validate` / `reference-diff` / `reference-import`) is transactional, serialised by
a PostgreSQL advisory lock, idempotent with UUID preservation by `ksh_code` / `line_code`,
and refuses an unverified dataset, an uncleared-reuse dataset, a version/content conflict,
and deactivating a railway line still assigned to a service area
(`REFERENCE_LINE_IN_USE`) — with a full transaction rollback on every refusal. It writes a
provenance row in `reference_dataset_imports` and a `REFERENCE_DATASET_IMPORTED` audit
event on a real import. See
[ADR 0006](architecture/adr/0006-reference-import-gates-and-partial-coverage-routing.md) for
the `reuseStatus` gate and the PARTIAL-coverage routing constraint it also records for
Phase 3C, and `docs/deployment/MAINTENANCE_CLI.md` for usage.

The real VPE-derived dataset stays `reuseStatus: PENDING` (§6) and therefore **cannot
currently be imported anywhere, including production** — the importer's own gate enforces
that, not discipline. It remains research/candidate material until VPE confirms in writing.

---

## 11. Distribution boundary verification — and a finding requiring owner action

Requested explicitly: prove the PENDING dataset is not imported, packaged or deployed
anywhere, and check whether the repository is public.

| Check | Result |
|---|---|
| Imported by a normal command | **No.** `reference-import` refuses any `reuseStatus != CLEARED` dataset; tested (`ReferenceImportUseCaseIT`). |
| Packaged into an Android APK | **No.** No reference to `reference-data/` anywhere under `android/`; nothing under any `assets/` source directory. |
| Bundled into Public Web | **No.** No reference to `reference-data/` anywhere under `web/`; `web/public-web/public/` holds only PWA icons and its own web manifest. |
| Packaged into backend runtime resources / container artifacts | **No.** No `processResources` hook copies it in; `unzip -l build/libs/backend.jar` lists zero CSV or manifest files. There is **no Dockerfile anywhere in this repository** - no container artifact exists to package it into. |
| Deployed | **No.** `deploy/caddy/Caddyfile`, `deploy/systemd/orszem-backend.service` and `deploy/env/backend.env.example` contain no reference to it. The `reference-data` CI workflow only runs the offline validator against the committed snapshot; it has no deploy step. |

**Finding: this GitHub repository is public.** Confirmed via the GitHub API on 2026-09-08
(`kplevi05/Orszem`, `visibility: public`). The VPE-derived canonical dataset —
`reference-data/current/{settlements,railway-lines,settlement-railway-lines}.csv` and its
`manifest.json` — is committed to that public history and visible to anyone right now.

This does not contradict the checks above: the importer's `reuseStatus: PENDING` gate
protects the *running service* from importing uncleared data. **It says nothing about a
git repository**, and it was never designed to. A dataset can simultaneously be correctly
blocked from ever reaching a database, and already sitting in public view in the
repository that holds it — those are two different exposure surfaces, and only the first
one this system's code can control.

**This is reported rather than acted on.** Removing the files, rewriting history, or
changing the repository's visibility are each either a scope decision or a destructive,
hard-to-reverse operation - recorded as **B10** and **A6** in
`DECISIONS_REQUIRING_OWNER.md`, with options, for the owner to choose from. Nothing in this
codebase change touches the repository's visibility or its history.

---

## 12. Remediation performed — read-only audit, then an owner-approved history rewrite

§11 above is left as it was written: a report, not yet acted on. This section records what
happened next, on explicit owner authorisation, choosing option 3 from §11/B10 (remove the
row-level content) without options 1 (private) or 2 (await VPE) — both declined explicitly.
**No paid service was introduced anywhere in this process.**

### Exposure audit (read-only, performed before any history was touched)

| Question | Finding |
|---|---|
| Which files carried substantial VPE-derived row-level content? | Exactly three: `railway-lines.csv` (231 rows), `settlement-railway-lines.csv` (967 rows), `needs-review.csv` (1,122 rows). `manifest.json` and `build-summary.json` held only aggregate counts/provenance — not treated as redistribution. |
| Which commit introduced them? | Exactly one: the commit that first built the canonical dataset. Two later commits touched only `manifest.json` metadata (`reuseStatus`, then per-component `coverage`) — the three CSVs were byte-identical from introduction to the rewrite. |
| Which refs reached that commit? | Exactly one branch: `feature/v2-reference-routing` (local and `origin/`). Not reachable from `main`, `demo-v1.1-final`, or any other branch/tag - verified directly by tree listing, not merely by ref-reachability. |
| Any PR ref? | No. Five PRs existed in this repository, all merged, none for this branch. |
| Any public fork? | No. `forks_count: 0`, `network_count: 0`, `stargazers_count: 0` at audit time. |
| In `main` or the V1 archive tag? | No, confirmed by direct tree listing of both. |
| Could the branch alone be rewritten cleanly from its Phase 2 base? | **Yes.** `git merge-base` of the branch and `main` was exactly that base commit; no other public ref reached any commit on the branch. |

### The rewrite

The branch's six commits were reconstructed from that same Phase 2 base, preserving every
safe change (V002/domain/AreaScopePolicy, the importer implementation and its tests, the
synthetic `CLEARED` fixtures, every ADR, the aggregate research findings, `reuseStatus` and
the per-component coverage semantics) while never introducing the three sensitive files at
any point in the new history. The public layout was restructured in the same pass -
`reference-data/cleared/` (KSH, actually CC BY 4.0), `reference-data/example/` (a small,
entirely fictional dataset that keeps the offline validator and the backend importer
exercised in CI without any real derived content), and `reference-data/local-research/`
(gitignored, real research continues there) - see `reference-data/README.md` for the full
layout and rationale.

Before the branch was force-pushed (with `--force-with-lease`, scoped to that one branch),
all of the following were verified and are recorded in the corresponding session:

1. the rewritten branch's merge-base with `main` was still the same Phase 2 commit;
2. `main`'s SHA was unchanged;
3. `demo-v1.1-final`'s SHA was unchanged;
4. the three sensitive paths/blobs were absent from every commit reachable from the
   rewritten branch;
5. no synthetic fixture accidentally contained a copied real row;
6. the working tree was clean;
7. the full backend regression suite, and every CI workflow, passed on the new tip.

The real VPE-derived files were preserved outside tracked Git state before any of this
began, and continue as local research material - see
`reference-data/local-research/README.md`.

### What this does not resolve

Git history is not the only place data can persist once public. The files were reachable
in this repository's history for a bounded window before the rewrite; anything GitHub
itself cached or indexed in that window, or that a crawler fetched, is outside what a git
operation can retract. Zero forks and zero stars were confirmed during the audit, which
bounds the realistic risk without eliminating it. Written VPE confirmation (B9) remains the
only way to make the underlying reuse question moot rather than mitigated.

**Phase 3C does not begin until the owner accepts this gate.**
