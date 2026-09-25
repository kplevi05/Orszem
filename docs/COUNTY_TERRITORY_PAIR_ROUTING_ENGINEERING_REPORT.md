# Vármegyealapú, páronkénti területi routing — engineering report

Kiindulás: `main` `be4dfb1` (PR #50 merge), CI 6/6 zöld. Ez a fázis **csak előkészítés**: nem történt éles
SSH, deploy, import, apply. Owner review-ig áll.

## 1. Tulajdonosi döntés (verziózva: `operational-data/county-v2/policy.json`, `ORSZEM-COUNTY-V2-1`)

Az Őrszem belső, vármegyealapú felosztása, nem állítás a MÁV/GYSEV hivatalos illetékességéről.
Egy jóváhagyott `(település, vasútvonal)` pár arra a ServiceArea-ra kerül, amelyhez a **település KSH szerinti
vármegyéje** tartozik; ugyanazon vonal különböző települései különböző területre kerülhetnek. Hiányzó/ismeretlen
vármegye → hozzárendelés nélkül. A GYSEV soha nem kap automatikus hozzárendelést.

Megfeleltetés: Budapest ← főváros (KSH-érték), Pest; Debrecen ← Hajdú-Bihar, Jász-Nagykun-Szolnok,
Szabolcs-Szatmár-Bereg; Miskolc ← Borsod-Abaúj-Zemplén, Heves, Nógrád; Pécs ← Baranya, Somogy, Tolna;
Szeged ← Bács-Kiskun, Békés, Csongrád-Csanád; Szombathely ← Győr-Moson-Sopron, Vas, Zala;
Székesfehérvár ← Fejér, Komárom-Esztergom, Veszprém; GYSEV ← (üres, csak külön jóváhagyott pair-override).

## 2. ServiceArea-inventory (élő, lekérdezve 2026-09-24)

8 ACTIVE (Budapest `fbde9347-…`, Debrecen `6b1aa304-…`, GYSEV `bee538cd-…`, Miskolc `8b519923-…`,
Pécs `5646c308-…`, Szeged `db15dd1e-…`, Szombathely `4c3ae602-…`, Székesfehérvár `e3b62884-…`; teljes ID-k a
policy-ban) + 3 INACTIVE (`[FIKTÍV] Déli`, `[FIKTÍV] Északi`, `Átnevezett terület mubf4kjp`) — utóbbiakat a terv
nem érinti, a policy tiltja célként. **Élesben nincs `service_area_settlement_lines` tábla (V007 még nincs
kitelepítve), `service_area_railway_lines` = 0.**

## 3. A 146 pár preview-ja (`operational-data/county-v2/preview/`)

Forrás: a promóter kimenete (`OSM-HU-RAIL-VERIFIED-2026-09-23`, 3178 település, 38 vonal, 146 pár; VERIFIED/PARTIAL/CLEARED).

| Terület | pár | település | vonal |
|---|---|---|---|
| Budapest | 2 | 2 | 1 |
| Debrecen | 39 | 38 | 10 |
| GYSEV | 0 | 0 | 0 |
| Miskolc | 18 | 17 | 5 |
| Pécs | 19 | 18 | 6 |
| Szeged | 17 | 17 | 4 |
| Szombathely | 11 | 11 | 7 |
| Székesfehérvár | 40 | 37 | 9 |
| **Összesen** | **146** | | 38 vonal |

- Egyértelmű (`ASSIGNED_GEOGRAPHIC`): **146**. Bizonytalan: **0** (nincs olyan pár, aminek hiányzik/ismeretlen a vármegyéje). Hozzárendeletlen: **0** (`unassigned-pairs.csv` üres).
- **Cross-territory** (`cross-territory-lines.csv`): 4 vonal lép át területhatárt — 13 (Szombathely 1 | Székesfehérvár 3), 14 (2 | 3), 145 (Debrecen 2 | Szeged 4), 75 (Budapest 2 | Miskolc 10). Egyik vonal sincs teljes egészében egy területhez rendelve.
- **Budapest-ellenőrzés:** 2 pár esik `főváros`/`Pest` vármegyébe, mind Budapestre kerül. A KSH `13578` „Budapest" összesítő rekord (üres vármegye) **dokumentált, KSH-kód alapú kivételt** kapott a Budapest területhez (`settlementIdExceptions`, indok: KSH fővárosi összesítő rekord). A kivétel kizárólag erre az ID-ra érvényes, és csak amíg a kanonikus rekord neve/üres vármegyéje egyezik; más üres vármegyéjű település (akár „Budapest" nevű is) `UNASSIGNED` marad; névalapú fallback nincs. A `13578` jelenleg egyik jóváhagyott párban sincs, ezért a darabszámok változatlanok (`kshIdExceptionPairs: 0`).
- **GYSEV-ellenőrzés:** 0 automatikus hozzárendelés; a build a futás elején hibával leáll, ha bármelyik pár GYSEV-re kerülne. **GYSEV_OVERRIDE_CANDIDATES: üres** (`gysev-override-candidates.json`). Nyilvános hivatalos GYSEV-forrás (gysev.hu „Pályavasút") használhatóságát — felhasználási feltételek, és hogy nem a PENDING hálózati üzletszabályzatból származik-e — nem sikerült megnyugtatóan igazolni, ezért nem használtam; nincs kitalált hozzárendelés. A jelöltekhez kötelező mezők (settlement, vonalkód, vonalnév, forrás URL, lekérdezési dátum, bizonyíték, confidence) és a `CANDIDATE_NOT_APPROVED` státusz a scriptben validált.

## 4. Izolált dry-run (eldobható PostgreSQL, az ismert production referenciaállapotot reprodukáló fixture; éles rendszer érintetlen)

Alapállapot: **az ismert production referenciaállapotot reprodukáló izolált fixture** (`evaluation` + `cleared` import → 3183 település (3178 aktív), 3 vonal, 5 kapcsolat). Ez nem production backup; nem állítom, hogy a teljes production adatbázissal megegyezik.
Az új jar (main) Flyway-je létrehozza a V007 táblát. Azután:
- `reference-validate`: VALID; `reference-diff`: `settlements +0 new 3178 upserted 0 reactivated 0 deactivated`, `railway lines +38 new (3 preserved)`, `relations +146 add -0 remove (5 preserved)`.
- `reference-import`: 41 vonal, 151 kapcsolat; második diff: `(no changes)`. Az import **0** pair-mapping-et hozott létre.
- A `apply-batch-payload.json` a valódi backend `/preview` végpontján: `applied:false, changedCount:146`; `/apply` után a területenkénti darabszám pontosan a fenti tábla.
- Routing: Tata + 1. vonal → **ROUTED Székesfehérvár**; Tata vonalválasztás nélkül → `UNCLASSIFIED / RAILWAY_LINE_NOT_SELECTED`; reláció nélküli település → `UNCLASSIFIED / NO_VERIFIED_RAILWAY_LINE_REFERENCE`.
- Üzemeltetési következmény (ellenőrizve, lásd §4/A): PARTIAL lefedettség mellett az egyetlen jelölt vonalat a rendszer nem következteti ki magától; a területi routinghoz a felhasználónak kell kiválasztania a vonalat, különben a jelentés a közös országos UNCLASSIFIED poolba kerül.

## 4/A. Vonalinferencia-szemantika — nem regresszió (ellenőrizve)

Kérdés: miért marad `UNCLASSIFIED` egyetlen jelölt vonal esetén is? Vizsgálat:
- **ADR 0006, Decision 2 (elfogadva 2026-09-07): „PARTIAL coverage forbids one-relation auto-inference"** — az egyetlen kapcsolat automatikus következtetése csak `COMPLETE` hatókörnél megengedett; `PARTIAL` mellett kötelező az explicit választás (`RAILWAY_LINE_NOT_SELECTED`). A szabály régebbi a KSH fallback fázisnál; annak követelménye („exactly one verified active relation → auto-infer *per current behavior*") a meglévő szemantika megőrzése volt, nem új viselkedés.
- Implementáció: `RoutingService` `activeCandidates.size == 1 && relationCoverage == COMPLETE`. Tesztek mindkét irányra: `RoutingServiceIT` — `COMPLETE coverage, one relation, no selection is inferred and routed`, `COMPLETE, one ACTIVE plus one INACTIVE relation ... infers the ACTIVE line`, és `PARTIAL coverage, exactly one active candidate, no selection must NOT be inferred`; `SettlementLineMappingIT` — „Complete single-candidate inference still works".
- A Public web tükrözi: `lineDecision.ts` — `single-inferred` csak COMPLETE + 1 elem; egyébként `requires-choice` explicit „nem tudom" opcióval (→ `railwayLineId: null`).

Következtetés: szándékos, ADR-alapú, tesztelt viselkedés; nem történt szemantikai változás, nincs owner döntést igénylő eltérés. Termékhatás: a jelenlegi adatkészlet PARTIAL, ezért a területi routinghoz a vonalválasztás szükséges.

## 4/B. Valódi rollback-próba V007 után (izolált fixture)

Részletes tábla és checksumok: `docs/deployment/V2_0_4_TERRITORY_ROLLOUT.md` §5. Röviden: a v2.0.3 jar (`524b4594…0600`, azonos a production jarral) **elindul** a V007-es adatbázison, a Flyway `validated 7 migrations` + csak WARN, health UP, Public/Service API és login működik, a régi snapshot változatlan, a pair-módú vonalak `UNCLASSIFIED / RAILWAY_LINE_UNASSIGNED`-ra esnek, a 146 pair-mapping megmarad, a main jarra visszaállva a routing újra `ROUTED`. A Flyway-validációt nem kellett lazítani.

## 5. Tesztek

`scripts/tests`: **55/55** sikeres (40 korábbi + 15 új; ebből 4 a 13578-as kivételre: 13578 + vonal → Budapest, más üres vármegye → UNASSIGNED még „Budapest" névvel is, megváltozott rekord nem örökli a kivételt, a policy-kivétel csak létező, nem GYSEV területre és KSH-kódra szólhat): vonal településenként eltérő területre kerül; ismeretlen vagy hiányzó vármegye soha nem kap automatikus területet; főváros/Pest → Budapest; GYSEV sosem kap automatikus párt, a jelölt sosem kerül alkalmazásra, és PENDING anyagra hivatkozó jelölt elutasított; nem VERIFIED/CLEARED vagy checksum-hibás adat a sorok olvasása előtt elutasítva; a policy érvényes, lefedi az összes KSH-vármegyét, egy vármegye nem kerülhet két területre, a 3 INACTIVE terület nem lehet cél; determinisztikus kimenet; az apply-payload csak a hozzárendelt párokat tartalmazza `expectedCurrent=null`-lal. CI: a `reference-data` workflow lefuttatja a promótert és a preview `--check`-et (a committolt preview mindig egyezzen). Az admin self-claim (PR #51) tesztelt volt: teljes backend build + Android build/lint/unit, `main` CI zöld.

## 6. Ismert korlátok / owner döntések

1. A GYSEV-jelöltek listája üres; ha kell, jelölj meg engedélyezett hivatalos forrást (felhasználási feltételekkel).
2. Csak 4 vonal lép át határt a 146 párban, mert a jóváhagyott párok a vonalaknak csak egy részét fedik (146/919). A területi kép a további review-csomagokkal bővül; a preview minden új promócióval újragenerálandó (a CI ezt ellenőrzi).
3. A rollback-próba izolált fixture-ön futott, nem production backupon; a production restore drillen megismétlendő. A rollback alatti biztonság feltétele az `ORSZEM_UNCLASSIFIED_SERVICE_USER_ACCESS_ENABLED=true` a futó konfigurációban.
4. A kiadáshoz verziócommit (2.0.4) és a Service APK (self-claim gomb) szükséges; ez a kiadási lépés része, nem ebben a PR-ben.

## 7. Megerősítések

Nem használtam `reference-data/local-research/`-t, PENDING VPE/KTI/GYSEV adatot vagy azokból levezetett információt. Production VM-hez nem nyúltam. Éles `reference-import` és apply nem történt. A 8 ServiceArea és a 3 INACTIVE terület érintetlen.
