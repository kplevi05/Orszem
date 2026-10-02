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
