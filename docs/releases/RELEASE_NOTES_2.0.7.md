# Őrszem V2 — 2.0.7 kiadási jegyzet

**Állapot: release-előkészítés.** Nincs `v2.0.7` tag, GitHub Release, aláírt APK vagy production-módosítás.

Alap: `main` `bf2267475afa3744f086e59d3d3e7953185871e0`, a #62 merge után. Előző publikus kiadás:
`v2.0.6`. A production backend jelenleg `v2.0.5`; a terjesztett Service Android kliens `v2.0.6`.

## Változás

A kiadás opcionális, nem egyedi becenevet ad a szolgálati felhasználókhoz úgy, hogy a kanonikus azonosító
továbbra is az `SZ-xxxxxx` marad. A megjelenítési forma például `SZ-408468(Levente)`.

- `SUPER_ADMIN` a saját profilján beállíthatja vagy törölheti a saját becenevét.
- Más felhasználó beceneve csak a meglévő rang- és területi hierarchia szerint módosítható; peer vagy magasabb
  rangú felhasználó nem módosítható.
- `MODERATOR` csak a hatókörébe tartozó `SERVICE_USER` becenevét módosíthatja.
- A becenév opcionális, nem egyedi, szélső whitespace nélkül legfeljebb 64 Unicode code point; üres érték törli.
- A tényleges változás auditált, az idempotens ismétlés nem hoz létre új auditbejegyzést.
- A becenév egységesen megjelenik a profil-, felhasználókezelési, riport-, assignment-, moderációs és
  auditfelületeken, de jogosultságra, tulajdonra és szűrési azonosságra továbbra is kizárólag a `service_id`
  szolgál.

## Verziók és rollout-egységek

| Komponens | Verzió | Production teendő |
|---|---:|---|
| Backend | 2.0.5 → 2.0.7 | új JAR, induláskor Flyway V008 |
| Service Android | 2.0.6 (7) → 2.0.7 (8) | új, azonos kulccsal aláírt APK |
| Public Android | 2.0.6 (7) → 2.0.7 (8) | egységes repóverzió; új Public APK nem szükséges |
| Public Web | 2.0.6 → 2.0.7 | egységes repóverzió; funkcionális változás és web-deploy nincs |

## Kompatibilitás és kötelező sorrend

1. **Backend először.** A V008 nullable `users.nickname` oszlopot és 1–64 karakteres adatbázis-korlátot ad hozzá;
   meglévő sor értékét nem módosítja. A Service 2.0.6 az új nullable válaszmezőket figyelmen kívül hagyja.
2. **Service APK másodszor.** A 2.0.7 kliens csak a 2.0.7 backend kihelyezése és smoke tesztje után terjeszthető.
   A régi backend nem ismeri a nickname végpontokat, ezért fordított rollout nem támogatott.
3. Public Android- vagy Public Web-kihelyezés ehhez a funkcióhoz nem szükséges.

## Kiadási kapuk

1. A release PR pontos végső HEAD-jén minden GitHub workflow legyen zöld.
2. Tiszta, pontos release SHA-ról teljes backend build fusson valódi PostgreSQL 16 Testcontainers környezetben.
3. A V008 migrációt disposable production-restore másolaton kell lefuttatni, majd ellenőrizni kell:
   `008` sikeres; a meglévő felhasználók `nickname IS NULL`; sor- és referenciaadat-darabszám nem változott.
4. Ugyanezen V008-as másolaton a jelenlegi production v2.0.5 JAR rollback-indítása és alap API/login smoke legyen
   sikeres. A V008-at tilos visszafelé módosítani vagy törölni.
5. A backend JAR kétszeri tiszta buildből legyen reprodukálható; SHA-256 rögzítendő.
6. A Service Android unit-, lint-, debug/release- és instrumented/Compose tesztje legyen zöld.
7. A Service release APK-t a korábbi production kiadásokkal azonos kulccsal kell aláírni. Kötelező signer-,
   package-, `versionName=2.0.7`, `versionCode=8` és checksum-ellenőrzés.
8. `adb install -r` frissítési próba szükséges a kiadott Service 2.0.6 fölé, uninstall és adatvesztés nélkül.
9. Production rollout csak külön tulajdonosi jóváhagyás után történhet a
   `docs/deployment/V2_0_7_USER_NICKNAMES_ROLLOUT.md` szerint.

## Visszavonás

- **Backend:** a megőrzött v2.0.5 JAR visszaállítható, ha a release előtti restore drill ezt igazolta. A nullable
  V008 oszlop a helyén marad; a Flyway history kézi módosítása tilos.
- **Service Android:** Android nem enged alacsonyabb `versionCode`-ot telepíteni. Visszavonáskor a terjesztést
  le kell állítani; javított kliens csak magasabb `versionCode`-dal adható ki.
- A release előtti adatbázis-backup kizárólag adatvesztés vagy integritási probléma esetén használható; normál
  alkalmazás-rollbackhez nem kell adatbázist visszaállítani.

Részletes implementációs bizonyíték: `docs/USER_NICKNAMES_ENGINEERING_REPORT.md`.
