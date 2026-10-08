# User nicknames — engineering report

Status: implementation candidate on `feature/user-nicknames`.

## Product contract

- `service_id` remains the only canonical user identity. No full name is introduced.
- A user may have one optional, non-unique nickname. The display form is
  `SZ-408468(Levente)`; without a nickname it remains `SZ-408468`.
- Nicknames accept arbitrary Unicode text, are trimmed, and are capped at 64 Unicode code
  points. Null or blank removes the nickname.
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
- User list, user detail header, own profile and reassignment picker use one shared display
  formatter for the canonical `serviceId(nickname)` form.
- No nickname is persisted locally as authority. The authenticated user's value comes from
  `/account/me`; managed-user values come from current API responses.

## Verification

Added coverage includes Unicode normalization and length, SUPER_ADMIN self-service,
rank/scope negative cases, peer rejection, removal, idempotent audit behaviour, nickname
search, ViewModel state replacement, display formatting, audit mapping coverage and Compose
visibility/submission checks.

The local sandbox cannot download the repository's Gradle 9.7.1 distribution, so final
backend/Android compilation and automated execution are delegated to the branch's normal
GitHub Actions workflows. No production data, deployment, tag, Release or signed APK is
changed by this feature branch.
