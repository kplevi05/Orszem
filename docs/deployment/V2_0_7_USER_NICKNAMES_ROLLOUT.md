# v2.0.7 — felhasználói becenevek production rollout és rollback

**Terv, nem végrehajtási bizonyíték.** Ez a dokumentum nem engedélyez deploymentet. A tag/Release és a production
rollout külön tulajdonosi jóváhagyást igényel.

## 1. Hatókör és sorrend

Két külön cutover-egység van:

1. backend v2.0.7 + Flyway V008;
2. Service Android 2.0.7 (`versionCode 8`).

Kötelező sorrend: **backend először, Service APK másodszor**. Public Web- és Public Android-deployment nincs.

## 2. Preflight kapuk

1. Rögzítsd a jóváhagyott `RELEASE_SHA`-t; a `v2.0.7` tag pontosan erre mutasson.
2. Mind az öt GitHub workflow legyen zöld ezen a SHA-n.
3. Tiszta checkoutból készítsd el kétszer a `backend.jar`-t és az unsigned Service APK-t; a párok legyenek bájtra
   azonosak, a SHA-256 értékeket rögzítsd.
4. Ellenőrizd a jelenlegi production állapotot: backend `/api/v1/meta`, health, aktuális migráció `007`, Service
   kliens `2.0.6 (7)`, és a fő táblák kontroll-darabszámai.
5. Készíts friss V2 backupot, ellenőrizd a checksumát, majd futtasd a disposable restore drillt:

   ```bash
   cd /home/opc/apps/orszem-v2
   ./backup.sh backup
   ./backup.sh drill
   ```

6. A restore másolaton hajtsd végre a migrációs/rollback próbát: v2.0.7 → V008 ellenőrzés → v2.0.5 JAR smoke →
   vissza v2.0.7. Bármely hiba: **STOP**, nincs production rollout.
7. Stage-eld az új JAR-t `run/backend.jar.next` néven; a futó fájlt ne írd felül a végső GO előtt.
8. Az aláírt APK signer certificate SHA-256 értéke pontosan
   `E6:2A:DE:23:9E:02:65:F5:DD:C1:5E:30:18:13:5A:D0:A5:93:0D:95:0C:BF:73:90:B5:5D:13:15:D7:B3:03:CF`
   legyen. Keystore vagy jelszó nem kerülhet Gitbe, logba vagy Release-assetbe.

## 3. Backend rollout

Tulajdonosi GO után:

```bash
cd /home/opc/apps/orszem-v2
cp run/backend.jar run/backend.pre-v2.0.7.jar
mv run/backend.jar.next run/backend.jar
docker compose --env-file .env config >/dev/null
docker compose --env-file .env up -d --force-recreate backend
docker compose --env-file .env ps
```

Kötelező ellenőrzés:

- backend healthy, `/api/v1/meta` 200 és verzió 2.0.7;
- `flyway_schema_history` legutolsó sora `008`, `success = true`;
- `users.nickname` oszlop létezik, minden korábbi sor kezdetben null;
- a kontrollált user/report/reference darabszámok változatlanok;
- régi Service 2.0.6 klienssel login, queue, report detail és user-list read-only smoke működik;
- nincs 5xx, stack trace, Flyway-hiba, token vagy credential a logokban.

## 4. Becenév-funkció smoke

Tesztcélokra kijelölt fiókokkal, a legkisebb szükséges írással:

1. `SUPER_ADMIN` saját profilján becenév beállítása, pontos `SZ-ID(Becenév)` megjelenítés, majd törlés;
2. `SUPER_ADMIN` egy alacsonyabb rangú tesztfelhasználón beállít és töröl egy becenevet;
3. peer `SUPER_ADMIN` módosítása 403;
4. `MODERATOR` saját beceneve 403, in-scope `SERVICE_USER` módosítása sikeres, out-of-scope cél nem módosul;
5. az auditban minden tényleges változás egyszer jelenik meg, az idempotens ismétlés nem ad új sort;
6. NUL/árva-surrogate 400 `VALIDATION_ERROR`, nincs adat- vagy auditváltozás;
7. a smoke végén minden tesztbecenév törölve, az auditnyom megmarad.

## 5. Service Android rollout

Csak a backend smoke sikeres lezárása után:

1. `apksigner verify --verbose --print-certs` sikeres, egy aláíró, a fingerprint a §2 szerinti;
2. package `hu.orszembejelento.service`, `versionName=2.0.7`, `versionCode=8`, HTTPS production base URL;
3. `adb install -r` próba a kiadott 2.0.6 fölé uninstall nélkül; a signer és az appadatok megmaradnak;
4. read-only UI-smoke, majd a §4 pozitív/negatív becenév-ellenőrzések a 2.0.7 klienssel;
5. csak ezután tölthető fel a signed APK és a `SHA256SUMS.txt` a `v2.0.7` GitHub Release-be, illetve terjeszthető.

## 6. Rollback

### Backend

Rollback-trigger: egészségtelen backend, Flyway-hiba, alap smoke hiba, váratlan 5xx/adateltérés vagy biztonsági
jelzés.

```bash
cd /home/opc/apps/orszem-v2
cp run/backend.pre-v2.0.7.jar run/backend.jar
docker compose --env-file .env up -d --force-recreate backend
docker compose --env-file .env ps
```

A V008 oszlop a helyén marad. Ne töröld vagy módosítsd a migrációt, constraintet vagy `flyway_schema_history`-t.
A v2.0.5 rollback JAR csak akkor használható, ha a preflight restore drill ezt a pontos utat sikeresen bizonyította.
Rollback után health, `/meta`, login és alap report workflow smoke kötelező.

### Service Android

Alacsonyabb `versionCode` nem telepíthető vissza normál frissítésként. Állítsd le a 2.0.7 terjesztését; ne kérj
uninstallt. Javítás csak új, magasabb `versionCode`-dal adható ki. A backend 2.0.7 a Service 2.0.6-tal kompatibilis,
ezért a régi kliens terjesztése a javított APK-ig folytatható.

### Adatbázis

Teljes backup-restore csak bizonyított adatvesztés vagy integritási probléma esetén, leállított írások mellett,
friss céladatbázisba történhet. Normál alkalmazás-rollbackhez nem szükséges.

## 7. Tiltott műveletek

- V008 szerkesztése, törlése vagy visszafelé migrálása;
- `flyway_schema_history` kézi módosítása;
- JAR vagy APK építése nem a jóváhagyott tag tiszta checkoutjából;
- unsigned APK terjesztése;
- Service 2.0.7 terjesztése a backend sikeres rolloutja előtt;
- production smoke során nem kijelölt felhasználók módosítása;
- tag, Release vagy deployment külön owner GO nélkül.
