# v2.0.6 — release engineering report

Állapot: **előkészítés, owner review-ra vár.** Nincs tag, GitHub Release, artifact-terjesztés, deploy vagy
production adatváltozás.

## 1. Kiindulás

- `origin/main`: `2e69bdb594d070a26f808e67dc67c1e36d9d7f52`.
- A commit tartalmazza a #60 ServiceArea UI-javítást és a #58 v2.0.5 production evidence dokumentációt.
- Mind az öt merge utáni workflow sikeres: backend, Android, Web, reference-data, deploy-config.
- Előző kiadás: publikus `v2.0.5`; production backend: v2.0.5.

## 2. Verziózás

| Komponens | Változás |
|---|---|
| Backend | 2.0.5 → 2.0.6 |
| Public Android | 2.0.5 → 2.0.6, versionCode 6 → 7 |
| Service Android | 2.0.5 → 2.0.6, versionCode 6 → 7 |
| Public Web | 2.0.5 → 2.0.6 |

Az egységes verzióemelés nem jelent minden komponensre külön deploymentet. A funkcionális változás csak a Service
Androidban van; új backend JAR, Public APK vagy Web deployment nem szükséges.

## 3. Funkcionális diff

- A szolgálatiterület-kártya külön sorban mutatja a teljes vonalak és a településenkénti hozzárendelések számát.
- A részletképernyő „Vasútvonal-hozzárendelések” szakasza külön mutatja a pair-level darabszámot és a
  teljes-vonalas hozzárendeléseket.
- A korábbi „Ehhez a területhez jelenleg nincs vasútvonal rendelve.” szöveg helyett a pontos
  „Nincs teljes vonal ehhez a területhez rendelve.” jelenik meg.
- Compose regressziós teszt bizonyítja, hogy pair-level mapping mellett a régi, félrevezető állítás nem jelenik meg.

Nincs backend-, séma-, routing-, jogosultsági vagy referenciaadat-változás.

## 4. Kötelező ellenőrzések

- Teljes GitHub CI a végső release HEAD-en.
- Service Android unit, lint, debug/release build és instrumented Compose suite valódi emulátoron.
- Az unsigned Service APK kétszeri, tiszta buildből bájtra azonos legyen; checksum rögzítendő.
- Owner-aláírás után signer-, package-, versionName/versionCode- és checksum-ellenőrzés.
- `adb install -r` frissítési próba a release-aláírású v2.0.5 fölé.
- UI-smoke alapértelmezett emulátorméreten: Budapestnél a pair-level darabszám látszik, a teljes-vonalas üres
  állapot pontos, és semmilyen adminisztratív írás nem történik.

## 5. Release-tartalom

A GitHub Release-be csak az ellenőrzött, owner-aláírt `service-app-release-2.0.6-SIGNED.apk` és a hozzá tartozó
`SHA256SUMS.txt` kerülhet. Unsigned APK, `.idsig`, keystore, properties, jelszó vagy tesztartifact nem tölthető fel.

## 6. Rollout és rollback

- Backend- vagy adatbázis-rollout nincs; a production backend v2.0.5 marad.
- Az APK a meglévő Service app fölé telepítendő; uninstall tilos, mert elveszítheti a lokális állapotot.
- Visszavonás: terjesztés leállítása. Android downgrade-et nem enged; javított buildhez magasabb `versionCode` kell.
- A v2.0.5 Release és artifactok rollback/audit alapként változatlanok maradnak.

## 7. Nem része

- Az alkalmazáson belüli updater (#59) külön feature.
- További OSM-candidate review, reference-import vagy territory apply.
- Production backend, adatbázis, ServiceArea vagy routing módosítása.

## 8. Ellenőrzési eredmények a pontos `0db54746d44e4fe09a4c99f21fa340cff6fb7546` HEAD-en

A #61 draft PR HEAD-je (`release/v2.0.6`); az ellenőrzések előtt a working tree tiszta volt (`git status --porcelain`: 0 sor),
a `HEAD` pontosan egyezett. A kód a verziócommitok óta nem változott; az ez a szakasz utáni commit kizárólag
dokumentáció.

| Ellenőrzés | Parancs / környezet | Eredmény |
|---|---|---|
| Service unit tesztek | `:service-app:testDebugUnitTest` | **198/198 zöld** (30 suite, 0 hiba, 0 kihagyott) |
| Lint | `:service-app:lint` | **0 error**; 7 warning-szintű jelzés: 6× `PluralsCandidate` (`strings.xml` darabszám-sztringek; a magyar nem különböztet többes számot) és 1× `ComposableNaming` (`NarrowWidthLayoutComposeTest`, androidTest) |
| Debug build | `:service-app:assembleDebug` | sikeres |
| Service instrumented/Compose suite | `:service-app:connectedDebugAndroidTest`, `orszem-test` AVD (API 35), **alapértelmezett 1080×2280, 420 dpi** | **107/107 zöld** (17 suite, 0 hiba, 0 kihagyott) |

Az instrumented suite az előző kiadás (106 teszt) óta eggyel bővült: a
`ServiceAreaAdminComposeTest.a_pair_mapped_area_shows_the_pair_count_without_claiming_that_no_railway_line_is_assigned`
regressziós teszt bizonyítja, hogy pair-level mapping mellett a régi, félrevezető állítás nem jelenik meg.

A hat `PluralsCandidate` jelzés darabszám-sztringekre vonatkozik. Ötnek a sztringje már a v2.0.5-ben is megvolt
(`area_card_line_count` — itt csak a szöveg változott —, `area_card_open_report_count`, `cd_line_details_toggle_count`,
`line_settlements_truncated`, `analytics_trend_bar_description`); egy új: `area_card_settlement_mapping_count`. A v2.0.5 lint-kimenetét
ezen a HEAD-en nem futtattam újra, ezért azt, hogy a régi sztringek akkor is jelezve voltak-e, nem állítom. Egyik sem hiba,
és a build nem bukik rajta.

## 9. Az unsigned Service release APK reprodukálhatósága

Két **külön, friss git worktree-ből** (mindkettő `0db54746…`, tiszta, `--no-build-cache`, `:service-app:assembleRelease`)
épített `service-app-release-unsigned.apk` **bájtra azonos** (`cmp`).

| Tulajdonság | Érték |
|---|---|
| Fájl | `service-app-release-unsigned.apk` |
| Fájlméret | **2 109 219 B** |
| **SHA-256** | `bea3335fd37700260a43c90cbf9ebf6332b9f0d47e526eb60629b4b24196f11c` |
| package | `hu.orszembejelento.service` |
| versionName / versionCode | **2.0.6 / 7** |
| Aláírás | **nincs** (`apksigner verify`: nincs `META-INF/MANIFEST.MF`, tehát aláíratlan) |

Az APK a Gitből kizárt `work/release-2.0.6/` mappában van (`SHA256SUMS-build-provenance.txt`). **Nem aláírt, nem
terjeszthető, nem lett feltöltve.** Az owner aláírása és az aláírt APK saját checksumja a terjesztés előtti külön kapu (§4).

## 10. Nem történt

Nem írtam alá APK-t, nem készült tag vagy GitHub Release, nem történt deploy, és nem nyúltam productionhöz (nincs SSH,
nincs API-hívás, nincs adatváltozás). A `v2.0.5` tag és Release érintetlen.
