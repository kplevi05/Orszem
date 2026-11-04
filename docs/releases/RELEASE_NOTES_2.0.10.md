# Őrszem V2 — 2.0.10 kiadási jegyzet

**Állapot: kiadásra előkészítve.**

Alap: `main`. Előző publikus kiadás: `v2.0.9`.

## Változások

1. **Alkalmazáson belüli önfrissítés (In-App Update) az Őrszem Szolgálat appban (`service-app`):**
   - Az Őrszem Szolgálat alkalmazás induláskor és előtérbe kerüléskor (legfeljebb óránként egyszer) ellenőrzi az elérhető új verziókat a szerverről (`https://orszembejelento.hu/letoltes/service-app/latest.json`).
   - **Ajánlott frissítés:** ha újabb verzió érhető el, de az aktuális verzió eléri a minimálisan támogatott szintet (`minSupportedVersionCode`), megjelenik egy párbeszédablak a változásleírással, valamint a **Frissítés** és **Később** gombokkal. A „Később” gomb elhalasztja az értesítést a következő app-indításig.
   - **Kötelező frissítés:** ha az alkalmazás verziója a minimálisan támogatott szint alá esik, az app egy teljes képernyős, megkerülhetetlen felületet jelenít meg, amely kizárólag a frissítés elvégzését teszi lehetővé.
   - **Biztonságos letöltés és többszintű integritás-ellenőrzés:**
     1. Az APK kizárólag a manifesttel azonos szerverről tölthető le (same-origin szabály).
     2. A letöltött fájl SHA-256 ellenőrzőösszegének szigorúan egyeznie kell a manifestben szereplő értékkel.
     3. Telepítés előtt ellenőrizzük a csomagnevet (`hu.orszembejelento.service`) és az aláíró tanúsítvány ujjlenyomatát (`e62ade239e0265f5ddc15e3018135ad0a5930d950cbf7390b55d1315d7b303cf`).
     4. A tényleges telepítést a rendszer `PackageInstaller` mechanizmusa végzi a felhasználó jóváhagyásával.
   - **Offline és hibabiztos működés:** ha a szerver nem elérhető vagy a manifest nem tölthető le, az app csendben kihagyja az ellenőrzést, nem akasztja meg a munkát.

2. **Szerveroldali statikus letöltési könyvtár (`/letoltes/`):**
   - Az `orszem-v2-edge` konténer konfigurációja (`Caddyfile.edge` és `docker-compose.yml`) kiegészült a `/letoltes/*` útvonallal, amely a `run/downloads/` mappát szolgálja ki statikus fájlként.
   - Nem igényel Spring Boot backend módosítást és adatbázis-migrációt sem.

## Verziók és komponensek

| Komponens | Verzió | Megjegyzés |
|---|---:|---|
| Service Android | 2.0.9 (10) → **2.0.10 (11)** | In-app update képesség |
| Public Android | 2.0.9 (10) | Változatlan |
| Backend | 2.0.9 | Változatlan |
| Public Web | 2.0.9 | Változatlan |
