# v2.0.5 — release engineering report

Állapot: **előkészítés, owner review-ra vár.** Nincs tag, nincs GitHub Release, nincs merge, nincs deploy, nem
történt production SSH, referenciaimport vagy territory apply. Kiadási jegyzet:
`docs/releases/RELEASE_NOTES_2.0.5.md`.

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

## 7. Nem történt

- Nem készült tag, GitHub Release; a `v2.0.4` címke és a draft Release (id 396517433) érintetlen.
- Nem történt merge, deploy, production SSH, referenciaimport, territory apply, ServiceArea-módosítás.
- Nem használtam `reference-data/local-research/`-t vagy PENDING (VPE/KTI/GYSEV) adatot; nincs GYSEV-hozzárendelés.
- Az aláíratlan APK-t nem terjesztettem; kulcsot vagy credentialt nem kezeltem.
- A `.claude/`, `dump*.xml`, `sample.py`, `work/` nem része a commitoknak.
