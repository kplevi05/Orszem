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

## 1. Kiadás tartalma — külön változásként

| # | Változás | Séma? | Kliens? |
|---|---|---|---|
| A | V007 `service_area_settlement_lines` (üres) + páronkénti routing + SUPER_ADMIN preview/apply | **igen (V007, előre-migráció)** | nem |
| B | Adminisztrátori self-claim (MODERATOR/SUPER_ADMIN) | nem | **Service APK** (a Claim gomb) |
| C | 146 ellenőrzött vasúti kapcsolat (adat, `reference-import`) | nem | nem |
| D | Vármegyealapú területi hozzárendelés (adat, pair-level apply) | nem | nem |

A C és a D nem a kiadás része, hanem külön, sorrendben végrehajtott üzemeltetési lépés.

## 2. Kapuk

1. Teljes CI zöld a **pontos** release SHA-n (backend, Android, Web, validate, caddy, backup-restore). Rögzítsd: `RELEASE_SHA`.
2. `git checkout $RELEASE_SHA`, a jar ebből épül (`./gradlew clean bootJar`), sosem piszkos fából.
3. Friss production backup és **sikeres** disposable restore drill:
   ```bash
   cd /home/opc/apps/orszem-v2 && ./backup.sh backup && ./backup.sh drill
   ```
   A drill kimenetében a táblák egyezzenek; a V007 után a `service_area_settlement_lines` is szerepeljen.
4. Az előző jar megőrzése: `cp run/backend.jar run/backend.pre-v2.0.4.jar`.
5. **Rollback-előpróba (kötelező, a restore drill DB-n):** indítsd a v2.0.3 jart a V007-tel migrált drill
   adatbázison. Elvárás: elindul (Flyway a „jövőbeli" alkalmazott migrációt figyelmen kívül hagyja), és nem szól
   bele a routingba. Ezt itt még **nem** ellenőriztem — feltevés, az első kapu lépése, hogy bebizonyosodjon.

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

- **Csak jar (V007 után is):** az előző jar visszaállítása és `docker compose ... up -d --force-recreate backend`.
  A V007 tábla és a benne lévő párok az régi jarnak ismeretlenek; a pair-módú vonalak a régi routingban
  „hozzárendeletlen" → `UNCLASSIFIED` (biztonságos). A §2/5. előpróba igazolja.
- **Csak a területi hozzárendelés:** az `apply` ugyanazzal a payloaddal, `targetServiceAreaId:null`,
  `expectedCurrentServiceAreaId:<terület>` — a párok visszakerülnek `UNCLASSIFIED`-ba. A meglévő jelentések
  snapshotja nem változik.
- **Az import (146 pár):** egy ellenőrzött backupból friss adatbázisba történő restore (a szokásos V2 restore-
  eljárás). Sosem szabad élőben, helyben felülírni az adatbázist.
- **Adminisztrátori self-claim (B):** funkció-visszavonás csak a jar visszaállításával lehetséges.
