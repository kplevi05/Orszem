# Phase 11 Engineering Report — Analytics / Statistics

Branch: `feature/v2-analytics` (base: `main` @ `4766afc`, Phase 10 merged via PR [#14](https://github.com/kplevi05/Orszem/pull/14))

## A. Git

- Backend: descriptive analytics (`analytics` package — domain/application/infrastructure/api), no new migration (see §J for why).
- Android: the live `Statisztika` screen replacing `StatsPlaceholderScreen`, its ViewModel, filter sheet, and native Compose trend chart.
- One infrastructure fix, unrelated to the feature itself but discovered while adding it: `AbstractAuthIntegrationTest` now explicitly `@Import`s its own `Containers` `@TestConfiguration` (see §N — Spring Boot's *implicit* nested-`@TestConfiguration` detection for that class proved unreliable for some, not all, leaf test classes several levels below it).
- No migration was edited, renumbered or deleted, and none was added — Phase 11 needed no schema change.

## B. Scope and product rules (brief §1/§10)

Descriptive-only: total volume, current-status breakdown, a daily trend, a category breakdown, and a top-5 event-type list — all for reports **received** (`submitted_at`) in a fixed period, by their **current** workflow state. No AI/LLM, no anomaly detection, no forecasting, no risk scoring, no employee/staff performance analytics of any kind (not even for SUPER_ADMIN), no exports, no scheduled/emailed reports, no custom dashboard builder, no public or Service Web analytics, no maps/heatmaps, no arbitrary SQL endpoint, no audit-query UI, no notifications. Phase 12 (audit) was not started.

## C. Time semantics — `submitted_at`, Europe/Budapest (brief §2/§3/§29)

The analytics clock is `reports.submitted_at` (backend ingestion time), never `occurred_at` — UI copy says "Beérkezett bejelentések" ("received"), never "események száma". Day boundaries are local-calendar boundaries in `Europe/Budapest`, computed by [`AnalyticsPeriodCalculator`](../backend/src/main/kotlin/hu/orszembejelento/backend/analytics/domain/AnalyticsPeriodCalculator.kt) — a pure function of `(AnalyticsPeriod, now: Instant)`, never device-local time, never a hardcoded UTC offset. Four fixed periods only: `TODAY`, `LAST_7_DAYS`, `LAST_30_DAYS` (default), `LAST_90_DAYS`. No custom range, no `ALL_TIME`.

The resolved window is **inclusive on both ends**, `[from, to]` — not `[from, to)`. `to` is literally `clock.instant()` ("now"), so a report submitted at the exact instant a request is made must still be counted; an exclusive upper bound would silently drop it. This was a genuine bug caught by the backend test suite itself (see §N) before this report was written, not a hypothetical.

Daily trend buckets use `(r.submitted_at AT TIME ZONE 'Europe/Budapest')::date` in SQL — PostgreSQL's own IANA tz database, real DST rules, never a hand-rolled offset. Verified against the actual 2026 Hungarian spring-forward and fall-back transitions, found dynamically via `ZoneId.getRules()` rather than a guessed calendar date (see §N).

## D. Inclusion / moderation semantics (brief §4)

A report is included iff it is **not currently** moderation-deleted — the exact `LEFT JOIN report_moderation_episodes rme ON rme.report_id = r.id AND rme.restored_at IS NULL ... rme.id IS NULL` predicate every other ordinary report read in this codebase already uses (`JdbcReportWorkflowQueryRepository`, `JdbcServiceAreaRepository`). A restored report re-enters analytics under its **original** `submitted_at`, immediately, with no duplicate counting — proven by a dedicated integration test replaying the brief's own 6-step sequence (§27) verbatim, and live against the real backend (§Q).

## E. Authorization / scope (brief §7/§8/§22-24)

No second authorization model. [`AnalyticsController`](../backend/src/main/kotlin/hu/orszembejelento/backend/analytics/api/AnalyticsController.kt) loads the actor through the **existing** `ReportWorkflowActorLoader`, producing the **existing** `ReportWorkflowActor` (userId, role, globalAreaAccess, ownActiveAreaIds) — the identical type the report-workflow queues already use. Analytics never invents its own actor/loader.

Role rules, expressed in SQL before aggregation (never "aggregate then hide in Kotlin", brief §55):

| Role | Default (no filter) scope | May filter to a specific area | `Besorolatlan` |
|---|---|---|---|
| SERVICE_USER (territorial) | own active assigned areas | own active areas only | never |
| SERVICE_USER (global) | every active area | any active area | never |
| MODERATOR (territorial) | own active assigned areas | own active areas only | never |
| MODERATOR (global) | every active area + UNCLASSIFIED | any active area | yes |
| SUPER_ADMIN | everything, incl. inactive-area history | any area, active or inactive | yes |

`areaId` and `unclassifiedOnly=true` are mutually exclusive — rejected outright (400 `VALIDATION_ERROR`) rather than one silently overriding the other. A territorial actor filtering to an area outside their scope, and one filtering to a nonexistent area, both get the identical 404 `ANALYTICS_AREA_NOT_AVAILABLE` — an existence-safe response, so the endpoint cannot be used to enumerate other areas.

**The one deliberate difference from the report-workflow queues' own visibility rule**, documented directly in [`JdbcAnalyticsRepository`](../backend/src/main/kotlin/hu/orszembejelento/backend/analytics/infrastructure/JdbcAnalyticsRepository.kt)'s KDoc: a global MODERATOR's report-workflow *queue* visibility is intentionally unrestricted by area status (so past operational work in a since-deactivated area is never stranded from the Archive), but Phase 11 brief §7/§26 explicitly scopes a global MODERATOR's *analytics* to "all currently-operational" (active) areas only, reserving inactive-area history for SUPER_ADMIN alone. This is a one-line, intentional, brief-mandated restriction (`sa.status = 'ACTIVE'` alongside the always-visible UNCLASSIFIED bucket) — not an accidental copy-paste divergence — and is called out explicitly so the two authorization surfaces never drift silently against each other.

`GET /service/analytics/areas` mirrors the same rules for the filter-sheet's own area-choice list, plus a `canViewUnclassified` boolean — `Besorolatlan`/`Minden terület` are never synthetic `ServiceArea` rows (brief §4/§23).

## F. Routing-snapshot analytics invariant (brief §5/§25/§28)

Area grouping and the area filter both join on `report_routing_snapshots.service_area_id` — the **immutable** snapshot resolved once at submission time — never on the *current* `service_area_railway_lines` mapping. Moving a RailwayLine between ServiceAreas (Phase 10) changes where a *future* submission on that line routes; it never regroups a report already counted under the old area. Renaming a ServiceArea changes only the displayed label for that grouping id, never which reports belong to it — no area-name snapshot table exists or is needed (brief §25).

Proven three ways:
1. A dedicated backend integration test (`AnalyticsModerationAndRoutingIT`) running the brief's exact 8-step sequence (§28) plus its §63 continuation (moderation delete/restore and a full NEW→IN_PROGRESS→ARCHIVED walk, asserting the status breakdown changes while the total never does).
2. Live, against the real backend and real Postgres (§Q): RailwayLine `L45` was assigned to "Keleti Teruleti Kozpont" (5 reports submitted through it), then **moved** to "Szombathelyi Teruleti Kozpont" through the real Phase 10 endpoint. Keleti's analytics total stayed at **5** before and after the move. A brand-new report submitted afterward on the same (now-moved) line was counted under **Szombathelyi** (6 → 7), never Keleti.
3. The 300k-row synthetic load test used for the index decision (§J) incidentally re-confirms the join shape scales correctly under real query-plan inspection.

## G. API contracts (brief §14-24/§30)

Root: `${ApiPaths.V1}/service/analytics` — no public endpoint, no generic SQL/query endpoint.

- `GET /summary?period=&areaId=&unclassifiedOnly=&categoryCode=` → `AnalyticsSummaryResponse` (period echo, `generatedAt`, `totalReports`, `statusCounts`, `trend[]`, `categories[]`, `topEventTypes[]`).
- `GET /areas` → `AnalyticsAreaOptionsResponse` (`areas[]` with `id`/`name`/`active`, `canViewUnclassified`).

Error codes, a fresh dedicated set (never reused from an unrelated area/category surface): `ANALYTICS_AREA_NOT_AVAILABLE` (404), `ANALYTICS_UNCLASSIFIED_FORBIDDEN` (403), `ANALYTICS_PERIOD_INVALID` (400), `ANALYTICS_CATEGORY_NOT_FOUND` (404); the mutual-exclusion violation reuses the existing generic `VALIDATION_ERROR` (400) rather than inventing a fifth code for one edge case. Every response carries `Cache-Control: no-store` (brief §31) — authenticated operational information, never client-cached.

## H. Aggregate-query design (brief §12/§54/§56)

Four bounded `GROUP BY` queries sharing one `[from, to]` + role-scope + optional-filter `WHERE` predicate — never a raw-row fetch aggregated in Kotlin, never N+1:

1. `statusCounts` — `SELECT r.status, COUNT(*) ... GROUP BY r.status`.
2. `dailyCounts` — `SELECT (submitted_at AT TIME ZONE 'Europe/Budapest')::date, COUNT(*) ... GROUP BY 1`; only days with ≥1 report are returned from SQL, the **use case** zero-fills every other requested local date (brief §18) from the period's own date list.
3. `categoryCounts` — grouped by category, `ORDER BY COUNT(*) DESC, code ASC`.
4. `topEventTypes` — grouped by event type, same tie order, `LIMIT 5`.

## I. Transaction consistency (brief §13/§58)

`AnalyticsQueryUseCase.summary` runs all four queries inside one short **read-only `REPEATABLE READ`** transaction — chosen over forcing everything into one unreadable mega-query, and over trusting default `READ COMMITTED` (under which a concurrent workflow mutation between two of the four queries could make `totalReports` disagree with the status-count sum). Only the aggregate-query block is inside the transaction; no network/client work is ever done while it is held open.

A dedicated concurrency test (`AnalyticsConsistencyIT`) does not assert which side of any race "wins" (per brief §58's own instruction) — it runs a background thread continuously cycling five fixture reports between NEW and IN_PROGRESS while the main thread repeatedly re-reads the summary, asserting on **every single response**: status counts sum to total, trend sums to total, and `totalReports` itself never drifts from 5 regardless of the churn underneath.

## J. Database indexes — no V007 (brief §11/§56)

Inspected before deciding: `ix_reports_status_submitted_at` (status, submitted_at) does not help a status-agnostic `submitted_at` range scan (status is the leading column); no plain `reports(submitted_at)` index exists. Rather than reason about this abstractly, a throwaway 300,000-row synthetic dataset (spread over 400 days, realistic category/status mix) was loaded into the local dev database and `EXPLAIN (ANALYZE, BUFFERS)` run against the real analytics queries:

- Status-count query, unfiltered `LAST_90_DAYS` window (~68,000 matching rows out of 300,002): **132.8 ms**, `Parallel Seq Scan` on `reports`.
- Daily-trend query, unfiltered `LAST_30_DAYS` window (~22,000 matching rows): **85.6 ms**, same plan shape.

Both are well within interactive-dashboard latency at a scale far beyond this system's realistic near-term data volume, without any new index. Per the brief's own stated preference ("NO V007... preferable to unused indexes"), **no `V007` migration was added**. The synthetic data was fully removed from the dev database afterward (`DELETE` by its own fixture ids, verified back to the pre-existing 2-row baseline). This decision should be revisited once real production volume is known, not before.

## K. Android architecture (brief §33-51)

- `analytics/data/AnalyticsApi.kt` + `AnalyticsRepository.kt` — mirrors `AreaAdminApi`/`AreaAdminRepository` exactly: a Retrofit interface, a plain-interface repository (`DefaultAnalyticsRepository` in production, a hand-written fake in every test), every call routed through `AuthRepository.authorizedCall`.
- `analytics/ui/AnalyticsViewModel.kt` — read-only, no mutation. `refresh()` loads `areaOptions` and `summary` together; a `SessionEnded` from either short-circuits to `onSessionEnded()` without ever calling the other. A failed refresh keeps whatever was previously shown and surfaces only an error banner/state — never blanks the screen on a transient failure.
- Session-scoped, not Activity-scoped: `ServiceNavHost` constructs the `AnalyticsViewModel` against its own session-lifetime `ViewModelStoreOwner`, the same one `ServiceAreaAdminListViewModel` already uses — a logout clears the whole store, so the next signed-in user on the same device starts from a brand-new instance, never a previous user's aggregates or filter state (brief §47).
- `analytics/ui/AnalyticsScreen.kt` — KPI grid (2×2, not 4-across, to stay safe at font scale 1.3 on a narrow phone), the trend section, category section, top-events section, empty state, freshness line. `analytics/ui/TrendChart.kt` — native Compose bars (`Box` + `fillMaxHeight(fraction)`, `Row` + `horizontalScroll`), no charting library.
- `analytics/ui/AnalyticsFilterSheet.kt` — `ModalBottomSheet` + `navigationBarsPadding()` + `FlowRow` filter groups, the exact established pattern from `ReportFilterSheet`/`DeletedReportFilterSheet`. `areaId`/`unclassifiedOnly` are kept mutually exclusive at the UI layer itself (selecting one always clears the other), mirroring the backend's own validation.
- Category options come from the existing, already-unauthenticated Public catalogue (`CatalogRepository`) — never hard-coded.

## L. Production copy (brief §35-46/§66)

Exact Hungarian strings as specified: `Statisztika` (title, reusing the existing `stats_title` resource — the placeholder string it used to pair with, `stats_unavailable`, was retired along with `StatsPlaceholderScreen`), subtitle "A jogosultsági körébe tartozó bejelentések összesítése.", period labels "Ma"/"Utolsó 7 nap"/"Utolsó 30 nap"/"Utolsó 90 nap", area labels "Minden jogosult terület" (never "Minden terület" — brief §24's own distinction) / "Besorolatlan" / an "(Inaktív)" suffix for SUPER_ADMIN, category default "Minden kategória", KPI labels "Összes bejelentés"/"Új"/"Folyamatban"/"Lezárt", section titles "Beérkezett bejelentések"/"Kategóriák"/"Leggyakoribb eseménytípusok", empty copy "Nincs megjeleníthető adat a kiválasztott időszakban.", load-error copy "A statisztika most nem tölthető be." with the existing retry action label, and freshness "Frissítve: HH:mm".

Grepped before screenshots, per brief §66: no raw period code, no raw status enum (`NEW`/`IN_PROGRESS`/`ARCHIVED`/`UNCLASSIFIED`), no `adminVersion`/`workflowVersion`, no raw category/event-type code, no internal id, and no literal "backend"/"Phase 11" string anywhere in the rendered UI — also asserted directly by an instrumented test (`no_adminVersion_or_workflowVersion_or_backend_word_or_raw_period_code_is_ever_shown`).

## M. Privacy — no personnel analytics (brief §9/§10/§57)

The response types (`AnalyticsSummaryResponse` and everything it contains) have no field that could carry a report id, a user id, an assignee, a moderation actor, or a capability/token value — there is structurally nothing to redact. Asserted by a backend test that greps the raw JSON body for every such forbidden substring, and by the Android instrumented test doing the equivalent check on rendered text. No employee/staff performance analytics of any kind exists anywhere in this phase, for any role including SUPER_ADMIN — this was a hard brief requirement (§10), not a UI omission to be added later.

## N. Backend tests (brief §57) — 38 new tests, all green

- `AnalyticsAuthorizationIT` (15) — every role/scope row in §E's table, the mutual-exclusion rejection, the existence-safe 404, and the `/analytics/areas` role-scoped list including the inactive-area/`canViewUnclassified` cases.
- `AnalyticsPeriodIT` (9) — the exact-midnight boundary (both sides), `LAST_7/30/90_DAYS` bucket counts, the unknown-period rejection, the default period, and both real 2026 Hungarian DST transitions (found dynamically via `ZoneId.getRules()`, never a guessed date).
- `AnalyticsSummaryIT` (11) — the empty case, status-sum-equals-total, trend-sum-equals-total, category sort/tie-order/sum-equals-total, the `categoryCode` filter narrowing both the summary and the breakdown, the unknown-category rejection, top-event-types limit/sort, the privacy grep, and the `no-store` header on both endpoints.
- `AnalyticsModerationAndRoutingIT` (2) — the two proofs the brief calls out as mandatory (§27's exact 6 steps; §28's exact 8 steps plus the §63 status-transition continuation).
- `AnalyticsConsistencyIT` (1) — the REPEATABLE READ snapshot-coherence proof under genuine concurrent mutation (§I above).

Two genuine bugs were caught by this suite before it went green, not found by inspection:
1. **The inclusive-upper-bound bug** described in §C — every non-empty-period test initially failed with `totalReports=0` because `submitted_at < :to` excluded a report submitted at the exact same `MutableClock` instant as the query itself. Fixed by making the window `[from, to]`.
2. **Two test-authoring bugs in my own new tests**, caught immediately by the suite's own accurate failures, not shipped: the moderation delete/restore endpoints return `204`, not `200` (I had asserted the wrong status in a fixture helper); and a fixture report submitted after a RailwayLine move must reuse the *moved line's own settlement relation* (`areaA.settlementId`), not the target area's unrelated settlement — a plausible but wrong first attempt at the §28 fixture.

Full backend suite after these fixes: **684 tests, 0 failures** (the prior Phase 10 baseline plus these 38).

## O. Android tests (brief §60-61)

Unit (13 new, `AnalyticsViewModelTest` + `ErrorCopyTest` additions): initial load fetching both calls, filter-driven reload and the `LAST_30_DAYS` default, a summary failure keeping stale data with an error surfaced, a first-load network error with no data yet, `SessionEnded` from either call (never both), retry not accumulating stale filters, area-options pass-through including `canViewUnclassified`, and the filter's own mutual-exclusion accounting. `ErrorCopyTest` extended with the four new codes, including the "never silently collapses to the generic fallback" check.

Instrumented (22, `AnalyticsComposeTest`, real emulator — 16 from the original submission plus 6 added in the owner correction pass, §V): the screen is live not a placeholder; KPI cards show real counts; category/top-event rows show display names, never a raw code; no forbidden raw string anywhere; the empty state renders cleanly with no broken chart section; a first-load network failure is retryable via the explicit "Újrapróbálás" action and calls the backend exactly once per tap (never a poll loop, never the app-wide "Újrapróbálkozás" wording); a refresh failure *after* a successful load keeps the stale KPI content visible and offers the same one-tap "Újrapróbálás"; the filter sheet opens and shows all three groups with every real period/area/category label; applying a period reloads with it and never renders the literal string "Minden terület"; `Besorolatlan` is absent for a territorial actor and present for an eligible global one; a `SUPER_ADMIN`-level actor sees the `(Inaktív)` suffix; selecting an area clears `unclassifiedOnly` and vice versa; clearing filters resets to the default period with no area/category; two separate ViewModel instances backed by two separate fakes never share state (the architectural session-isolation guarantee from §K, made concrete); and — the owner correction pass's own coverage — the default period is always visible with no area/category/Besorolatlan chip when nothing non-default is selected, a selected area's display name appears in the scope summary (never its id), `Besorolatlan` appears there when selected, a selected category's display name appears there (never its code), and a three-chip combination (period + area + category) stays present and displayed at once, the Compose-test-feasible proxy for the wrap-safety verified live in §V/§Q.

Existing instrumented suites (Phase 8/9/10, plus every Public-app instrumented test) were re-run unchanged and stayed green in the same pass, both in the original submission and again after the owner correction pass. Total connected-test run after the correction pass: **service-app 67 tests / public-app 16 tests, 0 failures**, on the real `orszem-test` AVD.

## P. Cross-phase tests

Covered by `AnalyticsModerationAndRoutingIT` (§F/§N) for the backend, and live in §Q for the full stack. `user_service_areas`/`global_area_access` were never touched by any Phase 11 code — analytics only *reads* the actor's current scope through the existing `ReportWorkflowActor`, exactly like every other report-workflow surface already does; there was nothing new to prove here beyond what Phase 6/7's own tests already establish, and no Phase 11 code path writes to either table.

## Q. Live emulator verification (real backend, real Postgres, real emulator — no fake data)

A throwaway dataset was built entirely through real APIs against the local dev database: two ServiceAreas ("Keleti Teruleti Kozpont", "Szombathelyi Teruleti Kozpont"), two RailwayLines related to one verified settlement, 10 Public reports submitted across 3 categories and 7 event types, one moderation delete+restore cycle, one workflow claim+close (archive), one claim leaving a report IN_PROGRESS, one UNCLASSIFIED report (no line selected), and — per brief §62's explicit allowance — `submitted_at` was backdated directly in the throwaway database *after* real creation, for a readable multi-day trend; no other column and no application behavior was touched, and this is disclosed here plainly rather than hidden.

All 18 steps of brief §74's flow, plus the role matrix from §64, were run end to end:

- **SUPER_ADMIN**: full unfiltered overview (12 total, correct status split, a real multi-day trend, 3 categories, 5 top event types); the area/category/`Besorolatlan` filter sheet; a single-area filtered view; the `Besorolatlan` filter; a freshly created-then-deactivated throwaway area showing the `(Inaktív)` suffix — the one indicator only SUPER_ADMIN sees.
- **Territorial SERVICE_USER** (Keleti): correctly scoped to 5 reports; filter sheet offers only "Minden jogosult terület" and "Keleti Teruleti Kozpont" — no `Besorolatlan`, no other area.
- **Territorial MODERATOR** (Szombathelyi): correctly scoped to 6 reports (including the restored one), same filter restriction as the territorial SERVICE_USER.
- **Global MODERATOR**: correctly scoped to all 12 (every active area plus UNCLASSIFIED); filter sheet offers both active areas and `Besorolatlan`, but **not** the inactive throwaway area — the one restriction that deliberately differs from SUPER_ADMIN (§E).
- **Cross-phase immutability**, live (§F item 2): moving RailwayLine `L45` from Keleti to Szombathelyi left Keleti's total at 5 both before and after the move; a new report submitted afterward on the same line counted under Szombathelyi, never Keleti.
- **Empty state**: `TODAY` + an unrelated category together produced a genuine zero-report result, rendering the exact empty copy with all four KPI cards at 0 and no broken chart section.
- **Selected category**: filtering to "Erőszak és közvetlen veszély" narrowed the total to 7, the category list to that one row, and the top-event list to its own 3 event types.
- **Network-error retry**: the emulator's own host networking (a known, pre-existing environment characteristic of this Windows/WHPX machine — see §T) produced several genuine transient `NetworkError`s during this session; every one displayed the exact "A kiszolgáló nem érhető el" copy over the previously-shown data, and a plain refresh tap recovered cleanly every time, with no retry loop and no crash.

One real, proactively-fixed bug came directly out of this pass, not out of any automated test: the trend chart's horizontal scroll defaulted to its start, so a `LAST_30_DAYS`/`LAST_90_DAYS` view opened showing a long, mostly-invisible run of zero-count days from the beginning of the period, with the actual recent activity scrolled off-screen to the right. Fixed by scrolling the chart to its end (today) whenever the trend data changes — verified live afterward (§Q's very first screenshot) showing the real upward trend immediately, with no scroll required.

## R. Accessibility (brief §67/§68/§73)

Verified live at font scale **1.3** and at a narrow (~360dp-equivalent) emulator width, together and separately: the KPI grid wraps its own labels onto a second line rather than clipping or squeezing a neighboring card; every filter-sheet chip group uses `FlowRow`, so a chip that doesn't fit wraps the whole row instead of squeezing another chip's label mid-word — confirmed for the two longest real category names ("Gyanús személy, tárgy vagy tevékenység", "Szabályszegés és egyéb biztonsági esemény"), each wrapping cleanly to two lines within its own chip; "Alkalmaz"/"Szűrők törlése" stayed fully visible above the 3-button navigation bar at both font scale 1.3 and the narrow width, confirming `navigationBarsPadding()` continues to work under the added content. The trend chart's own bars are never the only channel for its information (brief §43): every bar carries a merged accessibility node naming its exact local date and count (e.g. "2026. szeptember 11.: 7 bejelentés" / "…: nincs bejelentés" for a zero day), and the surrounding KPI cards and status note already restate the period and total in plain text regardless of the chart.

## S. Regression and CI

Full cross-stack regression, nothing skipped for being unchanged:

This is the regression as run for the **original submission** (before the owner correction pass in §V); its own regression, run fresh against the corrected code, is reported separately at the end of §V so the two are never conflated.

- Backend: `./gradlew build` — **684 tests, 0 failures** (full suite, including all 38 new Phase 11 tests, real PostgreSQL via Testcontainers throughout).
- Android: `:service-app:testDebugUnitTest :public-app:testDebugUnitTest` — **141 + 36 = 177 unit tests, 0 failures**; `:service-app:connectedDebugAndroidTest` — full instrumented suite, **0 failures**, real `orszem-test` AVD; `:service-app:assembleDebug :public-app:assembleDebug lint` — clean, 0 issues; `:service-app:assembleDebugAndroidTest :public-app:assembleDebugAndroidTest :public-app:assembleRelease` — all green (CI-parity batch). One run of the full connected suite (immediately after the extensive manual live-verification pass in §Q — repeated font-scale/window-size changes and app restarts on the same emulator) hit a single pre-existing Phase 10 test, `ServiceAreaAdminComposeTest.an_eligible_area_may_be_deactivated_and_the_action_is_enabled`, with an Espresso Activity-lifecycle timeout unrelated to any Phase 11 code path; it passed reliably both in isolation and in a subsequent full clean re-run, consistent with the same class of isolated flakiness already documented (and not hidden) in the Phase 9 report.
- Web, reference-data, deploy-config: untouched by this branch (confirmed via `git status` against `main`) — no Phase 11 change touches any of them.

Final pushed HEAD and its 5/5 GitHub Actions result are recorded in the PR itself and in the assistant's stop report for this turn — not re-edited into this document after the fact, for the same reason Phase 9/10 eventually settled on: a documentation commit cannot describe its own resulting commit hash without chasing itself through an unbounded series of further commits.

## T. Known limitations

- Analytics uses `submitted_at` (ingestion time), never `occurred_at` (incident time) — a deliberate, frozen product decision (brief §2), not an oversight. Future incident-time analysis is explicitly out of Phase 11's scope.
- Only the four fixed periods exist; there is no custom date range and no `ALL_TIME`.
- No export in any format, no staff/employee performance analytics for any role, and no historical "status as of date" reconstruction — the status breakdown always reflects *today's* current workflow state for reports received in the selected window, never a snapshot of what the breakdown looked like at the end of each day.
- The actor's *current* authorization determines which historical analytics are reachable — a territorial user who loses access to an area loses analytics visibility into it too, even for reports submitted while they still had access; this mirrors the existing report-workflow visibility model exactly and is intentional (brief §8).
- A ServiceArea's current display name is used for every historical grouping under its id — renaming an area changes the label on old analytics rows, never which group they belong to; no area-name history table exists.
- No caching or materialized aggregate exists yet (§J) — every request runs live aggregate SQL. This is a deliberate, revisit-when-volume-demands-it decision, backed by real `EXPLAIN` evidence at 300k rows (§J), not an oversight.
- Analytics is near-real-time on request, not a streaming/push surface — the freshness line ("Frissítve: HH:mm") communicates this honestly rather than implying a live feed.
- The emulator-host networking flakiness encountered repeatedly during the live verification pass (§Q) is a pre-existing, already-documented characteristic of this specific Windows/WHPX development machine (seen identically during Phase 9/10's own live verification) — never traced to the real backend, which stayed reliable and reachable via direct `curl` throughout every one of these episodes. It is disclosed here transparently rather than hidden, exactly as it was in the two prior phase reports. The same characteristic recurred, and was root-caused further, during the owner correction pass's own re-verification — see §V.

## V. Owner correction pass — scope summary + explicit retry

The owner reviewed the original 17 screenshots and approved the technical implementation, but requested one narrow UI correction pass before visual approval: no backend/product-semantics change, no redesign. Two gaps:

1. A filtered dashboard showed the active scope only as a numeric badge on the filter icon — the applied period/area/category was not visible without reopening the sheet.
2. The network-error state's only retry affordance was the top-right refresh icon; the frozen brief's own §49 wording, "Újrapróbálás", was never shown as an explicit inline action.

**Fix 1 — always-visible scope summary.** A new `FlowRow` of read-only chips sits directly under the header, in every state (loading/error/content): the current period's Hungarian label is always shown; a selected `ServiceArea` name, `Besorolatlan`, or selected category name is added only when that filter is non-default. Never a raw id, UUID, period code, or category code — the area name and category name are resolved from the same backend-truth sources the filter sheet already uses (`AnalyticsAreaOptionsResponse`, the Public report catalogue), so a selected category with zero matching reports still has a display name available even though the summary's own category breakdown would have omitted it entirely. The four period labels come from one new shared `periodLabelRes()` (`AnalyticsCopy.kt`), used by both the filter sheet and the new summary row, so the two can never drift to different wording for the same period. `FlowRow`, not `Row`, wraps a chip onto its own line rather than ever shrinking chip text — verified live at font scale 1.3 and at a ~360dp-equivalent narrow width (§V below), including a genuine three-chip case (period + area + category) that wraps to three full lines.

**Fix 2 — explicit "Újrapróbálás".** `ErrorState` (the shared full-screen error composable, `common/ui/Components.kt`) gained an optional `retryLabel: String` parameter, defaulting to the existing app-wide "Újrapróbálkozás" so every other screen's call site is unaffected; the Analytics screen passes its own frozen-brief "Újrapróbálás" for the first-load-failure case. A new Analytics-only `AnalyticsErrorBanner` composable — visually identical to the shared `InlineErrorBanner` `errorContainer` card, but with its own `Újrapróbálás` `TextButton` — replaces the bare banner in the "stale data retained, refresh failed" branch, so the retry action is present in both of this screen's own error surfaces, not only the first-load one. Both the button and the top-right refresh `IconButton` are `enabled = !state.loading`, so a tap while a request is already in flight is a no-op rather than a second concurrent request; `AnalyticsViewModel.refresh()` itself was not touched, so "one tap = one attempt, no polling, no automatic loop" is a property of the button being disabled during the in-flight request, not of any new retry-suppression logic.

**Tests.** `AnalyticsComposeTest` gained 6 cases (§O): the default period visible with no other chip; a selected area's name visible in the summary; `Besorolatlan` visible when selected; a selected category's name visible; the first-load retry renamed to assert "Újrapróbálás" specifically (and the absence of "Újrapróbálkozás"); and a new case for the second error surface — a refresh failure after a successful load keeps the stale KPI content on screen, shows "Újrapróbálás", and a tap on it produces exactly one additional call to the fake repository before the corrected data replaces the stale data. Two small pre-existing assertions needed updating precisely because the new feature makes text legitimately appear twice on screen at once (the applied default period, both as the always-visible scope chip and as the still-selectable option inside the open sheet) — `the_filter_sheet_opens_and_shows_period_area_and_category_groups` now asserts `assertCountEquals(2)` for "Utolsó 30 nap" instead of a single match, with a comment explaining why two are now correct.

**Re-verification screenshots (§Q's environment, same fixture data).** All 7 corrected screenshots are real backend + real Postgres + real emulator, no fake data — the default overview with the period chip visible at normal scale; a single-area (`Keleti Teruleti Kozpont`) filtered result with the area name in the scope summary; a `Besorolatlan`-filtered result; a category-filtered (`Erőszak és közvetlen veszély`) result; the stale-data-plus-`Újrapróbálás` error surface; and two font-scale-1.3/~360dp-narrow-width captures — one showing three scope chips (period + area + category) wrapping cleanly across three full lines at full text size while the same request happened to hit the network flakiness below (so it doubles as an in-context Újrapróbálás screenshot), the other a clean default-data success at the same scale.

**The network flakiness, followed to a real cause this time.** The same emulator-to-host connectivity issue noted in §T recurred repeatedly during this re-verification, badly enough (several consecutive failures on the same long-lived app process, including immediately after a *different* screen's request had just succeeded) that treating it as an unexamined blip would have been dishonest. It was chased down rather than waved away: a direct `curl` from the host, replaying the exact failing request/params/token, returned `200` with correct data every time — ruling out the backend and the query. `netstat` confirmed Tomcat listening correctly; `adb shell ping 10.0.2.2` succeeded; a different screen's own refresh (`Bejelentések`) succeeded on the same app process moments before and after a Statisztika failure — ruling out a global network or DNS problem. What did reliably reproduce the failure was request timing: any Analytics request issued after several minutes of idle time on a long-lived app process consistently failed with `ApiResult.NetworkError`, while the identical request on a **freshly relaunched process** (or shortly after any other request had just warmed the connection) always succeeded. `NetworkModule.kt`'s OkHttp client sets `retryOnConnectionFailure(false)` — a deliberate existing setting, not something touched in this pass — which means a pooled keep-alive connection silently reset by the emulator's NAT layer after an idle gap is *not* silently retried on a fresh connection the way OkHttp would by default; the failure surfaces to the caller instead. That is exactly the scenario a slow, `uiautomator`-dump-heavy manual verification session with many multi-second gaps between requests reliably creates, and exactly why disabling silent retry was the right call for a screen whose own brief demands "one tap = one attempt, no automatic retry" (§49) — the alternative would have masked exactly this kind of connectivity blip behind an invisible extra request. Every failure encountered was recovered by the very `Újrapróbálás`/refresh action under test, which is what §Q's original report already noted about this same environment and which this pass now explains rather than merely observes. No code change was made in response to this, and none was needed — it is a Windows/WHPX-emulator-specific, dev-verification-only characteristic, unrelated to the app's or backend's own correctness.

**Regression, run fresh for this correction pass.** Backend was not touched in this pass (confirmed via `git status`), so no backend re-run happened and none was claimed. Android, run fresh:
- `:service-app:compileDebugKotlin` / `:service-app:compileDebugAndroidTestKotlin` — clean.
- `:service-app:testDebugUnitTest :public-app:testDebugUnitTest` — **141 + 36 = 177 unit tests, 0 failures** (unchanged from §S, re-run to confirm; no Analytics unit test needed a change).
- `:service-app:connectedDebugAndroidTest` filtered to `AnalyticsComposeTest` — the two regressions from the new feature caught and fixed first (§ above), then **22/22 green** on the real `orszem-test` AVD.
- `:service-app:connectedDebugAndroidTest :public-app:connectedDebugAndroidTest` (full suites, both apps) — **service-app 67 tests / public-app 16 tests, 0 failures**.
- `:service-app:lint :public-app:lint` — clean, 0 issues.
- `:service-app:assembleDebug :public-app:assembleDebug :public-app:assembleRelease` — all green.

Every number above was produced by a Gradle invocation run in this turn against the corrected code, not carried over from the original submission's §S run or asserted from memory.

## U. Owner visual approval

**PENDING.** No PR has been opened. No merge has occurred. The original 17 screenshots' technical implementation was approved by the owner; visual approval itself remained pending on the §V correction. This report, with §V's changes applied, and the 7 corrected re-verification screenshots described in §V (real backend, real Postgres, real emulator, realistic Hungarian throwaway data — no placeholder names), are submitted for that approval. Phase 12 has not been started.
