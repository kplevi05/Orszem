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

## 4. Tartalom

Lásd `docs/releases/RELEASE_NOTES_2.0.4.md`: V007; SUPER_ADMIN preview/apply; admin self-claim (backend + APK);
146 ellenőrzött kapcsolat előkészítése; vármegyealapú routing előkészítése; import és apply **nem** automatikus.

## 5. Regresszió a release HEAD-en

Lásd a PR leírását és a lenti eredménytáblát (backend teljes teszt cache nélkül, Android, Web, Python, validátorok,
GitHub CI a végső HEAD-en).

| Ellenőrzés | Eredmény |
|---|---|
| Backend `test --rerun --no-build-cache` | lásd PR |
| Android assembleDebug ×2, lint, unit ×2 | lásd PR |
| Web typecheck + build | lásd PR |
| Python `scripts/tests` + preview `--check` + validátorok | lásd PR |

## 6. A production rollout előtti kötelező kapuk (külön bizonyítandók, még nem történtek meg)

Production backup + sikeres restore drill, és azon: a V007 migráció sikere; a v2.0.3 rollback indulása;
`ORSZEM_UNCLASSIFIED_SERVICE_USER_ACCESS_ENABLED=true`; egy területi SERVICE_USER hozzáférése a saját ROUTED
jelentéséhez; rollback után az érintett jelentések operatív elérhetősége; a más területre routolt jelentések
változatlan tiltása. Sorrend és rollback: `docs/deployment/V2_0_4_TERRITORY_ROLLOUT.md`.

## 7. Nem történt

Nincs `v2.0.4` tag, GitHub Release, merge, deploy, SSH, production backup/import/apply, artifact-másolás a
szerverre, új OSM review-csomag.
