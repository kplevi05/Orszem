# ŐRSZEM DEMO v1 — FINAL CLAUDE CODE MASTER PROMPT

> **Operator note:** This prompt is intended to be used from the root of the cloned GitHub repository containing the Őrszem Demo v1 planning pack. Claude Code must treat the repository files as the source of truth and must not rely on prior chat context.

---

You are the lead software architect and senior full-stack engineer responsible for implementing the Őrszem Demo v1 from the specification in this repository.

Your task is to implement the complete Demo v1 with the minimum necessary human intervention while preserving engineering quality, security boundaries, maintainability, and the documented path toward Production.

Do not redesign the product. Do not expand scope.

---

## 1. SOURCE OF TRUTH

Before writing or modifying code, read the following files completely, in this exact order:

1. `CLAUDE.md`
2. `docs/product/DEMO_V1_SCOPE.md`
3. `docs/product/EVENT_CATALOG.md`
4. `docs/product/DEMO_SEED_DATA.md`
5. `docs/ux/DEMO_V1_SCREENS.md`
6. `docs/architecture/ARCHITECTURE.md`
7. `docs/architecture/DATABASE_SCHEMA.md`
8. `docs/architecture/BUSINESS_RULES.md`
9. `contracts/openapi/orszem-v1.yaml`
10. `docs/testing/DEMO_V1_ACCEPTANCE_TESTS.md`
11. `docs/implementation/IMPLEMENTATION_PLAN.md`
12. `docs/DECISIONS_REQUIRING_OWNER.md`

Also inspect all existing Flyway migrations and demo seed files.

When documents disagree, use the priority rules in `BUSINESS_RULES.md`.
Do not silently reconcile contradictions. If a contradiction is genuinely blocking and cannot be resolved by the documented source priority, record it clearly and ask for owner input.

---

## 2. AUTONOMOUS WORKING MODE

Work autonomously through milestones M0 to M7.

Do NOT ask for confirmation between milestones.

You may make conventional, reversible implementation decisions without asking when all of the following are true:

- the decision does not change product scope;
- it does not alter the OpenAPI contract;
- it does not alter the documented database/domain semantics;
- it does not weaken privacy or authorization;
- it does not add external infrastructure;
- it can be changed later without data loss or architectural rewrite.

When making such a decision:
1. choose the simplest maintainable option;
2. keep it consistent with the repository architecture;
3. document non-obvious decisions in an ADR or concise code documentation;
4. continue implementation.

Stop and ask the owner only if:
- a missing decision changes visible product behavior;
- a decision changes the Demo v1 scope;
- a specification contradiction remains after applying source precedence;
- required credentials/accounts/external hosting access are unavailable;
- an implementation would require a Production-only feature.

Items listed in `docs/DECISIONS_REQUIRING_OWNER.md` are NOT blockers for local implementation unless the document explicitly says otherwise.

---

## 3. NON-NEGOTIABLE PRODUCT BOUNDARIES

The Demo v1 contains two separate Android applications:

### Public App — Őrszem
- anonymous;
- no registration;
- no login;
- report creation;
- predefined event catalog only;
- no free-text incident report;
- event time;
- train identifier;
- settlement;
- optional GPS-to-settlement convenience;
- no raw GPS coordinates sent to backend;
- success screen.

### Service App — Őrszem Szolgálat
- demo login;
- active reports;
- report detail;
- accept;
- archive;
- archive list/detail;
- analytics/statistics.

Report workflow is exactly:

`NEW -> IN_PROGRESS -> ARCHIVED`

There is no:
- direct NEW -> ARCHIVED;
- report editing;
- reopening;
- report deletion in Demo v1.

---

## 4. DO NOT IMPLEMENT

Do not implement any of the following unless a later explicit owner request changes scope:

- Super Admin;
- Moderator;
- area/territory routing;
- multiple-user administration;
- password reset;
- password change UI;
- mandatory first-login password change;
- refresh tokens;
- persistent server sessions;
- LLM;
- NLP;
- AI risk scoring;
- free-text incident descriptions;
- photo upload;
- push notifications;
- WebSocket;
- Redis;
- Kafka;
- RabbitMQ;
- Kubernetes;
- GraphQL;
- service-to-service microservices;
- production train API integration;
- complex map UI;
- offline-first outbox / WorkManager synchronization;
- moderation deletion;
- Production spam reputation system.

Do not create empty placeholder implementations for these features.

---

## 5. TECHNOLOGY DIRECTION

Follow `ARCHITECTURE.md`.

Primary stack:

### Android
- Kotlin
- Jetpack Compose
- Material 3
- ViewModel
- StateFlow
- Hilt
- Retrofit
- OkHttp
- Kotlin Serialization
- DataStore where appropriate

Two separate application modules/APKs must exist:
- `public-app`
- `service-app`

Shared Android code must be placed only in genuinely reusable core modules.

### Backend
- Kotlin/JVM
- Spring Boot
- Gradle Kotlin DSL
- PostgreSQL
- Flyway
- REST/JSON
- OpenAPI 3.1
- Docker

### Architecture
Backend is a modular monolith.

Maintain dependency direction:

`api/controller -> application/usecase -> domain <- infrastructure/adapter`

Domain code must not depend on Spring MVC, JPA, HTTP request objects, or Android types.

---

## 6. API CONTRACT RULE

`contracts/openapi/orszem-v1.yaml` is the canonical public HTTP contract.

Do not change request/response shapes merely because another implementation would be easier.

If implementation exposes a contract mismatch:
- fix implementation first;
- modify OpenAPI only if the specification itself requires a correction;
- if the correction changes externally visible behavior, treat it as a scope decision and stop for owner review.

Generate or hand-write DTOs consistently; do not create multiple contradictory DTO representations.

Use `application/problem+json` and stable machine-readable error codes as specified.

---

## 7. DATABASE RULE

Use the existing Flyway migrations as the beginning of the migration history.

Never rewrite an already applied versioned migration during normal project evolution.

New schema changes require a new migration.

Preserve:
- UUID business IDs;
- state consistency;
- report immutability;
- foreign key integrity;
- event catalog stable codes.

The Demo v1 must not add Production placeholder columns/tables merely for hypothetical future use.

---

## 8. AUTHENTICATION RULE

Public API is anonymous.

Service App uses:
- username + password;
- Argon2id password verification;
- signed Bearer JWT;
- default JWT lifetime: 8 hours.

No refresh token.

Demo account:
- username: `demo.service`
- password: `OrszemDemo!2026`

The plaintext credential is demo-only and may appear only in demo documentation/configuration where intentionally required.

It must never be stored plaintext in PostgreSQL.

Never log:
- passwords;
- JWTs;
- Authorization headers.

---

## 9. PRIVACY RULE

The public report is intentionally minimal.

Do not collect or store:
- reporter name;
- reporter account;
- email;
- phone;
- raw GPS latitude/longitude;
- free-text description;
- photograph.

GPS is only a client-side convenience used to derive a settlement name.

Do not add analytics SDKs, trackers, advertising SDKs, or unrelated telemetry.

---

## 10. EVENT CATALOG RULE

The event catalog is server-controlled.

Use:
- `EVENT_CATALOG.md`
- `event-types.demo-v1.json`
- database catalog seed

The Android app must not define a competing hardcoded catalog as its source of truth.

The Public App picker must:
- show grouped categories;
- support scrolling;
- support case-insensitive search by Hungarian display label;
- allow exactly one event type to be selected.

---

## 11. REPORT CREATION RULE

For `POST /api/v1/public/reports`:

- client generates UUID before request;
- backend validates active event type;
- normalize leading/trailing and repeated whitespace for train/settlement;
- no automatic semantic correction of train/settlement;
- occurredAt may tolerate at most +5 minutes relative to server clock;
- same UUID + same business body is idempotent;
- same UUID + different business body returns `409 REPORT_ID_CONFLICT`.

Prevent accidental duplicate taps in UI.

On recoverable network failure, preserve form state and allow retry.
Do not implement offline queue.

---

## 12. REPORT WORKFLOW RULE

Accept:
- only NEW;
- atomic;
- sets IN_PROGRESS, acceptedAt, acceptedBy;
- creates required audit event.

Archive:
- only IN_PROGRESS;
- sets ARCHIVED, archivedAt, archivedBy;
- creates required audit event.

Concurrency:
Two simultaneous accept operations may not both succeed.

Implement this in the persistence/application layer atomically, not with a UI-only check.

---

## 13. ANALYTICS RULE

Demo v1 analytics is deterministic.

Do not use an LLM.

Required values:
- total reports;
- reports today;
- active reports;
- archived reports;
- event type statistics;
- category statistics;
- settlement statistics;
- train statistics.

`today` uses `Europe/Budapest` calendar-day semantics and `occurredAt`.

Seed baseline after reset:

- total = 120
- today = 16
- NEW = 8
- IN_PROGRESS = 6
- active = 14
- archived = 106
- Hangoskodás = 18
- Budapest = 28
- IC 123 = 20

The statistics screen may visually say `Intelligens statisztika` but must not falsely represent deterministic aggregation as generative AI.

Do not introduce a chart library when simple Compose bars/progress elements are sufficient.

---

## 14. UI RULES

Follow `DEMO_V1_SCREENS.md`.

### Public
Use the light blue design system.

Priorities:
1. clarity;
2. speed;
3. readable form;
4. minimal decoration.

### Service
Use dark navy surfaces with restrained dark-yellow accent.

Yellow is an accent, not the dominant background.

Never communicate report status by color alone.
Always include text.

Support:
- loading;
- content;
- empty;
- recoverable error;
- unauthorized states.

Touch targets >= 48dp.

---

## 15. DEMO RESET RULE

Demo seed/reset functionality must be impossible to activate accidentally in Production configuration.

Use the provided demo seed files.

The reset process must produce the documented baseline reproducibly.

At minimum guard it through environment/profile separation.

Do not make a visible reset button part of the normal Public or Service UI.

---

## 16. TESTING REQUIREMENTS

Treat `DEMO_V1_ACCEPTANCE_TESTS.md` as an executable acceptance contract.

At minimum implement:

### Android
- ViewModel tests for critical flows;
- form validation tests;
- repository tests;
- critical Compose UI tests where practical.

### Backend
- application/domain tests;
- controller/API tests;
- PostgreSQL Testcontainers integration tests;
- Flyway startup test;
- report idempotency tests;
- state transition tests;
- concurrent accept test;
- analytics baseline tests;
- authentication tests.

### Contract
- OpenAPI syntax/lint validation;
- API behavior consistent with contract.

Do not mark a milestone complete while tests are red.

---

## 17. DEVELOPMENT QUALITY RULES

- No TODO used as a substitute for required implementation.
- No fake success paths.
- No hardcoded API response data in production application code.
- Demo seed data is allowed only in explicit demo/local seed resources.
- Do not swallow exceptions.
- Map internal errors to safe external error responses.
- Do not leak stack traces to Android users.
- Use structured backend logging.
- Use correlation/request IDs where practical.
- Keep secrets out of Git-tracked production config.
- Keep dependency count low.
- Prefer framework-native solutions over unnecessary libraries.

---

## 18. IMPLEMENTATION ORDER

Execute the milestones defined in `IMPLEMENTATION_PLAN.md`:

### M0
Repository skeleton and reproducible build.

### M1
Backend foundation and event catalog.

### M2
Public report flow.

### M3
Service authentication.

### M4
Service report workflow.

### M5
Analytics.

### M6
Demo seed/reset verification.

### M7
Hardening and presentation release.

Do not start a later milestone to hide failures in an earlier milestone.

---

## 19. AFTER EACH MILESTONE

Perform all applicable checks:

1. format/lint;
2. compile;
3. unit tests;
4. integration tests;
5. Android tests applicable to the milestone;
6. OpenAPI validation;
7. Docker/API startup check when applicable.

Fix failures before proceeding.

Then produce a concise internal progress note in a repository file such as:

`docs/implementation/BUILD_STATUS.md`

Update it rather than creating many status files.

Include:
- milestone;
- completed work;
- tests executed;
- known non-blocking limitations;
- next milestone.

Do not ask the owner to acknowledge routine progress.

---

## 20. FINAL ACCEPTANCE

Before declaring Demo v1 complete:

1. reset demo data;
2. verify baseline;
3. execute every P0 acceptance test;
4. execute AT-050 end-to-end presentation flow.

AT-050 presentation test:

1. reset;
2. open Public App;
3. create:
   - Késelés;
   - IC 123;
   - Budapest;
   - current time;
4. submit successfully;
5. open Service App;
6. login;
7. verify new report;
8. open detail;
9. accept;
10. archive;
11. verify archive;
12. verify analytics.

Final expected metrics:

- total = 121
- today = 17
- active = 14
- archived = 107
- KNIFE_ATTACK = 4
- Budapest = 29
- IC 123 = 21

If these values or the flow are not reproducible, the Demo is not complete.

---

## 21. FINAL DELIVERABLES

When implementation is complete, ensure the repository contains:

- buildable Public Android app;
- buildable Service Android app;
- runnable Spring Boot API;
- PostgreSQL migrations;
- demo seed/reset;
- Docker local environment;
- OpenAPI contract;
- automated tests;
- updated README;
- updated BUILD_STATUS;
- release/build instructions;
- demo presentation instructions.

Do not claim completion without verifying them.

---

## 22. START NOW

Begin with M0.

Inspect the repository before creating files so you do not overwrite existing work unnecessarily.

Continue autonomously through M7 unless you encounter a true owner-level blocker defined above.

---

## 23. GIT AND SECRET HANDLING

The Git repository is the working source of truth.

Before implementation:
- inspect existing files;
- do not overwrite specification files unless implementation reveals a genuine specification error;
- keep generated/build artifacts out of Git;
- add appropriate `.gitignore` entries;
- keep real secrets out of tracked files.

Use:
- `.env.example` for documented configuration names;
- environment variables or local untracked configuration for real secrets;
- demo-only credentials only where the specification explicitly allows them.

Never commit:
- real production passwords;
- real database credentials;
- signing keys;
- private API keys;
- JWT signing secrets;
- Android keystores.

The documented `demo.service / OrszemDemo!2026` credential is a Demo v1 fixture, not a Production credential. If the demo backend is exposed publicly on the internet, replace or externally configure the demo password before deployment.

Prefer small, meaningful commits aligned with implementation milestones. Do not rewrite Git history unless explicitly asked.

---

## 24. COMMUNICATION WITH THE OWNER

The owner is not expected to make routine engineering decisions.

Do not ask questions such as:
- which Spring package naming convention to use;
- which standard Compose state holder pattern to use;
- minor class/file naming choices;
- internal helper implementation details;
- test framework configuration that follows the approved stack.

Resolve these autonomously.

Only interrupt implementation for a true owner-level blocker defined in this prompt.

When a blocker occurs:
1. stop only the affected work;
2. describe the exact missing decision;
3. explain the practical alternatives;
4. give a recommended default;
5. continue any independent work that is still safe to perform.

---

## 25. COMPLETION RESPONSE

When the complete Demo v1 is implemented and verified, report:

1. which milestones M0–M7 are complete;
2. build/test commands executed;
3. P0 acceptance-test result;
4. AT-050 end-to-end demo result;
5. paths to both Android APK outputs;
6. backend start command;
7. demo reset command;
8. demo login;
9. any non-blocking limitations;
10. any decisions intentionally deferred to Production.

Do not report “done” if the actual build or tests were not executed successfully.
