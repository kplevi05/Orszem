# v2.0.5 — release engineering report

Állapot: **kiadva és productionben ellenőrizve 2026-10-02-án.** A release commit
`7db16c7c729f3588d7fecc95cd5625e464c5bea1`, az annotált tag `v2.0.5`, a publikus GitHub Release:
<https://github.com/kplevi05/Orszem/releases/tag/v2.0.5>. A részletes, végrehajtás utáni bizonyítékot a §8
tartalmazza. Kiadási jegyzet: `docs/releases/RELEASE_NOTES_2.0.5.md`.

## 1. Kiindulás

- Alap: `origin/main` = `a3527bc0d4c7da7de54ea0abd9775c75b6af4d1e` (a #56 merge-commitja; a #56 pinned head-je
  `0720b2d48b901c81958590f2371eec100ba3140c`). A fa tiszta volt, a merge-commiton mind a 6 GitHub workflow zöld.
- Branch: `release/v2.0.5`, kizárólag a fenti commitról leágazva. Előző kiadás: `v2.0.4` (a címke és a draft Release
  érintetlen).

## 2. Verziózási konvenció (v2.0.4 szerint) és a változtatások

Komponensenként külön `release:` commit, utána dokumentáció:

| Komponens | Változás |
|---|---|
| Backend (`backend/build.gradle.kts`) | `2.0.4` → `2.0.5` (`8a20b81`) |
| Public Android | versionName `2.0.5`, versionCode 5 → 6 (`6b5394f`) |
| Service Android | versionName `2.0.5`, versionCode 5 → 6 (`c2b7b32`) |
| Public Web | `package.json` `2.0.5` (`f0b623e`), lockfile (`54d35c5`) |

A kód a verziócommitok óta nem változott; a későbbi commitok kizárólag dokumentációt adnak.

## 3. Artifactok (reprodukálhatóság: mindegyik kétszer, tiszta buildből, azonos checksummal)

| Artifact | SHA-256 |
|---|---|
| `backend-2.0.5.jar` (55 081 575 B) | `ef79845e1267fcca3e72291bd1fd1066135525f9e98173e43427ca6ff1d2aedb` |
| `service-app-release-unsigned.apk` (2 107 859 B; `hu.orszembejelento.service` 2.0.5, versionCode 6) | `7a7375ade8797292ad319fe581d77671a7d49a4ad2c8c6c08b258424c3aea075` |

- Két független, tiszta (új git worktree, `clean bootJar` / `:service-app:clean :service-app:assembleRelease`)
  buildből, a `54d35c521eba59c6906874f726335b0763eba8a2` forrásfából: a két futás jarja és APK-ja bájtra azonos.
  Az aláíratlan állapotot és a versionCode/versionName értékeket ellenőriztem az APK-n.
- A bootJar kimenete `backend.jar`; a terjesztési név `backend-2.0.5.jar`.
- Az artifactok a lokális, Gitből kizárt `work/release-2.0.5/` mappában vannak
  (`SHA256SUMS-build-provenance.txt`); a szerverre nem másoltam és sehová nem töltöttem fel őket.

### 3/A. APK-aláírási kapu (kötelező a terjesztés előtt)

- Az **aláíratlan APK nem terjeszthető**, és nem kerül Release-assetként feltöltésre; checksumja nem helyettesíti az
  aláírt terjesztési artifact checksumát.
- Aláírás a **korábbi production kiadásokkal azonos kulccsal** (alias `orszem-v2`), a tulajdonos gépén; kulcsot vagy
  jelszót nem kezeltem és nem kértem.
- Aláírás után kötelező: `apksigner verify --verbose --print-certs <aláírt.apk>`; az aláíró certificate SHA-256
  ujjlenyomata egyezzen a telepített Service alkalmazáséval
  (`E6:2A:DE:23:9E:02:65:F5:DD:C1:5E:30:18:13:5A:D0:A5:93:0D:95:0C:BF:73:90:B5:5D:13:15:D7:B3:03:CF`); az
  **aláírt** APK saját SHA-256-ját külön rögzíteni kell.

A kapu a kiadáskor teljesült. A terjesztett `service-app-release-2.0.5-SIGNED.apk` mérete 2 126 246 B,
SHA-256 értéke `b36d70a67d49488624023704b0d5f83e156bf0260bc0521107dc2709dc85ecd6`. Az APK v2/v3 aláírása
érvényes, egyetlen signerrel; a certificate SHA-256 a fent rögzített production ujjlenyomat. A csomag
`hu.orszembejelento.service`, `versionName=2.0.5`, `versionCode=6`.

### 3/B. Post-merge / tag reproducibility gate (kötelező Release és deploy előtt)

A merge és a `v2.0.5` tag létrehozása után, **a Release publikálása és bármilyen deploy előtt**:

1. A **pontos `v2.0.5` tagből** egy **tiszta, új worktree** készül (`git worktree add <út> v2.0.5`; a tag által
   mutatott commit SHA-ját rögzíteni kell, és a `git status` tiszta, módosítatlan legyen).
2. Ebből újra kell építeni a backend JAR-t (`clean bootJar`, a kimenet `backend.jar` → `backend-2.0.5.jar`) és az
   aláíratlan Service APK-t (`:service-app:clean :service-app:assembleRelease`).
3. A két új SHA-256-nak **bájtra egyeznie kell** a §3 előkészítéskor rögzített értékekkel:
   - `backend-2.0.5.jar`: `ef79845e1267fcca3e72291bd1fd1066135525f9e98173e43427ca6ff1d2aedb`
   - `service-app-release-unsigned.apk`: `7a7375ade8797292ad319fe581d77671a7d49a4ad2c8c6c08b258424c3aea075`
4. **Bármilyen eltérés esetén STOP:** nincs GitHub Release, nincs feltöltés, nincs deploy, nincs aláírás, amíg az
   eltérés oka nincs feltárva és a tulajdonos újra nem hagyta jóvá. Az előkészített artifactokat ilyenkor nem
   szabad „megmenteni” (átnevezés, újraépítés addig, míg egyezik).
5. Az eredményt (tag SHA, két új checksum, egyezés igen/nem) a tulajdonos számára rögzíteni kell, mielőtt a Release
   vagy a rollout következik.

Megjegyzés: a tag a squash/merge után a `main` egy másik commitját is mutathatja, mint a `5ee9aa6`; ez nem baj,
amíg a futtatott forráskód azonos (a két docs commit kódot nem érint). A bájtazonosságot a build ténylegesen
bizonyítja — nem a commit SHA egyezése.

### 3/C. Public Android

A verzió csak az egységes verziózás miatt emelkedett; **új Public APK nem szükséges** és nem készült. A Public Web
kódja nem változott.

## 4. Tartalom (`git diff v2.0.4 a3527bc` elemzése)

**Futtatott kódot érintő változás (ez kerül a jarba/APK-ba):**

- Backend: 5 `areaadmin` fájl (`AreaAdminController`, `AreaAdminDtos`, `AreaAdminQueryUseCase`, `AreaAdminTypes`,
  `JdbcAreaAdminQueryRepository`) — **csak olvasó read model**: soronkénti `assignmentMode`
  (`UNASSIGNED`/`WHOLE_LINE`/`PER_SETTLEMENT`) és `settlementMappingCount`, `PER_SETTLEMENT` szűrő, új SUPER_ADMIN
  végpont a vonal települési leképezéseihez. **Nincs Flyway migráció**, nincs írási/routing/authorization változás.
- Service Android: 7 main fájl (`ErrorCopy`, `AreaAdminApi`, `AreaAdminRepository`, új `RailwayLineAssignmentState`,
  `RailwayLinePickerScreen`, `RailwayLinePickerViewModel`, `strings.xml`). Új kliens → régi backend fallback
  (`PairLevelSupport`), `MIXED_ROUTING_MODES` lokalizálva, teljes-vonalas művelet tiltva páronkénti vonalon.
- Web: nincs változás.

**Nem futtatott kód (nem kerül artifactba):** a #54 referenciaadat-promóciós eszköz (megjelenítésinév-override),
döntésfájl, licencdokumentum, county-v2 előnézet-payloadok, `reference-data.yml` CI-workflow és tesztjei, valamint
dokumentáció (ADR 0011 addendum, a páronkénti admin és a vonalnév-munka riportjai). A 38 vonalnév a productionben már
a külön, owner által jóváhagyott név-only adatkiadással szerepel; a 146 hozzárendelés és a snapshotok érintetlenek.

## 5. Regresszió a release kódon (a verziócommitok után, a végső kóddal azonos fán)

| Terület | Eredmény |
|---|---|
| Backend `./gradlew build` cache nélkül (Testcontainers, valódi PostgreSQL) | **902/902 zöld** |
| Android `assembleDebug` (public + service) ×2, unit tesztek ×2, `lint` | zöld |
| Service instrumented (Compose) valós emulátoron (`orszem-test`, API 35, alapfelbontás) | **106/106 zöld** |
| Web `typecheck` + unit tesztek + `build` | zöld (74 teszt) |
| Python/referenciaadat tesztek | **75/75 zöld** |
| Referenciaadat-validátorok; a promotált dataset reprodukálása | valid; „preview is up to date” |
| GitHub CI a `a3527bc` merge-commiton | 6/6 workflow zöld |

**A #57 draft release PR tényleges CI-eredménye a pontos `5ee9aa663b9258db0e340036fadf027aad567cb5` HEAD-en**
(a GitHub Actions API alapján; mind sikeres):

| Workflow | `pull_request` | `push` |
|---|---|---|
| `backend` | success | success |
| `android` | success | success |
| `web` | success | success |
| `reference-data` | success | success |
| `deploy-config` | success | success |

Ez 10 workflow-futás és 12 check-run (egyes workflow-k több jobból állnak: `build`, `validate`, `caddy`,
`backup-restore-scripts`), mind `success`, hiba vagy kihagyott check nélkül. Ha később új commit kerül a PR-ra,
ez a bejegyzés az új HEAD CI-jére külön frissítendő.

## 6. Production rollout és rollback (rövid terv — végrehajtás csak külön owner jóváhagyással)

**Rollout (backend elöl, nincs migráció):**

0. **Előfeltétel:** a §3/B post-merge/tag reproducibility gate teljesült (egyező checksumok a tag tiszta worktree-jéből).
1. Friss adatbázis-backup + a megszokott ellenőrzés; a `ORSZEM_UNCLASSIFIED_SERVICE_USER_ACCESS_ENABLED=true`
   beállítás változatlan marad.
2. A `backend-2.0.5.jar` checksum-ellenőrzött kihelyezése új néven (`.next` staging), a futó
   v2.0.4 jar megőrzése (`backend.pre-v2.0.5.jar`, SHA-256 `2de7d7ff…3a03`); csak a backend konténer újraindítása.
3. Smoke: `/actuator/health`, Flyway verzió változatlanul `007`, SUPER_ADMIN vonallista `assignmentMode` mezőkkel
   (az 1-es sor `PER_SETTLEMENT`), a települési részletek végpont; Public routing/jogosultsági smoke változatlan;
   a 8 aktív + 3 INACTIVE ServiceArea és a 146 hozzárendelés számai változatlanok.
4. Utána a Service APK 2.0.5 (versionCode 6) — **csak owner-aláírt**, a §3/A kapuval, telepítés a meglévő appra.

**Rollback:**

- Backend: a megőrzött v2.0.4 jar visszaállítása és a konténer újraindítása. Mivel nincs migráció és a változások
  csak olvasók, **adatbázis-restore nem kell**. A régi Service kliens a v2.0.4 backenddel és az új backenddel is
  működik; az új kliens régi backenddel is (fallback) — a sorrend tehát nem kritikus a biztonságra.
- Service APK: Android nem engedi az alacsonyabb versionCode-ra frissítést; visszavonás = terjesztés leállítása,
  szükség esetén a v2.0.4 build újra aláírva magasabb versionCode-dal (külön döntés).

## 7. Az előkészítési fázisban nem történt

- Nem készült tag, GitHub Release; a `v2.0.4` címke és a draft Release (id 396517433) érintetlen.
- Nem történt merge, deploy, production SSH, referenciaimport, territory apply, ServiceArea-módosítás.
- Nem használtam `reference-data/local-research/`-t vagy PENDING (VPE/KTI/GYSEV) adatot; nincs GYSEV-hozzárendelés.
- Az aláíratlan APK-t nem terjesztettem; kulcsot vagy credentialt nem kezeltem.
- A `.claude/`, `dump*.xml`, `sample.py`, `work/` nem része a commitoknak.

Ez a szakasz az owner-review előtti állapotot rögzíti. A későbbi, jóváhagyott kiadás és production rollout
eredménye a következő szakaszban szerepel; referenciaimport és territory apply a v2.0.5 rollout részeként ott sem
történt.

## 8. Végrehajtás utáni kiadási és production bizonyíték (2026-10-02)

### 8.1. Merge, tag, artifactok és Release

- A #57 PR a jóváhagyott `72ff087e9f315f7002cf5f702d0b64d81f1e5beb` headről merge-committal került a
  `main` ágba. A merge commit `7db16c7c729f3588d7fecc95cd5625e464c5bea1`; mind az öt workflow sikeres volt rajta.
- Az annotált `v2.0.5` tag erre a merge commitra mutat. A tag tiszta worktree-jéből végrehajtott §3/B gate
  bájtra azonos artifactokat adott: backend JAR `ef79845e…aedb`, unsigned Service APK `7a7375ad…a075`.
- A Service APK a production kulccsal aláírva, `apksigner`-rel ellenőrizve és `adb install -r` frissítésként
  kipróbálva került a Release-be. Az Android megőrizte a csomagazonosságot és a `firstInstallTime` értéket.
- A GitHub Release publikus, nem draft és nem prerelease. Assetjei: `backend-2.0.5.jar`,
  `service-app-release-2.0.5-SIGNED.apk`, `SHA256SUMS.txt`. A publikus URL-ekről visszatöltve a
  `sha256sum -c` mindkét artifactra sikeres volt, az APK aláírása változatlanul érvényes.

### 8.2. Production preflight, backup és rollout

- A rollout előtti backend v2.0.4 volt (`2de7d7ff…3a03`), Flyway `007`, a fallback flag `true`.
- A közvetlen rollback-backup:
  `/home/opc/backups/v2/orszem_v2_20261002T130017Z.dump`, SHA-256
  `6bf1151bf60bc79a855d58c0de5ab928dfbd0ebd34f35871ba96707693cfcad7`. Az eldobható restore drill
  mind a 19 táblán azonos tartalmat adott.
- A checksum-ellenőrzött v2.0.5 JAR került `run/backend.jar` alá; a v2.0.4 rollback artifact
  `run/backend.pre-v2.0.5.jar` néven megmaradt. Csak a backend konténer indult újra; a DB és az edge nem.
- A backend healthy, a boot log és `/api/v1/meta` szerint 2.0.5. A Flyway verzió maradt `007`, nem futott új
  migráció. A fallback flag maradt `true`; a nyilvános actuator/OpenAPI/Swagger útvonalak továbbra is 404-et adnak.
- A rollout előtti és utáni ujjlenyomat azonos: 146 pair-level hozzárendelés, 0 teljes-vonalas hozzárendelés,
  41 vonal, 151 település-vonal kapcsolat, 3183 település (3178 aktív), 5 referenciaimport, 8 ACTIVE és 3
  INACTIVE ServiceArea. Nem történt reference-import, territory apply vagy kézi adatírás.

### 8.3. Hitelesített backend smoke

- A SUPER_ADMIN vonallista mind a 41 sorban visszaadta az `assignmentMode` és `settlementMappingCount` mezőt:
  38 `PER_SETTLEMENT`, 3 `UNASSIGNED`, 0 `WHOLE_LINE`, az SQL-lel soronként egyezően.
- Az 1-es vonal `PER_SETTLEMENT`, egy beállított településsel és teljes-vonalas terület nélkül. Az
  `UNASSIGNED` szűrő nem tartalmaz pair-level vonalat; a három szűrő darabszáma egyezett az adatbázissal.
- A részletvégpont mind a 38 pair-level vonalnál egyezett az SQL-lel: településszám, település→ServiceArea pár,
  inaktív jelző és `truncated=false`. Ismeretlen azonosítóra 404 `RAILWAY_LINE_NOT_FOUND` érkezett.
- A csak olvasó ellenőrzés előtt és után a routing-, referencia- és területi ujjlenyomat, valamint a report/user
  darabszám változatlan volt. A bejelentkezés létrehozott egy sessiont, amelyet a smoke végén 204-es logout vont vissza.

### 8.4. Aláírt Service APK UI-smoke

- A release-aláírású Service 2.0.5 (versionCode 6) a production API-val, valódi emulátoron futott.
- A „Hozzárendelés nélküli” szűrőben csak a 900/901/902 fixture-vonal maradt; az 1-es vonal a
  „Településenként” szűrőben jelent meg „Településenként hozzárendelve” állapottal és 1 beállított településsel.
- Az 1-es vonal egészben nem volt hozzárendelhető. A lenyitott részlet Tata (Komárom-Esztergom) települést és a
  Székesfehérvár szolgálati területet mutatta, a részleges lefedettséget jelző felirattal. Nem jelent meg általános
  hibaüzenet.
- A smoke kizárólag navigációt, szűrést és részletnyitást végzett; sem hozzárendelés, sem más production adatírás
  nem történt. A session kijelentkezéssel zárult, az emulátor leállt.

### 8.5. Végső állapot és ismert következő munka

A v2.0.5 backend productionben fut, a Release publikált, az aláírt APK terjeszthető. A v2.0.4 draft Release
audit/rollback nyomként draft maradt. A ServiceArea-részlet régi, teljes-vonalas listája külön UI-javítást igényel:
a pair-level hozzárendelések mellett ne állítsa általánosan, hogy nincs vasútvonal a területhez rendelve.
