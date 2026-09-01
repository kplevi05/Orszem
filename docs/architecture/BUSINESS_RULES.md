# Őrszem — Demo v1 üzleti és API viselkedési szabályok

## 1. Források elsőbbsége

1. HTTP szerződés: `contracts/openapi/orszem-v1.yaml`
2. Perzisztencia: Flyway migrációk + `DATABASE_SCHEMA.md`
3. Termék scope: `DEMO_V1_SCOPE.md`
4. UX: `DEMO_V1_SCREENS.md`
5. Eseménylista: `EVENT_CATALOG.md`

Eltérés esetén Claude nem improvizálhat; a magasabb prioritású forrást követi és dokumentálja az eltérést.

## 2. Public report validáció

### `id`
- UUID;
- kliens generálja küldés előtt;
- idempotenciakulcs.

### `eventTypeCode`
- csak létező és `active=true` event type fogadható el;
- ismeretlen/inaktív kód -> `400 EVENT_TYPE_INVALID`.

### `trainIdentifier`
- trim;
- egymás melletti whitespace-ek egy szóközzé normalizálhatók;
- 1–64 karakter;
- Demo v1-ben szabad szöveg;
- backend nem találhat ki vagy javíthat automatikusan járatszámot.

### `settlement`
- trim;
- egymás melletti whitespace-ek egy szóközzé normalizálhatók;
- 1–128 karakter;
- Demo v1-ben szabad szöveg;
- nyers GPS koordináta nem része az API-nak.

### `occurredAt`
- kötelező UTC date-time;
- legfeljebb 5 perccel lehet a szerveridőnél későbbi a kliens clock-skew tolerancia miatt;
- ennél távolabbi jövő -> `400 OCCURRED_AT_IN_FUTURE`;
- Demo v1-ben nincs alsó időkorlát.

## 3. Public report idempotencia

Azonos UUID + azonos üzleti input:
- ugyanaz a report;
- nem keletkezik duplikáció;
- a backend visszaadhatja az eredeti create eredményt.

Azonos UUID + eltérő `eventTypeCode`, `trainIdentifier`, `settlement` vagy `occurredAt`:
- `409 REPORT_ID_CONFLICT`.

## 4. Service authentication

- Demo user: `demo.service`;
- JWT Bearer access token;
- default token TTL: **8 óra**;
- nincs refresh token;
- nincs szerveroldali logout/session revocation;
- kliens logout: token törlése;
- password hash: Argon2id;
- service auth endpointon rate limit alkalmazható.

## 5. Jogosultság Demo v1-ben

Egyetlen capability-csoport:
- `REPORT_READ_ACTIVE`
- `REPORT_ACCEPT`
- `REPORT_ARCHIVE`
- `ARCHIVE_READ`
- `ANALYTICS_READ`

A backend minden service műveletnél ellenőrzi a tokent/capabilityt. A kliensoldali elrejtés nem security boundary.

## 6. Állapotgép

### Elfogadás
Csak `NEW`:
- atomikus átmenet;
- `status=IN_PROGRESS`;
- `acceptedAt=now`;
- `acceptedBy=actor`.

Más állapot:
- `409 REPORT_NOT_ACCEPTABLE`.

### Archiválás
Csak `IN_PROGRESS`:
- `status=ARCHIVED`;
- `archivedAt=now`;
- `archivedBy=actor`.

Más állapot:
- `409 REPORT_NOT_ARCHIVABLE`.

### Konkurencia
Két egyidejű accept közül pontosan egy sikeres. A vesztes kérés 409-et kap.

## 7. Report módosíthatatlansága

A Demo v1-ben beküldés után nem módosítható:
- eseménytípus;
- vonat;
- település;
- eseményidő.

Nincs általános PATCH/PUT report endpoint.

## 8. Listák

Aktív lista:
- csak NEW és IN_PROGRESS;
- NEW előbb;
- csoporton belül `receivedAt DESC`.

Archívum:
- csak ARCHIVED;
- `archivedAt DESC`.

Default limit: 30, max 100.
Cursor opaque; kliens nem parse-olhatja.

## 9. Analytics

Az analytics minden reportot figyelembe vesz, függetlenül attól, hogy NEW, IN_PROGRESS vagy ARCHIVED, kivéve a status-specifikus KPI-kat.

- `totalReports`: minden report;
- `todayReports`: `occurredAt` alapján, `Europe/Budapest` naptári nap;
- `activeReports`: NEW + IN_PROGRESS;
- `archivedReports`: ARCHIVED;
- event type/category/settlement/train stat: minden report.

Azonos darabszám esetén stabil másodlagos rendezés: display label/string növekvően.

## 10. Demo public rate limit

Minimum, nem production spamrendszer:
- javasolt default: 20 report-kérés / 5 perc / forrás IP;
- memóriaalapú vagy egyszerű reverse-proxy limit elegendő;
- IP-t a report adatbázis nem tárolja;
- túllépés: `429 RATE_LIMITED`.

A pontos limiter implementáció technikai döntés, de a report domainhez nem kapcsolódhat.

## 11. GPS

A GPS kliensoldali kényelmi funkció:
- csak explicit gombnyomásra kér engedélyt;
- hely -> reverse geocode -> településnév;
- user felülírhatja a települést;
- permission denial nem blokkolja a reportot;
- backendhez nincs latitude/longitude.

## 12. Audit

Demo v1 minimum:
- `SERVICE_LOGIN_SUCCESS`;
- `SERVICE_LOGIN_FAILURE`;
- `REPORT_ACCEPTED`;
- `REPORT_ARCHIVED`.

Public report létrehozás auditja nem kötelező.
Jelszó/token/request body nem kerül auditba.

## 13. Hiba-UX

- validáció: mező közelében;
- hálózati hiba public küldésnél: form megmarad + retry;
- 401 Service App: token törlés + login;
- 409 workflow: report frissítés + érthető állapotváltozás üzenet;
- 5xx: generikus hiba, technikai részlet nélkül.
