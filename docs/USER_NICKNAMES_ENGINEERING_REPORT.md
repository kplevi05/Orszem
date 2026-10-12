# User nicknames — engineering report

Status: implementation candidate on `feature/user-nicknames`.

## Product contract

- `service_id` remains the only canonical user identity. No full name is introduced.
- A user may have one optional, non-unique nickname. The display form is
  `SZ-408468(Levente)`; without a nickname it remains `SZ-408468`.
- Nicknames accept arbitrary Unicode text, are trimmed, and are capped at 64 Unicode code
  points. Null or blank removes the nickname. The only refused characters are those no
  PostgreSQL text/jsonb value can hold — NUL and an unpaired UTF-16 surrogate — which answer
  `400 VALIDATION_ERROR` (see "Independent review").
- Every `SUPER_ADMIN` may change their own nickname from their own account screen.
- Another user's nickname follows the existing current-state management hierarchy:
  `SUPER_ADMIN` may manage `MODERATOR`/`SERVICE_USER`; `MODERATOR` may manage an in-scope
  `SERVICE_USER`; peers, higher roles, invisible targets and `SERVICE_USER` actors cannot.

## Persistence and API

- Flyway `V008__user_nicknames.sql` adds nullable `users.nickname VARCHAR(64)` with a
  database length constraint. No existing row changes value.
- `POST /api/v1/service/account/nickname` changes the current `SUPER_ADMIN`'s nickname.
- `POST /api/v1/service/user-management/users/{serviceId}/nickname` changes a manageable
  lower-ranked user's nickname.
- `/service/account/me` and user-management responses include nullable `nickname`.
- The existing user query searches both `service_id` and `nickname`.

Both mutation paths lock and re-read the target user inside the transaction. The managed
path reuses `UserManagementPolicy.canViewTarget`/`canManageTarget`; the self path separately
requires the live user row to remain `SUPER_ADMIN`.

## Audit

Every effective change writes one `USER_NICKNAME_CHANGED` event in the same transaction,
with whitelisted old/new nickname details. An idempotent retry writes no additional event.
The audit UI has explicit localized event/detail mappings; a removed nickname is shown as
`Nincs beállítva`.

## Android

- `SUPER_ADMIN` sees a nickname editor on the own-account screen; other roles do not.
- A manageable user's detail screen contains the nickname editor; read-only peers do not.
- User list, user detail header, own profile, reassignment picker, report cards/details,
  assignment history and moderation screens use one shared display formatter for the
  canonical `serviceId(nickname)` form.
- Report-workflow and moderation read models carry the optional nickname separately from the
  canonical service ID, so authorization, ownership comparisons and filters still operate on
  the immutable identifier. Audit actor/USER-target labels are resolved in the same format,
  and audit search accepts either the service ID or current nickname.
- No nickname is persisted locally as authority. The authenticated user's value comes from
  `/account/me`; managed-user values come from current API responses.

## Verification

Added coverage includes Unicode normalization and length, SUPER_ADMIN self-service,
rank/scope negative cases, peer rejection, removal, idempotent audit behaviour, nickname
search, report/assignment-history propagation, audit actor/target resolution, ViewModel state
replacement, display formatting, audit mapping coverage and Compose visibility/submission
checks.

At hand-over the author's sandbox could not download the repository's Gradle 9.7.1
distribution, so compilation and automated execution were delegated to the branch's GitHub
Actions workflows; the independent review below re-ran the suites locally. No production
data, deployment, tag, Release or signed APK is changed by this feature branch.

## Independent review (HEAD `26a1b11f`)

An independent review of the full diff, run locally with the Gradle distribution available
there, found one defect and fixed it on this branch.

**Defect.** `UserNickname.normalize` accepted a NUL character or an unpaired UTF-16 surrogate.
Neither can be stored in a PostgreSQL text/jsonb value, so `POST …/nickname` (own and managed)
answered `500 INTERNAL_ERROR` instead of a validation error — reproduced by
`UserNicknameIT` before the fix. **Fix:** `normalize` now rejects exactly those characters with
`NicknameInvalidCharactersException`, mapped to `400 VALIDATION_ERROR`; valid paired surrogates
(emoji) and ordinary control characters such as TAB stay allowed. Regression tests:
`UserNicknameTest` (unit) and `UserNicknameIT` (own + managed endpoint, nothing changed, no
audit row). Additional tests added: removal through the managed endpoint audits once and a
repeated removal is idempotent; the managed endpoint never lets an actor change their own
nickname; `AuthRepositoryTest` covers the own-nickname request and the failure path.

**Verified without change.** `service_id` is never altered and is the only value used for
authorization, ownership, filters and assignment comparisons; the nickname is read-only
presentation data in every read model. Both mutation paths lock the target row and re-read
it inside the transaction; the self path additionally requires the live row to be
`SUPER_ADMIN`; an equal value writes nothing and no audit row. V008 is additive, nullable and
forward-only. Audit details come from an explicit whitelist; user labels for report-workflow
metadata are resolved with one batch query. No new per-row lookups were introduced.

**Residual risks (accepted, not changed).**
- The acting user's role/scope is loaded just before the transaction, as in every existing
  user-management use case; only the target row is locked. A concurrent demotion of the actor
  in that short window is not re-checked (cosmetic impact for this feature).
- Audit responses keep the field name `actorServiceId`, but its value is now the display
  label `SZ-ID(Becenév)` (current nickname, not historical). Only display code reads it.
- "Arbitrary Unicode" also admits newlines, bidirectional controls and look-alike text, so a
  nickname could visually imitate the label format. Display only; an owner decision whether
  to restrict it.
- A request body without a `nickname` field is treated as null and removes the nickname.

**Local results after the fix.** Backend `./gradlew clean build` (Testcontainers, real
PostgreSQL 16): 914/914 tests, 0 failures. Service Android: 204/204 unit tests, `lint` and
debug builds of both apps green, instrumented/Compose suite on the `orszem-test` AVD
(1080×2280): 109/109. Public Web: typecheck, 74/74 tests and build green (no web change in
this branch).
