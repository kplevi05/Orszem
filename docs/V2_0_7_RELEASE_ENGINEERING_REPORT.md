# v2.0.7 — release engineering report

Állapot: **kiadva és productionben ellenőrizve.** A release merge commit
`034eb0a35ca0afb68932f6cd14f62b0edced0e34` (PR #63), az annotált tag `v2.0.7`. A részletes, végrehajtás utáni
production és artifact-bizonyítékot a §8 tartalmazza. Kiadási jegyzet: `docs/releases/RELEASE_NOTES_2.0.7.md`.

## 1. Kiindulás és hatókör

- Release-alap: `main` `bf2267475afa3744f086e59d3d3e7953185871e0` (PR #62 merge).
- Előző publikus release: `v2.0.6` (`b73b486674a565d0e2c9487b6f658d3e77189537`), APK-only kiadás.
- Production backend: `v2.0.5`; terjesztett Service Android: `v2.0.6` (`versionCode 7`).
- Funkcionális diff: kizárólag a PR #62 hierarchikus felhasználói becenév-funkciója és annak V008 migrációja.
- A független review a `17fc76ba272b84da40098f6bb1d8ac34b6cfdd96` feature HEAD-et vizsgálta; a review során
  talált PostgreSQL NUL/árva-surrogate validációs hibát regressziós tesztekkel javították.

## 2. Verziózás

| Komponens | Változás |
|---|---|
| Backend | 2.0.6 → 2.0.7 a forrásban; production ténylegesen 2.0.5 → 2.0.7 |
| Public Android | 2.0.6 → 2.0.7, versionCode 7 → 8 |
| Service Android | 2.0.6 → 2.0.7, versionCode 7 → 8 |
| Public Web | 2.0.6 → 2.0.7 |

Az egységes repóverzió nem jelent minden komponensre deploymentet. A kiadás production egységei a backend JAR
és a Service APK; Public Android és Public Web funkcionálisan változatlan.

## 3. Architektúra- és biztonsági eredmény

- A `service_id` marad az egyetlen kanonikus azonosító. A becenév külön nullable mező, nem kerül authorization-,
  ownership- vagy routing-döntésbe.
- A backend minden mutációt újraengedélyez a célfelhasználó aktuális, zárolt sora alapján; peer és magasabb rang
  nem módosítható. Saját becenevet kizárólag `SUPER_ADMIN` állíthat a saját végponton.
- A becenév normalizálása levágja a szélső whitespace-t, kódpontosan 64-re korlátoz, és 400-as validációs hibával
  elutasítja a PostgreSQL text/jsonb által nem tárolható NUL-t és árva UTF-16 surrogate-et.
- Audit csak tényleges változásra keletkezik, whitelistes old/new részletekkel; az azonos érték ismétlése idempotens.
- A report-, moderation- és auditcímkék batch feloldást használnak; új soronkénti adatbázis-lekérdezés nincs.
- A Service Android API-dekódere `ignoreUnknownKeys = true`, ezért a 2.0.6 kliens kompatibilis a 2.0.7 backend új
  nullable mezőivel. A 2.0.7 kliens kihelyezése mégis csak a backend után engedélyezett, mert új végpontokat hív.

## 4. Migráció és rollback-határ

`V008__user_nicknames.sql` additív, forward-only változás:

- nullable `users.nickname VARCHAR(64)`;
- `ck_users_nickname_length`: null vagy 1–64 PostgreSQL `char_length`;
- nincs backfill, nincs index, nincs unique constraint és nincs meglévő adatsor-módosítás.

Kiadási kapu a production backup disposable restore-ján:

1. v2.0.7 JAR indítása és V008 alkalmazása;
2. Flyway `008` success, minden meglévő nickname null, a kontroll-darabszámok változatlanok;
3. alap API/login/nickname smoke;
4. v2.0.5 JAR indítása ugyanazon V008-as másolaton és alap API/login smoke;
5. visszaállás v2.0.7-re ugyanazon adatbázison.

Az adatbázis-migrációt nem vonjuk vissza. Normál alkalmazás-rollback a korábbi JAR visszaállítása; teljes DB restore
csak bizonyított adatvesztés vagy integritási probléma esetén.

## 5. Ellenőrzési mátrix

A draft PR #63 első remote HEAD-je `a8178f0c8b6a353f4f3c8ca34c379f0428522b2b`. Ezen a pontos SHA-n mind az
öt GitHub workflow sikeres. A release-változás verziómezőkre és dokumentációra korlátozódik; a funkcionális kód
azonos a PR #62 függetlenül ellenőrzött és merge-elt kódjával.

| Ellenőrzés | Követelmény | Release-eredmény |
|---|---|---|
| Backend clean build | PostgreSQL 16 Testcontainers, minden teszt zöld | **GitHub backend success**, run `38219041860` |
| Android unit/lint/debug/release | Public + Service, 0 hiba | **GitHub android success**, run `38219041810` |
| Service instrumented/Compose | alapértelmezett AVD, minden teszt zöld | feature HEAD-en **109/109**; release CI instrumented teszteket fordít, nem futtat |
| Public Web | typecheck, tesztek, build | **helyben 74/74 + typecheck/build**, GitHub web success, run `38219041826` |
| Reference/deploy config | minden validátor zöld | **success**, runs `38219041803` és `38219041883` |
| GitHub Actions | öt workflow a pontos release HEAD-en | **5/5 success** az `a8178f0c…` SHA-n |
| Backend JAR reprodukálhatóság | két tiszta build bájtra azonos, SHA-256 rögzítve | **sikeres** (`8be050e7a032e815617653ab4eb5ce644d01cd8cacacb1c51868a7536eaf1231`, 55 098 294 B) |
| Unsigned Service APK reprodukálhatóság | két tiszta build bájtra azonos, SHA-256 rögzítve | **sikeres** (`26f7f6858ae5c631dc36fa02d341121e28e57050cea60486951397375c63c121`, 2 112 971 B) |
| V008 restore drill + production ellenőrzés | teljes §4 sorrend és élő restore drill sikeres | **sikeres** (`orszem_v2_20261104T074553Z.dump`, 19/19 tábla bájtra azonos) |

A jelenlegi végrehajtási környezetből a Gradle 9.7.1 disztribúció hálózati korlátozás miatt nem volt letölthető,
és Docker sem érhető el; ezért itt backend/Android buildet vagy Testcontainers restore drillt nem állítunk
lokálisan lefuttatottnak. A Public Web helyi futása és a teljes GitHub CI eredményei fent külön szerepelnek.

A végső owner-review egy rollout-dokumentációs hibát talált és javított: a `/api/v1/meta` az API-szerződés
verzióját (`v1`) adja vissza, nem a termék buildverzióját. A production kapu ezért a futó JAR rögzített SHA-256
értékét hasonlítja a jóváhagyott v2.0.7 release-artifacthoz; a meta végpont külön elérhetőségi ellenőrzés marad.

## 6. Release- és rollout-határok

- A release PR merge-je önmagában nem hoz létre taget vagy Release-t, nem futtat migrációt és nem deployol.
- A `v2.0.7` annotált tag és GitHub Release külön owner gate.
- GitHub Release-be csak ellenőrzött backend JAR, owner-aláírt Service APK és `SHA256SUMS.txt` kerülhet. Unsigned
  APK, `.idsig`, keystore, properties, jelszó vagy tesztartifact nem publikálható.
- Production rollout külön owner gate, friss backup + restore drill után, backend-first sorrendben.
- Reference import, territory apply, Public APK és Public Web deployment nem része ennek a kiadásnak.

## 7. Fennmaradó, elfogadott kockázatok

A feature engineering reportjában dokumentált négy nem blokkoló kockázat változatlan: az actor egyidejű
lefokozásának rövid ablaka; az audit `actorServiceId` mezőjének display-label tartalma; tetszőleges megjelenítési
Unicode; és a hiányzó `nickname` request mező null/törlés jelentése. Ezeket az owner a PR #62 review-ready kapujánál
elfogadta.

## 8. Végrehajtás utáni kiadási és production bizonyíték

### 8.1. Merge, tag és reprodukálható artifactok

- A #63 PR merge commitja a `main` ágon: `034eb0a35ca0afb68932f6cd14f62b0edced0e34`, amelyre az annotált `v2.0.7`
  tag mutat.
- Helyi ellenőrzés a `034eb0a3…` commiton: Public Web 74/74 unit teszt, typecheck és production build sikeres;
  Android unit tesztek (Service + Public), debug és release build sikeres; Backend Kotlin + tesztforrás fordítás és
  `bootJar` sikeres.
- Artifact ujjlenyomatok (`work/release-2.0.7/`):
  - `backend-2.0.7.jar` (55 098 294 B): `8be050e7a032e815617653ab4eb5ce644d01cd8cacacb1c51868a7536eaf1231`
  - `service-app-release-unsigned.apk` (2 112 971 B): `26f7f6858ae5c631dc36fa02d341121e28e57050cea60486951397375c63c121`
  - `service-app-release-2.0.7-SIGNED.apk` (2 134 438 B): `f398ffc7c2234eb056f2504ce7b3cdfda1fe0f975c6d10245d9d7d141557c853`
- Az aláírt APK `apksigner verify --verbose --print-certs` ellenőrzése sikeres (v2 + v3 scheme, 1 signer, RSA-4096,
  certificate SHA-256: `e62ade239e0265f5ddc15e3018135ad0a5930d950cbf7390b55d1315d7b303cf`).
- A publikálható csomag a `work/release-2.0.7/release-assets/` mappában áll rendelkezésre (`backend-2.0.7.jar`,
  `service-app-release-2.0.7-SIGNED.apk`, `SHA256SUMS.txt`).

### 8.2. Production backend, migráció és restore drill

- A production szerveren (`run/backend.jar` és a konténerben `/app/backend.jar`) a futó JAR SHA-256 értéke
  `8be050e7a032e815617653ab4eb5ce644d01cd8cacacb1c51868a7536eaf1231` (bájtra azonos a tiszta lokális builddel);
  a megőrzött rollback artifact `run/backend.pre-v2.0.7.jar` (`ef79845e1267fcca3e72291bd1fd1066135525f9e98173e43427ca6ff1d2aedb`, v2.0.5).
- A `flyway_schema_history` legutolsó sora: `008 | user nicknames | success = true` (`2026-10-24 10:32:23.267006`).
- Az élő production adatbázisról készült mentés és eldobható konténeres restore drill
  (`/home/opc/backups/v2/orszem_v2_20261104T074553Z.dump`, 304 374 B, SHA-256
  `be18b4cabeb2309a537a78a74e17bc82d01f7ee96c9602e4ffaaaaf6127e19a5`) mind a 19 táblán bájtra azonos tartalmat
  igazolt (`audit_events: 634`, `auth_sessions: 133`, `flyway_schema_history: 8`, `railway_lines: 41`,
  `reference_dataset_imports: 5`, `refresh_tokens: 396`, `report_assignments: 74`, `report_categories: 7`,
  `report_event_types: 61`, `report_moderation_episodes: 24`, `report_routing_snapshots: 85`, `reports: 85`,
  `service_area_railway_lines: 0`, `service_area_settlement_lines: 146`, `service_areas: 11`,
  `settlement_railway_lines: 151`, `settlements: 3183`, `user_service_areas: 18`, `users: 31`).
- A nyilvános `/api/v1/meta` végpont `200 OK` választ ad (`{"name":"orszem-backend","apiVersion":"v1"}`).
