# Őrszem — Demo v1 acceptance tesztek

A `P0` tesztek mind kötelezőek a bemutatható buildhez.

## A. Környezet és seed

### AT-001 [P0] Demo reset
**Given** tiszta demo adatbázis  
**When** `scripts/reset-demo.sh` lefut  
**Then**
- 120 report létezik;
- 8 NEW;
- 6 IN_PROGRESS;
- 106 ARCHIVED;
- aktív = 14;
- `demo.service` ACTIVE user létezik;
- reset napján `todayReports = 16`.

### AT-002 [P0] Demo login
`demo.service / OrszemDemo!2026` sikeresen belép és kap érvényes Bearer JWT-t.

### AT-003 [P0] Hibás login
Hibás jelszóval nincs token és a Service App érthető hibát mutat.

## B. Public App

### AT-010 [P0] Event catalog
A Public App lekéri a szerverről a 7 kategóriát / 61 aktív eseménytípust, kategória- és sort sorrendben.

### AT-011 [P0] Kötelező mezők
Hiányos report nem küldhető el.

### AT-012 [P0] Sikeres beküldés
Késelés / IC 123 / Budapest / aktuális idő beküldése 2xx választ és `NEW` reportot eredményez.

### AT-013 [P0] Idempotencia
Ugyanazt a UUID-t ugyanazzal a bodyval kétszer elküldve csak egy report létezik.

### AT-014 [P0] ID konfliktus
Azonos UUID eltérő üzleti bodyval -> `409 REPORT_ID_CONFLICT`.

### AT-015 [P0] Hálózati hiba
Sikertelen API-hívás után az űrlap értékei nem vesznek el és újrapróbálható.

### AT-016 [P1] GPS fallback
Helyengedély megtagadásakor továbbra is manuálisan megadható a település.

## C. Service App workflow

### AT-020 [P0] Aktív lista
Login után NEW és IN_PROGRESS reportok látszanak; ARCHIVED nem.

### AT-021 [P0] Rendezés
NEW reportok megelőzik az IN_PROGRESS reportokat; csoporton belül a frissebb van előbb.

### AT-022 [P0] Report detail
Minden kötelező report adat megjelenik.

### AT-023 [P0] Elfogadás
NEW report elfogadása után IN_PROGRESS lesz, és az elfogadás ideje/actor eltárolódik.

### AT-024 [P0] Dupla elfogadás
Már IN_PROGRESS report elfogadása -> `409 REPORT_NOT_ACCEPTABLE`.

### AT-025 [P0] Konkurens elfogadás
Két párhuzamos accept közül pontosan egy módosíthatja a reportot.

### AT-026 [P0] Archiválás
IN_PROGRESS report archiválása után ARCHIVED lesz és eltűnik az aktív listából.

### AT-027 [P0] Közvetlen archiválás tiltása
NEW report archive -> `409 REPORT_NOT_ARCHIVABLE`.

### AT-028 [P0] Archívum
Archivált report megjelenik az archívumban és részletei olvashatók.

### AT-029 [P0] 401 session expiry
Lejárt/hibás token esetén a kliens visszairányít loginra.

## D. Analytics

### AT-030 [P0] Baseline KPI
Reset után:
- total=120;
- today=16;
- active=14;
- archived=106.

### AT-031 [P0] Baseline toplisták
Legalább:
- Hangoskodás = 18;
- Budapest = 28;
- IC 123 = 20.

### AT-032 [P0] Új report bekerül a statisztikába
A prezentációs `KNIFE_ATTACK / IC 123 / Budapest` report beküldése után:
- total=121;
- today=17;
- active=15;
- archived=106;
- KNIFE_ATTACK=4;
- Budapest=29;
- IC 123=21.

### AT-033 [P0] Accept nem változtat totalon
Elfogadás után total/today változatlan, active=15.

### AT-034 [P0] Archive módosítja status KPI-t
Archiválás után:
- total=121;
- today=17;
- active=14;
- archived=107.

## E. Security / privacy minimum

### AT-040 [P0] Public endpoint anonim
Public reporthoz nincs login vagy user ID.

### AT-041 [P0] Service endpoint védelem
Bearer token nélkül service endpoint -> 401.

### AT-042 [P0] Nincs plaintext password
Adatbázisban a demo user password mező hash, nem `OrszemDemo!2026`.

### AT-043 [P0] GPS adatminimalizálás
Report API requestben nincs latitude/longitude.

### AT-044 [P0] Log sanitization
Jelszó, access token és Authorization header nem jelenik meg normál szerverlogban.

## F. Prezentációs smoke test

### AT-050 [P0] Teljes 5 perces flow
1. reset;
2. Public App megnyitása;
3. Késelés / IC 123 / Budapest report;
4. siker képernyő;
5. Service App login;
6. új report megjelenik;
7. detail;
8. accept;
9. archive;
10. archívumban megjelenik;
11. statisztika a várt `121 / 17 / 14 / 107` baseline utáni értékeket mutatja.

Ha ez a teszt nem stabil, a Demo v1 nem bemutatható.
