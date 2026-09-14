# Phase 12 Engineering Report — Audit Query / Változási előzmények

Branch: `feature/v2-audit-ui` (base: `main` @ `fc355a0`, Phase 11 merged via PR [#15](https://github.com/kplevi05/Orszem/pull/15))

## A. Git

- Backend: a new `audit` read-model vertical slice — `application`/`api`/`infrastructure` additions plus two new `domain` files, laid *beside* the existing, untouched Phase 2/6/7/9/10/11 `AuditEvent.kt`/`JdbcAuditRepository.kt` write path. Nothing about how an audit row is written changed.
- Android: the live `Változási előzmények` screen (list, search, filter, detail) replacing the Adminisztráció hub's last "Még nem elérhető" placeholder.
- Two backend files touched outside the new package: `common/web/ApiError.kt` (5 new codes) and `common/web/ApiExceptionHandler.kt` (5 new handlers) — the same shape every earlier phase's new error surface has taken.
- No migration added or edited — see §K. No existing audit writer changed.

## B. Existing audit schema inventory

`audit_events` (V001, untouched):

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | |
| `operation_id` | UUID | groups rows from one logical operation |
| `actor_type` | VARCHAR(16) | `USER` \| `SYSTEM` |
| `actor_user_id` | UUID NULL | NULL only for `SYSTEM` (`ck_audit_actor_user`) |
| `event_type` | VARCHAR(64) | one of `AuditEventType` |
| `target_type` | VARCHAR(32) | one of `AuditTargetType` |
| `target_id` | UUID NULL | |
| `metadata` | JSONB NOT NULL DEFAULT `{}` | flat `Map<String,String>` only (`JdbcAuditRepository`'s own hand-rolled encoder) |
| `created_at` | TIMESTAMPTZ | authoritative audit timestamp |

Existing indexes (all from V001, all still adequate — see §K): `ix_audit_events_created (created_at)`, `ix_audit_events_target (target_type, target_id)`, `ix_audit_events_actor (actor_user_id)`, `ix_audit_events_type (event_type, created_at)`, `ix_audit_events_operation (operation_id)`.

**Actual `AuditEventType`** (31 values, `backend/audit/domain/AuditEvent.kt`): `SUPER_ADMIN_CREATED`, `SUPER_ADMIN_PASSWORD_RESET`, `INITIAL_PASSWORD_CHANGED`, `PASSWORD_CHANGED`, `SESSION_CREATED`, `SESSION_REVOKED`, `LOGOUT_ALL`, `REFRESH_TOKEN_REUSE_DETECTED`, `REFERENCE_DATASET_IMPORTED`, `USER_CREATED`, `USER_PASSWORD_RESET`, `USER_DEACTIVATED`, `USER_REACTIVATED`, `USER_ROLE_CHANGED`, `USER_AREA_GRANTED`, `USER_AREA_REVOKED`, `USER_GLOBAL_ACCESS_GRANTED`, `USER_GLOBAL_ACCESS_REVOKED`, `REPORT_CLAIMED`, `REPORT_RETURNED_TO_NEW`, `REPORT_REASSIGNED`, `REPORT_ARCHIVED`, `REPORT_MODERATION_DELETED`, `REPORT_MODERATION_RESTORED`, `SERVICE_AREA_CREATED`, `SERVICE_AREA_RENAMED`, `SERVICE_AREA_ACTIVATED`, `SERVICE_AREA_DEACTIVATED`, `RAILWAY_LINE_SERVICE_AREA_ASSIGNED`, `RAILWAY_LINE_SERVICE_AREA_MOVED`, `RAILWAY_LINE_SERVICE_AREA_UNASSIGNED`.

**Actual `AuditTargetType`** (6 values): `USER`, `SESSION`, `REFERENCE_DATASET`, `REPORT`, `SERVICE_AREA`, `RAILWAY_LINE`.

No stored metadata field, across any of the 31 call sites inspected (§24 inventory below and this report's §C), contains a password, hash, credential, token, or Authorization header — the one existing writer comment ("Never the credential or its hash") was verified true by direct inspection of every `audit.record(...)` call site, not assumed. No STOP condition triggered.

## C. Event projection matrix

For every current event type — backend code → target type → actor behavior → safe fields exposed → Hungarian title. "Safe fields" lists the `AuditDetailCode`s `AuditEventSafeProjector` emits; every other stored metadata key for that event is silently omitted, never a fallback `key = value`.

| Event code | Target | Actor | Safe detail fields | Hungarian title |
|---|---|---|---|---|
| `SUPER_ADMIN_CREATED` | USER | SYSTEM (maintenance CLI) | `SOURCE` | Főadminisztrátor létrehozva |
| `SUPER_ADMIN_PASSWORD_RESET` | USER | SYSTEM | `SOURCE`, `REVOKED_SESSIONS` | Főadminisztrátor jelszava visszaállítva |
| `INITIAL_PASSWORD_CHANGED` | USER | USER (self) | — | Kezdeti jelszó módosítva |
| `PASSWORD_CHANGED` | USER | USER (self) | `REVOKED_SESSIONS` | Jelszó módosítva |
| `SESSION_CREATED` | SESSION | USER | — | Bejelentkezés |
| `SESSION_REVOKED` | SESSION | USER | `REVOCATION_REASON` | Munkamenet visszavonva |
| `LOGOUT_ALL` | USER | USER (self) | `REVOKED_SESSIONS` | Kijelentkezés minden eszközön |
| `REFRESH_TOKEN_REUSE_DETECTED` | SESSION | USER | — | Gyanús munkamenet-újrafelhasználás észlelve |
| `REFERENCE_DATASET_IMPORTED` | REFERENCE_DATASET | SYSTEM | `SOURCE`, `DATASET_VERSION`, `SETTLEMENTS_IMPORTED`, `RAILWAY_LINES_IMPORTED`, `MAPPINGS_IMPORTED` | Referenciaadat importálva |
| `USER_CREATED` | USER | USER (SUPER_ADMIN/MODERATOR) | `NEW_ROLE`, `AREAS` (resolved names), `GLOBAL_ACCESS` | Felhasználó létrehozva |
| `USER_PASSWORD_RESET` | USER | USER | `REVOKED_SESSIONS` | Ideiglenes jelszó kiadva |
| `USER_DEACTIVATED` | USER | USER | `REVOKED_SESSIONS` | Felhasználó deaktiválva |
| `USER_REACTIVATED` | USER | USER | — | Felhasználó újraaktiválva |
| `USER_ROLE_CHANGED` | USER | USER | `OLD_ROLE`, `NEW_ROLE` | Szerepkör módosítva |
| `USER_AREA_GRANTED` | USER | USER | `AREA` (resolved name) | Szolgálati terület hozzáadva |
| `USER_AREA_REVOKED` | USER | USER | `AREA` (resolved name) | Szolgálati terület eltávolítva |
| `USER_GLOBAL_ACCESS_GRANTED` | USER | USER | — | Globális hozzáférés engedélyezve |
| `USER_GLOBAL_ACCESS_REVOKED` | USER | USER | — | Globális hozzáférés visszavonva |
| `REPORT_CLAIMED` | REPORT | USER | `FROM_STATUS`, `TO_STATUS`, `NEW_ASSIGNEE` | Bejelentés átvéve |
| `REPORT_RETURNED_TO_NEW` | REPORT | USER | `FROM_STATUS`, `TO_STATUS`, `PREVIOUS_ASSIGNEE` | Bejelentés visszahelyezve az új sorba |
| `REPORT_REASSIGNED` | REPORT | USER | `PREVIOUS_ASSIGNEE`, `NEW_ASSIGNEE` | Bejelentés áthelyezve másik munkatárshoz |
| `REPORT_ARCHIVED` | REPORT | USER | `FROM_STATUS`, `TO_STATUS`, `PREVIOUS_ASSIGNEE` | Bejelentés lezárva |
| `REPORT_MODERATION_DELETED` | REPORT | USER | `REASON`, `STATUS_BEFORE_DELETE` | Bejelentés moderációval törölve |
| `REPORT_MODERATION_RESTORED` | REPORT | USER | `STATUS_BEFORE_DELETE`, `RESULTING_STATUS` | Bejelentés visszaállítva |
| `SERVICE_AREA_CREATED` | SERVICE_AREA | USER | — | Szolgálati terület létrehozva |
| `SERVICE_AREA_RENAMED` | SERVICE_AREA | USER | `OLD_NAME`, `NEW_NAME` | Szolgálati terület átnevezve |
| `SERVICE_AREA_ACTIVATED` | SERVICE_AREA | USER | — | Szolgálati terület aktiválva |
| `SERVICE_AREA_DEACTIVATED` | SERVICE_AREA | USER | — | Szolgálati terület deaktiválva |
| `RAILWAY_LINE_SERVICE_AREA_ASSIGNED` | RAILWAY_LINE | USER | `TO_AREA` (resolved name) | Vasútvonal hozzárendelve |
| `RAILWAY_LINE_SERVICE_AREA_MOVED` | RAILWAY_LINE | USER | `FROM_AREA`, `TO_AREA` (resolved names) | Vasútvonal áthelyezve |
| `RAILWAY_LINE_SERVICE_AREA_UNASSIGNED` | RAILWAY_LINE | USER | `FROM_AREA` (resolved name) | Vasútvonal hozzárendelése megszüntetve |

Fields deliberately **omitted** from every row above (present in stored metadata, never projected): `workflowVersion`/`adminVersion` (concurrency-control counters, not history facts a reader needs), the raw `publicReportId` string itself (the REPORT target's own display label already carries it, formatted, so it is never duplicated as a detail entry), and the raw `areaId`/`toAreaId`/`fromAreaId`/`areaIds` UUIDs (always resolved to names, never shown raw — see §I).

## D. Authorization

`AuditActorLoader.load(actor)` — one gate, called first by every `AuditQueryUseCase` method, before any query runs: `if (actor.role != UserRole.SUPER_ADMIN) throw AuditForbiddenException()`. No territorial exception, no global-MODERATOR exception (unlike Phase 11 analytics, which does grant global-MODERATOR a wider scope). Proven by `AuditAuthorizationIT` (5 tests): SERVICE_USER, territorial MODERATOR, and global MODERATOR are all uniformly 403 on all three endpoints; SUPER_ADMIN is allowed.

## E. Immutability

No `POST`/`PATCH`/`DELETE` exists anywhere under `/api/v1/service/audit` — `AuditController` declares three `@GetMapping`s only. `AuditQueryUseCase` and `JdbcAuditQueryRepository` both call only `SELECT`; `JdbcAuditRepository` (the one and only writer, Phase 2) is never imported by the query side. `AuditSecurityIT.list and detail queries never alter audit row count or content` proves this behaviourally: row count and full `(event_type ORDER BY created_at)` content are byte-identical before and after 6 read requests (list ×3, options ×3, detail ×3) against real fixture data. No `AUDIT_VIEWED`/`AUDIT_SEARCHED` event type exists in the enum, and no test needed to prove its absence — it structurally cannot be written, since the query use case never calls `JdbcAuditRepository.record(...)`.

## F. Time / period semantics

`AuditPeriodCalculator` (`domain`, pure, `Clock`-free) mirrors `AnalyticsPeriodCalculator` (Phase 11) exactly: `Europe/Budapest` local-calendar day boundaries via the JDK's own IANA tz database, `to` always `now()` itself (inclusive upper bound — a row written at the exact query instant still counts). The one addition beyond Phase 11: `AuditPeriod.ALL` resolves to `from = null`, a genuinely unbounded lower edge, not a sentinel date. Proven at both real 2026 Hungarian DST transitions (found dynamically via `ZoneId.getRules().nextTransition(...)`, never a hardcoded guess) by `AuditPeriodIT`.

## G. Search/filter semantics

`query` (brief §17) searches only named, parameterised columns — `au.service_id` (actor), `tu.service_id` (target, when `target_type = 'USER'`), `tsa.name` (ServiceArea target name), `trl.line_code`/`trl.display_name` (RailwayLine target), and `(ae.metadata ->> 'publicReportId')` (one specific whitelisted JSONB key, read by name — not the forbidden generic `metadata::text ILIKE` search). Minimum length 2 unless blank (`AuditQueryUseCase.parseQuery`); a 1-character non-blank query is `400 VALIDATION_ERROR`, not silently ignored. `%`/`_` are escaped (`escapeLike`) so SQL wildcards in user input are literal. `eventType`/`targetType` are exact-match only, validated against the real enum (`400 AUDIT_EVENT_TYPE_INVALID`/`AUDIT_TARGET_TYPE_INVALID` for anything else). All proven by `AuditSearchIT` (9 tests) and `AuditListIT`'s filter tests.

## H. API contracts

Root: `/api/v1/service/audit`, SUPER_ADMIN only, `Cache-Control: no-store` on every response.

- `GET /events` — query params `period`/`eventType`/`targetType`/`query`/`page`/`size` (default `page=0`, `size=50`, max `100`); response `{items, page, size, totalElements, totalPages}`, `items[].eventType`/`targetType` nullable (§I).
- `GET /events/{auditEventId}` — `404 AUDIT_EVENT_NOT_FOUND` for any unknown id.
- `GET /options` — `{eventTypes: [...], targetTypes: [...]}`, the actual current enum names, for the Android filter sheet.

Error codes added: `AUDIT_FORBIDDEN` (403), `AUDIT_EVENT_NOT_FOUND` (404), `AUDIT_PERIOD_INVALID` / `AUDIT_EVENT_TYPE_INVALID` / `AUDIT_TARGET_TYPE_INVALID` (400). A too-short search query reuses the existing generic `VALIDATION_ERROR`, mirroring Phase 11's `AnalyticsFilterInvalidException`.

## I. Safe-projection architecture

`AuditEventSafeProjector` is the **only** place `AuditRawRow.metadataJson` is ever parsed (`objectMapper.readTree(...)`) — no controller, DTO factory, or repository touches it. Every value that reaches a client is read by an **explicit named JSON path** (`node.path("exactKey")`); there is no loop over the stored object's own keys anywhere in the class, so a future writer's new metadata key can never reach a response no matter what it is called (`AuditSecurityIT`'s unknown-metadata-leak test proves this with a synthetic `secret_probe` key on an otherwise-real event). The `when (row.eventType)` building `fullDetails`/`summaryDetails` is exhaustive over `AuditEventType` with **no `else` branch** — the Kotlin compiler itself refuses to build if a new event type is added to the enum without a matching branch here, which is the "explicit compile ... obligation" brief §6 asks for. A stored value the current build's enum cannot parse (`AuditRawRow.eventType == null`) instead falls through to a safe generic row/detail — safe actor, safe target, empty detail array, never raw bytes; this is the *only* path where an unmapped stored string could exist, and it never happens for any of today's 31 real event types.

Internal identifiers (`ServiceArea`/`RailwayLine`/`User` UUIDs embedded inside metadata, e.g. `USER_CREATED`'s `areaIds`, `USER_AREA_GRANTED`'s `areaId`, the `RAILWAY_LINE_SERVICE_AREA_*` events' `fromAreaId`/`toAreaId`) are resolved to names via one **batched** `serviceAreaNamesByIds(ids)` query per page/detail call (never one query per row — brief §29), and only ever surface as the resolved name, never the raw UUID.

## J. Target/actor identity resolution

Actor: `actorServiceId` — `LEFT JOIN users au ON au.id = ae.actor_user_id`; `null` for a `SYSTEM` actor (the only legitimate source of null in today's data — see §T), rendered by Android as `Rendszer`. Historical role is never inferred from the actor's *current* role (brief §9) — the actor identity carries only their Service ID, nothing else; `AuditDetailAndCrossPhaseIT`'s role-change test proves a role-changed-twice user's *first* event still reports `SERVICE_USER → MODERATOR`, not whatever the target's role is by the time of the query.

Target: `USER` → `target_user_service_id` (joined); `REPORT` → `#` + first 8 hex chars of `metadata.publicReportId`, uppercased (matches the app's own existing `shortReportId` convention, computed backend-side so Android never needs event-type-specific formatting logic); `SERVICE_AREA` → the area's *current* name (a live display label per brief §12, joined by `target_id`); `RAILWAY_LINE` → `"{line_code} · {display_name}"`; `SESSION`/`REFERENCE_DATASET` → no per-instance label at all (neither carries anything an admin would recognise) — only the type name itself (Android's own `audit_target_type_session`/`audit_target_type_reference_dataset`) is shown, which is the safe representation brief §11 calls for when no better one exists.

## K. DB index / V007 decision

**No `V007` migration.** All four dimensions the brief flagged as "likely" (timestamp, event-type+timestamp, actor+timestamp, target-type/id+timestamp) are already covered by V001's own indexes (`ix_audit_events_created`, `ix_audit_events_type`, `ix_audit_events_actor`, `ix_audit_events_target`) — Phase 12 needed no new index because Phase 2 already anticipated exactly this access pattern. Verified with real `EXPLAIN (ANALYZE, BUFFERS)` against 100,000 synthetic rows loaded into the local dev Postgres (the same methodology Phase 11 used): the default `LAST_30_DAYS` list query executes in **0.2 ms** via `Index Scan Backward using ix_audit_events_created`; an `eventType`-filtered query in **0.44 ms** via `ix_audit_events_type`; the unbounded `ALL`-period query in **0.21 ms**, still via `ix_audit_events_created`. The synthetic rows were deleted immediately afterward (`DELETE ... WHERE metadata->>'source' = 'SYNTHETIC'`), confirmed back down to the real 54-row baseline.

## L. Android architecture

`audit/data` — `AuditApi` (Retrofit), `AuditRepository`/`DefaultAuditRepository`, `AuditFilter`/`AuditPeriod`. `audit/ui` — `AuditListViewModel` (mirrors `DeletedReportsListViewModel`'s exact pagination shape: filter change resets to page 0, refresh replaces, loadMore appends), `AuditDetailViewModel` (single-fetch, read-only, no mutation method exists on it at all), `AuditListScreen`/`AuditFilterSheet`/`AuditDetailScreen`, and `AuditCopy.kt` — the one place every backend code is turned into Hungarian. Both ViewModels are session-scoped (`ServiceNavHost`'s own session `ViewModelStoreOwner`), so a logout clears all audit state (brief §60), proven live in §R and by `session isolation` reasoning shared with every earlier phase's own equivalent test.

`Változási előzmények` is reachable from exactly one place: the SUPER_ADMIN `Adminisztráció` hub. No fifth bottom-nav tab; `Moderáció`'s hub gains nothing.

## M. UI and copy

List card: event title → target line → (short summary, when the event has one) → `Végrehajtotta: <Service ID | Rendszer>` → timestamp (date+hour+minute, `Europe/Budapest`, never a raw ISO string). Detail screen: `Esemény` / `Végrehajtotta` / `Érintett elem` / `Részletek` sections, `Esemény részletei` title. Filter sheet: `Időszak` (`FlowRow` chips, 5 values including `Teljes előzmény`), `Érintett elem` (`FlowRow` chips, 6 values), `Eseménytípus` (a **vertical**, full-width selectable list — brief §50's explicit instruction not to squeeze 15+ long Hungarian labels into one horizontal row; there are 31). Search placeholder: "Felhasználó, bejelentés, terület vagy vasútvonal…". Empty state: "Nincs megjeleníthető esemény a kiválasztott feltételekkel." First-load error: "A változási előzmények most nem tölthetők be." with the Phase-11-established explicit "Újrapróbálás" action (`ErrorState`'s existing `retryLabel` parameter, reused verbatim — no new retry convention invented); a refresh failure after data already exists keeps the stale list visible with the same "Újrapróbálás" via a small `AuditErrorBanner`, a direct copy of Phase 11's own `AnalyticsErrorBanner` correction-pass pattern.

Reused rather than duplicated: `filters_title`, `action_apply`, `action_clear_filters`, `action_filters`, `action_refresh`, `analytics_retry`, `analytics_filter_period_group` and all four fixed period labels (`analytics_period_*`), `roleLabelRes`, `hu.orszembejelento.service.reports.domain.statusLabelRes`, `hu.orszembejelento.service.common.ui.moderationReasonLabelRes` — brief §26's own instruction against a second inconsistent localization table.

## N. Privacy/security tests

`AuditSecurityIT` (6 tests) — the first three are behavioural/substring proofs, the last three are **structural** proofs that parse the real response JSON with the same `ObjectMapper`/`JsonNode` the production code uses, rather than relying on string search alone:

1. A synthetic `secret_probe` metadata key on an otherwise-real `USER_ROLE_CHANGED` event never appears, by key or by value, in either the list or the detail response, while the row's legitimately-whitelisted `newRole` value is still present (proving the absence is the whitelist working, not the whole row being suppressed).
2. A representative set of real events (role change, area creation, area grant) produces list/detail responses free of `password`/`credential`/`refreshToken`/`accessToken`/`sessionSecret`/`capabilityHash`/`bearer `/`authorization`/`rawMetadata`, case-insensitively (substring proof, kept as a coarse belt-and-suspenders check).
3. Reading the audit trail six ways in a row never changes the row count or content.
4. **Closed field shape**: every list item, summary/detail entry, and detail response's parsed JSON field-name set is asserted equal to the exact expected DTO field set (`AuditListItemResponse`/`AuditEventDetailResponse`/`AuditDetailItemResponse`'s own declared fields) — a raw `metadata` object, or any other unexpected field, would fail this test structurally even if its name or content happened to dodge every substring in test (2).
5. **No internal UUID as display data**: every textual field and every summary/detail `value` across real list and detail responses (covering a role change, an area grant) is asserted to never match the raw-UUID shape — proving actor/target UUIDs are never exposed as display content, checked by parsing the JSON tree, not by scanning raw text.
6. **No forbidden term outside a closed known-safe set**: every textual field and detail value is scanned for `password`/`credential`/`token`/`hash`/`secret`/`capability`/`authorization`/`bearer`, with a single explicit exemption — a value that is itself a member of the real, closed `AuditEventType` enum or the real, closed `RevocationReason` object (e.g. `INITIAL_PASSWORD_CHANGED`, `REFRESH_TOKEN_REUSE`) — proving the earlier manual observation that "`password` only ever appears as the *event type code*" is a structurally enforced guarantee, not a coincidence of today's fixture data.

## O. Backend tests

50 new tests, all green, across six classes:

- `AuditAuthorizationIT` (5) — SERVICE_USER/territorial MODERATOR/global MODERATOR forbidden on all three endpoints; SUPER_ADMIN allowed; `no-store` on every response.
- `AuditPeriodIT` (12) — TODAY/LAST_7/30/90_DAYS exact-boundary pairs, `ALL`'s unbounded lower edge, default period, unknown-period rejection, both real 2026 DST transitions, the pure calculator.
- `AuditListIT` (11) — default period, newest-first ordering, deterministic `id DESC` tie-break on identical timestamps, default/max page size, second-page distinctness, `eventType`/`targetType` exact and combined filters, invalid-code rejection, empty-result shape.
- `AuditSearchIT` (9) — actor/target Service ID, ServiceArea name, RailwayLine code, no-match, 1-char rejection, blank-is-no-search, literal SQL wildcards, and the negative proof that a legitimate-but-unwhitelisted metadata *value* (`MODERATOR`) is never matched.
- `AuditDetailAndCrossPhaseIT` (7) — 404 on an unknown id, and six proofs built from **real HTTP mutations** (`createUser`, `changeRole`, `delete`/`restore`, `createArea`/`renameArea`, `assignLine`, `grantArea`) rather than any direct `record()` call: real user creation, a role change whose first event's detail is unaffected by a second later change, moderation delete+restore with the true reason, a ServiceArea rename with true old/new names, a RailwayLine move with true from/to area names, and an area grant with a resolved (never raw) area name.
- `AuditSecurityIT` (6) — §N above.

All 50 confirmed green together in one run (`./gradlew test --tests "hu.orszembejelento.backend.audit.*"`, `BUILD SUCCESSFUL`) immediately after the three structural security tests were added, and again as part of the full-build run recorded in §T.

## P. Android tests

22 unit tests (`AuditCopyTest` 6, `AuditListViewModelTest` 11, `AuditDetailViewModelTest` 5): the mandatory mapping-coverage contract test (every one of the 31 actual backend event types and 6 target types has a real Android label — an explicit hand-maintained fixture list, since Android and the backend share no code, per brief §69's own accepted approach), default period, pagination append/reset, filter-driven reload-to-page-0, refresh-replaces-vs-loadMore-appends, first-load vs stale-data error handling, one-tap-one-request retry, `SessionEnded` handling from either the options or the events call, and the `AuditFilter.activeFacetCount` helper.

## Q. Cross-phase integration tests

Covered by `AuditDetailAndCrossPhaseIT` (§O) for the backend and by §R below for the full stack — every event type family from Phase 2/6/7/9/10 is proven queryable through a real business mutation, not a synthetic insert.

## R. Live emulator verification

Performed end to end against the real backend, the real throwaway dev PostgreSQL (`orszem-v2-dev-db`), and a real `orszem-test` AVD (cold-booted mid-session to clear an unrelated SystemUI ANR the earlier long instrumented-test run had left behind) — no fake runtime repository anywhere.

**Manual API security review (brief §80)**, done first, before any UI walkthrough: real `GET /audit/options`, `GET /audit/events` (default, `targetType=USER`, `targetType=SERVICE_AREA`, `targetType=RAILWAY_LINE`), and `GET /audit/events/{id}` calls against the live backend with a real SUPER_ADMIN bearer token, inspected by hand and swept with `grep -io` for `password`/`credential`/`token`/`hash`/`secret`/`Authorization`/`rawMetadata`/`capability` — the only match across every response was the legitimate `INITIAL_PASSWORD_CHANGED` *event-type code*, never a value. This also re-confirmed, live, the exact Phase-11-era `RAILWAY_LINE_SERVICE_AREA_MOVED` event (line L45, Keleti→Szombathelyi) is still correctly queryable months later with resolved `FROM_AREA`/`TO_AREA` names.

**Real business mutations**, all through the actual production UI (never a direct repository call):

- User management: created throwaway `SZ-836405` (SERVICE_USER, Keleti Teruleti Kozpont); changed its role SERVICE_USER→MODERATOR→SERVICE_USER; granted Szombathelyi Teruleti Kozpont; revoked Keleti Teruleti Kozpont.
- ServiceArea administration: created "Vasvari Ideiglenes Terulet"; renamed it to "Vasvari Atnevezett Terulet".
- RailwayLine administration: moved line `L30` (Szombathely–Kőszeg) from Szombathelyi Teruleti Kozpont to the new area; then unassigned it.
- Public report lifecycle: submitted a real throwaway report (`#A032E2F4`, "Lopás", Vasvár, routed via the L30 line to Szombathelyi) through the **Public** app; moderation-deleted it (reason "Duplikált bejelentés") and restored it through the **Service** app.

**Változási előzmények UI**, opened as SUPER_ADMIN and checked against every one of those real mutations:

- Newest-first list confirmed, including every event type produced above, each with a natural Hungarian title, safe actor (`SZ-153255`), and safe target — never a raw code or UUID anywhere on screen.
- `SESSION` target type confirmed live to show no per-instance label (just "Munkamenet"), matching the documented design decision — seen on real `SESSION_CREATED`/`SESSION_REVOKED` rows from this very session's own logins/logout.
- Server-side search proven against three different whitelisted columns: actor/target Service ID (`SZ-836405`), public report identifier (`A032E2F4`), and RailwayLine code (`L30`) — each returned exactly the matching rows, live.
- Filter sheet opened and exercised: `Időszak`/`Érintett elem` `FlowRow` groups and the vertical, full-width `Eseménytípus` list (brief §50) both confirmed; applied a `targetType=REPORT` filter and saw the request re-run with the new facet.
- Detail screens opened for a moderation-restore event (`ÚJ → ÚJ`, second-precision timestamp `21:05:02`), an area-grant event, and a RailwayLine-move event (`Korábbi terület: Szombathelyi Teruleti Kozpont` → `Új terület: Vasvari Atnevezett Terulet`) — every one showed only `Esemény`/`Végrehajtotta`/`Érintett elem`/`Részletek`, no raw metadata, and **no restore/undo/replay control anywhere**, confirming the read-only guarantee live, not just in tests.
- Session isolation proven twice: logged out via `Saját fiók → Kijelentkezés`, confirmed the app returned to the login screen, logged back in as the same SUPER_ADMIN, and opened `Változási előzmények` again — the search field was blank and the filter badge was gone (no leaked prior search/filter state), and the fresh list correctly showed the very `SESSION_REVOKED`/`SESSION_CREATED` rows the logout/login had just produced.
- Network-error handling exercised for real, unprompted: the emulator's virtual NIC intermittently dropped the connection to the host backend several times during the session (confirmed as a genuine transient emulator-network condition, not a backend fault, via `curl`/`ping` health checks run in parallel each time) — every occurrence correctly surfaced the exact documented copy ("A kiszolgáló nem érhető el. Ellenőrizze a kapcsolatot." inline banner over a stale-but-visible list, and "A változási előzmények most nem tölthetők be." on a first-load detail fetch), and every "Újrapróbálás" tap recovered cleanly with no duplicate requests or stuck state.
- Accessibility: font scale set to 1.3 system-wide: the list, cards, and detail screen all re-rendered with larger text, full wrapping, and no clipping or overlap; the bottom nav's `Adminisztráció` label wrapped to two lines exactly as the already-accepted, pre-existing app-wide narrow-width behavior predicts, and was deliberately left untouched per brief §64.
- Temporary-credential safety: the one-time credential dialog shown when creating `SZ-836405` (Service ID + a generated password) was **never screenshotted** — inspected only via `uiautomator dump`'s text content, then dismissed via "Elmentettem", exactly matching brief §73's requirement that a real temporary credential must never appear in any audit-adjacent screenshot.

16 screenshots were captured for owner review (§ list below), covering the live hub, default and multi-event-type lists, all three search cases, the filter sheet (both compact and full vertical event-type list), a filtered result, four representative detail screens (moderation restore, area grant, RailwayLine move, and a first-load error state), the session-isolation proof, the inline network-error-and-retry state, and both list and detail at font scale 1.3. None contain a raw UUID, a raw backend code, or any credential-bearing content.

## S. Accessibility

Verified at font scale 1.3 and narrow width (the same `wm size`/`settings put system font_scale` technique every earlier phase used): the vertical event-type filter rows, the `FlowRow` period/target-type chips, the list card's stacked (never crammed) title/target/actor/timestamp lines, and the detail screen's stacked label/value `DetailRow` all wrap cleanly with no text shrinking. `navigationBarsPadding()` on the filter sheet clears 3-button navigation, matching every Phase 8-11 filter sheet. No meaning is conveyed by color alone anywhere on this screen. The known, already-owner-accepted Phase 15 note (extreme narrow-width + 1.3 bottom-nav label wrapping is shared/pre-existing app-wide behavior) applies here unchanged and was deliberately left untouched.

## T. Full regression / CI

Backend: `./gradlew build` (full suite, not just the audit package) — **733/733 tests passed, 0 failures**, real PostgreSQL via Testcontainers throughout. This full run caught a genuine pre-existing bug the isolated audit-package run had not: `AuditDetailAndCrossPhaseIT`'s role-change test issued two `changeRole` mutations with no clock advance between them: when both landed in the same `created_at` tick, the deterministic-but-not-chronological `id DESC` tie-break (the same tie-break `AuditListIT`'s own "two events written at the exact same instant" test documents) could return the two role-change events in the wrong order, making `events.last()` pick the *second* mutation instead of the first. Fixed with the same `mutableClock.advance(Duration.ofSeconds(1))` pattern `AuditListIT.kt:79` already established, forcing genuinely distinct, ordered timestamps. Re-confirmed green both in isolation (`--tests "hu.orszembejelento.backend.audit.*"`, 50/50) and in the full 733-test suite immediately after.

Android: an earlier `connectedDebugAndroidTest` attempt failed on an emulator/tooling fault (`Can't find service: package`/`activity` — the AVD's package manager service was transiently unavailable mid-run, diagnosed via `adb shell pm`/`service list` and confirmed **not** a real test or app assertion failure) and was correctly **discarded, not counted** as a regression result. The emulator was confirmed healthy again (`pm list packages`, `service list` both responding, `sys.boot_completed=1`) and the full suite was re-run from a clean state (`--rerun`): `BUILD SUCCESSFUL`. Real, fresh JUnit XML confirms:

- `service-app:connectedDebugAndroidTest` — **86/86 passed, 0 failures** (`AnalyticsComposeTest` 22, `AuditComposeTest` 19 — the full new Phase 12 instrumented suite, all green — `ServiceAreaAdminComposeTest` 16 including the one-line `onOpenAudit` fixture fix, `ModerationDeleteActionComposeTest` 4, `DeletedReportDetailComposeTest` 3, `LoginComposeTest` 2, `AuthFlowInstrumentedTest` 7, `KeystoreTokenStoreInstrumentedTest` 10, `CredentialDialogComposeTest` 1, `DeletedReportsListComposeTest` 1, `ReportWorkflowConflictComposeTest` 1).
- `public-app:connectedDebugAndroidTest` — **16/16 passed, 0 failures**.
- `:service-app:assembleDebug :public-app:assembleDebug :public-app:assembleRelease` — all green.

Backend: full `./gradlew build` (not just the audit package) re-run against the final commit before push — result recorded immediately below once complete.

Web/reference-data/deploy-config: untouched by this branch (confirmed via `git status` against `main`).

Final pushed HEAD and its 5/5 GitHub Actions result are recorded in the PR and the assistant's stop report for this turn, not re-edited into this document afterward — the same reasoning Phase 9-11 settled on.

## U. Known limitations

- SUPER_ADMIN-only, by design — no territorial or global-MODERATOR audit visibility exists or was considered.
- No arbitrary custom date range; only the five fixed periods.
- No export in any format (CSV/PDF/Excel/copy/share/print) and no audit mutation/undo of any kind.
- A target's *current* display name (a ServiceArea's current name, a RailwayLine's current code/display name) may differ from its name at the time of the event unless that historical value was actually stored in the event's own metadata (only `SERVICE_AREA_RENAMED`'s old/new name and the `RAILWAY_LINE_SERVICE_AREA_*` from/to area names are — everything else shows a live label).
- An actor's historical role/status is never inferred from their current role/status — only what the specific event's own metadata actually stored.
- No IP investigation UI, no external SIEM, no audit alerting, no streaming/live updates, no audit retention management.
- Unknown future metadata is intentionally hidden until an explicit whitelist entry is added — a deliberate safety default, not an oversight (§I).
- The `SESSION`/`REFERENCE_DATASET` target types show only their type name, never a per-instance identifier — neither carries anything an admin would recognise as a "which one" fact.

## V. Owner visual approval

**GIVEN — 2026-09-14.** The owner reviewed all 16 delivered screenshots and approved, without requested changes: the Adminisztráció hub integration; the `Változási előzmények` list; mixed audit-event cards; Service ID search; report-ID search; server-side filtered results; the filter sheet; the vertical event-type selector; moderation/report audit presentation; RailwayLine audit presentation; historical before/after detail presentation; actor/target identity presentation; the network-error state with explicit "Újrapróbálás"; the detail error state; the session-isolation result; the font-scale 1.3 list and detail screens; and the existing dark-navy/gold visual language. No further Phase 12 product, UI, or code changes were requested.

Two **non-blocking** notes were recorded for a future phase, explicitly not to be acted on in Phase 12:

1. The already-known shared bottom-navigation label wrapping at extreme narrow width + font scale 1.3 is not a Phase 12 regression (brief §64's own note, reconfirmed).
2. With 31 audit event types, the full-width vertical `Eseménytípus` filter (brief §50) is safe and readable but requires substantial scrolling. A future phase (tentatively Phase 15) may consider a sticky filter action/footer or an ergonomically equivalent polish — explicitly **not** a Phase 12 redesign.

Following approval: this document was updated with the approval line above (the only permitted post-approval change, per brief §81), committed and pushed as a single docs-only commit, and the PR was opened only after 5/5 CI was reconfirmed green on that exact final HEAD. See the assistant's stop report for the PR link, the exact final SHA, and that SHA's CI results.
