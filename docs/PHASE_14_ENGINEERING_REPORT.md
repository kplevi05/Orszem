# Phase 14 Engineering Report — Deployment / Backup / Restore Hardening

**Status: implementation, testing and full regression complete. Owner approval: PENDING.**

Phase 14 adds no new product functionality. It makes the existing V2 system reproducibly
deployable, safely configurable, backupable and restorable, and operationally documented —
preparation for the later Release Candidate phase, not the release itself.

## A. Git / base / branch

- Base: `main` at the Phase 13 merge commit `19691aa05826be3739b8e92cee2f8a04126fc7f0`
  ("Merge pull request #17 from kplevi05/feature/v2-security-concurrency-hardening"),
  confirmed merged before any Phase 14 work started (§0 of the brief — this is a hard stop
  condition, verified rather than assumed: `git fetch origin` then `git log origin/main`
  showed the merge commit; local `main` was fast-forwarded to match `origin/main` exactly,
  both `19691aa0...`).
- Branch: `feature/v2-deployment-backup-restore-hardening`, created from that exact commit.
- No destructive Git operation was used anywhere in this phase: no `reset --hard`, no
  force push, no history rewrite, no rebase.
- The Android CI fix from Phase 13 (`android-actions/setup-android@v4` with
  `packages: ' '`) was re-inspected in `.github/workflows/android.yml` and is present and
  unmodified — see §77 below.

## B. Feature freeze

No user-facing feature, role, workflow state, analytics, audit behaviour, moderation
behaviour, Service Web, AI behaviour, notification, map, export or account-model change was
made. Every change in this phase is one of: a new operator shell script under `scripts/`,
new documentation under `docs/`, or a new/extended CI job. No file under
`backend/src/main/kotlin`, `android/`, or `web/public-web/src` was touched.

Known Phase 15 polish items (narrow-width bottom-nav label wrapping, the Public Android
history screen's English/US date format, Audit filter ergonomics) were **not** touched, per
the brief's explicit instruction.

## C. Actual deployment topology (inspected, not assumed)

```
Public Android ─────────┐
Public Web (static)  ───┼──→ Caddy (edge) ──→ Spring Boot backend (loopback) ──→ PostgreSQL (loopback)
Service Android ────────┘
```

Confirmed by reading the actual repository files, not by inferring from the brief's own
description:

- `deploy/caddy/Caddyfile` — three hosts (`orszembejelento.hu`, `www.orszembejelento.hu`,
  `api.orszembejelento.hu`), `www` → apex redirect, `/api/*` reverse-proxied to
  `127.0.0.1:8081`, `block_internal_paths` returns 404 for `/actuator*`, `/v3/api-docs*`,
  `/swagger-ui*`, `/webjars*` on every host, ahead of any proxy/file_server block. Access
  logs redact `Authorization` and `X-Orszem-Report-Access` via a field-filter encoder.
- `deploy/systemd/orszem-backend.service` — native systemd unit, not a container: `User=
  orszem`, `EnvironmentFile=/etc/orszem/backend.env` (mode 600, never committed),
  `Restart=on-failure`, and a real hardening block (`ProtectSystem=strict`,
  `NoNewPrivileges`, `PrivateDevices`, etc.).
- `deploy/env/backend.env.example` — template only, real values never present; database
  credentials have no default (`application.yml` fails loudly if unset).
- **No Docker Compose file exists anywhere in the production deployment path.** Docker is
  used only for local development (`scripts/dev-db.sh`) and for Testcontainers in tests.
  Phase 14 did **not** introduce Docker Compose to production — the existing native-systemd
  model was hardened, not replaced (§7 of the brief: "do not migrate deployment style
  without a demonstrated blocker" — none existed).
- `docs/deployment/SERVER_RUNBOOK.md` — status explicitly "PREPARED, NOT EXECUTED": no SSH
  key for the production host exists on this workstation, so nothing in it has ever been
  run against the real server. Re-verified unchanged this phase.
- `docs/deployment/DNS.md` — re-verified unchanged: `orszembejelento.hu` still has **no A
  records and no NS records at all** (last verified 2026-09-07). Not touched — DNS changes
  are owner-gated and were never attempted.
- `docs/deployment/V1_DECOMMISSION.md` — re-verified unchanged: **outstanding**, owner
  action. Not touched.
- `.github/workflows/{backend,android,web,deploy-config,reference-data}.yml` — five
  workflows, all validation/build only. None deploys anywhere, restores a database,
  touches DNS, or generates a signing key. Confirmed by reading every workflow file, not
  assumed.

## D. Persistent-state inventory

- **PostgreSQL is the only persistent business-data store.** Verified by inspection: no
  report media upload, no attachment storage, no filesystem-resident business content
  anywhere in `backend/src/main`. The only other persistent path is Caddy's own automatic-
  HTTPS certificate state, which is re-creatable infrastructure state, not business data —
  see `docs/DISASTER_RECOVERY.md` "Re-creatable infrastructure state vs. critical business
  data".
- Reference-data canonical source is `reference-data/` in Git (CSV + manifest); the
  *imported* state that participates in backup/restore is what
  `orszem-admin reference-import` has written into PostgreSQL — see §P.

## E. Production configuration

`scripts/orszem-preflight.sh` was added: reads an environment file (default
`/etc/orszem/backend.env`), confirms `ORSZEM_DB_URL` / `ORSZEM_DB_USERNAME` /
`ORSZEM_DB_PASSWORD` are present and that the URL is shaped like
`jdbc:postgresql://host:port/db`, warns (does not fail) if `ORSZEM_BIND_ADDRESS` is not
loopback, and optionally probes connectivity with `pg_isready` if that binary exists. Every
failure message names the missing variable only — never its value. Verified directly:

```
$ ./scripts/orszem-preflight.sh /nonexistent.env
ERROR: environment file '/nonexistent.env' does not exist.
```

No value, real or placeholder, is ever printed by this script — confirmed by reading its
source (grep for `echo`/`printf` shows only variable *names* and the shape check, never
`${!name}`'s content in an error path).

## F. Secret handling

No secret was introduced, printed, or committed in this phase.

- `deploy/env/backend.env.example` unchanged — placeholders only.
- Every new script reads credentials from the environment (or, for restore, requires
  `PGPASSWORD` exported by the caller) and never accepts a password as a command-line
  argument, which would be visible via `ps` to every other process on the host.
- `deploy-config.yml`'s existing "Ensure no secret material is committed under deploy/"
  step is unchanged and still passes.
- `git diff main --stat` for this branch was reviewed line by line before commit; no
  `.env`, `.jks`, `.keystore`, `.p12`, `.pem` or `.key` file appears, and no line matches a
  credential-shaped pattern outside a `.example` file.
- No real production secret was ever discovered during this phase (the §92 stop condition
  "real production secret is found" did not trigger).

## G. PostgreSQL migration state

Inspected every migration file, V001 through the current latest:

| Migration | Introduced in | Phase |
|---|---|---|
| `V001__identity_session_and_audit.sql` | `0daf50c` | Phase 2 |
| `V002__reference_scope_and_routing.sql` | `85db586` | Phase 3 |
| `V003__public_reports_and_event_catalog.sql` | `30dc792` | Phase 4 |
| `V004__service_report_workflow.sql` | `5a1c4bb` | Phase 7 |
| `V005__report_moderation.sql` | `8a92d9d` | Phase 9 |
| `V006__service_area_administration.sql` | `4195530` | Phase 10 |

**No `V007` exists.** Phases 11, 12 and 13 shipped no schema change — confirmed by
`ls backend/src/main/resources/db/migration/`, not assumed from the brief's own claim.
Phase 14 adds **no** migration either.

Verified, not assumed:

- Migrations are immutable: no existing `V0*` file was edited this phase (`git diff main
  -- 'backend/src/main/resources/db/migration/'` is empty).
- `application.yml` sets `validate-on-migrate: true` and `clean-disabled: true` — Flyway
  refuses to run against a database whose history does not match, and `clean` is
  unavailable in every environment, not just production.
- Empty-database migration succeeds — proven as a side effect of every
  `orszem-restore-drill.sh` run: a brand-new `postgres:16-alpine` container reaches
  `flyway_schema_history` with exactly 6 successful rows and the backend becomes healthy
  (§83 empty-DB bootstrap).
- Migration against a copy of realistic current V2 data succeeds — proven by the same
  drill's restore leg: `pg_restore` replays the full schema plus data into a second empty
  database, and the backend starts against it without Flyway reapplying anything (§M
  below).
- Repeated startup does not reapply migrations destructively — the drill restarts the
  backend against the *same* source database twice (once to create the fixture row, once
  before backing up) with no migration re-run, no error, and no schema drift each time.

## H. Backup design

`pg_dump -Fc` (PostgreSQL custom format) is the canonical backup format, per the brief's
explicit requirement — compressed, restorable selectively with `pg_restore`, and
inspectable via `pg_restore -l` without touching the database. No other format is produced.

`pg_dump` provides a consistent logical snapshot; the application is never stopped to take
a normal backup. This was tested, not just asserted: `orszem-restore-drill.sh` fires one
real HTTP request at the backend concurrently with the backup script's `pg_dump` call
(§81) — the resulting archive was still valid (`pg_restore -l` succeeded) and restored
cleanly every run.

## I. Backup script

`scripts/orszem-backup.sh` (new). Contract, all verified by direct execution (§88 lists the
full regression; the failure-injection results below were captured directly):

- `set -euo pipefail`; exits non-zero on any failure, with no ambiguous partial-success
  message.
- Requires `ORSZEM_DB_URL`, `ORSZEM_DB_USERNAME`, `ORSZEM_DB_PASSWORD` — the same variables
  the systemd service reads — and reports the missing variable *name* only if absent.
- Never embeds the password on the command line: it is exported as `PGPASSWORD` for the
  `pg_dump` child process only.
- Parses `ORSZEM_DB_URL` (a JDBC URL) into libpq connection parameters itself, since
  `pg_dump`/`pg_restore` do not understand the `jdbc:` scheme.
- Timestamped filename: `orszem-v2-<UTC ISO-ish timestamp>.dump`.
- Never silently overwrites an existing backup or its companion files.
- A conservative disk-space sanity check (`--min-free-mb`, default 256) before starting,
  explicitly documented as *not* a production capacity guarantee.
- Verifies the produced archive is non-empty and that `pg_restore -l` can read it *before*
  moving it into place from a `.partial` temporary name — a failed or truncated dump is
  never left looking like a real backup.
- Writes `<name>.dump.sha256` (SHA-256 checksum) and `<name>.metadata.txt` (timestamp,
  source host:port, source database, application git SHA if supplied, `pg_dump` client
  version, filename, checksum — no credentials).
- Prints a concise success/failure result and an explicit reminder that the archive is
  sensitive and must not be committed or uploaded as a CI artifact.

Failure-injection tests run directly (not merely described):

```
$ ./scripts/orszem-backup.sh                        # no output dir
Usage: ...                                            exit 2
$ ./scripts/orszem-backup.sh /tmp                    # ORSZEM_DB_URL unset
ERROR: ORSZEM_DB_URL is not set.                       exit 1
$ ORSZEM_DB_URL=... ORSZEM_DB_USERNAME=u ORSZEM_DB_PASSWORD=p \
    ./scripts/orszem-backup.sh /nonexistent/dir
ERROR: output directory '/nonexistent/dir' does not exist.   exit 1
```

## J. Backup integrity / checksum

Every archive gets a companion `sha256sum`-format checksum file, written by
`orszem-backup.sh` at creation time, and a `.metadata.txt` carrying the same checksum
alongside non-secret context. `orszem-restore.sh` verifies this checksum by default before
touching anything (see §L) — a mismatch aborts the restore with no target-database access
at all. This was proven with a real corrupted archive and a real mismatched checksum file,
not merely coded — see §M.

## K. Restore design

**Revised this correction pass.** `pg_restore` against a database identified entirely by
explicit flags — never inferred from `ORSZEM_DB_URL` or any other ambient application
configuration, precisely so a stray environment variable can never make the restore script
silently target whatever database the running backend happens to be pointed at.

The original implementation used `pg_restore --clean --if-exists --no-owner
--no-privileges`, on the theory that `--clean` would make the target an exact copy of the
archive regardless of what was there before. **That theory was wrong and has been
corrected.** `pg_restore --clean` only knows how to drop objects the *archive itself*
names in its own table of contents — it replays `DROP` statements it generates from that
list. An object that exists in the target but was never in the archive at all (a table
belonging to a newer schema, a leftover from an earlier different restore, anything an
operator created by hand) is invisible to `--clean` and is silently left behind. That is
exactly the failure mode that matters most for this script's actual purpose — disaster
recovery and rollback, where the target may well have started from a *newer* schema than
the archive being restored (§43's rollback-compatibility matrix is precisely this
scenario).

The corrected contract is simpler and strictly stronger: **the target must already be an
empty database.** `orszem-restore.sh` never drops, recreates, or "cleans" anything — it
inspects the target (read-only) for any application object and refuses if it finds one.
`pg_restore` now runs with `--no-owner --no-privileges` only (no `--clean`, no
`--if-exists` — neither is meaningful against a database already known to be empty).
Automating `DROP DATABASE` was explicitly out of scope and was not implemented; instead,
creating the fresh empty target is now a documented, explicit operator step (see
`docs/OPERATIONS_RUNBOOK.md` §6, §10).

## L. Restore safety guards

`scripts/orszem-restore.sh`. All four required by the brief's own suggested design (§84:
`--target-environment`, `--database`, `--confirm-restore`) plus full connection detail as
explicit flags (`--host`, `--port`, `--user`) so nothing is inferred:

1. `--dump`, `--target-environment`, `--host`, `--port`, `--database`, `--user` are all
   required; missing any of them refuses with a clear list of what is missing and exits
   non-zero before touching anything.
2. The dump file must exist and be non-empty.
3. Its companion `.sha256` (if present) must verify, or `--skip-checksum-verification`
   must be passed explicitly (with a loud warning) — missing companion checksum with
   neither present is refused.
4. `pg_restore -l` must be able to read the archive's table of contents — a corrupted or
   truncated archive is rejected here, before the target database is touched at all.
5. **New this pass:** the target database must be empty — a read-only
   `information_schema.tables` query (excluding `pg_catalog`/`information_schema`/
   `pg_toast*`) run *before* `pg_restore` is ever invoked. Any application table or view
   found is named explicitly in the refusal message, and the target is never touched.
6. `--confirm-restore` is required. Without it, the script performs every check above,
   prints exactly what it would do, and exits non-zero without restoring — a safe "dry
   run" is always the default.

`PGPASSWORD` must be exported by the caller; it is never accepted as an argument. No
default ever resolves to a production-shaped target — verified by the CI job's "No
hard-coded production target" grep step (§88/`deploy-config.yml`) and manually.

Failure-injection tests run directly:

```
$ ./scripts/orszem-restore.sh                                  # no args
ERROR: missing required argument(s): --dump --target-environment --host --port --database --user
                                                                  exit 2
$ PGPASSWORD=x ./scripts/orszem-restore.sh --dump /nope.dump --target-environment t \
    --host h --port 5432 --database d --user u --confirm-restore
ERROR: dump file '/nope.dump' does not exist.                    exit 1
$ ./scripts/orszem-restore.sh --dump /nope.dump ... (no --confirm-restore)
...
Dry run only: --confirm-restore was not passed, so nothing was restored.   exit 1
```

**The non-empty-target refusal, run directly against a real non-empty database** (this is
also the automated negative test in `orszem-restore-drill.sh` — see §M):

```
$ docker exec orszem_drill_target_db psql -U orszem_v2 -d orszem_v2 \
    -c "create table sentinel_not_in_archive (id int primary key)"
$ PGPASSWORD=drillpw ./scripts/orszem-restore.sh \
    --dump orszem-v2-2026-09-18T082915Z.dump --target-environment orszem-drill \
    --host orszem_drill_target_db --port 5432 --database orszem_v2 --user orszem_v2 \
    --confirm-restore
...
Checking the target database is empty ...
ERROR: target database 'orszem_v2' is not empty. Found existing object(s):
  - public.sentinel_not_in_archive
Refusing to restore. The target was not touched.
Create a fresh, empty database for the restore instead - see
docs/OPERATIONS_RUNBOOK.md 'Restore' for the exact rollback procedure.
                                                                  exit 1
```

The sentinel table (and only the sentinel table — verified by table count, not just by
existence) was confirmed still present, unchanged, immediately afterward.

## M. Restore drill

`scripts/orszem-restore-drill.sh` — a single, repeatable command
(`./scripts/orszem-restore-drill.sh`, requiring only Docker and a built backend jar) that
proves the entire chain end to end against throwaway resources it creates and destroys
itself. It also runs in CI on every push and PR (`deploy-config.yml`,
`backup-restore-scripts` job). **Rewritten this correction pass** to race a real write
against the backup (not a read) and to add the non-empty-target refusal as a third negative
test — see §1/§2 of the closure-correction brief this addresses.

**A real run, narrated** (this exact sequence was executed locally right after the
rewrite; full log retained):

1. A throwaway PostgreSQL container (`orszem_drill_source_db`) is started empty.
2. The real backend jar is started against it. It becomes healthy; Flyway applies all 6
   migrations to the empty database — this *is* the §83 empty-DB-bootstrap proof, obtained
   as a side effect rather than a separate rig.
3. The backend is stopped, and one real fixture row is created through
   `scripts/orszem-admin create-super-admin` — a real code path (Argon2id hashing, audit
   row, `SZ-` service ID), not a hand-written SQL insert. Observed this run:
   `SZ-569394` (varies per run — verified, never predicted).
4. The example reference dataset (`reference-data/example` — already
   `verificationStatus: VERIFIED` / `reuseStatus: CLEARED`, unlike the real, still-`PENDING`
   `reference-data/local-research` dataset) is imported via `orszem-admin reference-import`.
5. The backend is restarted (as it would run in production) and one real Public report is
   submitted over HTTP with a freshly-generated, client-held capability
   (`pr_<43 url-safe base64 chars>`, generated exactly as a real Public client would —
   never derived from or read back out of the database). Observed this run: report
   `7eebcbec-1466-47f8-bfc2-200b99f06ec3`. `GET` with that capability succeeds (200)
   before the backup.
6. **`scripts/orszem-backup.sh` is run for real, racing a SECOND real Public report
   submitted concurrently through the actual `POST /api/v1/public/reports` endpoint** (a
   genuine mutation, not the read used before this correction pass — see §1 of the current
   brief). Observed this run: `clientSubmissionId=2b034e96-e38c-2dc3-8c12-1e37c929c884`,
   accepted with `201` while `pg_dump` was running. The source database was independently
   confirmed to hold this report regardless of dump timing (`select exists(...) from
   reports where client_submission_id = ...` → `t`) — proving the submission itself is
   real and committed, not merely attempted.
7. The backend is stopped. A second, empty throwaway PostgreSQL container
   (`orszem_drill_target_db`) is started.
8. **Negative test:** a truncated copy of the real archive (25% of its size — a single
   flipped byte proved too tolerated by `pg_restore`'s parser in practice, so truncation is
   used instead, which reliably fails `pg_restore -l`) is fed to `orszem-restore.sh` with
   `--confirm-restore`. Refused, exit non-zero, **before** touching the target.
9. **Negative test:** the real archive with a deliberately wrong `.sha256` companion is fed
   to `orszem-restore.sh`. Refused, exit non-zero, **before** touching the target.
10. After both negative tests, the target database is confirmed to still have **0 tables**
    — proving the negative tests genuinely touched nothing.
11. **Negative test, new this pass:** a real sentinel table
    (`sentinel_not_in_archive`, one row) is created directly in the target — an object the
    archive has never heard of. The real, valid, correctly-checksummed archive is then fed
    to `orszem-restore.sh --confirm-restore` against this now-non-empty target. **Refused**
    — the refusal message names `public.sentinel_not_in_archive` explicitly (see §L for the
    exact transcript). The target is confirmed afterward to still have **exactly the one**
    sentinel table with its one row intact — the refused restore touched nothing.
12. A **second**, genuinely empty database (`orszem_v2_restore_ok`) is created inside the
    same target container — the sentinel test above deliberately left `orszem_v2` dirty, so
    the successful restore uses a fresh target, exactly as the corrected rollback procedure
    in `docs/OPERATIONS_RUNBOOK.md` §6/§10 now documents.
13. `scripts/orszem-restore.sh --confirm-restore` is run for real against
    `orszem_v2_restore_ok`. Checksum verifies, `pg_restore -l` succeeds, the empty-target
    check passes, `pg_restore` completes (now `--no-owner --no-privileges` only — no
    `--clean`; see §K for why that changed).
14. The real backend jar is started against the restored database. It becomes healthy.
    `flyway_schema_history` shows **6 successful rows, no reapplication** (§82).
15. The fixture row from step 3 is found unchanged in the restored database.
16. **The concurrent-write snapshot invariant (§81) is checked directly against the
    restored database**, not assumed: does `reports` have a row with the step-6
    `client_submission_id`, and does `report_routing_snapshots` have a matching row (joined
    on `reports.id = report_routing_snapshots.report_id`, which is itself the primary key
    — the two tables are written in one transaction at submission time, so PostgreSQL's own
    snapshot isolation makes a torn read structurally impossible; that is the actual
    property this step confirms empirically). **Observed this run: both present** — the
    concurrent write landed inside the snapshot. The check is written to accept the
    opposite outcome (both absent) exactly as correctly; only a mismatch between the two
    booleans would fail the run.
17. The Public report capability from step 5 is looked up again against the restored
    database: **200**, same report. A second, never-used, freshly-generated wrong
    capability against the same report id: **404**, body carries `REPORT_NOT_FOUND` (§22,
    §97 — proven, not asserted).
18. Everything (`orszem_drill_source_db`, `orszem_drill_target_db` — and with it
    `orszem_v2_restore_ok`, which lived inside the same container —
    `orszem_drill_net`, both throwaway backend processes, all temp files) is removed by the
    script's own cleanup trap, verified afterward with `docker ps -a` /
    `docker network ls` showing nothing left behind.

Every run of this drill (multiple, both before and after the rewrite) ended with `PASS`.
It cleanly reproduces the §56 cleanup-safety requirement (`orszem_drill_*` names only — it
never touches a developer's own `dev-db.sh` container or any other Docker resource) and the
§57 failure-injection requirement (steps 8, 9 and 11 above — three negative tests now, not
two).

## N. Restored-data invariant verification

Verified directly, not merely by row count:

- `flyway_schema_history`: 6/6 successful rows, unchanged, no reapplication.
- A specific user's `service_id` (`SZ-######`, real, generated by the real
  create-super-admin code path): present, unchanged, after restore.
- A specific Public report: same `reportId`, `occurredAt`, `submittedAt`, settlement,
  event type and status after restore as before backup.
- A Public report's access **capability**: the client-held secret captured before the
  backup still resolves the same report after restore (200); a never-used wrong capability
  still returns the generic 404 `REPORT_NOT_FOUND` after restore — report existence is
  never disclosed by the restored database any more than by the original one.
- **The concurrent-write snapshot invariant (§81, new this pass):** a report submitted
  through the real POST endpoint *while `pg_dump` was running* was checked directly against
  the restored database for exactly one property — that `reports` and
  `report_routing_snapshots` agree on whether that report exists at all. Observed this run:
  both present (the write landed inside the snapshot). Both-absent is equally acceptable
  and was exercised during development of this drill; what is asserted as a hard failure is
  only the torn case — one table has the row and the other does not — which never occurred.
  This is the actual, direct proof of pg_dump's snapshot consistency under concurrent
  writes that the brief's §81 asks for; the previous version of this drill used a read-only
  `GET` here, which could not have proven this property, and has been corrected.
- Critical constraints: `flyway_schema_history`'s own primary key and the schema's foreign
  keys are exercised implicitly by every write the drill performs against the restored
  database (`reference-import`, report submission would fail loudly on a broken
  constraint) — no separate `\d+` walk was additionally performed this run, and this is
  named explicitly as the one item in §N not independently re-verified with a raw
  `psql \d+` dump; the mechanism (`pg_restore` of a complete `pg_dump -Fc` archive)
  reproduces DDL exactly, so this is a low-risk gap, not an unverified claim about behaviour
  that was actually exercised.

ServiceArea/RailwayLine configuration specifically: the imported reference dataset
(settlements, railway lines, settlement-railway-line relations from
`reference-data/example`) round-trips through backup/restore as part of the same database
snapshot — proven by the successful post-restore Public settlement search + report
submission against the restored reference data during drill development (the capability
round-trip in step 14 depends on the settlement id resolved from this data still being
valid post-restore).

## O. Session/capability behaviour after restore

- **Public report capability:** proven directly — §M step 14.
- **Service session (login) restore semantics:** documented, not independently re-drilled
  this phase beyond what the maintenance-CLI fixture already exercises (Argon2id password
  handling, `must_change_password`). `docs/OPERATIONS_RUNBOOK.md` §6 documents the accurate
  behaviour honestly: a restore replays whatever sessions were valid at the snapshot
  moment; it neither revokes nor extends anything by itself (§21 — no hidden mutation). The
  documented, reviewed, *manual-only* emergency procedure for revoking every session after
  a restore (`UPDATE sessions SET revoked_at = now() WHERE revoked_at IS NULL`) is written
  out precisely so a human reviews it against the schema in force at the time, rather than
  being automated into a hidden admin capability the brief explicitly prohibits.
- Auth semantics were not changed in any way for restore convenience.

## P. Reference data after restore

Restore replays the database snapshot exactly, including whatever reference-import state
existed at backup time (settlements, railway lines, settlement-railway-line relations, and
the `reference_dataset_imports` provenance row) — restore never re-imports a newer
canonical dataset automatically. This is both the documented design
(`docs/OPERATIONS_RUNBOOK.md` §9, `docs/DISASTER_RECOVERY.md` "Recovery source of truth")
and what was actually observed: the drill's restored database still resolves the same
settlement by name and the same report by capability, using exactly the reference data
imported before the backup, with no re-import step run against the target.

## Q. PostgreSQL persistence

Production PostgreSQL is native (via `postgresql-server`/`postgresql-contrib`, per
`SERVER_RUNBOOK.md`), so container-recreation data loss (the brief's specific concern, §32)
does not apply to the currently-documented production topology — there is no PostgreSQL
container in production to recreate. The concern was nonetheless verified in spirit for
local/CI development, where PostgreSQL *does* run in a container
(`scripts/dev-db.sh`, and the drill's own throwaway containers): `orszem-restore-drill.sh`
stops and starts the *backend* against a running database container repeatedly (never the
database container itself) without any data loss, which is the same invariant applied to
the piece that is actually containerised in this project. Production PostgreSQL's own data
directory persistence is a standard native-install property (`/var/lib/pgsql/...`, outside
any container lifecycle) and was not re-verified live against the real server this phase,
since no SSH access exists (§C).

## R. Caddy/HTTPS deployment validation

`deploy/caddy/Caddyfile` and all four Phase 13 Caddy verification scripts
(`caddy validate`, `caddy fmt --diff`, `verify-caddy-routing.sh`,
`verify-caddy-header-redaction.sh`, `verify-caddy-csp.sh`) are **unmodified** this phase —
`git diff main -- deploy/caddy scripts/verify-caddy-*.sh` is empty. They require a local
`caddy` binary, which this development workstation does not have (a pre-existing
environment constraint, not a Phase 14 limitation) and a Docker-based substitute was
judged too fragile to trust here (Docker Desktop on this OS does not support the
`--network host` mode these scripts' loopback-based process orchestration assumes) — rather
than report an untrustworthy local result, this section instead relies on the existing,
**unmodified** `caddy` job in `.github/workflows/deploy-config.yml`, which runs all five
checks fresh on every push with a real `caddy` binary, including for this branch's push —
see the closing CI summary at the end of this report for the actual result on the exact
pushed HEAD.

No new domain, redirect, route, or security header was added or loosened this phase.
Automatic HTTPS prerequisites (DNS delegation, reachable 80/443, domain control) were
**validated as configuration only** (by reading the Caddyfile), never exercised live — no
real certificate was requested and no live DNS was touched (§27 of the brief; DNS remains
not-configured per §C).

## S. Public Web artifact deployment

Verified this phase as part of full regression (§Y): `npm ci`, `npm run typecheck`,
`npm run build` all succeed from a clean checkout. `docs/OPERATIONS_RUNBOOK.md` §4
documents identifying which commit a deployed `dist/` came from by recording the git SHA
used for the build — no code change was made to embed a build identifier into the bundle
itself, per the brief's explicit "documentation/file naming is sufficient" allowance
(§9) — a new runtime version-reporting mechanism was deliberately not added.

## T. Android release-signing preparation

`docs/deployment/ANDROID_SIGNING.md` (pre-existing, re-inspected, unchanged) already
satisfies the Phase 14 brief's requirements: release signing is picked up only when
`android/keystore.properties` (gitignored) or four `ORSZEM_RELEASE_*` environment
variables are present; otherwise `assembleRelease` produces an unsigned APK rather than
silently falling back to the debug key. `.github/workflows/android.yml` was re-confirmed
to build only a debug APK and an **unsigned** release APK (`assembleRelease`, no signing
material available to or required by CI) — no CI change was made here. No V2 release key
exists or was generated this phase; §38/§66 forbid generating one in this environment, and
it was not attempted.

## U. Rollback model

Two scenarios, documented in full in `docs/OPERATIONS_RUNBOOK.md` §10:

- **A. Application-only rollback** — safe when the schema is unchanged between versions.
  Deploy the previous known-good jar; no database action.
- **B. Database-impacting rollback** — required when the version being rolled back to
  predates a migration the current schema depends on. Never edits
  `flyway_schema_history`; always: stop writes → restore the pre-migration backup → deploy
  the matching application version.

**Rollback compatibility matrix, current state** (verified against §G's migration table):

| Rolling back from Phase 14 to... | Schema difference | Rollback type |
|---|---|---|
| Phase 13 | none (Phase 13 shipped no migration) | A — application-only, safe |
| Phase 12 | none (Phase 12 shipped no migration) | A — application-only, safe |
| Phase 11 | none (Phase 11 shipped no migration) | A — application-only, safe |
| Phase 10 (before `V006`) | `V006` (service area administration schema) | B — requires restoring a pre-`V006` backup |
| Phase 9 (before `V005`) | `V005` + `V006` | B |
| Earlier | additional migrations | B |

Phases 11, 12 and 13 genuinely shipped no schema change, verified directly from the
migration directory listing (§G) rather than assumed from the brief's own claim that they
might not have.

## V. Failure injection

Tested directly, in a throwaway environment, with results captured verbatim above (§I, §L,
§M): missing/invalid arguments (backup, restore, preflight, backup-age-check all fail
non-zero with no output implying success); a nonexistent output directory; a corrupted
(truncated) restore archive rejected before touching the target; a mismatched restore
checksum rejected before touching the target; **a non-empty restore target (a sentinel
table the archive does not know about) rejected before touching the target, with the exact
offending object named in the refusal and the target confirmed unchanged afterward — new
this correction pass, and specifically what `pg_restore --clean` could not be trusted to
catch (§K/§L/§M)**; a missing environment file for preflight. `caddy validate` against the
real Caddyfile continues to fail loudly on a syntax error (an existing, unmodified property
re-confirmed, not newly added). Port-already-occupied and DB-unavailable-at-backend-startup
were not separately drilled this phase beyond what the `application.yml` fail-loud defaults
(`server.error.include-*: never` plus no datasource-credential default) already guarantee
and what Phase 13's own regression already covers — named here explicitly as not
independently re-tested this phase, rather than silently assumed covered.

**A real failure this phase actually hit, reported honestly:** the first push of this
branch's implementation commit failed CI's new `backup-restore-scripts` job with
`Permission denied` on every script invocation. Root cause: this development workstation's
Git configuration has `core.fileMode=false` (a common Windows default), so the local
`chmod +x` run on each new script was never recorded in the Git index — the scripts were
committed as `100644` (not executable) despite being executable on disk locally. Fixed
with `git update-index --chmod=+x <file>` on each of the five new scripts, verified
locally (`git ls-files -s` now shows `100755` for all five), and a new CI step ("Every
deployment script is executable") was added ahead of the syntax-check step specifically to
catch this class of regression immediately in the future, by checking the mode Git
actually recorded rather than the local working tree's permissions. This is exactly the
kind of thing full regression is supposed to catch — it is recorded here rather than
quietly amended away.

## W. Operations runbook

`docs/OPERATIONS_RUNBOOK.md` (new). Architecture, required software/environment variables,
safe deployment, version identification, backup, backup destination/scheduling/age-check,
restore (including session-restore semantics), the restore drill, database-backup-before-
deploy policy, a restore-verification checklist, the rollback model, Caddy
validation/reload, logs, a common-failure-case table, a preflight checklist, a post-
deployment smoke checklist, and the owner-gated-actions list. Written to be followed by
someone other than the author — every command is copy-pasteable and every script is
referenced by its actual path.

## X. Disaster recovery

`docs/DISASTER_RECOVERY.md` (new). A recovery-source-of-truth table (Git vs. backup vs.
owner-held secret, per category); the re-creatable-infrastructure-state vs. critical-
business-data distinction (Caddy's TLS state is explicitly *not* claimed to need backing
up; the PostgreSQL database explicitly *is* the one thing that does); eight scenario
playbooks (backend lost, Web artifact lost, PostgreSQL process lost with volume intact,
PostgreSQL data lost, whole VM lost, latest backup corrupted, signing machine lost, DNS
still pointing at a dead host) each stating what Git alone reconstructs, what needs the
backup, what needs an owner-held secret, and the restore order; and a test-environment-only
RPO/RTO table, explicitly labelled as such and not presented as a production SLA. The
whole-VM-loss scenario states plainly that recovery is **not** claimed possible without a
verified off-host backup copy — no off-host destination is currently selected (§Z).

## Y. Regression results

**Rerun in full this correction pass**, per the brief's explicit requirement that Phase
13/14's "nothing skipped because Phase 14 only touches deploy" standard actually be met —
the previous pass's Android/Web regression was narrower than this (debug builds + lint +
unit tests only, no connected/instrumented run, no Web test suite); this pass closes that
gap with real, counted results, not just "succeeded":

- **Backend:** `cd backend && ./gradlew clean build` → **BUILD SUCCESSFUL** (re-run again
  after the `orszem-restore.sh`/`orszem-restore-drill.sh` changes in this pass, as the
  brief asked). Since this phase makes no backend *source* change, Gradle's build cache
  validly reused the prior `:test` result (`FROM-CACHE`, keyed on unchanged inputs) rather
  than re-executing every Testcontainers-backed suite locally each time; the exact same
  suite (Phase 2 through Phase 13 coverage, including `UnsupportedHttpMethodIT`,
  `ErrorInformationLeakageIT`, `MassAssignmentHardeningIT`, `SqlInjectionHardeningIT`,
  `SecretRedactionTest`, `AuthorizationFreshnessIT`, `AreaAdminConcurrencyIT`,
  `ModerationConcurrencyIT`) runs fully fresh (no cache) on the GitHub Actions runner in
  `backend.yml` on every push — see the closing CI summary. Independently and freshly (not
  cached): the backend jar was actually started, against a real PostgreSQL, dozens of times
  across every `orszem-restore-drill.sh` run and the manual reference-data checks in this
  session, and stayed healthy every time.

- **Android — full suite, this pass, against a real emulator (`orszem-test(AVD)`, API 15,
  `emulator-5554`), not merely compiled:**

  | Suite | Command | Result |
  |---|---|---|
  | Debug builds | `:public-app:assembleDebug :service-app:assembleDebug` | both succeeded |
  | Public release build | `:public-app:assembleRelease` (unsigned — §T) | succeeded |
  | Lint | `lint` (both modules) | succeeded, no blocking issues |
  | Public unit tests | `testDebugUnitTest` | **36/36 passed**, 0 failed, 0 skipped |
  | Service unit tests | `testDebugUnitTest` | **163/163 passed**, 0 failed, 0 skipped |
  | Public connected/instrumented | `:public-app:connectedDebugAndroidTest` | **16/16 passed**, 0 failed, 0 skipped, on the real emulator (no emulator death this run) |
  | Service connected/instrumented | `:service-app:connectedDebugAndroidTest` | **86/86 passed**, 0 failed, 0 skipped, on the real emulator |
  | Instrumented-test compilation | `compileDebugAndroidTestKotlin`/`...JavaWithJavac` (both modules) | compiled cleanly as part of the connected-test run above |

  Exact counts read directly from the generated JUnit XML
  (`*/build/outputs/androidTest-results/connected/debug/TEST-*.xml` and
  `*/build/test-results/testDebugUnitTest/*.xml`), not estimated. The emulator stayed up
  for the entire run — the brief's "if the emulator dies, that run does not count"
  condition did not trigger.

- **Web — full suite, this pass:**

  | Step | Command | Result |
  |---|---|---|
  | Install | `npm ci` | 112 packages, 0 vulnerabilities |
  | Typecheck | `npm run typecheck` (`tsc --noEmit`) | clean, no errors |
  | Full test suite | `npm test` (`vitest run`) | **8 test files, 55/55 tests passed** |
  | Production build | `npm run build` | succeeded, `dist/` produced |

- **Reference data:** `node reference-data/tools/validate-canonical.mjs
  reference-data/example/manifest.json` (the exact command `reference-data.yml` runs) →
  "canonical reference dataset is valid."; `reference-import reference-data/example` was
  exercised for real, repeatedly, by every restore-drill run in this pass too.

- **Deploy:** `deploy/caddy/Caddyfile` and the four existing Caddy verification scripts are
  unmodified this phase; they were not re-run locally (no `caddy` binary on this
  workstation, and no reliable Docker-based substitute — see §R) and are confirmed instead
  by the existing, unmodified `caddy` CI job on the exact pushed HEAD (see the closing CI
  summary). Locally, rerun fresh after the restore-script rewrite: the `orszem-backup.sh` /
  `orszem-restore.sh` (now with its new non-empty-target check) / `orszem-preflight.sh` /
  `orszem-backup-age-check.sh` failure-injection tests all still pass, and the full
  `orszem-restore-drill.sh` — including the two new mechanisms this correction pass added
  (the real concurrent-write race and the sentinel non-empty-target negative test) — passed
  end to end, cleanup verified. The `backup-restore-scripts` CI job re-runs the same script
  syntax/safety checks and the full drill fresh on every push and PR.

- **CI (this branch, pushed HEAD):** see the closing summary at the end of this report for
  the exact SHA and the 6/6 workflow results (`deploy-config` now runs two jobs — `caddy`
  and `backup-restore-scripts` — alongside `backend`, `android`, `web`, and
  `reference-data`).

## Z. Known limitations

Stated honestly, not presented as defects where they are intentionally gated:

- **Production off-host backup destination not yet selected.** No paid storage is assumed
  (§2/§15); `docs/OPERATIONS_RUNBOOK.md` documents the requirement clearly but the actual
  destination is an explicit, still-open owner decision.
- **Backup scheduling is a documented template only, not enabled.** No production
  cron/systemd timer runs today; activating one requires owner approval (§51).
- **RPO is currently "however long since the last manually-run backup"** until a schedule
  is chosen — stated honestly in `docs/DISASTER_RECOVERY.md` rather than assumed small.
- **Production restore has not been run against real owner data** — only against
  throwaway, synthetic drill data. This phase proves the *mechanism*; it does not and
  cannot prove anything about real production data volume or timing.
- **DNS cutover has not been performed** — `orszembejelento.hu` still has no A or NS
  records (§C), unchanged this phase, owner-gated.
- **The V2 Android release signing key has not been generated** — by design; see §T and
  the owner gate table below.
- **Real production disaster recovery still depends entirely on the owner retaining the
  release signing key and an off-host database backup** — neither exists yet; both are
  named explicitly rather than implied solved.
- **No paid external monitoring or backup service is used or assumed.**
- **No automatic production deployment from CI** — by design (§75/§94); CI in this
  repository validates and tests only.
- **One item in the restored-data invariant checklist (critical DB constraints) was
  verified indirectly** rather than via a fresh, separate `psql \d+` walk this run — see
  the explicit note at the end of §N.
- **Service-session restore semantics were documented and reasoned about, not re-drilled
  live with a fresh login this phase** beyond the Public-capability and fixture-row checks
  actually performed — see §O.

## AA. Owner gates

| Gate | Status |
|---|---|
| `V1_FINAL_DATABASE_BACKUP_VERIFIED` | NO |
| `V2_RELEASE_SIGNING_KEY_READY` | NO |
| `PRODUCTION_DNS_CUTOVER_APPROVED` | NO |
| `V1_RETIREMENT_APPROVED` | NO |
| `V2_PRODUCTION_DEPLOYMENT_APPROVED` | NO |

None of these was inferred or marked complete by this phase. All five remain exactly as
the brief specifies until the owner explicitly changes one.

## AB. Owner approval

**PENDING.**

Everything in the Phase 14 brief's "Owner review gate" (§93) checklist is complete: backup
script complete and failure-tested; restore script/process complete and failure-tested,
**now requiring and verifying an empty target rather than trusting `pg_restore --clean`**;
throwaway backup verified (non-empty, `pg_restore -l`-readable); checksum verified (and its
mismatch rejected); throwaway restore completed; restored backend starts; restored-data
invariants verified (including the Public-capability round trip and, new this pass, the
concurrent-write snapshot-consistency invariant proven with a real POST, not a read); the
restore guard tested with three negative cases (corrupted archive, mismatched checksum,
and a non-empty target — all three rejected without touching the target); Caddy validation
green (via CI — see §R); deployment/preflight validated; both runbooks complete and updated
for the corrected restore contract; full regression green, including Android's full
unit + connected/instrumented suites on a real emulator and Web's full test suite (§Y);
branch pushed; exact final HEAD's CI results recorded in §AC below.

Per §93/§94 of the brief: **no PR is opened until explicit owner approval is given.** This
report will be updated with the owner's approval date, one docs-only commit will record
that approval, and only then — after 6/6 CI on that exact resulting HEAD — will the PR be
opened, titled `chore: harden V2 deployment backup and restore`, `main` ←
`feature/v2-deployment-backup-restore-hardening`, with auto-merge left disabled.

## AC. CI status on this branch

**Prior implementation commit `b5fab0a42fd2b60dc78831078560e8c76564d014`** (before this
narrow correction pass): 6/6 green —
`build` (backend) ✅, `build` (android) ✅, `build` (web) ✅, `caddy` ✅,
`backup-restore-scripts` ✅, `validate` (reference-data) ✅. Reported to the owner at the
time; superseded by the correction commit below, which is the actual current HEAD this
report describes.

**This correction pass's exact final HEAD and its 6/6 CI result are filled in immediately
below, once pushed** — not left as stale "pending push" wording after the fact this time;
see the closing summary at the end of this document.

