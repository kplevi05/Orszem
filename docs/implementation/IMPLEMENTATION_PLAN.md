# Őrszem — Demo v1 implementációs terv

**Cél:** Claude Code vagy fejlesztő ezt a sorrendet kövesse. Egy milestone csak zöld build + tesztek után zárható.

## M0 — Repository skeleton

Létrehozandó:
- Android multi-module build;
- `public-app`;
- `service-app`;
- core network/model/designsystem modulok;
- Spring Boot API;
- Dockerfile;
- Docker Compose PostgreSQL;
- Flyway;
- OpenAPI contract check;
- CI alap.

Kimenet: mindkét Android app üres shellként buildel, API indul, DB migrál.

## M1 — Backend domain + catalog

- DB schema;
- event catalog seed;
- `GET /public/event-types`;
- domain model;
- problem+json hibakezelés;
- alap repository/integration tests.

Kimenet: 7 kategória / 61 event type API-ból elérhető.

## M2 — Public report flow

Backend:
- `POST /public/reports`;
- validáció;
- idempotencia;
- public rate limit minimum.

Public Android:
- P01–P03;
- event picker kereséssel;
- dátum/idő;
- train;
- settlement;
- GPS fallback;
- submit/error/retry.

Kimenet: Public Appból valós report DB-be jut.

## M3 — Service authentication

- demo user seed;
- Argon2id ellenőrzés;
- JWT issuance, 8h TTL;
- `GET /service/me`;
- token storage adapter;
- login UX;
- 401 handling.

Kimenet: Service App védett API-t használ.

## M4 — Service report workflow

- active list;
- detail;
- atomic accept;
- archive;
- archive list;
- audit actions;
- concurrency integration test.

Kimenet: `NEW -> IN_PROGRESS -> ARCHIVED` teljes flow működik.

## M5 — Analytics

- summary;
- event type statistics;
- category statistics;
- settlement statistics;
- train statistics;
- Service App statisztika UI.

Kimenet: seed baseline számok pontosak.

## M6 — Demo seed/reset

- demo JSON;
- demo service user SQL;
- 120 report SQL;
- reset script;
- környezeti guard: csak local/demo.

Kimenet: egy paranccsal ismert baseline állapot.

## M7 — Hardening és prezentáció

- loading/error/empty state;
- accessibility minimum;
- log sanitization;
- rate limit;
- health/readiness;
- release APK-k;
- teljes AT-050 smoke test;
- demo README.

## Claude munkaszabály

Claude egy milestone-on belül önállóan hozhat visszafordítható implementációs döntést, ha:
- nem módosít scope-ot;
- nem töri az OpenAPI-t;
- nem változtat DB üzleti jelentésen;
- dokumentálja a döntést.

Új termékfunkciót nem adhat hozzá „jó ötlet” alapon.

## Merge/commit elv

Ajánlott milestone commitok:
- `chore: initialize demo repository`
- `feat(api): add event catalog`
- `feat(public): implement report submission`
- `feat(service): add demo authentication`
- `feat(service): implement report workflow`
- `feat(analytics): add demo statistics`
- `chore(demo): add deterministic seed and reset`
- `chore: harden demo release`
