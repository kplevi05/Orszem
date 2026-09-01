# Őrszem — Demo v1 scope

**Státusz:** implementálandó termék-scope  
**Cél:** bemutatható, valódi end-to-end Demo v1, amely később Production verzióvá bővíthető.

## 1. Termékcél

A Demo v1 azt bizonyítja, hogy egy anonim utas strukturált biztonsági bejelentést küldhet, a bejelentés a közös backendben eltárolódik, a Szolgálati Appban megjelenik, elfogadható és archiválható, valamint az adat bekerül a statisztikába.

Fő lánc:

`Public App -> API -> PostgreSQL -> Service App -> esetkezelés -> Analytics`

## 2. Public App — kötelező funkciók

- nincs regisztráció vagy bejelentkezés;
- kezdőképernyő;
- új bejelentés indítása;
- esemény időpontja;
- vonat/járat azonosítója;
- település;
- opcionális GPS-alapú településkitöltés, manuális fallbackkel;
- eseménytípus kiválasztása a szerveroldali katalógusból;
- nincs szabad szöveges jelentés;
- bejelentés beküldése;
- sikeres beküldés képernyő;
- új bejelentés indítása.

A jóváhagyott eseménykatalógus: `EVENT_CATALOG.md`.

## 3. Service App — kötelező funkciók

- demo szolgálati bejelentkezés;
- aktív esetek (`NEW`, `IN_PROGRESS`) listája;
- esetrészletek;
- `NEW -> IN_PROGRESS` elfogadás;
- `IN_PROGRESS -> ARCHIVED` lezárás/archiválás;
- archivált esetek listája;
- archivált eset részletei csak olvasásra;
- statisztikai képernyő.

## 4. Kötelező statisztikák

- összes bejelentés;
- mai bejelentések;
- aktív esetek;
- archivált esetek;
- eseménytípus toplista;
- eseménykategória-megoszlás;
- település toplista;
- vonat/járat toplista.

A Demo v1-ben nincs LLM vagy NLP. A technikai komponens neve `Analytics Engine`; determinisztikus aggregációkat számol.

## 5. Report állapotgép

`NEW -> IN_PROGRESS -> ARCHIVED`

Nincs közvetlen `NEW -> ARCHIVED`, újranyitás vagy report-szerkesztés.

## 6. Demo adatok

Reset után:

- 120 seedelt report;
- 8 `NEW`;
- 6 `IN_PROGRESS`;
- 106 `ARCHIVED`;
- összes aktív: 14;
- a reset napján 16 report számít „mai” bejelentésnek;
- 1 `SERVICE_USER` demo-fiók.

Demo login:

- azonosító: `demo.service`
- jelszó: `OrszemDemo!2026`

Ez kizárólag local/demo környezetre vonatkozik.

## 7. Tudatosan NEM Demo v1

- Fő Admin;
- Moderátor;
- több szolgálati felhasználó kezelése;
- területi routing és területi jogosultság;
- user CRUD;
- jelszó-reset;
- kötelező első jelszócsere;
- refresh token és session management;
- szabad szöveges jelentés;
- LLM/NLP;
- AI kockázati besorolás;
- moderátori spam/troll törlés;
- fotó;
- push notification;
- WebSocket;
- valós vasúti API;
- komplex térkép;
- offline-first outbox;
- report újranyitása;
- report szerkesztése.

## 8. Definition of Done

A Demo v1 akkor kész, ha a `DEMO_V1_ACCEPTANCE_TESTS.md` összes P0 tesztje sikeres, a két APK buildelhető, a backend + PostgreSQL reprodukálhatóan indítható, és a prezentációs end-to-end flow seed reset után végrehajtható.
