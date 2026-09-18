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
5. **Corrected again this pass** (the first correction's version checked only
   `information_schema.tables`, which a second review found insufficient — see the
   closure-correction brief's own §1): the target database must be genuinely empty,
   checked with a read-only query covering every kind of object that can exist with zero
   tables anywhere in sight — a sequence, a materialized view, a foreign table, a
   function/procedure, a custom composite type/domain/enum, a custom schema (even an
   empty one), or an installed extension other than `plpgsql` (which every fresh
   PostgreSQL 16 database already has — confirmed empirically against the `postgres:16`
   image, not assumed: a freshly created database's `pg_extension` contains exactly one
   row, `plpgsql`, and its only namespaces are `pg_catalog`, `information_schema`,
   `pg_toast` and an empty `public`). Any object this check finds is named explicitly —
   with its kind — in the refusal message, and the target is never touched.
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

**The non-empty-target refusal, run for each of the four object kinds §1 of the
correction brief names**, against a real database that has nothing else wrong with it
(the real, valid archive; a correct checksum) — all four are also automated negative
tests in `orszem-restore-drill.sh` (see §M), each against its own freshly created,
otherwise-empty database so the refusal reason is unambiguous:

```
$ docker exec orszem_drill_target_db psql -U orszem_v2 -d orszem_v2_nonempty_table \
    -c "create table sentinel_not_in_archive (id int primary key)"
...
ERROR: target database 'orszem_v2_nonempty_table' is not empty. Found existing object(s):
  - relation (table): public.sentinel_not_in_archive
                                                                  exit 1

$ docker exec orszem_drill_target_db psql -U orszem_v2 -d orszem_v2_nonempty_sequence \
    -c "create sequence sentinel_sequence"
...
ERROR: target database 'orszem_v2_nonempty_sequence' is not empty. Found existing object(s):
  - relation (sequence): public.sentinel_sequence
                                                                  exit 1

$ docker exec orszem_drill_target_db psql -U orszem_v2 -d orszem_v2_nonempty_matview \
    -c "create materialized view sentinel_matview as select 1 as x"
...
ERROR: target database 'orszem_v2_nonempty_matview' is not empty. Found existing object(s):
  - relation (materialized view): public.sentinel_matview
  - type: public.sentinel_matview
                                                                  exit 1

$ docker exec orszem_drill_target_db psql -U orszem_v2 -d orszem_v2_nonempty_function \
    -c "create function sentinel_fn() returns int as \$\$ select 1 \$\$ language sql"
...
ERROR: target database 'orszem_v2_nonempty_function' is not empty. Found existing object(s):
  - function: public.sentinel_fn
                                                                  exit 1
```

(The materialized-view case names two objects, not one — PostgreSQL automatically creates
a composite row type for every relation, including a materialized view; both are
genuinely present, so reporting both is correct, not a bug.) After each refused attempt,
the drill directly re-queries the sentinel object's own catalog entry — `pg_class.relkind`
for the table/sequence/materialized-view cases, `pg_proc` for the function — and asserts
it is still present with the expected kind, rather than only checking a table count or
logging "left as found" as an unverified claim; all four passed.

## M. Restore drill

`scripts/orszem-restore-drill.sh` — a single, repeatable command
(`./scripts/orszem-restore-drill.sh`, requiring only Docker and a built backend jar) that
proves the entire chain end to end against throwaway resources it creates and destroys
itself. It also runs in CI on every push and PR (`deploy-config.yml`,
`backup-restore-scripts` job). **Rewritten again this correction pass**, closing all three
gaps the closure-correction brief identified: the empty-target check now covers every
object kind (§L), the concurrent write is proven to genuinely overlap an active `pg_dump`
rather than merely raced against it, and the fixture dataset now spans the mandatory
cross-phase state (§3 of the brief) instead of one row and one report.

**A real run, narrated** (this exact sequence was executed locally right after the
rewrite; two full clean runs, both `PASS`, ~90 seconds each; full logs retained):

1. A throwaway PostgreSQL container (`orszem_drill_source_db`) is started empty; the real
   backend becomes healthy against it, applying all 6 migrations to the empty database —
   the §83 empty-DB-bootstrap proof, obtained as a side effect.
2. **Cross-phase fixture, built through the real HTTP APIs, not hand-written SQL** (§3 of
   the brief):
   - a **SUPER_ADMIN** via the maintenance CLI (`SZ-592092` this run — varies per run,
     verified, never predicted), who completes the forced initial password change to get
     a real session;
   - a **SERVICE_USER** (`SZ-124569`) and a **MODERATOR** (`SZ-718294`), both created via
     the real `POST /api/v1/service/user-management/users` (SUPER_ADMIN-gated), each
     completing its own forced password change for a real session; the MODERATOR is
     granted global moderation access via the real grant endpoint;
   - a **fourth user** (`SZ-256305`), created the same way and then **deactivated** via
     the real `POST .../deactivate` endpoint — confirmed `DEACTIVATED` in the response
     body itself;
   - a **ServiceArea** created via the real admin API and a real **RailwayLine** (line
     `900`, from the imported example dataset) **mapped into it** via the real
     assign endpoint; the SERVICE_USER is granted access to that area;
   - **four Public reports**, all routed into that area, covering four different
     workflow states: one left `NEW` with an untouched capability (for the capability
     round trip), one **claimed and left `IN_PROGRESS`**, one **claimed then closed into
     `ARCHIVED`**, and one **moderation-deleted** by the MODERATOR (one real moderation
     episode, reason `OTHER`);
   - **two real login sessions** for the SERVICE_USER: one (`VALID_SESSION_BEARER`) is
     left alone; a second, independent login is immediately **logged out for real**
     (`POST /logout`) and confirmed rejected (401) *before* the backup even runs — so the
     later "still revoked after restore" check proves something real, not an assumption.
3. **Critical constraint/index definitions are captured** (§ Critical DB constraints,
   below) before anything else happens.
4. **`scripts/orszem-backup.sh` is run for real, its overlap with a fifth, concurrently
   submitted Public report proven deterministically** — see the dedicated section below,
   not merely "raced and hoped".
5. The backend is stopped. A second, empty throwaway PostgreSQL container
   (`orszem_drill_target_db`) is started.
6. **Six negative tests**, all refused, none touching their target:
   - a truncated copy of the real archive (`pg_restore -l` fails) — corrupted-archive
     rejection;
   - the real archive with a deliberately wrong `.sha256` companion — checksum rejection;
   - **four non-empty-target cases**, each against its own freshly created,
     otherwise-empty database, using the real, valid, correctly-checksummed archive: an
     ordinary table, a sequence with no table at all, a materialized view, and a
     user-defined function — see §L for the exact transcripts and the object each refusal
     named. Each sentinel object's own catalog entry (`pg_class`/`pg_proc`) was
     re-queried directly and asserted still present with the correct kind immediately
     afterward — not merely logged as "left as found".
7. A **seventh**, genuinely empty database (`orszem_v2_restore_ok`) is created in the same
   target container, and `scripts/orszem-restore.sh --confirm-restore` is run for real
   against it. Checksum verifies, `pg_restore -l` succeeds, the (corrected, comprehensive)
   empty-target check passes, `pg_restore` completes — `--no-owner --no-privileges` only,
   no `--clean` (§K).
8. The real backend jar is started against the restored database. It becomes healthy.
   `flyway_schema_history` shows **6 successful rows, no reapplication** (§82).
9. **Every invariant from §3 of the brief is checked directly against the restored
   database** — not assumed, not row-counted — detailed in §N below.
10. Everything (`orszem_drill_source_db`, `orszem_drill_target_db` — and with it every
    database that lived inside it — `orszem_drill_net`, both throwaway backend processes,
    all temp files, the coprocess used for the concurrency barrier) is removed by the
    script's own cleanup trap, verified afterward with `docker ps -a` / `docker network
    ls` showing nothing left behind.

It cleanly reproduces the §56 cleanup-safety requirement (`orszem_drill_*` names only — it
never touches a developer's own `dev-db.sh` container or any other Docker resource) and the
§57 failure-injection requirement (six negative tests now, not two).

### Proving the concurrent write genuinely overlaps an active `pg_dump`

The correction brief is right that "start a background POST, then launch pg_dump" proves
nothing about actual overlap — the POST could finish before pg_dump even connects. The
drill now uses PostgreSQL's own locking as a deterministic **test-only** barrier, entirely
outside `scripts/orszem-backup.sh` (which is run completely unmodified):

1. A throwaway table (`zz_drill_concurrency_barrier`) is created in the source database —
   an object no application code has ever heard of.
2. The harness opens its own long-lived `psql` session (a bash `coproc`) and issues
   `BEGIN; LOCK TABLE zz_drill_concurrency_barrier IN ACCESS EXCLUSIVE MODE;`, then reads
   that session's own output for a marker confirming the lock is actually held — a
   catalog-level fact, not a sleep.
3. `scripts/orszem-backup.sh` is launched in the background. `pg_dump` takes an ACCESS
   SHARE lock on every table it is about to dump — including the barrier table — as part
   of its normal operation, so it now blocks.
4. The harness polls `pg_stat_activity` (again: real server state, not a sleep) until it
   sees a backend named `pg_dump` with `wait_event_type = 'Lock'` — i.e. **connected,
   inside its transaction, and specifically blocked on a lock** — and records its PID.
   Observed this run: pid `210`.
5. **Only now** does the harness submit the concurrent Public report through the real
   `POST /api/v1/public/reports` endpoint. It commits (`201`).
   `clientSubmissionId=93b9899b-231c-15f6-d624-6ca2d47857e9` this run.
6. The harness immediately re-queries `pg_stat_activity` for that exact same PID. It is
   still present. This is the actual proof of overlap: the same `pg_dump` backend was
   observed alive and blocked both immediately before and immediately after the
   concurrent write committed — not inferred from timing, read directly off PostgreSQL's
   own activity view.
7. The harness releases the barrier (`COMMIT;`); `pg_dump` proceeds and the backup
   completes normally.

One consequence worth stating precisely rather than glossed over: `pg_dump`'s
`REPEATABLE READ` snapshot is established at its transaction's *first statement*, which
happens well before it reaches the per-table locking phase this barrier blocks it at. So
the concurrent write, submitted while `pg_dump` is blocked on the barrier, is **excluded
from that snapshot by construction** — every run of this drill lands in the "both absent"
branch of the invariant below, deterministically, not by chance. The drill's assertion
still accepts either outcome (as the brief requires), and logs plainly which branch
occurred and why it was expected; only a *torn* result — one table has the row, the other
does not — is a hard failure, and that never occurred.

## N. Restored-data invariant verification

Every item below was checked **directly against the restored database**, by comparing a
value captured from the source database before the backup against the same query run
against the restored database after restore — not by row count, and not assumed:

| Invariant | Result |
|---|---|
| `flyway_schema_history` | 6/6 successful rows, unchanged, no reapplication |
| All four users' `service_id`s | present, unchanged |
| The deactivated user's `status` | still `DEACTIVATED` (restore never resurrects one) |
| `IN_PROGRESS` report's `status`/`assigned_user_id` | byte-identical before/after |
| `ARCHIVED` report's `status`/`archived_at` | byte-identical before/after |
| That report's full identity: `public_id`, `submitted_at`, `status`, and its routing snapshot's `routing_status`/`service_area_id`/`routing_reason` together | byte-identical before/after — not two columns in isolation |
| One concrete moderation episode, by its own `id`: `reason`, `deleted_at`, open/closed state, and `deleted_by_user_id` (the immutable stored actor identity) | byte-identical before/after |
| One concrete audit event, by its own `id`: the real `REPORT_MODERATION_DELETED` event tied to the moderated report — `id`, `event_type`, `created_at` | byte-identical before/after |
| `audit_events` total row count | 22 before backup, 22 after restore this run (an additional sanity check, never the only proof) — never drops; the concurrent write may add at most one more |
| The current reference-import row (`dataset_version`/`is_current`) | byte-identical before/after |
| The ServiceArea↔RailwayLine mapping row for the fixture line | byte-identical before/after |
| The concurrent-write snapshot invariant (§81) | `reports` and `report_routing_snapshots` agree — both absent this run, exactly as the barrier's timing predicts (see §M) — a torn result would be the only failure |
| Public report capability (§22/§97) | the real capability still resolves the same report (200); a never-used wrong one still returns generic `REPORT_NOT_FOUND` (404) |
| The still-valid session | still authenticates against the restored database (200 on a real authenticated `GET`) |
| The already-revoked session | still rejected against the restored database (401) — restore never resurrects a revoked session |
| Critical constraint/index definitions | see the dedicated section below — no longer "DDL is presumably reproduced", now byte-compared |

### Critical DB constraints — compared, not assumed

The prior version of this report treated `pg_restore` reproducing DDL as sufficient proof
that constraints survive. The correction brief is right that this is an assumption, not a
check — and a second review found the first correction's own query still incomplete: it
named `report_routing_snapshots_pkey` and, in prose, called that primary key "also its
foreign key back to reports". **That was wrong. A primary key is not a foreign key, even
when both happen to be declared on the same column** — PostgreSQL creates two entirely
separate `pg_constraint` rows from a single inline `report_id UUID PRIMARY KEY REFERENCES
reports (id)` column definition (confirmed empirically against a real PostgreSQL 16
instance: `report_routing_snapshots_pkey`, `contype = 'p'`, and
`report_routing_snapshots_report_id_fkey`, `contype = 'f'`, as two distinct rows). The
query now captures both, and the foreign key is found **by relation and constraint type
(`conrelid`/`contype`/`confrelid`), not by its generated name** — a more robust query, per
the correction brief's own preference, that would keep working even if Postgres's naming
convention ever changed:

```sql
select 'INDEX:' || indexname || ':' || indexdef
from pg_indexes
where indexname in (
  'ux_users_service_id', 'ux_reports_public_id', 'ux_reports_client_submission_id',
  'ux_report_assignments_open_episode', 'ux_report_moderation_episodes_open_episode',
  'ux_service_area_railway_lines_line'
)
union all
select 'CONSTRAINT:' || conname || ':' || pg_get_constraintdef(oid)
from pg_constraint
where conname in ('report_routing_snapshots_pkey', 'service_area_railway_lines_pkey')
union all
select 'CONSTRAINT:' || conname || ':' || pg_get_constraintdef(oid)
from pg_constraint
where conrelid = 'report_routing_snapshots'::regclass and contype = 'f'
  and confrelid = 'reports'::regclass
order by 1;
```

Plain unique indexes (`CREATE UNIQUE INDEX`) never register as `pg_constraint` rows, so
`pg_get_constraintdef` alone would have silently missed every one of them — `pg_indexes`
covers those; `pg_constraint`/`pg_get_constraintdef` covers the three named constraints.
Together this now names: the users/service_id uniqueness, the reports/public_id and
reports/client_submission_id uniqueness (the idempotency key), the "at most one open
assignment per report" invariant, the "at most one open moderation episode per report"
invariant, the report_routing_snapshots table's **primary key** (identity/uniqueness — one
snapshot per report) **and, separately, its foreign key** back to `reports` (referential
integrity — the actual mechanism §81's "never torn" property structurally relies on), and
the ServiceArea↔RailwayLine mapping's primary key plus its "a line belongs to at most one
area" uniqueness. **9 definitions captured this run** (the FK query correctly returns
exactly one row — `report_routing_snapshots`'s two *other* foreign keys, to
`railway_lines` and `service_areas`, are deliberately excluded by the `confrelid =
'reports'` filter, since only the FK back to `reports` was in scope), asserted to be at
least 9 before the comparison even runs (so an incomplete comparison could never pass by
accident), and found **byte-identical** between the source snapshot and the restored
database.

## O. Session/capability behaviour after restore

- **Public report capability:** proven directly — §N, and unchanged from the prior pass.
- **Service session (login) restore semantics — now proven, not merely documented.**
  Two real sessions were established before the backup: one left valid, one deliberately
  logged out (revoked) and confirmed rejected *before* the backup ran. After restore,
  against the real restored database: the still-valid session's original access token
  authenticates a real protected endpoint (200); the already-revoked session's token is
  still rejected (401). This matches the honestly-documented behaviour in
  `docs/OPERATIONS_RUNBOOK.md` §6: a restore replays whatever sessions were valid at the
  snapshot moment, and neither revokes nor resurrects anything by itself (§21 — no hidden
  mutation) — now backed by a real, repeatable drill rather than reasoning alone.
- The documented, reviewed, *manual-only* emergency procedure for revoking every session
  after a restore (`UPDATE auth_sessions SET revoked_at = now(), revocation_reason = ...
  WHERE revoked_at IS NULL` — corrected this pass to the table's actual name and its
  actual `revocation_reason` column, which the original text got wrong) remains
  deliberately manual, not automated into a hidden admin capability the brief explicitly
  prohibits.
- Auth semantics were not changed in any way for restore convenience, this pass or the
  last.

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
checksum rejected before touching the target; **four shapes of non-empty restore target —
an ordinary table, a sequence with no table at all, a materialized view, and a
user-defined function — each rejected before touching the target, each with the exact
offending object(s) and kind named in the refusal, each target confirmed unchanged
afterward (§L/§M) — this is specifically what a naive `information_schema.tables`-only
check, and what `pg_restore --clean`, could not be trusted to catch**; a missing
environment file for preflight. `caddy validate` against the real Caddyfile continues to
fail loudly on a syntax error (an existing, unmodified property re-confirmed, not newly
added). Port-already-occupied and DB-unavailable-at-backend-startup were not separately
drilled this phase beyond what the `application.yml` fail-loud defaults
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

**This is the third regression pass** (the implementation pass, then the "empty-restore-
target / real-concurrent-write / cross-phase-invariants" correction pass this section now
describes). Per the current brief's own §5: "do not rerun the entire Android emulator
suite merely because a shell test changed unless repository CI/product inputs change" —
this pass touched only `scripts/orszem-restore.sh`, `scripts/orszem-restore-drill.sh`, and
documentation; zero Android/Web/backend *source* files changed (verified: `git diff
<previous-pass-HEAD>..HEAD --stat` touches only `scripts/` and `docs/`). Accordingly:

- **Backend:** re-run fresh this pass — `cd backend && ./gradlew clean build` →
  **BUILD SUCCESSFUL**. No backend source changed, so Gradle's build cache validly reused
  the prior `:test` result (`FROM-CACHE`, keyed on unchanged inputs) rather than
  re-executing every Testcontainers-backed suite locally; the exact same suite (Phase 2
  through Phase 13 coverage) runs fully fresh (no cache) on the GitHub Actions runner in
  `backend.yml` on every push — see the closing CI summary, including how the pre-existing
  `ModerationConcurrencyIT` flake was handled if it recurs. Independently and freshly (not
  cached): the backend jar was started, against a real PostgreSQL, dozens more times across
  every `orszem-restore-drill.sh` run this pass (now a substantially larger, cross-phase
  fixture), and stayed healthy every time.

- **Android — carried forward from the previous regression pass, not re-run this pass**,
  per the brief's own instruction above: no Android source, no Android CI input, and no
  product behavior changed since that pass's real, counted, real-emulator run (full
  results below, reproduced for reference — not re-executed today):

  | Suite | Command | Result |
  |---|---|---|
  | Debug builds | `:public-app:assembleDebug :service-app:assembleDebug` | both succeeded |
  | Public release build | `:public-app:assembleRelease` (unsigned — §T) | succeeded |
  | Lint | `lint` (both modules) | succeeded, no blocking issues |
  | Public unit tests | `testDebugUnitTest` | **36/36 passed**, 0 failed, 0 skipped |
  | Service unit tests | `testDebugUnitTest` | **163/163 passed**, 0 failed, 0 skipped |
  | Public connected/instrumented | `:public-app:connectedDebugAndroidTest` | **16/16 passed**, 0 failed, 0 skipped, on the real emulator |
  | Service connected/instrumented | `:service-app:connectedDebugAndroidTest` | **86/86 passed**, 0 failed, 0 skipped, on the real emulator |
  | Instrumented-test compilation | `compileDebugAndroidTestKotlin`/`...JavaWithJavac` (both modules) | compiled cleanly as part of the connected-test run |

  Exact counts read directly from the generated JUnit XML at the time of that run, not
  estimated. CI (`android.yml`) still runs its own full build/lint/unit pass fresh on this
  pass's pushed HEAD regardless — see the closing CI summary; only the *additional*,
  slower, real-emulator connected-test run (not part of CI, which compiles but does not
  execute instrumented tests) was carried forward rather than repeated locally.

- **Web — carried forward from the previous regression pass, not re-run locally this
  pass** for the same reason (no Web source or input changed); CI (`web.yml`) still runs
  `npm ci`/`typecheck`/`build` fresh on this pass's pushed HEAD:

  | Step | Command | Result |
  |---|---|---|
  | Install | `npm ci` | 112 packages, 0 vulnerabilities |
  | Typecheck | `npm run typecheck` (`tsc --noEmit`) | clean, no errors |
  | Full test suite | `npm test` (`vitest run`) | **8 test files, 55/55 tests passed** |
  | Production build | `npm run build` | succeeded, `dist/` produced |

- **Reference data:** re-run fresh this pass —
  `node reference-data/tools/validate-canonical.mjs reference-data/example/manifest.json`
  (the exact command `reference-data.yml` runs) → "canonical reference dataset is valid.";
  `reference-import reference-data/example` was exercised for real, repeatedly, by every
  restore-drill run this pass too, now as part of a substantially larger fixture (a
  ServiceArea, a RailwayLine mapping, four users, four reports).

- **Deploy:** `deploy/caddy/Caddyfile` and the four existing Caddy verification scripts
  remain unmodified and were not re-run locally this pass either (same standing constraint
  — see §R); confirmed instead by the existing, unmodified `caddy` CI job on this pass's
  exact pushed HEAD (see the closing CI summary). Locally, rerun fresh, twice, after the
  rewrite: `orszem-backup.sh` / `orszem-restore.sh` (now with the comprehensive
  empty-target check) / `orszem-preflight.sh` / `orszem-backup-age-check.sh`
  failure-injection tests all still pass, and the full, substantially-rewritten
  `orszem-restore-drill.sh` — cross-phase fixture, the deterministic concurrency-barrier
  overlap proof, all six negative tests, the constraint-definition comparison, the
  service-session proof — passed end to end **twice in a row**, ~90 seconds each,
  cleanup verified both times. The `backup-restore-scripts` CI job re-runs the same script
  syntax/safety checks and the full drill fresh on every push and PR.

- **CI (this branch, pushed HEAD):** see §AC for the exact SHA and the full, honest
  per-check results (`deploy-config` runs two jobs — `caddy` and `backup-restore-scripts`
  — alongside `backend`, `android`, `web`, and `reference-data`) — including the one
  pre-existing test that did not go green on this SHA, reported plainly rather than
  rounded up.

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
- **The empty-target guard is defense-in-depth, not an exhaustive enumeration of every
  conceivable PostgreSQL catalog object class.** It covers the object kinds the
  implementation and its tests actually exercise — tables, views, materialized views,
  sequences, foreign tables, functions/procedures, custom types/domains/enums, custom
  schemas, non-default extensions (§L) — which is what a database an operator actually
  populated by hand or with a previous restore would contain. The supported operational
  contract is, and remains, that an operator creates a genuinely fresh, empty target
  database for every restore (`docs/OPERATIONS_RUNBOOK.md` §6); this guard is the safety
  net that catches the mistake if that did not happen, not a substitute for it.

Two items in earlier drafts of this report — "critical DB constraints were only verified
indirectly" and "service-session restore semantics were not re-drilled live" — are removed
here because they are no longer true: both are now directly proven (§N, §O, and the FK-vs-
PK correction below) and calling them limitations would contradict the report's own
evidence.

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
**requiring and verifying a genuinely empty target** — as defense-in-depth for the object
kinds the implementation and its tests actually cover (tables, views, materialized views,
sequences, foreign tables, functions/procedures, custom types/domains/enums, custom
schemas, non-default extensions — §L), not a claim of exhaustive coverage of every
conceivable PostgreSQL catalog object class; the supported contract remains an operator
creating a genuinely fresh, empty target — **rather than trusting `pg_restore --clean`**;
throwaway backup verified (non-empty, `pg_restore -l`-readable); checksum verified (and its
mismatch rejected); throwaway restore completed; restored backend starts; restored-data
invariants verified against a **real cross-phase fixture** — four users spanning
active/deactivated, a ServiceArea with a RailwayLine mapped into it, four Public reports
covering NEW/IN_PROGRESS/ARCHIVED/moderation-deleted, two real service sessions (one valid,
one revoked before the backup) — including one concrete report's full identity
(`public_id`/`submitted_at`/`status`/routing snapshot), one concrete moderation episode's
full identity (`id`/`reason`/`deleted_at`/open-state/actor), one concrete audit event's
full identity (`id`/`event_type`/`created_at`), the Public-capability round trip, the
service-session outcomes, and the concurrent-write snapshot-consistency invariant now
proven via a **deterministic PostgreSQL-lock test barrier** (not an assumed race) with a
real POST; the restore guard tested with **six** negative cases (corrupted archive,
mismatched checksum, and four non-empty-target object shapes — all six rejected without
touching the target, **each sentinel object's own catalog entry directly re-queried and
confirmed unchanged afterward**, not merely logged as "left as found"); **critical
constraint/index definitions captured before backup and compared byte-for-byte after
restore, correctly including the routing snapshot's primary key AND, separately, its
foreign key back to `reports` — a primary key is not a foreign key, even on the same
column, and this report's own prior draft wrongly said otherwise** (§N); Caddy validation
green (via CI — see §R); deployment/preflight validated; both runbooks complete and updated
for the corrected restore contract and the comprehensive empty-target definition; full
regression run and its results recorded honestly, not rounded up (§Y).

Per §93/§94 of the brief: **no PR is opened until explicit owner approval is given.** This
report will be updated with the owner's approval date, one docs-only commit will record
that approval, and only then — after 6/6 CI on that exact resulting HEAD — will the PR be
opened, titled `chore: harden V2 deployment backup and restore`, `main` ←
`feature/v2-deployment-backup-restore-hardening`, with auto-merge left disabled.

## AC. CI status on this branch

Two different things, kept deliberately separate rather than conflated: the commit history
that produced and evidenced the implementation this report describes, and the CI status of
the branch's actual latest tip at the time this section was last written. This section
does **not** attempt to name its own commit's resulting SHA inside itself — that SHA does
not exist until after this file is committed, so no document can correctly contain it in
advance; the chat turn that accompanies each push is the actual source of truth for "the
current tip", and this section is refreshed after the fact, honestly, rather than guessed.

### Implementation/evidence commit history (not the current tip — historical record only)

- `e239cee52c6a00d12a0c230d03ef4897d440672c` — initial implementation. 5/6: one genuine
  failure (`backup-restore-scripts`, a real executable-bit bug), fixed next commit.
- `b5fab0a42fd2b60dc78831078560e8c76564d014` — executable-bit fix. 6/6 green.
- `ef8690f44d6198016c92a8b0c8c57f63bc6a9177` — first correction pass (empty-target v1,
  real-write concurrency v1, initial cross-phase fixture). 6/6 green after one retry of
  the pre-existing `ModerationConcurrencyIT` flake; confirmed unrelated (zero backend
  source diff) and non-reproducing in 4/4 fresh local isolated runs.
- `4a8c6f972970766b33abf5f69e5d2441af97f378` — second correction pass: the comprehensive
  empty-target check (every object kind, §L), the deterministic PostgreSQL-lock overlap
  barrier (§M), and the expanded cross-phase fixture (four users, ServiceArea/RailwayLine
  mapping, four workflow-state reports, two sessions). **5/6** on this exact SHA: `build`
  (backend) failed twice on the same pre-existing `ModerationConcurrencyIT` flake
  (`ModerationConcurrencyIT.kt:147`, zero backend source diff on this branch, one retry
  used per the correction brief's own §5 allowance, not retried further, not weakened or
  skipped, reproduced 7/7 clean in fresh local isolated runs across both correction
  passes) — the other five checks (`android`, `web`, `caddy`,
  `backup-restore-scripts` including the entire rewritten drill, `reference-data`) were
  green on the first attempt.
- `12756d39bfb7df31f231f4221eeb75bc08776646` — docs-only commit recording the honest 5/6
  result above (no code change). **6/6 green** — the pre-existing flake did not recur on
  this SHA's own backend run.

### Third correction pass — sentinel-preservation assertions, extended immutable
### comparisons (report/audit/moderation), the routing-snapshot foreign-key proof

This pass changes `scripts/orszem-restore-drill.sh` (assertion logic only — no restore
contract change) and this document. Its own resulting commit SHA and 6/6 (or honestly
reported otherwise) CI status are **not written into this file** — see the chat turn that
accompanies the push for the authoritative current branch tip, per the note at the top of
this section.

