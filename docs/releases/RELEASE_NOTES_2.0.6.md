# Őrszem V2 — 2.0.6 kiadási jegyzet

**Állapot: release-előkészítés.** Nincs `v2.0.6` tag, GitHub Release, aláírt APK vagy production-módosítás.

Alap: `main` `2e69bdb594d070a26f808e67dc67c1e36d9d7f52`, a #58 és #60 merge után. A pontos SHA-n mind az
öt GitHub workflow sikeres. Előző kiadás: `v2.0.5`.

## Változás

A Service Android szolgálatiterület-listája és részletképernyője most külön mutatja:

- a teljes-vonalas hozzárendelések számát;
- a településenkénti `(település + vasútvonal) → ServiceArea` hozzárendelések számát.

Pair-level hozzárendelés mellett a képernyő többé nem állítja általánosan, hogy nincs vasútvonal a területhez
rendelve. Az üres állapot pontos szövege: „Nincs teljes vonal ehhez a területhez rendelve.” A regressziós
Compose-teszt külön ellenőrzi ezt az esetet.

## Hatókör

- Futtatott funkcionális változás kizárólag a Service Android megjelenítése.
- Nincs backend API-, adatbázis-, Flyway-, routing-, authorization-, referenciaadat- vagy ServiceArea-adatváltozás.
- A production v2.0.5 backend már visszaadja a szükséges `mappedRailwayLineCount` és
  `mappedSettlementLineCount` mezőket; backend-deploy nem szükséges.
- Public Android és Public Web funkcionálisan változatlan; verziójuk a projekt egységes verziózását követi,
  de új Public APK vagy web deployment nem szükséges.

## Terjesztési kapuk

1. A release PR pontos végső HEAD-jén minden CI-workflow legyen zöld.
2. A pontos `v2.0.6` tag tiszta worktree-jéből készüljön az unsigned Service APK; a build legyen reprodukálható.
3. Az APK-t a korábbi production kiadásokkal azonos kulccsal kell aláírni. Kötelező az
   `apksigner verify --verbose --print-certs`; az aláíró certificate SHA-256 legyen
   `E6:2A:DE:23:9E:02:65:F5:DD:C1:5E:30:18:13:5A:D0:A5:93:0D:95:0C:BF:73:90:B5:5D:13:15:D7:B3:03:CF`.
4. Az aláírt APK saját SHA-256 checksumát rögzíteni kell.
5. `adb install -r` frissítési próba v2.0.5 fölé, uninstall és downgrade nélkül.
6. Read-only UI-smoke: a területkártyán és a részletképernyőn a teljes-vonalas és pair-level számok pontosan
   jelenjenek meg; hozzárendelés vagy más production írás ne történjen.

## Telepítés és visszavonás

A felhasználók az aláírt Service 2.0.6 APK-t a meglévő app fölé telepítik; a régi appot nem kell és nem szabad
előtte törölni. Android nem enged alacsonyabb `versionCode`-ra visszafrissíteni, ezért visszavonáskor a terjesztést
le kell állítani; szükség esetén a javított APK csak újabb `versionCode`-dal adható ki.
