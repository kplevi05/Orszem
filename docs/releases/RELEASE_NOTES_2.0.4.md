# Őrszem V2 — 2.0.4 kiadási jegyzet

**Állapot: előkészítés (draft release PR). Nincs tag, nincs GitHub Release, nincs deploy.** A production
rolloutot külön owner jóváhagyás és a `docs/deployment/V2_0_4_TERRITORY_ROLLOUT.md` kapui vezérlik.

Alap: `main` `f86321a3a2a270ad1c584b30609fd374192dd711` (6/6 GitHub workflow zöld). Előző kiadás: `v2.0.3`.
Verziók: backend `2.0.4`; Public/Service Android `2.0.4` (versionCode 4 → 5); Public Web `2.0.4`.

## Új a v2.0.3-hoz képest (külön felsorolva)

1. **V007 páronkénti routing séma.** Új, üres `service_area_settlement_lines` tábla
   (`settlement + railwayLine → serviceArea`). Előre-migráció, nem módosítható a bevezetése után. Ha egy vonalhoz
   van páronkénti hozzárendelés, a routing kizárólag azt használja; hiányzó pár → `UNCLASSIFIED`. A meglévő
   jelentések snapshotja változatlan. (ADR 0011.)
2. **SUPER_ADMIN territory preview/apply.** `POST …/service-area-admin/settlement-line-mappings/preview` (nem ír)
   és `…/apply` (atomikus, auditált, verzió- és elvárt-állapot-ellenőrzött, legfeljebb 1000 pár).
3. **Adminisztrátori self-claim (backend).** A MODERATOR/SUPER_ADMIN a saját láthatósági körén belül maga is
   claim-elheti a NEW jelentést (területi moderátor csak a saját területén). A SERVICE_USER szabályai változatlanok.
4. **Új Service APK self-claim felület.** A Service app NEW jelentésen az adminisztrátornak a Claim gombot is
   megjeleníti (`hu.orszembejelento.service`, 2.0.4, versionCode 5). A backend claim az APK nélkül is működik;
   az APK nélkül az admin app nem kínálja fel a gombot.
5. **146 ellenőrzött vasútvonal–település kapcsolat előkészítése.** 96 → 146 `INDEPENDENTLY_VERIFIED`
   hozzárendelés (29 → 38 vasútvonal) a repóban (`reference-data/osm-review/decisions/`); a promóciós parancs
   ebből importálható csomagot állít elő. **Az import nem része a kiadásnak.**
6. **Vármegyealapú területi routing előkészítése.** Verziózott `ORSZEM-COUNTY-V2-1` szabály
   (`operational-data/county-v2/`), a 146 pár preview-ja és az apply-payload, KSH `13578` ID-alapú kivétellel,
   GYSEV automatikus hozzárendelés nélkül. **Az apply nem része a kiadásnak.**

## Ami NEM automatikus része a kiadásnak

- A **referenciaimport** (`reference-import` a 146 párra) külön, tudatos üzemeltetési lépés (backup, restore drill,
  validate/diff után).
- A **territory apply** külön owner jóváhagyás után történik; a v2.0.4 telepítése önmagában nem hoz létre
  hozzárendelést, és nem módosít ServiceArea-t.
- A 8 kézzel létrehozott ServiceArea és a 3 INACTIVE terület érintetlen.
- Új Public kliens **nem** szükséges (az opcionális vonalválasztást a jelenlegi Public kliensek már támogatják).

## Kompatibilitás és visszavonás

A v2.0.3 jar bizonyítottan elindul a V007-es adatbázison (izolált fixture, lásd az engineering reportot); a
rollback-biztonsághoz az `ORSZEM_UNCLASSIFIED_SERVICE_USER_ACCESS_ENABLED=true` szükséges. A rollout és rollback
lépései: `docs/deployment/V2_0_4_TERRITORY_ROLLOUT.md`.

## Terjesztési kapuk

- **Service APK:** az aláíratlan release APK nem terjeszthető. A korábbi production kiadásokkal **azonos kulccsal** kell
  aláírni; utána `apksigner verify --verbose --print-certs` és az aláíró certificate SHA-256 ujjlenyomatának
  egyeznie kell a jelenleg telepített Service alkalmazáséval; az **aláírt** APK saját SHA-256 checksumát külön
  rögzíteni kell (az unsigned checksum nem helyettesíti). Kulcs vagy jelszó nem kerülhet Gitbe, logba vagy chatbe.
  Részletek: `docs/V2_0_4_RELEASE_ENGINEERING_REPORT.md` §3/B.
- **Public Android:** a verzió 2.0.4-re emelkedett, de **új Public APK terjesztése nem szükséges** ehhez a
  kiadáshoz; nem készül és nem publikálandó production Public APK.
- A backend jar checksumja (`2de7d7ff…3a03`) és az aláíratlan APK checksumja (`bce6e15d…57b8`) a pontos
  `e50c6f8` forrásfából épített artifactokra vonatkozik.
