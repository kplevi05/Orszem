# B9-B — reference-data quality and completeness: findings and review plan

**Status (owner, 2026-09-19): plan approved; manual quarantine classification is DEFERRED until written VPE reuse confirmation is received.** Step 0 onwards below does not start before then.

Part of [B9_DECISION_PACKAGE.md](B9_DECISION_PACKAGE.md). B9-B is about whether the recovered railway reference data is
**good and complete enough to route reports**. It is independent of the licence question (B9-A): nothing here depends on
VPE's answer, and nothing here is affected by it, except that reviewed data may only be *used* once B9-A allows it.

**Boundaries kept:** no quarantined row was classified, no relation was inferred or added, `manual-review.csv` is unchanged,
no import. Figures are **aggregates** computed read-only from the durable backup (`2026.09.07-1`, checksums verified); no
station names or rows appear here, and none may be committed (see the memo §7 and B10).

## 1. Quantitative summary

| Measure | Value |
|---|---|
| KSH settlements / lines / verified relations | 3,178 / 231 / 967 |
| Settlements with ≥ 1 relation | 824 (25.9 %); relations per such settlement: 1 → 716, 2 → 80, 3 → 23, 4 → 4, 6 → 1 |
| Lines with ≥ 1 relation | 147 (63.6 %); **84 with none** |
| Annex data rows / extracted (99.76 %) | 2,116 / 2,111; **5 rows not extracted**, not itemised in any record |
| Extracted rows → outcome | 989 exact matches (→ 967 relations) + 606 flagged `AMBIGUOUS_NAME` + 516 `NO_SETTLEMENT_MATCH` = 2,111 |
| Quarantined rows | **1,122** = 53.1 % of extracted rows |
| — by source | MÁV 973 (505 flagged, 468 no match); GYSEV 149 (101 flagged, 48 no match) |
| — distinct station names | 935 (151 names recur, accounting for 338 rows) |
| — lexical shape of the name (descriptive, first match) | hyphenated 342; contains parentheses 18; slash 10; contains a digit 32; none of these ("plain") 720 (501 no match, 219 flagged by another marker word) |
| Lines with quarantined rows | 227 (MÁV 207, GYSEV 32, in both 12) |
| Lines with relations **and** quarantined rows | 143; on 52 of them quarantined rows outnumber resolved relations |
| Lines with relations and **no** quarantined rows | 4 (7 relations) |
| Lines with **only** quarantined rows (the 84) | 193 rows, 155 distinct names (MÁV 179, GYSEV 14; 145 flagged, 48 no match) |
| Budapest-prefixed quarantined rows | 8 rows, 4 distinct names, 7 lines; all flagged `AMBIGUOUS_NAME`; 2 on zero-relation lines, 6 on lines that already have relations |
| Manual decisions recorded | **0** accepted, **0** rejected |

## 2. The investigated questions

### 2.1 The 227 vs 231 railway-line difference: explained

The roster has 231 lines. Phase 3B §4 originally labelled 207 (MÁV), 32 (GYSEV) and 12 (both), so 227 distinct, as "lines seen"; that label has been corrected. Recomputing from the
backup: those three figures equal the number of lines **having at least one quarantined row** by source (207 / 32 / 12, union 227),
not the number of lines in each annex. The remaining **4** lines have relations and **no** quarantined rows (every service point
on them matched exactly), so they never appear in the quarantine. 227 + 4 = 231, and every roster line is in the union of
(lines with a relation) and (lines with a quarantined row); none is in neither and none is outside the roster. So:

- **No lines are missing or unexplained.** The 231-line roster is internally consistent.
- The heading "Lines seen" in Phase 3B §4 is inaccurate as a description; the true number of lines *per annex* is **not recorded**
  (the 4 fully resolved lines have no per-source label in the canonical files, and the annex text is not on this machine). The
  derived "84 % of GYSEV lines have a relation" therefore cannot be re-verified and should not be relied on.

### 2.2 The 1,122 quarantined station rows

Produced by three builder rules (`build-canonical.mjs`), all deliberately conservative:

1. `AMBIGUOUS_NAME`: the **name contains a marker** (hyphen, comma, parentheses, *alsó*, *felső*, *külső*, *belső*, *elágazás*,
   *szelvény*, *ipvk*, *rakodó*, *országhatár*, *oh.*, or `Budapest`). This is a **lexical flag**, not a finding that the name is
   ambiguous: many flagged rows may name a real settlement plus a qualifier. It is checked *before* any matching.
2. `NO_SETTLEMENT_MATCH`: after removing a short whitelist of facility suffixes (`mh.`, `pvh.`, …), no KSH settlement has exactly
   that name (Unicode-normalised, case-folded; no diacritic folding, no similarity score).
3. (`SETTLEMENT_NAME_NOT_UNIQUE`: two settlements share the name; **0** occurrences.)

What cannot be determined from the data: how many of the 1,122 rows correspond to a KSH settlement at all. `NO_SETTLEMENT_MATCH`
rows may be sidings, junctions, border points or stations named after a neighbour. **An upper bound only:** at most 935 distinct
names could yield a relation; the true number is unknown and may be far lower.

### 2.3 Why there are zero manual decisions

`reference-data/review/manual-review.csv` was created as an **empty template in the same commit as the pipeline** (`1dd9505`,
2026-09-07) and has never held a decision. No document records a decision, a deferral or a reviewer. Phase 3B §8 lists the review
as the zero-cost way to raise coverage and states each decision needs an *authoritative source* and, for the hardest class, "a person
with a map". It was scoped as future manual work, not started. This is a **state of the project, not a defect in the data**.

### 2.4 Budapest 0 / 24

KSH lists `Budapest` (13578) and 23 districts as 24 settlements; **none has a relation**. Every Budapest-named station row (8) is
flagged by the `Budapest` marker rule (names of the form "Budapest-…"), so none can equal a settlement name. Two of those rows are on
lines with no other relation; six are on lines that are already reachable from other settlements. Resolving them needs (a) the B8
decision (attribute to the city row, to a district, or to both) and (b) a person to attribute each named station to a district
from an authoritative source. This is the smallest batch and the plan's first step.

### 2.5 Which quarantined rows would materially affect routing

"Material" cannot be fully measured yet, because routing needs **service areas** and their line assignments (B2), which are not
configured, and the owner has not said which lines or regions matter. What can be measured without inference is *structural* impact:

| Tier | Rows | Why it matters | Confidence |
|---|---|---|---|
| **T1 — Budapest** | 8 (4 names, 7 lines) | Largest population; a 0 % region; B8 decides the target | Measured |
| **T2 — the 84 zero-relation lines** | 193 rows, 155 names | A line with no relation can never be offered to a reporter, so it is unusable for routing regardless of service-area configuration | Measured |
| **T3 — lines with relations, but more quarantined rows than relations** | 448 rows, 430 names, on 52 lines | The line is selectable but its reach is understated | Measured |
| **T4 — the remainder** | 481 rows, on the other 91 lines that already have relations | Extends reach on already usable lines | Measured |
| Unknown | any | Which rows correspond to a real settlement | **Not determinable without the review itself** |

T2, T3 and T4 partition the 1,122 rows (193 + 448 + 481); T1 is a subset spread across them (2 rows in T2, 2 in T3, 4 in T4). Do not treat tier size as expected gain: a tier's rows are candidates, not relations.

### 2.6 Does PARTIAL make each gap operationally acceptable?

`PARTIAL` never produces a false statement; it makes the system route **less**, and it decides what each gap costs. Under the current
code (`RoutingService`, Public client `railwayLineStepFor`):

| Situation | What the reporter sees | Result | Acceptable? |
|---|---|---|---|
| Settlement with ≥ 1 verified line (824 settlements) | Must choose a line, or "Nem tudom / másik vonal" (never auto-selected, even for one line) | Chosen line assigned to an active service area → **routed**; chosen line unassigned → `UNCLASSIFIED / RAILWAY_LINE_UNASSIGNED`; "unsure" → `UNCLASSIFIED / RAILWAY_LINE_NOT_SELECTED` | Yes as a safe design; yield depends on B2 and on reporters knowing the line |
| Settlement with no verified relation (2,354 settlements, incl. Budapest) | Message: "no verified railway line on record; this does not mean there is no railway" | Report accepted, `UNCLASSIFIED / NO_VERIFIED_RAILWAY_LINE_REFERENCE`, visible only to global MODERATOR / SUPER_ADMIN | Acceptable only if a moderator triages the `UNCLASSIFIED` queue; not acceptable as the primary path for Budapest |
| Line among the 84 with no relation | Cannot be offered | As above: unclassified | Same |
| Quarantined-row gaps on lines that are selectable | Line is offered but for fewer settlements | As the first row for the settlements that have it | Acceptable; improves with review |

So: **no gap corrupts data or misroutes a report**; every gap degrades to `UNCLASSIFIED`. Acceptability is an operational choice:
it holds if a global moderator watches the `UNCLASSIFIED` queue, and it fails if the owner expects most reports to reach a service area
automatically. With 25.9 % settlement coverage, most settlements will fall in the unclassified group. `COMPLETE` is not reachable by
review (Phase 3B §7 needs boundary geometry, which is paid), so PARTIAL is the permanent state of this dataset, and single-line
inference will not switch on.

## 3. Review plan (no classification yet)

**Rule for every decision:** a row is admitted or rejected only by an entry in `manual-review.csv`
(`ksh_code,line_code,decision,source,reason`) naming an **authoritative source consulted**; no similarity score; nothing edited in
Kotlin; the builder rebuilds and the validator re-runs. Rejected rows are recorded too, so they are not re-reviewed. Review output
(the CSV) is row-level VPE-derived data and stays **outside Git**, in the backup location, exactly like the dataset.

| Step | Scope | Effort driver | Exit check |
|---|---|---|---|
| 0 | Prerequisites: B9-A route chosen; B8 decision (city vs district); re-acquire and hash the annexes; pick the reviewer and the authoritative source per class | Owner decisions | Recorded in the register |
| 1 | T1 Budapest, 8 rows | Small, a person and the annex | 24 Budapest settlements each either related or explicitly "none" |
| 2 | T2, 84 zero-relation lines (193 rows, 155 names) | The bulk of the "usable line" gain | Number of lines with a relation rises from 147 towards 231; report the new count |
| 3 | T3, lines whose quarantined rows outnumber relations (52 lines, 448 rows) | Repeated names; 151 recurring names cover 338 rows, so one decision can clear several rows | Per-line before/after |
| 4 | T4, the remainder | Slowest; `NO_SETTLEMENT_MATCH` needs a map | Stop when the marginal gain is not worth it |
| 5 | Re-run builder/validator; record aggregate before/after; new manifest version; import per procedure | Mechanical | Aggregates in this document updated; **no rows committed** |

Measures to report after each step (aggregates only): settlements with ≥ 1 relation, lines with ≥ 1 relation, quarantined rows,
manual decisions accepted/rejected, Budapest coverage.

**Open point recorded, not decided:** ERA RINF or OSM data may only ever *suggest* candidates for a human to confirm (Phase 3B §8);
they are not proposed here.

## 4. What this plan does not do

It does not classify a row, add a relation, edit `manual-review.csv`, change the manifest, or import. It does not estimate how many
settlements truly have a railway, because the source cannot show that (Phase 3B §7).
