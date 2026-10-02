# Őrszem V2 — 2.0.5 kiadási jegyzet

**Állapot: előkészítés (draft release PR). Nincs tag, nincs GitHub Release, nincs deploy.** A production
rolloutot külön owner jóváhagyás vezérli (lásd az alábbi ajánlott sorrendet és a
`docs/V2_0_5_RELEASE_ENGINEERING_REPORT.md` §6-ot).

Alap: `main` `a3527bc0d4c7da7de54ea0abd9775c75b6af4d1e` (a #56 merge-commitja; 6/6 GitHub workflow zöld).
Előző kiadás: `v2.0.4`. Verziók: backend `2.0.5`; Service Android `2.0.5` (versionCode 5 → 6);
Public Android `2.0.5` (versionCode 5 → 6, csak a repóban követi a kiadási verziót); Public Web `2.0.5`.

## Mi hibás a v2.0.4-ben

Productionben már 146 páronkénti (település + vasútvonal) → szolgálati terület hozzárendelés él, a v2.0.4 Service
alkalmazás „Vasútvonal kiválasztása” képernyője viszont még csak a régi, teljes-vonalas modellt ismeri. Emiatt a
páronként konfigurált vonalak (pl. az 1-es) „Nincs szolgálati területhez rendelve” állapotban, a „Hozzárendelés
nélküli” szűrőben jelentek meg, az adminisztrátor megpróbálhatta a teljes vonalat egy területhez rendelni — amit a
backend helyesen elutasít (`409 SETTLEMENT_LINE_MIXED_ROUTING_MODES`) —, az alkalmazás viszont csak „Váratlan hiba
történt.” üzenetet mutatott. A routing és a hozzárendelések maguk helyesek voltak; a megjelenítés és az adminfelület
nem.

## Új a v2.0.4-hez képest

1. **A páronként konfigurált vonalak már nem „hozzárendelés nélküliek”.** A vonallista soronként kifejezetten megadja a
   módot (`UNASSIGNED` / `WHOLE_LINE` / `PER_SETTLEMENT`) és a beállított települések számát; a „Hozzárendelés nélküli”
   szűrő csak a valóban hozzárendelés nélküli vonalakat tartalmazza.
2. **„Településenként hozzárendelve” állapot** a Service alkalmazás vonalválasztójában, új „Településenként” szűrővel.
   Az addigi „Hozzárendelt” szűrő felirata „Teljes vonalhoz rendelt” (a backend `ASSIGNED` érték csak a teljes-vonalas
   módot jelenti).
3. **Település- és szolgálatiterület-részletek.** Egy vonal lenyitható részletében látható, mely települések
   ellenőrzöttek jelenleg a vonalhoz, és melyik szolgálati területre routolnak („Jelenleg elérhető települések ezen a
   vonalon”; „A lista az ellenőrzött referenciaadatok bővítésével frissül.”). Nem állít teljes hálózati lefedettséget.
   Új, csak olvasó, csak SUPER_ADMIN számára elérhető végpont:
   `GET /api/v1/service/service-area-admin/railway-lines/{id}/settlement-mappings`. A megszűnt (inaktív) települések
   kapcsolata is megjelenhet, szavakkal inaktívnak jelölve — ez adminisztratív/történeti láthatóság, nem azt jelenti,
   hogy a település a Public kliensben jelenleg választható.
4. **A teljes-vonalas művelet tiltása páronkénti vonalon.** A kliens nem kínál fel teljes-vonalas hozzárendelést vagy
   áthelyezést ilyen vonalra. A backend változatlanul a jogosultság forrása: csak SUPER_ADMIN módosíthat, és a régi
   végpont továbbra is `409 SETTLEMENT_LINE_MIXED_ROUTING_MODES`-szel utasítja el a kevert routingmódot.
5. **Lokalizált `MIXED_ROUTING_MODES` hiba.** Ha egy elavult kliens mégis a régi végpontot hívja, a felhasználó azt
   látja, hogy a vonal településenként van konfigurálva, ezért egészében nem rendelhető egyetlen területhez.
6. **Biztonságos új kliens → régi backend fallback.** A kliens a vonallistából ismeri fel, hogy a backend nem ismeri a
   páronkénti módot (egy sorból sem hiányozhat az `assignmentMode`); ilyenkor a „Településenként” szűrőt és a
   részletek gombot nem jeleníti meg, és a nem létező végpontot nem hívja — a teljes-vonalas adminisztráció
   változatlanul működik. Régi kliens + új backend is működik (a backend változásai additívak).

## Ami NEM része a kiadásnak

- **Nincs adatbázis-migráció** (a Flyway továbbra is `007`), sémaváltozás, routing-változás, referenciaadat- vagy
  county-policy-változás; a 146 éles hozzárendelés és a routing snapshotok érintetlenek.
- A `main` a `v2.0.4` óta a #54-et is tartalmazza (vasútvonal-megjelenítésinév-override a referenciaadat-promóciós
  folyamatban, döntésfájl, licencdokumentum, dokumentáció). Ez **nem kerül a jarba vagy az APK-ba**; a 38 vonalnév a
  production adatbázisban már egy külön, owner által jóváhagyott név-only adatkiadással szerepel.
- **Új Public APK nem szükséges** és nem készül; a Public Web nem változott (csak a verziószám követi a kiadást).
- Nincs referenciaimport, territory apply és ServiceArea-módosítás.

## Ajánlott rollout (backend elöl) és visszavonás

1. **Backend először.** Friss backup + restore drill a bevált eljárással; a v2.0.5 jar kihelyezése a futó jar
   felülírása nélkül; csak a backend konténer újraindítása. A v2.0.4 jar megőrzése (`2de7d7ff…3a03`) a rollbackhez.
   Nincs migráció, ezért **adatbázis-restore nem része a rollbacknek**.
2. **Utána a Service APK**, a lenti aláírási kapuval. Bármelyik sorrend biztonságos, de a javítás csak a kettővel
   együtt teljes.
3. **Visszavonás.** Backend: a megőrzött v2.0.4 jar visszaállítása és a backend konténer újralétrehozása. A Service
   APK a backendtől függetlenül működik mindkét backenddel; visszavonása a terjesztés leállítása (Android nem enged
   alacsonyabb versionCode-ra frissíteni).

## Terjesztési kapuk

- **Service APK:** az aláíratlan release APK nem terjeszthető és nem tölthető fel Release-assetként. A korábbi
  production kiadásokkal **azonos kulccsal** kell aláírni; utána `apksigner verify --verbose --print-certs`, és az
  aláíró certificate SHA-256 ujjlenyomatának egyeznie kell a jelenleg telepített Service alkalmazáséval
  (`E6:2A:DE:23:…:B3:03:CF`); az **aláírt** APK saját SHA-256 checksumát külön rögzíteni kell. Kulcs vagy jelszó nem
  kerülhet Gitbe, logba vagy chatbe.
- **Public Android:** a verzió 2.0.5-re emelkedett, de új Public APK nem szükséges.
- A backend jar checksumja (`ef79845e…aedb`) és az aláíratlan APK checksumja (`7a7375ad…a075`) a pontos
  forrásfából, kétszer, tiszta buildből épített artifactokra vonatkozik (részletek: engineering report §3).
