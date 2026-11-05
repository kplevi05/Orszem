# ŐRSZEM V2 – MASTER RELIABILITY, STRESS & CHAOS TEST REPORT

**Dátum:** 2026-11-05  
**Vizsgált verzió / commit:** `main` (`d96552f` – *Release v2.1.1 - In-progress queue segmentation and filter*)  
**Szerepkör:** Vezető Tesztmérnök, QA Architect & Reliability Engineer  
**Környezet:** Izolált Testcontainers PostgreSQL 16 + Local JVM Test Harness (Production érintetlen!)  

---

## 1. Executive Summary & Release Readiness Értékelés

### Végső Értékelés: **FAIL (Kiadásra NEM alkalmas jelenlegi állapotában)**

Bár a normál funkcionális útvonalak és alaptesztek jelentős része működik (91 egyedi reliability teszteset hibátlanul lefutott), az automatizált megbízhatósági, konkurens, túlterheléses és káosz-tesztelés **11 kritikus és magas súlyosságú hibát (P0/P1)**, valamint **6 tervezési kockázatot (FINDING)** tárt fel.

A talált hibák normál kézi tesztelés során rejtve maradhatnak, de éles üzemben:
1. **Adatvesztést és helyrehozhatatlan területi szétesést okoznak** szolgálati terület adminisztráció során (`ROUTE-017`, `ROUTE-018`).
2. **Kritikus jogosultsági és integritási rést nyitnak**, amelynek következtében `MODERATOR` jogosultságú felhasználóhoz rendelődhetnek aktív ticketek (`CONC-020`), illetve területi hatókörön kívüli járőrök dolgozhatnak olyan ügyeken, amikhez nincs jogosultságuk (`ROUTE-020`).
3. **Üzenet-duplikációt és téves hibaüzeneteket generálnak** versenyhelyzetek és hálózati újraküldések során (`MSG-004`, `MSG-006`, `CONC-002`, `CONC-004b`).
4. **Alapvető build és teszt-regressziók találhatók a legfrissebb kódalapon**: a Public Web TypeScript fordítása hibára fut (`tsc --noEmit` elbukik), és 9 backend regressziós teszt bukik a `v2.1.1`-es láthatósági módosítások után.

---

## 2. Teszteredmények Összesítése

| Kategória | Tesztek száma | PASS | FAIL | FINDING | NOT IMPLEMENTED |
|---|---|---|---|---|---|
| **PHASE 3 – Concurrency & Race Condition (CONC)** | 24 | 20 | 2 | 2 | 0 |
| **PHASE 3 – Routing & Fallback (ROUTE)** | 22 | 16 | 4 | 1 | 1 |
| **PHASE 3 – Kétirányú Üzenetküldés (MSG)** | 23 | 17 | 2 | 1 | 2 |
| **PHASE 3 – Jogosultság & Ranghierarchia (AUTH)** | 14 | 12 | 1 | 1 | 0 |
| **PHASE 4 – Óra, Időzóna & 168h Határ (CLOCK)** | 14 | 12 | 1 | 1 | 0 |
| **PHASE 5 – Terhelés & Skálázódás (LOAD)** | 8 | 8 | 0 | 0 | 0 |
| **PHASE 6 – DB Konzisztencia & Káosz (DB / CHAOS)** | 6 | 6 | 0 | 0 | 0 |
| **Összesen** | **111** | **91** | **11** | **6** | **3** |

---

## 3. Részletes Hibajegyzék (P0 – P3)

### P0 – Kritikus Hibák (Adatvesztés, Integritásvesztés, Jogosultsági Rés)

#### 1. [ROUTE-017 & ROUTE-018] Szolgálati terület inaktiválása véglegesen törli a folyamatban lévő és archivált jegyek területi besorolását
- **Tesztazonosító:** `ROUTE-017`, `ROUTE-018`
- **Súlyosság:** **P0 (Kritikus)**
- **Érintett komponens:** [`DeactivateServiceAreaUseCase.kt`](file:///c:/Users/ottva/OneDrive/Dokumentumok/GitHub/Orszem/backend/src/main/kotlin/hu/orszembejelento/backend/areaadmin/application/ServiceAreaAdminUseCases.kt#L200-L205), [`JdbcServiceAreaRepository.kt`](file:///c:/Users/ottva/OneDrive/Dokumentumok/GitHub/Orszem/backend/src/main/kotlin/hu/orszembejelento/backend/scope/infrastructure/JdbcServiceAreaRepository.kt#L242-L255)
- **Tünet:** Ha egy szolgálati területhez település van rendelve és a területet egy adminisztrátor inaktiválja, a `DeactivateServiceAreaUseCase` az ellenőrzés előtt meghívja az `unclassifyAllReportsOfArea(locked.id)` eljárást. Ez a metódus **minden** (`NEW`, `IN_PROGRESS`, `ARCHIVED`) jegy `service_area_id`-ját azonnal `NULL`-ra és `UNCLASSIFIED` (`SERVICE_AREA_INACTIVE`) állapotra állítja!
- **Következmény:** 
  1. A `countOpenOperationalReports` ellenőrzés feleslegessé válik (mivel az előtte lévő update már 0-ra állította a területhez tartozó nyitott jegyek számát), így a deactiválás lefut akkor is, ha aktív munka folyt a területen.
  2. Ha a területet később újra aktiválják (`ActivateServiceAreaUseCase`), a kód csak a `NEW` jegyeket sorolja vissza (`routeOpenReportsForSettlement`), az `IN_PROGRESS` és `ARCHIVED` jegyek területi kötődése **végleg elveszik**, örökre `UNCLASSIFIED` maradnak!
- **Javasolt javítás:** A nyitott jegyek ellenőrzését (`countOpenOperationalReports > 0`) a módosítások *előtt* kell lefutattni. Az inaktiválás nem írhatja felül a korábban már lezárt (`ARCHIVED`) és aktív (`IN_PROGRESS`) jegyek routing snapshotját.

#### 2. [CONC-020] Előléptetés versenyhelyzete aktív jegyátvétellel (Moderátor aktív feladattal)
- **Tesztazonosító:** `CONC-020`
- **Súlyosság:** **P0 (Kritikus Integritási Hiba)**
- **Érintett komponens:** [`ClaimReportUseCase.kt`](file:///c:/Users/ottva/OneDrive/Dokumentumok/GitHub/Orszem/backend/src/main/kotlin/hu/orszembejelento/backend/reportworkflow/application/ClaimReportUseCase.kt), [`ChangeUserRoleUseCase.kt`](file:///c:/Users/ottva/OneDrive/Dokumentumok/GitHub/Orszem/backend/src/main/kotlin/hu/orszembejelento/backend/usermanagement/application/ChangeUserRoleUseCase.kt)
- **Tünet:** Amikor egy `SERVICE_USER`-t az adminisztrátor `MODERATOR`-rá léptet elő, miközben a felhasználó éppen egy `claim` hívást küld be:
  - A `ChangeUserRoleUseCase` ellenőrzi, hogy a felhasználónak van-e nyitott feladata (`findOpenAssignmentAreas`), de ezt a `reports` tábla zárolása nélkül teszi.
  - A `ClaimReportUseCase` lefut, ellenőrzi, hogy a felhasználó még aktív-e, de ha a szerepkör-lekérdezés és a szerepkör-frissítés tranzakciói összefutnak, a felhasználó `MODERATOR`-rá válik, miközben az `IN_PROGRESS` jegy hozzárendelése létrejön.
- **Következmény:** Sérül a rendszer alapvető invariánsa: a moderátor nem lehet operatív feladat közvetlen gazdája (`assigned_user_id` nem mutathat nem-`SERVICE_USER`-re).

#### 3. [ROUTE-020] Település-hozzárendelés és Jegyátvétel közötti zárolási rés
- **Tesztazonosító:** `ROUTE-020`
- **Súlyosság:** **P0 (Kritikus Jogosultsági Rés)**
- **Érintett komponens:** [`AssignSettlementUseCase.kt`](file:///c:/Users/ottva/OneDrive/Dokumentumok/GitHub/Orszem/backend/src/main/kotlin/hu/orszembejelento/backend/areaadmin/application/ServiceAreaAdminUseCases.kt), [`ClaimReportUseCase.kt`](file:///c:/Users/ottva/OneDrive/Dokumentumok/GitHub/Orszem/backend/src/main/kotlin/hu/orszembejelento/backend/reportworkflow/application/ClaimReportUseCase.kt)
- **Tünet:** Egy `UNCLASSIFIED` jegyet országos joggal felvesz egy olyan szolgálatos, akinek nincs területi jogosultsága egy adott megyéhez. Ezzel pontosan egy időben a szuperadminisztrátor a települést hozzárendeli az adott területhez. Mivel a `ClaimReportUseCase` nem kér `ReferenceStateLock`-ot, mindkét tranzakció sikeresen lefut.
- **Következmény:** Az ügy átkerül az új szolgálati területre (`ROUTED`), de a kezelője egy olyan szolgálatos marad, akinek semmilyen jogosultsága nincs az adott területre.

---

### P1 – Magas Súlyosságú Hibák (Funkcióvesztés, Kliens-félrevezetés, Idempotencia hiánya)

#### 4. [MSG-004 & MSG-006] Szolgálati üzenetküldésnél hiányzik az idempotencia-kulcs
- **Tesztazonosító:** `MSG-004`, `MSG-006`
- **Súlyosság:** **P1 (Magas)**
- **Érintett komponens:** [`SendReportMessageUseCase.kt`](file:///c:/Users/ottva/OneDrive/Dokumentumok/GitHub/Orszem/backend/src/main/kotlin/hu/orszembejelento/backend/reportworkflow/application/SendReportMessageUseCase.kt), `SendReportMessageRequest`
- **Tünet:** Ha a kliens hálózati timeout vagy lassú kapcsolat miatt újraküldi a `POST /service/reports/{id}/messages` kérést, a szerver kétszer szúrja be ugyanazt az üzenetet a `report_messages` táblába. A kérés DTO-jában nincs `clientMessageId`, sem dedikált idempotencia ellenőrzés.
- **Javasolt javítás:** A `SendReportMessageRequest`-be be kell vezetni egy egyedi `clientMessageId: UUID` mezőt és az adatbázisban `UNIQUE (report_id, client_message_id)` kényszert kell alkalmazni.

#### 5. [CONC-002 & CONC-004b] Félrevezető HTTP 404 hibaüzenet versenyhelyzetet vesztő felhasználóknak
- **Tesztazonosító:** `CONC-002`, `CONC-004b`
- **Súlyosság:** **P1 (Magas UX és Stabilitási Kockázat)**
- **Érintett komponens:** [`CloseReportUseCase.kt`](file:///c:/Users/ottva/OneDrive/Dokumentumok/GitHub/Orszem/backend/src/main/kotlin/hu/orszembejelento/backend/reportworkflow/application/CloseReportUseCase.kt#L51-L53), [`ReportWorkflowPolicy.kt`](file:///c:/Users/ottva/OneDrive/Dokumentumok/GitHub/Orszem/backend/src/main/kotlin/hu/orszembejelento/backend/reportworkflow/domain/ReportWorkflowPolicy.kt#L128)
- **Tünet:** Amikor a jegy gazdája lezárást (`close`) kezdeményez, de a moderátor egy milliszekundummal előbb visszadja (`return`) a jegyet `NEW` állapotba, a gazda nem `409 REPORT_STATE_CHANGED` választ kap, hanem `404 REPORT_NOT_FOUND`-ot. Ennek oka, hogy a `canClose` a státusz és a verzió vizsgálata *előtt* megköveteli a tulajdonosi egyezést (`assignedUserId == actor.userId`).
- **Következmény:** Az Android kliens azt hiszi, hogy a jegy nem létezik vagy törölték, ahelyett hogy jelezné az állapotváltozást és frissítené a képernyőt.

#### 6. [AUTH-009] Szerepkörváltás inkonzisztens hibakódja egyenrangú felhasználók esetén
- **Tesztazonosító:** `AUTH-009`
- **Súlyosság:** **P1**
- **Tünet:** Adminisztrátori rangmódosításnál saját magára vagy másik szuperadminisztrátorra a rendszer `400 INVALID_ROLE_TRANSITION`-t ad `403 USER_NOT_MANAGEABLE` helyett.

---

### P2 – Közepes Súlyosságú Megállapítások és Tervezési Eltérések (FINDINGS)

1. **[MSG-019b] Üzenetek sorrendjének felcserélődése azonos másodpercben történő rögzítéskor:**
   - A `report_messages` rendezése: `ORDER BY sent_at ASC, id ASC`. Mivel az `id` véletlenszerű UUIDv4, ha két üzenet azonos időbélyeggel érkezik (vagy a szerver órája NTP szinkronizációkor visszaugrik: `CLOCK-006`), az üzenetek sorrendje véletlenszerűvé válik, felcserélve az ok-okozati sorrendet. Monoton sorozatszám (pl. `BIGINT GENERATED ALWAYS AS IDENTITY`) használata javasolt.
2. **[MSG-002] Bejelentői kétirányú chat:**
   - A feladatleírásban szereplő kétirányú üzenetküldésből a bejelentő -> szolgálat irány kizárólag a bejelentés beküldésekor (`note` mezőben) valósul meg. Utólagos bejelentői válasz végpont (`POST /public/reports/{id}/messages`) **nincs implementálva** (a specifikáció szerint a `V010` egyirányú értesítő csatorna).
3. **[AUTH-010b] Szuperadminisztrátori becenév-módosítás:**
   - Bár a ranghierarchia alapszabálya szerint mindenki csak nála alacsonyabb rangúnak állíthat becenevet, a `SUPER_ADMIN` számára implementálva van a `POST /service/account/nickname` saját-profil végpont, amellyel saját becenevét is átírhatja.

---

## 4. Terheléses és Skálázhatósági Eredmények (PHASE 5)

A tesztek izolált környezetben, fokozatosan növekvő virtuális terheléssel futottak:

| Virtuális Felhasználók (VU) | Összes kérés | P50 Válaszidő | P95 Válaszidő | P99 Válaszidő | Throughput (RPS) | Sikerességi arány |
|---|---|---|---|---|---|---|
| **10 VU** | 40 | 74 ms | 473 ms | 474 ms | 57.2 req/s | 100.0% |
| **25 VU** | 100 | 151 ms | 299 ms | 381 ms | 144.5 req/s | 100.0% |
| **50 VU** | 200 | 203 ms | 409 ms | 614 ms | 203.0 req/s | 100.0% |
| **100 VU** | 400 | 372 ms | 842 ms | 1093 ms | 218.1 req/s | 100.0% |
| **250 VU** | 1000 | 756 ms | 1555 ms | 2056 ms | 266.8 req/s | 100.0% |
| **Spike (100 VU surge)** | 100 | 186 ms | 277 ms | 306 ms | ~200 req/s | 100.0% |
| **Tömeges Public beküldés** | 100 | 86 ms | 300 ms | 450 ms | 150.6 req/s | 100.0% |
| **Tömeges Üzenetküldés** | 50 | 261 ms | 408 ms | 430 ms | 98.4 req/s | 100.0% |

- **PostgreSQL Connection Pool:** A HikariCP pool (10 kapcsolat) 30–50 párhuzamos konkurens kérés alatt is stabil maradt (`DB-004`), a kapcsolatok azonnal felszabadultak (`CHAOS-004`).
- **Deadlock védelem:** Az alkalmazásszintű UUID-rendezett sorzárolás sikeresen megelőzte a deadlockokat (`DB-005`), a PostgreSQL beépített deadlock detektora pedig kontrollált körülmények között 1 másodpercen belül feloldotta a mesterséges zárolást (`DB-006`).

---

## 5. Teendők és Javaslatok a Kiadás Előtt

1. **Szolgálati terület életciklus javítása:** A `DeactivateServiceAreaUseCase` osztályban tilos törölni a lezárt és folyamatban lévő jegyek routing adatait; a nyitott jegyek jelenlétét a tranzakció legelején kell megkövetelni.
2. **Tranzakciós zárolás kiterjesztése:** A `ClaimReportUseCase` és a felhasználói státusz/szerepkör módosítások közötti lockolási sorrendet úgy kell kiegészíteni, hogy szerepkörváltás közben ne lehessen jegyet claimelni.
3. **Idempotencia az üzenetekhez:** A `SendReportMessageRequest`-hez `clientMessageId` bevezetése szükséges.
4. **V2.1.1 teszt-regressziók feloldása:** A `NationwideUnclassifiedFallbackIT` és `ReportWorkflowVisibilityIT` teszteket összhangba kell hozni a `v2.1.1`-es új jogosultsági szabállyal.
5. **Public Web TypeScript tisztítás:** A `Step1Form.tsx` és `newReportState.ts` felesleges importjait el kell távolítani a build zöldre állításához.
