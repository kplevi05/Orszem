# Railway line display names + per-line settlement detail — owner review (v2)

**Status: STOP still in effect.** No production data was modified, no `reference-import` ran,
no territory apply ran, no Release was published, no `v2.0.4` tag/artifact was touched, no code
was written. This is v2 of the planning document, correcting v1 per owner review. `Territory
apply stays paused until the owner approves the Phase A name list and production names have
actually improved` (§8).

## 0. Diff from v1 (what changed and why)

| # | v1 problem | v2 fix |
|---|---|---|
| 1 | Line 29's settlement count was hand-counted and wrong (19 vs. actual 20); no consistency check existed | Every count in §3 is now taken directly from `awk -F, 'NR>1{c[$2]++} END{...}' work/promote-release/settlement-railway-lines.csv` (146 rows total, 38 distinct codes, 0 duplicate `(ksh_code,line_code)` pairs, checked in the same pass) — see §1 for the exact reproducible command and the planned automated test |
| 2 | The wireframe used a fabricated `"1 / 8 település"` example | Wireframe now uses the real line 75 (12 settlements, machine-counted) and a neutral `N` in the generic mock-up |
| 3 | All 38 proposed names were presented as roughly equally trustworthy | Every line now carries a `decisionStatus`; 18 of 38 are `NEEDS_OWNER_REVIEW`, not silently defaulted to approved (§3, §3.2) |
| 4 | Lines 6, 64, 89, 110, 115, 151 etc. picked one candidate name without separating "legal endpoint" from "common name" from "domestically evidenced section" | §3.2 gives each disputed line its own record with those three concepts kept distinct, plus full source metadata |
| 5 | The Wikipedia table was called "official" because it cites a decree | §2 now explicitly separates the **legal instrument** (what the decree actually names), the **Wikipedia table** (a tertiary transcription of it, CC BY-SA, itself possibly imperfect), and the **commonly-used line name** (the linked article's title, a fourth, independent signal) |
| 6 | The plan implied hand-editing `work/promote-release/railway-lines.csv` | §4 replaces that with a versioned, owner-approved override file and a promoter contract that never invents an unapproved name |
| 7 | No explicit version boundary | §5 states plainly what stays `v2.0.4` and what is `v2.0.5` |
| 8 | The API plan treated the Wikipedia-sourced Hungarian collation as a given, had no bound on `ids`, and returned undefined behaviour for an unknown id | §6 fixes all of this |
| 9 | Fixture filtering was assumed to already work | §7 replaces the assumption with a named, unexecuted read-only investigation step, and a rule to report a finding rather than invent a name-prefix filter if it turns out fixtures currently leak |

## 1. Data consistency — reproducible counts

Recomputed directly from the promoted, already-approved dataset (`work/promote-release/`,
`datasetVersion OSM-HU-RAIL-VERIFIED-2026-09-23` — no new data, no re-import):

```bash
awk -F, 'NR>1{c[$2]++} END{for(k in c) print k, c[k]}' \
  work/promote-release/settlement-railway-lines.csv | sort -k1,1n
awk -F, 'NR>1' work/promote-release/settlement-railway-lines.csv | wc -l        # 146
awk -F, 'NR>1{print $2}' work/promote-release/settlement-railway-lines.csv | sort -u | wc -l   # 38
awk -F, 'NR>1{print $1","$2}' work/promote-release/settlement-railway-lines.csv | sort | uniq -d  # duplicates, empty
```

Result: 146 pairs, 38 distinct line codes, 0 duplicate `(ksh_code, line_code)` pairs — matches the
promotion manifest exactly. Every "imported settlements" count in §3 is this command's output,
not a hand count.

**Planned automated test** (not written yet, part of §8 Phase A): a small script
(e.g. `scripts/tests/test_railway_line_review_sheet.py`, alongside the existing
`scripts/tests/test_county_territory_preview.py`) that parses this document's §3 table (or,
more robustly, a machine-readable sidecar the table is generated from) and asserts every
`Imported settlements` count equals `awk`'s own recount, failing the build if they ever drift —
mirroring the `--check` pattern already used by `scripts/build-county-territory-preview.py`.

## 2. Source handling — three distinct concepts, not one "official" answer

Previous version conflated these. They are now kept separate for every line:

1. **Jogszabályi végpont (legal endpoint)** — what the cited decree's own line-number table says
   the line's registered endpoints are (a `Vonal szám` row in
   [Magyarországi vasútvonalak listája](https://hu.wikipedia.org/wiki/Magyarorsz%C3%A1gi_vas%C3%BAtvonalak_list%C3%A1ja)).
   This is a Wikipedia editor's transcription of *2011. évi CXCVI. törvény* 1. melléklete /
   *2005. évi CLXXXIII. törvény* 4. melléklete / *194/2016. (VII. 13.) Korm. rendelet*
   1. melléklete — **not the decree text itself**, and therefore not infallible; it is the
   strongest single source found, not a guarantee.
2. **Közhasznált vonalnév (common name)** — the *title* of that line's own, separate Wikipedia
   article (the table's "Kapcsolódó szócikk" column), reflecting how the line is commonly
   referred to, which sometimes names a different, more recognizable endpoint than the legal
   table row (e.g. line 6: legal endpoint "Lovasberény", article title "Bicske–Székesfehérvár").
3. **Jelenleg igazolt magyar szakasz (currently evidenced Hungarian section)** — the subset of
   that route our own `OSM-HU-RAIL-VERIFIED-2026-09-23` dataset actually names settlements for.
   For several lines this section does not reach either legal endpoint (e.g. line 13: legal
   endpoints Tatabánya/Pápa, but the four verified settlements — Kecskéd, Kisbér, Környe,
   Veszprémvarsány — are all intermediate stops).

**Source metadata, common to every citation below:**

| Field | Value |
|---|---|
| Source URL | https://hu.wikipedia.org/wiki/Magyarorsz%C3%A1gi_vas%C3%BAtvonalak_list%C3%A1ja |
| Retrieval date | 2026-09-29 |
| Source type | Tertiary (Wikipedia table transcribing cited Hungarian legal instruments) |
| License | CC BY-SA 4.0 |
| What it supports | The "Jogszabályi végpont" column only, per line, per §2.1 above |
| Decision status | See per-line status in §3 |
| Owner approval | **Not yet given** — this document requests it |

Per-line "Kapcsolódó szócikk" (own Wikipedia article) links are given in §3 for the owner's own
deeper verification; they were **not** individually fetched for this document (38 separate page
loads was judged disproportionate for a first pass) — each linked title is itself the "common
name" signal in §2.2, already usable without a further fetch.

## 3. 38-line review sheet (corrected)

`RailwayLine.id` is still not resolved (no production query was run for this document); resolve
later with `select id, line_code from railway_lines where line_code = '<code>';` (read-only).
Current name for all 38 rows, identically: `"<code>. számú vasútvonal"`. **Imported settlements**
and its count are both machine-recomputed per §1.

| Code | Legal endpoint (Wikipedia table) | Common name (linked article) | Imported settlements (count) | Endpoint evidence | Decision status |
|---|---|---|---|---|---|
| 1 | Budapest-Keleti–Rusovce | *Budapest–Hegyeshalom–Rajka-vasútvonal* | Tata (1) | Neither legal endpoint verified; domestic border town Rajka not in our data either | **NEEDS_OWNER_REVIEW** |
| 6 | Lovasberény rh.–Székesfehérvár | *Bicske–Székesfehérvár-vasútvonal* | Székesfehérvár (1) | One legal endpoint (Székesfehérvár) verified; the other end's identifier (Lovasberény vs. Bicske) is contested | **NEEDS_OWNER_REVIEW** |
| 12 | Környe–Oroszlány | *Tatabánya–Oroszlány-vasútvonal* | Környe, Oroszlány (2) | Both legal endpoints verified | READY_FOR_OWNER_APPROVAL |
| 13 | Tatabánya–Pápa | *Környe–Pápa-vasútvonal* | Kecskéd, Kisbér, Környe, Veszprémvarsány (4) | Neither legal endpoint verified — all 4 are intermediate stops | **NEEDS_OWNER_REVIEW** |
| 14 | Pápa–Csorna | *Pápa–Csorna-vasútvonal* | Csorna, Marcaltő, Nemesgörzsöny, Pápa, Rábapordány (5) | Both legal endpoints verified | READY_FOR_OWNER_APPROVAL |
| 15 | Sopron–Szombathely | *Sopron–Szombathely-vasútvonal* | Bük (1) | Neither legal endpoint verified | **NEEDS_OWNER_REVIEW** |
| 18 | Szombathely–Kőszeg | *Szombathely–Kőszeg-vasútvonal* | Gyöngyösfalu, Kőszeg, Lukácsháza, Szombathely (4) | Both legal endpoints verified | READY_FOR_OWNER_APPROVAL |
| 22 | Körmend–Zalalövő | *Körmend–Zalalövő-vasútvonal* | Körmend (1) | One legal endpoint verified | READY_FOR_OWNER_APPROVAL |
| 23 | Zalaegerszeg–Rédics | *Zalaegerszeg–Rédics-vasútvonal* | Gutorfölde (1) | Neither legal endpoint verified | **NEEDS_OWNER_REVIEW** |
| 24 | Zalabér-Batyk–Zalaszentgrót | *Zalabér-Batyk–Zalaszentgrót-vasútvonal* | Zalaszentgrót (1) | One legal endpoint verified | READY_FOR_OWNER_APPROVAL |
| 27 | Lepsény–Hajmáskér | *Lepsény–Hajmáskér-vasútvonal* | Csajág, Hajmáskér, Lepsény, Sóly (4) | Both legal endpoints verified | READY_FOR_OWNER_APPROVAL |
| 29 | Szabadbattyán–Tapolca (article: Börgönd–Szabadbattyán–Tapolca) | *Börgönd–Szabadbattyán–Tapolca-vasútvonal* | Alsóörs, Aszófő, Badacsonytomaj, Balatonakarattya, Balatonalmádi, Balatonfüred, Balatonfűzfő, Balatonkenese, Balatonrendes, Balatonszepezd, Balatonudvari, Csajág, Csopak, Füle, Polgárdi, Révfülöp, Szabadbattyán, Tapolca, Ábrahámhegy, Örvényes (**20**, corrected from v1's 19) | Both legal endpoints verified (Szabadbattyán, Tapolca); "Börgönd" third name and "Balaton-part" colloquial qualifier are **unsourced for this document** and must not enter the approved display name without a separate citation | **NEEDS_OWNER_REVIEW** |
| 38 | Nagyatád–Somogyszob | *Nagyatád–Somogyszob-vasútvonal* | Nagyatád, Somogyszob (2) | Both legal endpoints verified | READY_FOR_OWNER_APPROVAL |
| 43 | Mezőfalva elágazás–Rétszilas | *Mezőfalva–Rétszilas-vasútvonal* | Alap, Nagykarácsony (2) | Neither legal endpoint verified | **NEEDS_OWNER_REVIEW** |
| 44 | Pusztaszabolcs–Szabadbattyán (article: Székesfehérvár–Pusztaszabolcs) | *Székesfehérvár–Pusztaszabolcs-vasútvonal* | Pusztaszabolcs, Seregélyes, Székesfehérvár, Zichyújfalu (4) | One legal-table endpoint (Pusztaszabolcs) verified; the other legal-table endpoint is "Szabadbattyán", but the *article title* says "Székesfehérvár" instead — v1 silently used the article's endpoint without flagging this mismatch | **NEEDS_OWNER_REVIEW** |
| 47 | Godisa–Komló | *Godisa–Komló-vasútvonal* | Bodolyabér, Komló, Magyarhertelend, Magyarszék, Mecsekpölöske (5) | Both legal endpoints verified | READY_FOR_OWNER_APPROVAL |
| 61 | Szentlőrinc–Sellye | *Szentlőrinc–Sellye-vasútvonal* | Kákics, Sellye, Sumony (3) | One legal endpoint verified | READY_FOR_OWNER_APPROVAL |
| 62 | Középrigóc–Nagyharsány rh. | *Középrigóc–Villány-vasútvonal* | Csányoszró, Drávafok, Sellye, Vajszló (4) | Neither legal endpoint (Középrigóc, Nagyharsány) nor the article's own endpoint (Villány) is among the verified settlements; three different place names (Középrigóc, Nagyharsány, Villány) are in play for one line | **NEEDS_OWNER_REVIEW** |
| 64 | Pécs–Pécsvárad rh. | *Pécs–Bátaszék-vasútvonal* | Bátaszék, Palotabozsok, Pécs (3) | One legal endpoint (Pécs) verified; Bátaszék (our other verified settlement) matches the *article's* endpoint, not the legal table's "Pécsvárad" — primary candidate is now **`64 – Pécs–Bátaszék`**, per owner steer, since it is the name best matching our own evidence | **NEEDS_OWNER_REVIEW** |
| 66 | Villány–Mohács | *Pécs–Mohács-vasútvonal* | Magyarbóly, Villány (2) | One legal endpoint (Villány) verified | READY_FOR_OWNER_APPROVAL |
| 75 | Vác–Drégelypalánk (+75A: Drégelypalánk–Balassagyarmat) | *Vác–Balassagyarmat-vasútvonal* | Balassagyarmat, Berkenye, Borsosberény, Dejtár, Diósjenő, Drégelypalánk, Ipolyszög, Ipolyvece, Nagyoroszi, Nógrád, Szokolya, Vác (12) | Vác, Drégelypalánk and Balassagyarmat all verified — the merged name is well-evidenced; the only open item is documenting that our single `line_code=75` covers two legal sub-lines (75 + 75A) | READY_FOR_OWNER_APPROVAL *(structural note required, see below)* |
| 76 | Diósjenő–Romhány | *Diósjenő–Romhány-vasútvonal* | Diósjenő (1) | One legal endpoint verified | READY_FOR_OWNER_APPROVAL |
| 81 | Hatvan–Fiľakovo (article: Hatvan–Somoskőújfalu) | *Hatvan–Somoskőújfalu-vasútvonal* | Somoskőújfalu (1) | Legal endpoint is a foreign town (Fiľakovo, Slovakia); our own evidence matches the article's domestic endpoint (Somoskőújfalu) exactly | **NEEDS_OWNER_REVIEW** |
| 82 | Hatvan–Újszász | *Hatvan–Újszász-vasútvonal* | Jászberény, Jászfényszaru, Pusztamonostor, Újszász (4) | One legal endpoint verified | READY_FOR_OWNER_APPROVAL |
| 85 | Vámosgyörk–Gyöngyös | *Vámosgyörk–Gyöngyös-vasútvonal* | Gyöngyös, Gyöngyöshalász (2) | One legal endpoint verified | READY_FOR_OWNER_APPROVAL |
| 89 | Tiszapalkonya-TIFO ipv.–Hejőkeresztúr | *Nyékládháza–Tiszaújváros-vasútvonal* | Hejőkeresztúr, Nagycsécs, Sajószöged, Tiszaújváros (4) | One legal endpoint (Hejőkeresztúr) verified; the legal table's *other* endpoint is an industrial siding ("Tiszapalkonya-TIFO ipv."), while the article's endpoint (Nyékládháza) is not verified in our data at all | **NEEDS_OWNER_REVIEW** |
| 105 | Debrecen–Valea lui Mihai | *Debrecen–Nyírábrány-vasútvonal* | Debrecen, Nyírábrány, Vámospércs (3) | Legal endpoint is a foreign town (Valea lui Mihai, Romania); domestic evidence (Debrecen, Nyírábrány) is strong and matches the article | **NEEDS_OWNER_REVIEW** *(cross-border framing only, not a naming dispute)* |
| 106 | Debrecen–Nagykereki | *Debrecen–Nagykereki-vasútvonal* | Debrecen (1) | One legal endpoint verified | READY_FOR_OWNER_APPROVAL |
| 109 | Tócóvölgy–Tiszalök | *Debrecen–Tiszalök-vasútvonal* | Hajdúböszörmény, Hajdúdorog, Hajdúnánás, Szorgalmatos, Tiszavasvári (5) | Neither legal endpoint (Tócóvölgy, Tiszalök) is among the verified settlements — all 5 are intermediate stops | **NEEDS_OWNER_REVIEW** |
| 110 | Apafa–Nyírbátor | *Apafa–Mátészalka-vasútvonal* | Hajdúsámson, Hodász, Mátészalka, Nyíradony, Nyírbogát, Nyírbátor, Nyírcsászári, Nyírgelse, Nyírmeggyes, Nyírmihálydi (10) | Nyírbátor (legal endpoint) is verified, but the *article* only covers Apafa–Mátészalka, a shorter section than our own data implies (which reaches past Mátészalka to Nyírbátor and several other stops) — **do not accept "Debrecen–Nyírbátor" without deeper checking**, per owner instruction | **NEEDS_OWNER_REVIEW** |
| 114 | Kocsord alsó–Csenger | *Kocsord–Csenger-vasútvonal* | Csenger, Győrtelek, Ököritófülpös (3) | One legal endpoint verified | READY_FOR_OWNER_APPROVAL |
| 115 | Carei–Mátészalka | *Mátészalka–Nagykároly-vasútvonal* | Nagyecsed, Nyírcsaholy, Tiborszállás (3) | Legal/article endpoint (Carei/Nagykároly) is foreign and unverified; **v1's "Mátészalka–Nagyecsed" is rejected per owner instruction, because Nagyecsed is an intermediate stop, not a line endpoint**; candidate domestic name to evaluate: **`115 – Mátészalka–Tiborszállás`**, with the Carei/Nagykároly cross-border continuation kept as a separate note, not part of the short name | **NEEDS_OWNER_REVIEW** |
| 116 | Nyíregyháza–Vásárosnamény | *Nyíregyháza–Vásárosnamény-vasútvonal* | Apagy, Baktalórántháza, Napkor, Nyírmada, Vásárosnamény, Ófehértó (6) | One legal endpoint verified | READY_FOR_OWNER_APPROVAL |
| 117 | Ohat-Pusztakócs–Görögszállás | *Ohat-Pusztakócs–Görögszállás-vasútvonal* | Tiszaeszlár, Tiszalök (2) | Neither legal endpoint verified | **NEEDS_OWNER_REVIEW** |
| 145 | Kecskemét–Szolnok | *Kecskemét–Szolnok-vasútvonal* | Kecskemét, Lakitelek, Nyárlőrinc, Tiszakécske, Tiszavárkony, Tószeg (6) | One legal endpoint (Kecskemét) verified | READY_FOR_OWNER_APPROVAL |
| 151 | Kunszentmiklós-Tass–Dunapataj (article also lists *Dunaföldvár–Solt-vasútvonal*) | *Kunszentmiklós-Tass–Dunapataj-vasútvonal* | Apostag, Dunaegyháza, Dunavecse, Solt, Szalkszentmárton (5) | Neither exact legal endpoint verified; our `line_code=151` spans what the source treats as **two separate historic/administrative articles** merged under one code — needs explicit documentation, not silent merging | **NEEDS_OWNER_REVIEW** |
| 153 | Kiskőrös–Kalocsa | *Kiskőrös–Kalocsa-vasútvonal* | Kalocsa, Kecel, Öregcsertő (3) | One legal endpoint verified | READY_FOR_OWNER_APPROVAL |
| 155 | Kiskunhalas–Kiskunfélegyháza | *Kiskunhalas–Kiskunfélegyháza-vasútvonal* | Harkakötöny, Jászszentlászló, Kiskunfélegyháza, Kiskunhalas, Kiskunmajsa (5) | Both legal endpoints verified | READY_FOR_OWNER_APPROVAL |

**Totals:** 38 lines, 146 imported pairs (matches §1 exactly). **18 lines NEEDS_OWNER_REVIEW**,
**20 lines READY_FOR_OWNER_APPROVAL** — "ready" means *no known factual dispute in the sourcing
used here*, not "approved"; every one of the 38 still requires an explicit owner approval
action before it can be written into `humanApproved: true` (§4).

### 3.1 READY_FOR_OWNER_APPROVAL — proposed short names (20)

| Code | Proposed short display name |
|---|---|
| 12 | 12 – Környe–Oroszlány |
| 14 | 14 – Pápa–Csorna |
| 18 | 18 – Szombathely–Kőszeg |
| 22 | 22 – Körmend–Zalalövő |
| 24 | 24 – Zalabér-Batyk–Zalaszentgrót |
| 27 | 27 – Lepsény–Hajmáskér |
| 38 | 38 – Nagyatád–Somogyszob |
| 47 | 47 – Godisa–Komló |
| 61 | 61 – Szentlőrinc–Sellye |
| 66 | 66 – Villány–Mohács |
| 75 | 75 – Vác–Balassagyarmat *(+ structural note: covers legal sub-lines 75 and 75A)* |
| 76 | 76 – Diósjenő–Romhány |
| 82 | 82 – Hatvan–Újszász |
| 85 | 85 – Vámosgyörk–Gyöngyös |
| 106 | 106 – Debrecen–Nagykereki |
| 114 | 114 – Kocsord–Csenger |
| 116 | 116 – Nyíregyháza–Vásárosnamény |
| 145 | 145 – Kecskemét–Szolnok |
| 153 | 153 – Kiskőrös–Kalocsa |
| 155 | 155 – Kiskunhalas–Kiskunfélegyháza |

This list (20 rows) is a plain transcription of §3's `READY_FOR_OWNER_APPROVAL` rows, kept in the
same order; it must be regenerated from §3 if §3 changes, rather than maintained separately by
hand — exactly the kind of manual-transcription drift §1's planned automated test is meant to
catch.

### 3.2 NEEDS_OWNER_REVIEW — full decision records (18)

Each record separates the three concepts from §2 and gives the source metadata the owner asked
for. `decisionStatus` is `NEEDS_OWNER_REVIEW` for all of these; `humanApproved` is `false` for
all of these until the owner resolves the open question and says so explicitly.

| Code | Legal endpoint | Common name (article) | Domestically evidenced section | Open question |
|---|---|---|---|---|
| 1 | Budapest-Keleti–Rusovce (SK) | Budapest–Hegyeshalom–Rajka | only Tata verified | Is "Budapest–Győr–Hegyeshalom" (domestic, well-known) acceptable as the short name despite matching neither the legal nor the article endpoint literally? |
| 6 | Lovasberény rh.–Székesfehérvár | Bicske–Székesfehérvár | only Székesfehérvár verified | Which non-Székesfehérvár endpoint identifier should the public see: Lovasberény (legal) or Bicske (common)? |
| 13 | Tatabánya–Pápa | Környe–Pápa | Kecskéd, Kisbér, Környe, Veszprémvarsány (all intermediate) | Legal endpoints unverified by our own data — accept the legal name anyway, or wait for stronger evidence? |
| 15 | Sopron–Szombathely | Sopron–Szombathely | only Bük verified (intermediate) | Same as above. |
| 23 | Zalaegerszeg–Rédics | Zalaegerszeg–Rédics | only Gutorfölde verified (intermediate) | Same as above. |
| 29 | Szabadbattyán–Tapolca | Börgönd–Szabadbattyán–Tapolca | 20 settlements (endpoints included) | Which third-town qualifier, if any ("Börgönd"), and whether an unsourced "Balaton-part" tag may be added at all. |
| 43 | Mezőfalva elágazás–Rétszilas | Mezőfalva–Rétszilas | Alap, Nagykarácsony (intermediate) | Legal endpoints unverified — same open question as 13/15/23. |
| 44 | Pusztaszabolcs–Szabadbattyán | Székesfehérvár–Pusztaszabolcs | Pusztaszabolcs, Seregélyes, Székesfehérvár, Zichyújfalu | Legal table's second endpoint (Szabadbattyán) vs. article's second endpoint (Székesfehérvár) — these are two different towns; which should the public name use? |
| 62 | Középrigóc–Nagyharsány rh. | Középrigóc–Villány | Csányoszró, Drávafok, Sellye, Vajszló (none match any of the three names) | Three different place names in play (Középrigóc, Nagyharsány, Villány) for the far endpoint; none verified by our own data. |
| 64 | Pécs–Pécsvárad rh. | Pécs–Bátaszék | Bátaszék, Palotabozsok, Pécs | Legal endpoint (Pécsvárad) vs. article/evidence endpoint (Bátaszék) — **primary candidate for owner approval: `64 – Pécs–Bátaszék`**, since it is the only one matching our own verified data. |
| 81 | Hatvan–Fiľakovo (SK) | Hatvan–Somoskőújfalu | Somoskőújfalu (matches article exactly) | Confirm the domestic short name should stop at Somoskőújfalu, not the foreign legal endpoint. |
| 89 | Tiszapalkonya-TIFO ipv.–Hejőkeresztúr | Nyékládháza–Tiszaújváros | Hejőkeresztúr, Nagycsécs, Sajószöged, Tiszaújváros | Legal endpoint is an industrial siding name; article's endpoint (Nyékládháza) is itself unverified by our data — which name is more useful to a citizen? |
| 105 | Debrecen–Valea lui Mihai (RO) | Debrecen–Nyírábrány | Debrecen, Nyírábrány, Vámospércs | Confirm the domestic short name should stop at Nyírábrány, not the foreign legal endpoint (framing only, evidence is otherwise strong). |
| 109 | Tócóvölgy–Tiszalök | Debrecen–Tiszalök | Hajdúböszörmény, Hajdúdorog, Hajdúnánás, Szorgalmatos, Tiszavasvári (all intermediate) | Neither legal endpoint verified by our data at all. |
| 110 | Apafa–Nyírbátor | Apafa–Mátészalka | Hajdúsámson, Hodász, Mátészalka, Nyíradony, Nyírbogát, Nyírbátor, Nyírcsászári, Nyírgelse, Nyírmeggyes, Nyírmihálydi | Per explicit owner instruction: **do not accept "Debrecen–Nyírbátor"** without deeper checking, since the article covering the route only documents it as far as Mátészalka while our own data continues past it — needs a dedicated follow-up, not a same-session decision. |
| 115 | Carei (RO)–Mátészalka | Mátészalka–Nagykároly | Nagyecsed, Nyírcsaholy, Tiborszállás | v1's "Mátészalka–Nagyecsed" rejected (Nagyecsed is a waypoint). Evaluate `115 – Mátészalka–Tiborszállás` as the domestic name; document Carei/Nagykároly continuation separately. |
| 117 | Ohat-Pusztakócs–Görögszállás | Ohat-Pusztakócs–Görögszállás | Tiszaeszlár, Tiszalök (neither is a legal endpoint) | Neither legal endpoint verified by our data at all. |
| 151 | Kunszentmiklós-Tass–Dunapataj (+ *Dunaföldvár–Solt-vasútvonal*) | Kunszentmiklós-Tass–Dunapataj | Apostag, Dunaegyháza, Dunavecse, Solt, Szalkszentmárton | Confirm whether the merged `line_code=151` should be documented (in the display name's longer form, or only in an internal note) as covering two distinct historic articles. |

Every row above still needs, before `humanApproved: true` can be set: (a) the owner's explicit
choice among the listed options, and (b) — for the six rows with genuinely thin domestic
evidence (13, 15, 23, 43, 109, 117) — an explicit owner decision on whether "legal endpoints,
weakly evidenced" is an acceptable public name at all, or whether those six should keep the
current safe placeholder until more OSM/relation evidence exists.

## 4. Reproducible name override — plan only (no code written)

**Never edit `work/promote-release/railway-lines.csv` by hand.** Instead:

**New versioned decision file:** `reference-data/osm-review/railway-line-display-name-decisions.json`

```json
{
  "schemaVersion": 1,
  "policyVersion": "RAILWAY-LINE-NAME-V1",
  "generatedAt": "<UTC ISO 8601>",
  "decisions": [
    {
      "lineCode": "12",
      "approvedDisplayName": "12 – Környe–Oroszlány",
      "source": {
        "url": "https://hu.wikipedia.org/wiki/Magyarorsz%C3%A1gi_vas%C3%BAtvonalak_list%C3%A1ja",
        "retrievedAt": "2026-09-29",
        "sourceType": "WIKIPEDIA_TABLE_CITING_LEGAL_DECREE",
        "license": "CC BY-SA 4.0"
      },
      "evidence": "both legal endpoints (Környe, Oroszlány) match imported verified settlements",
      "decisionStatus": "READY_FOR_OWNER_APPROVAL",
      "humanApproved": false,
      "policyVersion": "RAILWAY-LINE-NAME-V1"
    }
  ]
}
```

**Planned promoter contract** (a small addition to the promotion pipeline, e.g.
`scripts/apply-railway-line-display-names.py` — not written yet):

- Loads the decisions file; requires `schemaVersion` and `policyVersion` to match what the
  promoter expects, same discipline as `scripts/promote-osm-railway-reference.py`'s
  `--expect-policy-version`.
- For each of the 38 `railway-lines.csv` rows: applies `approvedDisplayName` **only** when a
  decision exists for that `lineCode` **and** `humanApproved == true` **and**
  `decisionStatus == "READY_FOR_OWNER_APPROVAL"`. Every other line keeps its current safe
  placeholder (`"<code>. számú vasútvonal"`) unchanged — exactly the same "silence is safe"
  principle already used for GYSEV overrides in `operational-data/county-v2/`.
- Refuses with a hard error and writes nothing when: a decision names an unknown `lineCode`; two
  decisions target the same `lineCode`; a decision is missing a required field; a decision has
  `humanApproved: true` but is not accompanied — in the same discipline as the existing OSM
  review decision files — by an explicit owner-approval record for that exact name (never a
  self-authored `humanApproved: true`, matching this project's standing rule that only the owner
  sets that flag, after the exact approval sentence for that content).
- Produces byte-deterministic output (stable line-code ordering, same `csv_bytes`/`json_bytes`
  serialization style already used by `scripts/build-county-territory-preview.py`), so a
  `--check` re-run detects drift.
- Writes the decisions file's own SHA-256 and a fixed attribution string into the new dataset's
  `manifest.json` — a new field, e.g. `"railwayLineNameSource"` — and creates
  `reference-data/LICENSES/Wikipedia-CC-BY-SA.md`, following the existing
  `reference-data/LICENSES/KSH-helysegnevtar.md` / `OpenStreetMap-ODbL.md` pattern exactly
  (required attribution text, retrieved-artefact table with URL/date/SHA-256).

**No `humanApproved: true` record will be created by me for any of the 38 lines until the owner
gives the same kind of explicit, per-content approval sentence already required for OSM review
decisions.** This document is the review step that precedes that approval, not a substitute for
it.

## 5. Version boundary

- **`v2.0.4` tag, GitHub Release, and its release artifacts (jar, signed Service APK,
  `SHA256SUMS.txt`) are never modified or re-tagged.** Nothing here touches them.
- A **display-name-only reference dataset** (new `datasetVersion`, same 3178 settlements / 38
  lines / 146 mappings, only `railway-lines.csv`'s `display_name` column changed for the
  owner-approved subset) is a **separate data release** — importable by the **current, already
  deployed `v2.0.4` backend** via the existing `reference-validate` → `reference-diff` →
  owner-approved `reference-import` path, with no code deployment at all. This is Phase A (§8).
- The **new batch settlement-detail endpoint**, the **Public Web** expandable panel, and the
  **Public Android** expandable panel are **`v2.0.5`** work: new backend code, a new Public Web
  deployment, and a **new, separately signed Public Android APK** (the Public app has never
  needed release signing before in this project's history — this will be its first signed
  production build, following the same signing-continuity rules already used for the Service
  app). None of this is in scope until Phase A's name-only dataset is approved and imported.

## 6. API plan — precise contract (v2.0.5)

`GET /api/v1/public/reference/railway-lines/settlements?ids={uuid1},{uuid2},...`

- **Unknown or inactive id:** silently **omitted** from the `lines` array — the endpoint never
  promises a phantom entry for an id it cannot serve, and never distinguishes "unknown" from
  "inactive" from "exists but no active settlements" in its response (all three simply produce
  no entry, or an entry with `settlements: []` only for the third case — an id that legitimately
  resolves to a real, active line with zero currently-verified settlements). This avoids leaking
  which ids exist internally.
- **Bounds:** maximum 50 ids per request (well above the 38-line total and any plausible
  simultaneous UI display); request rejected with 400 if exceeded. Maximum total query-string
  length enforced at the same layer that already bounds other query parameters (`MAX_SEARCH_RESULTS`-style constant), to prevent a pathologically long URI.
- **Invalid UUID syntax in `ids`:** 400, same `VALIDATION_ERROR` shape already used elsewhere in
  the Public API.
- **Empty `ids` (missing or zero-length):** 400 — never a bare empty 200, so a client bug that
  forgets to populate `ids` fails loudly instead of silently rendering nothing.
- **Duplicate ids in the request:** deduplicated before querying, deterministically (each id
  appears at most once in the response, in the same stable order regardless of how many times
  it was repeated in the request).
- **Hungarian sort order:** v1 assumed a `hu-HU` PostgreSQL collation exists in production
  without checking. **This must be verified, not assumed**, before implementation:
  `select collname from pg_collation where collname ilike '%hu%';` (read-only, safe to run
  against production when this phase starts) — if no Hungarian collation is installed, sort
  application-side with a tested `java.text.Collator` for `Locale("hu")`, which needs no database
  extension and is already how most JVM services handle this. The implementation plan picks
  whichever the read-only check confirms is actually available.
- **N+1 avoidance, verified by test:** the planned integration test asserts total executed query
  count for an N-line request (e.g. via a query-counting Testcontainers/datasource-proxy setup,
  or a simpler `EXPLAIN`/statement-count assertion consistent with how this codebase's other
  integration tests are structured) stays constant regardless of how many ids are requested —
  not just "looks like one query" in the SQL source.
- Everything else from v1 stands: single JOIN query, active-only settlements and lines, no
  `verificationStatus`/`evidence`/`routing`/`ServiceArea` field anywhere in the response, 503
  `REFERENCE_DATASET_UNAVAILABLE` if no dataset was ever imported.

## 7. Fixture filtering — investigate first, do not assume (still unexecuted)

The 3 fictional railway lines and 5 fixture settlement-line relations used by earlier phases'
tests **must be checked, read-only, before any implementation work starts** — not assumed safe.
Planned read-only checks (to run when this phase is actually approved to proceed, not now):

1. `select line_code, active from railway_lines where line_code not in (<the 38 real codes>);`
   — are the fixture lines `active = true`?
2. For each fixture line, `select count(*) from settlement_railway_lines where railway_line_id = ...`
   and whether those settlement rows are themselves `active = true`.
3. A live, credential-free call to the *existing* `GET /public/reference/settlements/{id}/railway-lines`
   for a settlement known to carry a fixture relation — does a fixture line currently appear in
   a real Public API response today?
4. If step 3 shows a fixture line **is** currently Public-visible, that is reported as its own,
   separate **security/data-quality finding** — not silently patched here, and not worked around
   with an invented name-prefix filter (e.g. matching on `"[FIKTÍV]"` in a display name) without
   a proper domain model for it (e.g. a `test_fixture` boolean column, decided in its own ADR).
   The new batch endpoint's implementation plan is written to reuse whatever the *existing*,
   already-audited filtering mechanism turns out to be (most likely: `active = true`, the same
   condition `findActiveLinesOfSettlement` already applies) rather than inventing a new one.

## 8. Two-phase plan

**Phase A — name-only data release (importable by the current `v2.0.4` backend, no code deploy):**
1. This document's 38-line review, finalized with the owner's decisions on all 20
   `NEEDS_OWNER_REVIEW` rows (§3.2) and explicit approval of the 18 `READY_FOR_OWNER_APPROVAL`
   rows (§3.1).
2. `reference-data/osm-review/railway-line-display-name-decisions.json` created with
   `humanApproved: true` **only** for lines the owner has actually approved by name, by the
   exact-sentence discipline already used for OSM review decisions.
3. Promoter run (§4) → new `datasetVersion`, `display_name`-only changes, byte-deterministic,
   with the Wikipedia CC BY-SA attribution recorded in the manifest and in
   `reference-data/LICENSES/`.
4. `reference-validate` then `reference-diff` against production — expected: `0 new / 38 upserted
   (or fewer, if not all 38 were approved) / 0 reactivated / 0 deactivated` for railway lines,
   and **no change at all** to `settlements.csv` or `settlement-railway-lines.csv`.
5. A separately, explicitly owner-approved `reference-import` of the name-only dataset (its own
   approval sentence, distinct from this document and distinct from the territory-apply
   approval already on file).

**Phase B — `v2.0.5` (new code, new client builds):**
6. The new read-only batch settlement-detail endpoint (§6).
7. Public Web expandable panel.
8. Public Android expandable panel — first signed production Public APK.
9. Full regression (backend, Web, Android) per this repo's existing gates.
10. A `v2.0.5` release and the separate client deployments/distribution it requires (its own
    owner approvals, following the same rollout discipline as `v2.0.4`).

**Territory apply remains paused** until the owner approves Phase A's name list and the
public-facing railway line names have actually improved in production — i.e. until step 5 above
has run, not merely been planned.

## 9. Output of this document

- This revised (v2) review document.
- The corrected 38-line table (§3), with machine-recomputed counts (§1) and a
  `READY_FOR_OWNER_APPROVAL` (§3.1, 20 lines) / `NEEDS_OWNER_REVIEW` (§3.2, 18 lines) split.
- The reproducible override-file plan (§4) — schema and promoter contract, no code written.
- The `v2.0.5` API/UI plan (§6, and the UI wireframe carried over from v1 with the fabricated
  example replaced).
- This §0 diff summary.

Still not done, on purpose: no production data touched, no `reference-import`, no territory
apply, no `v2.0.4` tag/Release change, no code. Standing by for owner review of the 20
`NEEDS_OWNER_REVIEW` rows and explicit approval of the 18 `READY_FOR_OWNER_APPROVAL` rows.
