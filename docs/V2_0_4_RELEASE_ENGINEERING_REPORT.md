# v2.0.4 — release engineering report

Előkészítés, **nincs tag, GitHub Release, deploy, production-hozzáférés, import vagy apply.**

## 1. Kiindulás

- `main` = `f86321a3a2a270ad1c584b30609fd374192dd711` (PR #52 merge); mind a **6/6** kötelező workflow zöld ezen a
  pontos SHA-n (backend build, Android build, Web build, validate, caddy, backup-restore-scripts).
- Release branch: `release/v2.0.4`, a fenti SHA-ról.

## 2. Verziózási konvenció (a v2.0.3 release commitjai alapján) és a változtatások

Komponensenként külön `release:` commit: backend `version`; Public és Service Android `versionName` + `versionCode`
(4 → 5); Public Web `package.json` + `package-lock.json` (két hely). Mind a 4 komponens 2.0.4; sehol nem maradt
`2.0.3` a verziófájlokban. Új dokumentumok: `docs/releases/RELEASE_NOTES_2.0.4.md`, ez a report.

## 3. Artifactok (reprodukálhatóság: mindegyik kétszer, tiszta buildből, azonos checksummal)

| Artifact | SHA-256 |
|---|---|
| `backend-2.0.4.jar` (55 060 844 B) | `2de7d7ff10c8acd9a6f02c1002752f151c307277fca173e76423cd8bb8b23a03` |
| `service-app-release-unsigned.apk` (2 098 923 B; `hu.orszembejelento.service` 2.0.4, versionCode 5) | `bce6e15d6710a87d4b4c474a02ae7dda0d6e59b494bbbb56f027f401e07157b8` |

- Forráscommit: `c6916de` (a verziócommitok utolsója); az azutáni commitok kizárólag dokumentációt adnak, a kód nem
  változik. A jar checksumát a végső HEAD-en újra ellenőriztem (lásd §5).
- **Az APK aláíratlan**: ezen a gépen nincs release kulcs, és kulcsot nem kezelek. Az aláírás és az aláírt APK
  checksumának rögzítése a tulajdonos lépése a terjesztés előtt. Az artifactok a lokális, Gitből kizárt
  `work/release-2.0.4/` mappában vannak (`SHA256SUMS.txt`); a szerverre nem másoltam őket.
- Összevetés: a v2.0.3 jar a címkéből épített formában azonos a production jarral (`524b4594…0600`), tehát a
  build reprodukálhatósága a gyakorlatban is igazolt.

### 3/A. Az artifactok forrásfához kötése (ellenőrizve)

A jar és az aláíratlan APK **tiszta buildből, a pontos `e50c6f8d21779c94d33212c0feedeaa2a5254a6d` forrásfából
újraépítve** (`git diff --quiet HEAD` tiszta, `clean bootJar` / `:service-app:clean :service-app:assembleRelease`)
bájtra azonos checksumot adott a §3 értékeivel. A `c6916de` (a build első forrása) és a `e50c6f8` közti
különbség kizárólag a két dokumentum; kód nem változott.

### 3/B. APK-aláírási kapu (kötelező a terjesztés előtt)

- Az **aláíratlan APK nem terjeszthető.** Az unsigned checksum **nem helyettesíti** az aláírt terjesztési artifact
  checksumát.
- A Service APK-t a **korábbi production kiadásokkal azonos kulccsal** kell aláírni (a jelenleg telepített Service
  alkalmazás frissítéséhez ez feltétel; más kulccsal az Android nem frissít).
- Aláírás után kötelező: `apksigner verify --verbose --print-certs <aláírt.apk>`.
- Ellenőrizni kell, hogy az **aláíró certificate SHA-256 ujjlenyomata megegyezik a jelenleg telepített Service
  alkalmazáséval** (telepített APK: `adb shell pm path hu.orszembejelento.service` → `adb pull` →
  `apksigner verify --print-certs`; a két „Signer #1 certificate SHA-256 digest" sor egyezzen).
- Az **aláírt APK saját SHA-256 checksumát** külön rögzíteni kell (ez a terjesztési artifact azonosítója).
- Signing key, jelszó vagy bármilyen credential **nem kerülhet Gitbe, logba vagy chatbe**; az aláírást a tulajdonos
  a saját gépén végzi. Ebben a fázisban kulcsot nem kezeltem és nem kértem.

### 3/C. Public Android

A Public Android `versionName` 2.0.4-re (versionCode 5) emelkedett a komponensek egységes verziózása miatt, de ehhez
a kiadáshoz **nem kell új Public APK-t terjeszteni**: a vasúti routinghoz a jelenlegi Public kliensek már
támogatják az opcionális vonalválasztást. Nem készült és nem publikálandó feleslegesen Public production APK; a
Public verzióemelés csak a repóban követi a kiadási verziót.

## 4. Tartalom

Lásd `docs/releases/RELEASE_NOTES_2.0.4.md`: V007; SUPER_ADMIN preview/apply; admin self-claim (backend + APK);
146 ellenőrzött kapcsolat előkészítése; vármegyealapú routing előkészítése; import és apply **nem** automatikus.

## 5. Regresszió a release HEAD-en

A release HEAD-en (a verziócommitok + dokumentáció) lokálisan futtatva:

| Ellenőrzés | Eredmény |
|---|---|
| Backend `test --rerun --no-build-cache` | **887 teszt, 0 hiba, 0 kihagyott**; BUILD SUCCESSFUL (4 p 46 mp) |
| Backend jar checksum a végső HEAD-en | `2de7d7ff…3a03`, azonos a §3-mal |
| Android assembleDebug ×2, lint, unit tesztek (public + service), `--rerun-tasks` | BUILD SUCCESSFUL |
| Public Web `npm ci`, typecheck, build | sikeres |
| Python `scripts/tests` | 55/55; promóció 146/919 hozzárendelés, 38 vonal; preview `--check` naprakész |
| Referenciaadat-validátorok (example, evaluation, cleared, promótált) | mind valid |
| GitHub CI a végső HEAD-en | lásd a PR check-jeit (a PR leírása rögzíti az eredményt) |

## 6. A production rollout előtti kötelező kapuk (külön bizonyítandók, még nem történtek meg)

Production backup + sikeres restore drill, és azon: a V007 migráció sikere; a v2.0.3 rollback indulása;
`ORSZEM_UNCLASSIFIED_SERVICE_USER_ACCESS_ENABLED=true`; egy területi SERVICE_USER hozzáférése a saját ROUTED
jelentéséhez; rollback után az érintett jelentések operatív elérhetősége; a más területre routolt jelentések
változatlan tiltása. Sorrend és rollback: `docs/deployment/V2_0_4_TERRITORY_ROLLOUT.md`.

## 7. Nem történt

Nincs `v2.0.4` tag, GitHub Release, merge, deploy, SSH, production backup/import/apply, artifact-másolás a
szerverre, új OSM review-csomag.
