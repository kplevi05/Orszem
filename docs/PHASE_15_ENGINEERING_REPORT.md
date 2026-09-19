# Phase 15 Engineering Report — UI / Accessibility Polish

## A. Git / base / branch

- PR #18 (`feature/v2-deployment-backup-restore-hardening`) is merged into `main` at commit `165ef77` (merge commit).
- Branch `feature/v2-ui-accessibility-polish` was created from exactly that commit (`git merge-base main feature/v2-ui-accessibility-polish` → `165ef77`).
- The tree was clean before Phase 15 work started (only pre-existing, unrelated untracked scratch files `dump1.xml`–`dump6.xml` at the repo root, left untouched).
- Code HEAD (the state that was reviewed and approved): `fbba009`. The final branch HEAD is one docs-only commit on top of it that adds only this report. Five code commits on the merged base (the four below, then `fbba009` `fix(ui): render the Public date/time pickers in Hungarian`): `974ce1f` `fix(ui): Hungarian date/time, nav-label wrap, audit filter stickiness`; `f7f3863` `fix(ui): adaptive bottom-nav label height; date-picker actions to resources`; `754c66e` `fix(ui): readable selected nav label; accurate Moderator service-area copy`; `be99169` `test(moderation): assert open-assignment count per race outcome`.
- No rebase, no reset, no force-push was used.

## B. Feature freeze

No new report field, role, navigation destination, status, moderation capability, analytics metric, audit semantic, user-management capability, service-area operation, backend endpoint, API contract, Service Web, push, maps, export, AI, media upload, geolocation, or auth/session behavior was added. No backend business logic changed, no database migration was added. `deploy/`, `scripts/` and `.github/` have zero diff against `165ef77`. `backend/` has **no production-code diff**; its only diff is one test-assertion correction in `ModerationConcurrencyIT.kt` (+2/−1 lines, commit `be99169`, see §W). Earlier text in this report that says the backend diff is empty refers to the state before that commit.

## C. Existing visual direction preserved

The Service Android dark navy/gold palette, the Public Android light-blue identity, current information architecture, screen hierarchy, and role-driven navigation are all unchanged. No new design system, no palette replacement, no typography overhaul, no navigation-model change. Every change in this phase is a targeted layout/formatting fix, not a redesign.

## D. UI inventory reviewed

Reviewed via static audit (grep across both Android apps) plus live interaction on a real emulator (`orszem-test` AVD) against a throwaway backend + Postgres instance seeded with the repository's own fictional `reference-data/example` dataset (3 settlements, 2 railway lines — committed, non-real data, used exactly as its own README recommends for this kind of testing):

- Icon-only `IconButton`s: all have a real (non-null, non-raw) `contentDescription` — no violations found.
- `Icon(..., contentDescription = null)` usages: all are decorative icons paired with a visible adjacent text label (nav items, status pills, the "locate me" button) — correct per Compose a11y guidance, not a bug.
- Raw-enum-exposure grep (`Text(x.name)` / `Text(x.toString())`): every `.name` hit is a data-class display-name field (settlement/area choice objects), not a Kotlin enum; the only `.toString()` hit formats an `Int` count. No raw enum reaches the UI.
- No `IconButton` has an explicit `Modifier.size()` below the Material3 default 48dp touch target.
- Public Web: reviewed `strings.ts`, all route/component files, and `styles.css` — already has `:focus-visible` outlines, `prefers-reduced-motion` handling, and mobile-first responsive breakpoints from an earlier phase.

## E. Known Phase 15 items — status

| Item | Status |
|---|---|
| A. Public Android date/time localization | **Fixed** |
| B. Service Android bottom-nav narrow/font-scale wrapping | **Fixed** |
| C. Audit filter ergonomics | **Fixed** |

## F. Public Android date/time localization (Item A)

**Root cause**: `DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)` (in `HistoryScreen.kt`) and ad-hoc formatters in `OccurredAtPicker.kt` never called `.withLocale(...)`, so they silently used the JVM/device default locale. An en-US device locale produced `9/16/26, 11:40 AM` inside an otherwise all-Hungarian screen.

**Fix**: added [`android/public-app/src/main/kotlin/hu/orszembejelento/app/ui/components/HungarianDateTime.kt`](../android/public-app/src/main/kotlin/hu/orszembejelento/app/ui/components/HungarianDateTime.kt) — a centralized object with `DATE_TIME` / `DATE` / `TIME` formatters, each fixed to `Locale.forLanguageTag("hu-HU")`, matching the pattern (`yyyy. MM. dd. HH:mm`) and zone strategy (`ZoneId.systemDefault()`) already used by Service Android's audit screens. `HistoryScreen.kt` and `OccurredAtPicker.kt` now use it exclusively. Only rendering changed — the underlying `Instant`/zone semantics and backend timestamps are untouched.

**Service Android**: inspected all five date-formatting call sites on Service Android — every one already fixed an explicit locale. The bug did not exist there; no fix was needed.

**Verified live**: date picker button now reads `"2026. szept. 18."` / `"18:27"` (24-hour); a real submitted throwaway report's history card reads `"2026. 09. 18. 13:14"` and `"Utoljára ellenőrizve: 2026. 09. 18. 13:28"` — no US ordering, no AM/PM, anywhere.

## G. Bottom-navigation narrow/font-scale fix (Item B)

**Root cause**: the four `NavigationBarItem` labels were unconstrained single-line `Text`. At font scale ≈1.3, "Adminisztráció" (the longest label) wrapped to two lines while its three siblings stayed on one, so that item measured taller than the others — throwing icons out of vertical alignment across the bar. This was confirmed empirically on a real emulator via `uiautomator dump` bounds comparison, not just a screenshot (screenshots alone proved ambiguous during earlier attempts — see below).

**Fix attempts** (`android/service-app/src/main/kotlin/hu/orszembejelento/service/nav/ServiceNavHost.kt`):
1. `Modifier.heightIn(min=X).wrapContentHeight(...)` — no measurable effect on rendered bounds.
2. A `Box` with height derived from `MaterialTheme.typography.labelMedium.lineHeight` — equalized heights but caused "Adminisztráció" to ellipsize to one line (`"Adminisztr…"`) instead of wrapping to two.
3. A `Box` with a **fixed `32.sp`** height (chosen because `.sp` respects system font scale via `LocalDensity`, same as text itself) — still ellipsized; 32sp proved too short for two real lines at 1.3× scale.
4. First accepted version: the same fixed-`sp` approach at `56.sp` — sufficient at 1.3x, but it reserved two lines on every Service screen, making the bottom bar taller than the Material default even at 1.0x (caught in owner review).
5. **Final fix (commit `f7f3863`): adaptive.** Each label is measured (`rememberTextMeasurer`) against the real item width (`BoxWithConstraints`: width / item count, minus the 8dp-per-side item padding). Only if at least one label needs more than one line do all labels reserve the tallest measured height (labels may wrap up to 3 lines before the last-resort ellipsis). Otherwise nothing is reserved. Verified on the emulator: at 1.0x the bar is the normal compact bar (about 80dp from the capture, versus about 95dp in the rejected iteration); at 1.3x "Adminisztrá/ció" wraps in full with all four icons level. No automated test covers this inline logic; it was verified on the device at both scales.

**Verified via `uiautomator dump`** (not screenshot inspection alone) on the final adaptive build, font scale 1.3, SUPER_ADMIN: the fourth label's raw `text=` attribute is the full, unellipsized `"Adminisztráció"` (bounds `[826,2034][1080,2144]` — a two-line box), the other three labels sit in `y=2062–2117` boxes, and all four icons are level (see the 1.3× screenshot). At font scale 1.0 every label fits on one line, nothing is reserved, and the label bounds are `y=2070–2112` for all four. `TextOverflow.Ellipsis` remains only as a last-resort safety net, never exercised by the four real labels.

**Selected-label contrast** was a separate defect found in review of the final screenshots — see §S.

No navigation item was renamed, reordered, or removed to make this fit — the full Hungarian labels are used exactly as before.

## H. Audit filter ergonomics (Item C)

**Problem**: with ~31 real event types, the `AuditFilterSheet`'s Apply/Clear buttons sat at the bottom of one long scrollable column — reaching them meant scrolling past every chip first.

**Fix** (`android/service-app/src/main/kotlin/hu/orszembejelento/service/audit/ui/AuditFilterSheet.kt`): split the sheet into an outer `Column` containing (a) a `weight(1f, fill=false).verticalScroll(...)` inner column with the title, period/target-type chip groups, and the vertical event-type list, and (b) a sibling, **non-scrolling** `Column` holding Apply/Clear, always visible regardless of scroll position. No filtering semantics, option source, or chip behavior changed — this is a layout-only fix.

**Verified live**: opened the sheet, scrolled the full ~31-item list to its last entry ("Referenciaadat importálva"), and confirmed Apply/Clear remained on screen throughout — never scrolled out of reach.

## I–L. Android typography/font-scale, small-screen, touch-target, and semantics review

- Font scale tested at 1.0 and ≈1.3 on a real emulator across the Service Android login, reports list/detail, archive, statistics, admin hub, users, service areas, and audit list/filter/detail screens, and the Public Android home, new-report (both steps), and history screens. No clipped or hidden text, no lost button labels found beyond Items A/B/C (both now fixed) and the one bonus finding in §M below.
- Static touch-target audit (§D) found every `IconButton` at or above the Material3 default 48dp target; no non-interactive element was artificially enlarged.
- Accessibility semantics audit (§D) found no icon-only control missing a real spoken label, no icon double-announced alongside a redundant visible label, and no raw enum reaching the UI anywhere in either Android app.
- Small-screen (~320dp-class) and narrow/font-scale representative screenshots were captured for both apps (see §Y).

## M. Public Android polish — bonus finding and fix

While visually verifying Item A's fix on a real device, found that `HistoryItemCard`'s title `Text` (the event-type name) had no `weight()` and so claimed the row's width before the status pill was measured. At increased font scale, a sufficiently long title left almost no width for the pill, forcing its own short text (e.g. `"Beérkezett"`) to wrap mid-word inside what should have been a one-line pill — a "visually broken wrapping" bug in the same class as Item B, just on a different screen and component, discovered rather than pre-specified.

**Fix**: the title now uses `Modifier.weight(1f, fill = false)` with `maxLines = 2` (wrapping onto a second line itself, never truncated with an ellipsis, since the event-type name is the card's primary information), so the pill is measured first and always keeps the room it needs. Verified live (before/after) and covered by a new instrumented test (`HistoryItemCardComposeTest`) that fails on the pre-fix code (pill height would balloon to ~3 lines) and passes on the fix.

No other Public Android issues were found in the reviewed screens (Kezdőlap, both new-report steps, validation state, submission success, history empty/populated).

## N. Service Android polish

Beyond Items B and C, no further issues were found in the reviewed screens (login, generic invalid-login, reports list/detail, archive, statistics, admin hub, users, service areas, audit list/filter/detail, moderation delete-confirmation dialog). The generic invalid-login copy (`"Hibás azonosító vagy jelszó."`) was re-confirmed unchanged and still generic (no user-exists-vs-wrong-password leak) — verified via a real failed login attempt on-device.

## O. Public Web responsive polish

Ran the actual dev server (`vite`, proxying `/api` to the same throwaway backend) in the built-in browser and walked the full new-report → submit → history flow at three viewport widths:

- **1280px** (desktop): clean, no overflow, native `datetime-local` input renders `2026. 09. 18. 19:13` (already correctly localized — this bug never existed on Web), settlement autocomplete and railway-line radio selection both work correctly.
- **768px**: identical layout to 1280px (bottom-nav breakpoint is at ~896px), no overflow, no clipping.
- **390px** (mobile): nav switches cleanly to a bottom-tab bar; new-report form, category chips, and event-type list all fit without horizontal overflow.

No visual bugs found at any of the three required breakpoints. No source changes were needed on Public Web.

## P. Public Web keyboard/semantic accessibility

- Verified visible keyboard focus: tabbing to the "Tovább" button shows a clear focus ring (existing `:focus-visible` CSS rule, not new).
- Semantic HTML already in place: native `<label>`/`<input>` pairing, `<fieldset>`/`<legend>` for the railway-line radio group, `role="alert"` on the category-load-failure message, `aria-describedby` linking the settlement input to its results list.
- The date/time field uses a native `<input type="datetime-local">` — its exact rendered format is entirely browser-chrome, outside the app's control, and is the correct semantic-HTML choice per this phase's own guidance.

No changes were needed; all of this was already correct from an earlier phase.

## Q. Loading/error/empty-state consistency

- **Loading**: Service Android's "Munkamenet helyreállítása…" (session restore) spinner-and-text state, observed on a cold relaunch, is a legitimate, non-empty-looking loading state.
- **Error+retry**: observed two genuine transient network errors during testing (`Felhasználók` list: `"A kiszolgáló nem érhető el. Ellenőrizze a kapcsolatot."`; audit detail: `"A változási előzmények most nem tölthetők be."`), each with a working `"Újrapróbálás"`/retry action, Hungarian copy, and no stack trace or raw exception text. Root-caused the audit-detail case to my own test methodology (repeated direct `curl` logins outside the app rotated/invalidated its stored refresh token) — confirmed by a clean reinstall + fresh login resolving it immediately, and by `AuditComposeTest`'s existing detail-screen tests (which use a fake repository, not the network) continuing to pass unchanged. Not a Phase 15 regression.
- **Empty**: `"Nincs lezárt bejelentés."` (archive), `"Nincs törölt bejelentés."` (moderation), `"Még nincs bejelentés ezen a készüléken."` (Public Android home/history) — each states plainly what is empty, is not styled as an error, and invents no new action.

## R. Hungarian localization/copy consistency

No typo, capitalization inconsistency, or raw enum was found in any screen reviewed this phase, and no developer terminology (scope, backend, workflowVersion, archivedAt, PHASE N, TODO, debug, localhost, UUID, enum, exception, endpoint, JSON, null) appears in any rendered string (both `strings.xml` files with comments stripped, `strings.ts`, hard-coded Compose/TSX text). Required terms (`Bejelentések`, `Archívum`, `Statisztika`, `Profil`, `Moderáció`, `Adminisztráció`, `Változási előzmények`) are unchanged everywhere.

Copy changes this phase: the Public date/time dialog actions `OK`/`Mégse` (hard-coded) became string resources `Rendben`/`Mégse`, and the Moderator hub note "…ebben a verzióban még nem érhető el" (release-note style) became "A szolgálati területek kezelése rendszergazdai jogosultságot igényel." after confirming the rule in `AreaAdminActorLoader` (SUPER_ADMIN only).

**The Material date/time pickers (resolved in `fbba009`).** Material's `DatePicker`/`TimePicker` draw all of their own text (title, headline, month and weekday names, every accessibility label) from the *device* locale, so on an en-US device the Public pickers were English inside the Hungarian app. Material 1.4.0 already ships complete Hungarian translations for all 15 picker strings (`m3c_date_picker_*` in `values-hu`), and the shrunk release APK keeps them (checked with `aapt2 dump resources`); they were just never requested.

**Why the first attempt failed, and the fix.** The first attempt wrapped the whole dialog in a `hu-HU` `LocalContext`/`LocalConfiguration`/`LocalResources` override. It translated only the pre-built calendar state (month header, weekday letters) and left the title, headline and labels English. Cause: every `Dialog` window hosts its own compose view, which re-provides `LocalContext`/`LocalConfiguration`/`LocalResources` from the window, so an override placed *outside* the dialog never reaches the picker inside it. The fix (`OccurredAtPicker.kt`, private `HungarianPickerLocale`) applies the same standard override in two places: around the state creation (the calendar model reads the locale when built) and *inside* the dialog content, for both the `DatePicker` and the `TimePicker`. It uses only supported Compose APIs (`CompositionLocalProvider`, `createConfigurationContext`), reads `LocalConfiguration.current` (a lint rule flagged the first draft for reading the configuration from the context), and adds no custom strings, no `title`/`headline` slots, no custom `DatePickerFormatter`, no forked components, and no app-wide locale. Step 2 of the planned fallback (custom title/headline slots) was therefore not needed.

**Verified on the en-US emulator** (uiautomator tree of the open dialogs): the title "Dátum kiválasztása", the headline "2026. szept. 19.", month header "2026. szeptember", weekday letters H K Sz Cs P Sz V, day descriptions such as "2026. szeptember 17., csütörtök", and "Mégse"/"Rendben" are Hungarian; the accessibility labels are Hungarian too ("Jelenleg kiválasztva: …", "Váltás a következő hónapra", "Váltás az előző hónapra", "Váltás az év kiválasztására", "Váltás szövegbeviteli módra", "Váltás naptárbeviteli módra", year-picker "Navigálás a következő évhez: N"). Text-input mode ("Dátum") and the year picker are Hungarian. The time picker's labels are Hungarian ("Óra kiválasztása", "Perc kiválasztása", "N óra", "N perc"). A search of the final dialog's tree for the English strings ("Select date", "Cancel", "OK", "Change to…", "Switch to…", "Current selection", "Navigate to…") finds none. Selecting a date and confirming still works (the form button changed from "2026. szept. 19." to "2026. szept. 17."). The date/time buttons on the form are Hungarian regardless of device locale (`HungarianDateTime`).

One string is Material's own localized format hint: text-input mode shows the hint "YYYY.MM.DD" (Material's Hungarian date pattern), which is not translated further.

The device locale is not changed by this fix, only the picker subtree; the rest of the app is unaffected.

## S. Color/contrast review

**Finding (fixed): selected bottom-nav label contrast.** Material3 draws the selected `NavigationBarItem` label in `colorScheme.secondary`, which the dark Service scheme maps to the navy `ServicePalette.SurfaceAlt` (`#173B59`). Measured from the rendered screenshot, that is `rgb(23,59,89)` on the bar `rgb(33,31,38)` = **1.40:1** (WCAG AA needs 4.5:1 for normal text), against 9.05:1 for the unselected labels (`onSurfaceVariant`, `#B5C3CE`).

**Fix:** `NavigationBarItemDefaults.colors(selectedTextColor = MaterialTheme.colorScheme.onSurface)` — an existing theme token (`ServicePalette.Text`, `#F4F7FA`), no new colour, palette unchanged. Selected icon colour, indicator pill, unselected colours and the compact/adaptive layout are unchanged.

**Verified from rendered pixels** (decoded from the emulator screenshots; label glyph colour vs the bar background sampled next to it), selected label vs unselected labels:

| Role | Font scale | Tab selected | Selected label | Unselected labels |
|---|---|---|---|---|
| SUPER_ADMIN | 1.0 | 1st (Bejelentések) | 15.16:1 | 9.05:1 |
| SUPER_ADMIN | 1.0 | 4th (Adminisztráció) | 15.16:1 | 9.05:1 |
| MODERATOR | 1.0 | 1st | 15.16:1 | 9.05:1 |
| MODERATOR | 1.0 | 4th (Moderáció) | 15.16:1 | 9.05:1 |
| SERVICE_USER | 1.0 | 1st | 15.16:1 | 9.05:1 |
| SERVICE_USER | 1.0 | 4th (Profil) | 15.16:1 | 9.05:1 |
| SUPER_ADMIN | 1.3 | 1st | 15.16:1 | 9.05:1 |
| SUPER_ADMIN | 1.3 | 4th (Adminisztráció, two lines, full text) | 15.16:1 | 9.05:1 |

All eight measurements clear 4.5:1. No other palette change and no other contrast defect was found in the screens reviewed. This finding was missed in the first review pass and caught by the owner from the final screenshots; the earlier text of this section ("no combination failing") was wrong.

## T. Secret/privacy UI review

Reviewed the Public Android submission-success screen, which displays the report's own public tracking ID (`Bejelentés azonosítója: <uuid>`) — this is the citizen's own reference number for their anonymous report, intentionally shown by design from an earlier phase, not an internal/system UUID leak. No password, temp credential (beyond its existing one-time display), token, capability, or hash was found rendered anywhere. All screenshots use synthetic data (fictional settlements `Mintaváros`/`Példafalva`/`Tesztület` from the repository's own committed example dataset, throwaway `SZ-371703` super-admin, throwaway reports) — no production data, no real secrets.

## U. Accessibility findings

1. **(Fixed, Item B)** Bottom-nav label height inconsistency at increased font scale — see §G.
2. **(Fixed, Item C)** Audit filter action buttons unreachable without scrolling past ~31 chips — see §H.
3. **(Fixed, bonus)** History card status pill squeezed into mid-word wrapping at increased font scale — see §M.
4. **(Fixed)** Selected bottom-nav label was 1.40:1 against the bar (WCAG AA fail) — now 15.16:1 for all three roles; see §S.
5. **(Fixed, `fbba009`)** Material date/time picker text and accessibility labels followed the device locale (English on an en-US device); now Hungarian, see §R.

No other accessibility defect was found in the screens reviewed this phase (after the corrections above).

## V. Visual change log

| File | Change |
|---|---|
| `android/public-app/.../ui/components/HungarianDateTime.kt` | **New.** Centralized `hu-HU`-locale date/time formatter. |
| `android/public-app/.../ui/history/HistoryScreen.kt` | Uses `HungarianDateTime`; title `Text` now `weight(1f, fill=false)` + `maxLines=2` so it no longer squeezes the status pill. |
| `android/public-app/.../ui/newreport/OccurredAtPicker.kt` | Uses `HungarianDateTime.DATE`/`TIME` instead of locale-less formatters; dialog actions read `Rendben` / `Mégse` from string resources; the date and time pickers are rendered under a picker-local `hu-HU` configuration (private `HungarianPickerLocale`, applied inside the dialogs). |
| `android/public-app/src/main/res/values/strings.xml` | +2 strings: `action_confirm` = `Rendben`, `action_cancel` = `Mégse`. |
| `android/service-app/.../nav/ServiceNavHost.kt` | **Adaptive** label height: labels are measured against the real item width and the tallest measured height is reserved only when at least one label needs a second line (otherwise the bar is the plain Material bar). Selected label colour is `onSurface` instead of Material3's default `secondary` (see §S). |
| `android/service-app/.../audit/ui/AuditFilterSheet.kt` | Apply/Clear moved to a separate, non-scrolling region. |
| `android/service-app/.../hub/ui/HubScreens.kt` | KDoc corrected; Moderator hub note now uses `moderation_service_areas_require_admin`. |
| `android/service-app/src/main/res/values/strings.xml` | `moderation_functions_unavailable` replaced by `moderation_service_areas_require_admin` = "A szolgálati területek kezelése rendszergazdai jogosultságot igényel." (service-area administration is SUPER_ADMIN-only: `AreaAdminActorLoader` rejects every other role; no permission changed). |
| `android/public-app/build.gradle.kts` | +3 lines: Compose UI-test dependencies (same artifacts service-app already uses). |
| tests | `HungarianDateTimeTest` (3 unit), `HistoryItemCardComposeTest` (2 instrumented), `OccurredAtPickerLocaleComposeTest` (3 instrumented, asserts Hungarian title and accessibility labels present and English absent for both pickers; verified to fail without the in-dialog override), +1 sticky-actions test in `AuditComposeTest`. |

## W. Automated regression (final code)

**Public Android** — unit **39 / 0 failures**; instrumented on the emulator **21 / 0 failures** (includes the 2 `HistoryItemCardComposeTest` and 3 `OccurredAtPickerLocaleComposeTest` tests); lint **0 errors, 10 warnings** (9 pre-existing dependency-version notices and one unused string, plus the new `AppBundleLocaleChanges` warning explained in §Z); `assembleDebug` + `assembleRelease` green.

**Service Android** (not changed by the picker commit; last run on `754c66e`) — unit **163 / 0 failures**; instrumented **87 / 0 failures** (includes the new sticky-actions test in `AuditComposeTest`); lint **0 errors, 3 pre-existing warnings**; `assembleDebug` + `assembleRelease` green. One earlier instrumented run failed 2 tests in `ServiceAreaAdminComposeTest` (`Activity never becomes requested state DESTROYED`, emulator stall, 9 tests not run); the full suite re-ran green and was green again on the final code. Nothing was weakened or skipped.

**Web** (zero source diff): `npm run typecheck` clean; `npm test` 55 passed; `npm run build` succeeded.

**Reference-data / deploy** (zero diff under `deploy/`, `scripts/`, `.github/`): reference-data validator valid; `caddy validate` valid; the deploy-config and backup-restore-scripts GitHub jobs pass on every pushed HEAD.

**Backend — `ModerationConcurrencyIT` was NOT a flake; it was a wrong assertion, now fixed.**
- Symptom: `delete races reassign on an IN_PROGRESS report` failed at `ModerationConcurrencyIT.kt:147` in full-suite runs (2 of 2 local full runs, and on CI for `f7f3863` and `754c66e`) and passed in isolation. Earlier phases and earlier drafts of this report called it a "timing flake"; that label was wrong.
- Diagnosis (temporary logging, not committed): run alone, the delete always won (`reassign=404 delete=204 status=NEW open=0 history=1`, six of six runs). In the full suite the reassign sometimes won, and the failing race logged `reassign=200 delete=409 status=IN_PROGRESS assignedToTarget=true open=1 history=2`. That is the **correct product outcome**: every behaviour check for that branch had already passed. The test then ran an unconditional `openAssignmentCount == 0`, which is only true when the delete wins.
- Fix (`be99169`, +2/−1, test only): the count is asserted inside each branch: **0** when the delete wins, **exactly 1** when the reassign wins. It is strictly stronger than before. Nothing was skipped, quarantined or loosened. **No production backend code changed.**
- Verification: full local `./gradlew build --rerun-tasks` after the fix: **782 tests, 0 failures**. CI on `be99169`: see §AC.
- Caveat: the reassign-wins outcome is probabilistic, so a single green run does not by itself prove that branch was exercised; the diagnosis above is what establishes that the assertion was wrong.

## X. Manual accessibility/responsive verification

- Font scale 1.0 and ≈1.3 exercised live on a real emulator across both Android apps (§I–L, §G, §M).
- Public Web exercised live at 1280px/768px/390px plus keyboard-focus verification (§O, §P).
- Generic invalid-login behavior re-confirmed live (§N).
- Loading/error/empty states observed live, not just read from code (§Q).

## Y. Screenshot evidence

All screenshots are real emulator captures (`adb exec-out screencap`) with synthetic data only: the repository's fictional example settlements and lines, throwaway accounts (SUPER_ADMIN, MODERATOR, SERVICE_USER) and throwaway reports. No production data or secrets. The final-state captures are the ones in the owner review package (revision 3):

- **Selected-label contrast, 1.0×, all three roles**: SUPER_ADMIN (Bejelentések selected; Adminisztráció selected), MODERATOR (Bejelentések selected; Moderáció hub with the new copy, Moderáció selected), SERVICE_USER (Bejelentések selected; Profil selected).
- **1.3×, SUPER_ADMIN**: Bejelentések selected, and Adminisztráció selected showing the full two-line "Adminisztrá/ció".
- **Comparison**: the original bug at 1.3×, and the rejected fixed-height iteration at 1.0×.
- **Audit filter** (Item C): sheet top and scrolled, with Apply/Clear pinned.
- **Public history card** before/after (bonus fix) with the Hungarian date/time.
- **Date picker and time picker** on the en-US emulator, fully Hungarian (final build).
- State screens (loading, error+retry, empty, validation, delete dialog) and the remaining screens were captured earlier in the phase and are unchanged by the later commits. Where they show the bottom bar they show an earlier nav iteration; the final bar is shown in the captures above.

Contrast figures in §S were computed from the rendered pixels of these captures.

**Public Web**: no source changes; verified live (§O/§P); no saved screenshot files (accepted by the owner).

## Z. Known limitations

- **New lint warning `AppBundleLocaleChanges`** (Public app lint is now 0 errors, 10 warnings; it was 9): lint notes that picker-local locale changes are not safe if the app is ever published as an **App Bundle with language splits**, because Material's Hungarian strings could then be split out for a device that only has English installed. This repo distributes signed release **APKs** (`assembleRelease`; no AAB flow and no `bundle {}` block), and the shrunk release APK was verified to contain the Hungarian variants, so the fix is safe as shipped. If Play/AAB distribution is ever adopted, set `bundle { language { enableSplit = false } }`. Not suppressed, not changed here.
- The picker override relies on Material shipping `values-hu` (present in 1.4.0); a future Material upgrade should keep the new instrumented test green.
- The bottom-nav adaptive logic has no automated test (it lives inline in `ServiceNavHost`); it was verified on the emulator at 1.0× and 1.3×.
- On the test emulator only: after a lost session, a fresh Service login created a backend session but the app stayed on the login screen until a clean reinstall. Not root-caused; unrelated to Phase 15 changes; a Phase 16 candidate if it reproduces on a device.
- Public Web screenshot files do not exist (tooling); there are no "before" images for the date bug and the audit filter.
- The full deploy-config and backup-restore-scripts jobs were not reproduced locally command for command; they pass on CI for every pushed HEAD.

## AA. Phase 16 carry-forward items

1. **AAB language splits** (§Z): if the Public app is ever published as an App Bundle, disable language splitting so the picker's Hungarian strings ship.
2. **Emulator-only Service login anomaly** (§Z), if it reproduces on a device.
3. **Correct the record on `ModerationConcurrencyIT`**: earlier phase reports call it a timing flake; §W shows it was a wrong final assertion, fixed in `be99169`.
4. Standalone Public Web screenshots, if wanted.

No real visual or accessibility regression found in Phase 15 was left unfixed.

## AB. Owner visual approval

**Owner visual approval: APPROVED — 2026-09-19**

The owner approved the final visual state after four review revisions (fixed-height nav rejected and made adaptive; selected-label contrast fixed; Moderator copy corrected; date/time pickers made Hungarian). The approved code is `fbba009`, with 6 of 6 CI checks green (§AC). The `AppBundleLocaleChanges` lint warning (§Z, §AA) was reviewed as a future App Bundle consideration and is **not** a current blocker: this repository ships signed release APKs, and the shrunk release APK was verified to contain Material's Hungarian picker strings. No further UI or production-code changes were made after approval; this report is the only file in the final docs-only commit.

## AC. Owner technical approval / final CI

**Final HEAD `fbba009`: all 6 CI checks green.**

| Check | Result |
|---|---|
| `backend` | success |
| `android` | success |
| `web` | success |
| `reference-data` | success |
| `deploy-config` / caddy | success |
| `deploy-config` / backup-restore-scripts | success |

CI history for the record: `974ce1f` 6/6; `f7f3863` and `754c66e` each 5/6 with `backend` failing on `ModerationConcurrencyIT` (a wrong test assertion, not a product defect; fixed in `be99169`, see §W); `be99169` 6/6; `fbba009` (Hungarian date/time pickers) 6/6. The earlier failures were confirmed by reproducing them locally with logging; the CI job logs themselves were not readable without GitHub authentication.

Technical checks were complete on the code HEAD `fbba009`. Owner visual approval was granted afterwards (§AB). The single docs-only commit that adds this report changes no code, and CI is re-run on it: see the PR for the exact-HEAD result.
