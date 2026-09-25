# v2.0.4 — V007 + 146 ellenőrzött vasúti kapcsolat + vármegyealapú területi routing: rollout és rollback

**Csak terv. Ez a dokumentum nem bizonyíték arra, hogy bármi élesben megváltozott.** Éles importot,
deployt vagy területi apply-t ebben a fázisban senki nem végzett.

## 0. Kiindulópont (ellenőrizve 2026-09-24/25)

- Élő backend: **v2.0.3**. Az élő adatbázisban **nincs** `service_area_settlement_lines` tábla (a V007 még nem
  futott le); `service_area_railway_lines` üres. Élő ServiceArea: a 8 kézzel létrehozott (ACTIVE) + 3 INACTIVE
  (2 fiktív, 1 „Átnevezett terület"), pontos ID-k: `operational-data/county-v2/policy.json`.
- `main` = a kiadási jelölt. A v2.0.3 óta a `main`-ben: V007 + páronkénti routing (PR #32), a review
  döntések (PR #33–#50, 146 ellenőrzött pár), **adminisztrátori self-claim (PR #51, külön változás)**.
  A backend verziója még `2.0.3`: a kiadáshoz verziócommit kell (a korábbi kiadások mintájára).
- Élő referenciaállapot: 3183 település (3178 aktív), 3 fiktív vonal, 5 fiktív kapcsolat.

## 1. Kiadási és cutover-egységek — mindegyik külön rollback/cutover egység

| # | Egység | Séma? | Kliens? | Visszavonás |
|---|---|---|---|---|
| 1 | **Backend v2.0.4 + V007** (páronkénti routing, SUPER_ADMIN preview/apply) | igen, V007 (előre-migráció, üres tábla) | nem | §5/A |
| 2 | **Adminisztrátori self-claim backend** (PR #51; ugyanabban a jarban, de *külön változás*) | nem | — | csak a jar visszaállításával (§5/A) |
| 3 | **Service Android APK** — a self-claim gombot jeleníti meg | nem | **igen, új APK** | APK-terjesztés visszavonása; a backend enélkül is elfogadja a claimet |
| 4 | **Referenciaimport** (146 ellenőrzött pár) | nem | nem | §5/C (restore) |
| 5 | **Pair-level territory apply** (146 hozzárendelés) | nem | nem | §5/B (null-apply) |

Kliens-határ: a **vasúti routinghoz nem kell új Public kliens** — a jelenlegi Public web (ellenőrizve:
`web/public-web/src/domain/lineDecision.ts`) és a Public Android (a KSH fallback fázisban ellenőrizve) már
támogatja az opcionális vonalválasztást (`requires-choice`, explicit „nem tudom" → `railwayLineId: null`). Az
adminisztrátori self-claim használatához viszont **új Service APK kell**; a backend telepítése és az APK
terjesztése külön cutover- és rollback-egység, nem egymás előfeltétele.

## 2. Kapuk

1. Teljes CI zöld a **pontos** release SHA-n (backend, Android, Web, validate, caddy, backup-restore). Rögzítsd: `RELEASE_SHA`.
2. `git checkout $RELEASE_SHA`, a jar ebből épül (`./gradlew clean bootJar`), sosem piszkos fából.
3. Friss production backup és **sikeres** disposable restore drill:
   ```bash
   cd /home/opc/apps/orszem-v2 && ./backup.sh backup && ./backup.sh drill
   ```
   A drill kimenetében a táblák egyezzenek; a V007 után a `service_area_settlement_lines` is szerepeljen.
4. Az előző jar megőrzése: `cp run/backend.jar run/backend.pre-v2.0.4.jar`.
5. **Rollback-előpróba — MEGTÖRTÉNT izolált környezetben (2026-09-25), lásd §5.** Az éles restore drillen a
   kapu megismétlendő ugyanazzal a próbával (régi jar a V007-es drill-adatbázison), mielőtt az éles jar cserélődik.

## 3. Sorrend

1. **v2.0.4 backend** (V007 lefut induláskor). Ellenőrzés: `docker compose ... ps` healthy;
   `select version, success from flyway_schema_history order by installed_rank desc limit 1;` → `007 | t`;
   `select count(*) from service_area_settlement_lines;` → `0`. Public/Service smoke (bejelentés, claim).
2. **Referenciaadat validate + diff** a szerveren, a promótált csomagra (a csomag lokálisan generálódik és kerül a
   szerverre a `datasets/` alá, sosem Gitbe):
   ```bash
   python3 scripts/promote-osm-railway-reference.py --review-dir reference-data/osm-review --out <tmp> --expect-policy-version 2026-09-23.1
   ./admin.sh reference-validate /datasets/<dir>
   ./admin.sh reference-diff /datasets/<dir>
   ```
   **Elvárt diff** (izolált próbán már megvan): `settlements +0 new 3178 upserted 0 deactivated`;
   `railway lines +38 new … (3 preserved)`; `relations +146 add -0 remove (5 preserved)`. Bármely törlés/
   deaktiválás → STOP.
3. **`reference-import`** a 146 párra. Utána `reference-diff` → `(no changes)`. Az import **nem** rendel területet:
   a párok routingja addig `UNCLASSIFIED` marad.
4. **Területi preview** (nem ír): a `apply-batch-payload.json` POST a `…/settlement-line-mappings/preview`-ra
   SUPER_ADMIN tokennel → `applied:false`, `changedCount:146`, és minden `targetServiceAreaId` a 8 aktív terület egyike.
5. **Külön owner jóváhagyás** után `…/settlement-line-mappings/apply` (atomikus, auditált, `expectedCurrent=null`).
   Ellenőrzés: `select a.name, count(*) from service_area_settlement_lines m join service_areas a on a.id=m.service_area_id group by 1;`
   → Budapest 2, Debrecen 39, Miskolc 18, Pécs 19, Szeged 17, Szombathely 11, Székesfehérvár 40 (GYSEV 0).
6. **Smoke + jogosultság:** (a) Tata + 1. vonal → `ROUTED` Székesfehérvár; (b) Tata vonalválasztás nélkül →
   `UNCLASSIFIED`; (c) reláció nélküli település → `UNCLASSIFIED`; (d) egy területi SERVICE_USER nem látja a
   más területre routolt jelentést; (e) az UNCLASSIFIED országos pool változatlanul működik; (f) a régi
   jelentések snapshotja változatlan. A tesztjelentéseket a végén a szokásos módon zárd le.

## 4. Amit sosem szabad

A 8 élő ServiceArea törlése, átnevezése, inaktiválása; a 3 INACTIVE terület módosítása; új terület owner
jóváhagyás nélkül; a `flyway_schema_history` szerkesztése; V007 módosítása; teljes-vonalas hozzárendelés;
`reference-import` a szerverre másolt, nem a promóterből származó adaton.

## 5. Rollback

### Bizonyíték: a v2.0.3 jar V007 után (izolált fixture)

Az ismert production referenciaállapotot reprodukáló izolált fixture (nem production backupból: az `evaluation` +
`cleared` import adja a 3183 településes / 3 vonalas / 5 kapcsolatos állapotot). Menet: v2.0.3 jar → alapállapot
(Flyway 001–006) → **main jar**: V007 lefut, import (41 vonal / 151 kapcsolat), 146 pár apply, egy ROUTED
jelentés → leállítás → **ugyanarra az adatbázisra a v2.0.3 jar**.

A használt v2.0.3 jar a `v2.0.3` címkéből (`c899ae3`) épült, SHA-256
`524b4594846c310901cfe1151e01c7dbb28a13a47dee829d3609bff370660600` — **azonos** a productionbe másolt jar
ellenőrzött checksumával. A main jar SHA-256: `88573d110f6684559546e8b4e6796254d7b5dda12fdf02d15a663e9dcc419aa5`
(a `be4dfb1` main build; a végső release jar checksumát a release SHA-ról újra rögzíteni kell).

| Ellenőrzés | Eredmény a v2.0.3 jarral a V007-es adatbázison |
|---|---|
| Elindul-e | **igen** (15,6 s) |
| Flyway validáció | `Successfully validated 7 migrations`; `Schema "public" has a version (007) that is newer than the latest available migration (006)` **csak WARN**, `No migration necessary`; `validate-on-migrate: true` **változatlan** |
| Health | `UP` (liveness + readiness) |
| Public API | település-keresés (Tata) és bejelentés (201) működik |
| Service API | SUPER_ADMIN login OK; a NEW sor kilistázza a V007 alatt routolt jelentést is |
| Régi snapshot | változatlan: `ROUTED → Székesfehérvár` |
| Routing a régi jarral | pair-módú vonal (Tata + 1) → `UNCLASSIFIED / RAILWAY_LINE_UNASSIGNED`; vonalválasztás nélkül `RAILWAY_LINE_NOT_SELECTED`; reláció nélkül `NO_VERIFIED_RAILWAY_LINE_REFERENCE` |
| Adat | a 146 pair-mapping érintetlen (`service_area_settlement_lines` = 146) |
| Vissza a main jarra | health UP, Tata + 1 → újra `ROUTED → Székesfehérvár` |

Következtetés: **a backend-only jar rollback V007 után is használható**; a Flyway-validációt nem kellett lazítani,
`flyway_schema_history`-hoz és a V007-hez nem nyúltunk. A rollback alatt a pair-módú vonalak jelentései
`UNCLASSIFIED`-ba esnek, ezért **a rollback biztonsága feltételezi, hogy az
`ORSZEM_UNCLASSIFIED_SERVICE_USER_ACCESS_ENABLED=true` be van kapcsolva** (különben csak MODERATOR/SUPER_ADMIN látja
ezeket). Ezt a release előtt ellenőrizni kell a futó compose-ban
(`docker compose ... config | grep UNCLASSIFIED`). Korlát: a próba fixture, nem production backup; a production
restore drillen ugyanezt meg kell ismételni.

### A. Csak jar (V007 után is)
Az előző jar visszaállítása (`run/backend.pre-v2.0.4.jar` → `run/backend.jar`) és
`docker compose --env-file .env up -d --force-recreate backend`. Traffic cutover: a backend egyetlen konténer, a
recreate alatti pár másodperces kiesés a szokásos; adatvesztés nincs, mert a régi jar nem ír vissza sémát, és a
Public kliens a saját `clientSubmissionId`-jával idempotensen újrapróbálkozhat. Az adminisztrátori self-claim
ilyenkor visszavonódik (a v2.0.3 az admin claimet 403-mal elutasítja).

### B. Csak a területi hozzárendelés
Az `apply` ugyanazzal a payloaddal, `targetServiceAreaId:null` és `expectedCurrentServiceAreaId:<terület>`: a párok
visszakerülnek `UNCLASSIFIED`-ba. A meglévő jelentések snapshotja nem változik.

### C. Az import (146 pár) visszavonása
Ellenőrzött pre-release backupból **friss** adatbázisba történő restore a szokásos V2 restore-eljárással; sosem
helyben felülírva. Az utolsó backup óta érkezett jelentések elvesznének, ezért csak akkor, ha az import
adatminősége kérdéses; egyébként a B lépés és a jar-rollback elég.

Sosem: `flyway_schema_history` kézi módosítása, a V007 törlése/módosítása, a Flyway-validáció lazítása.
