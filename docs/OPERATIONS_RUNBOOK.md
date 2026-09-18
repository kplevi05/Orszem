# V2 operations runbook

Written to be usable by someone other than the person who wrote the code. It assumes
`SERVER_RUNBOOK.md` has already been followed once (backend running as `orszem-backend`,
Caddy running, a clean `orszem_v2` database and role) and covers what happens after that:
backup, restore, verification, rollback, logs, and the common failure cases.

No secret value appears anywhere in this document.

## 1. Architecture / topology

```
Public Android ─────────┐
Public Web (static)  ───┼──→ Caddy (edge, loopback admin) ──→ Spring Boot (loopback) ──→ PostgreSQL (loopback)
Service Android ────────┘
```

- Caddy is the only process reachable from the public internet (ports 80/443). It
  terminates TLS, serves the Public Web static bundle, and reverse-proxies `/api/*` to the
  backend. See `deploy/caddy/Caddyfile`.
- The backend (`orszem-backend.service`, systemd) binds `127.0.0.1:8081` only - see
  `deploy/systemd/orszem-backend.service` and `deploy/env/backend.env.example`.
- PostgreSQL binds loopback only and is never in the OCI security list. See §61 of the
  Phase 14 brief / `docs/PHASE_14_ENGINEERING_REPORT.md` §Q.
- No Docker Compose, no Kubernetes, no message broker in production. This is a deliberate,
  boring, three-native-process deployment - see `SERVER_RUNBOOK.md`.

## 2. Required software (production host)

- PostgreSQL 16 (`postgresql-server` + `postgresql-contrib`, which also provides the
  client tools `pg_dump`/`pg_restore`/`psql`/`pg_isready` this runbook's scripts use).
- A JDK 21 runtime for the backend jar.
- Caddy 2.11.x.
- `bash`, `sha256sum`, `openssl`, `curl` (all standard on the target distribution).

## 3. Required environment variables

See `deploy/env/backend.env.example` for the authoritative, commented list. In summary:

| Variable | Required | Purpose |
|---|---|---|
| `ORSZEM_DB_URL` | yes | `jdbc:postgresql://127.0.0.1:5432/orszem_v2` |
| `ORSZEM_DB_USERNAME` | yes | the `orszem_v2` role |
| `ORSZEM_DB_PASSWORD` | yes | never has a default, on purpose (§33) |
| `ORSZEM_BIND_ADDRESS` | no (defaults to loopback) | never set this to a public address |
| `ORSZEM_PORT` | no (defaults to 8081 via the env file) | |
| everything under "Authentication" in the example file | no | tuned pilot defaults |

Run `./scripts/orszem-preflight.sh /etc/orszem/backend.env` before (re)starting the
service after any configuration change. It reports **missing variable names only**, never
values - see its own header comment.

## 4. Safe deployment

Follow `SERVER_RUNBOOK.md` for the first install. For an update to an already-running
instance:

```bash
# 1. Preflight the environment file first - catches an obviously broken config for free.
./scripts/orszem-preflight.sh /etc/orszem/backend.env

# 2. If this deployment includes a NEW database migration, back up first (§8 below is
#    mandatory in that case; see "Database backup before deploy").

# 3. Validate the Caddy config before touching anything running.
caddy validate --config deploy/caddy/Caddyfile --adapter caddyfile

# 4. Stop, replace the artifact, restart (see SERVER_RUNBOOK.md §6).
sudo systemctl stop orszem-backend
sudo install -o orszem -g orszem -m 0644 /tmp/backend.jar /home/opc/apps/orszem-v2/backend.jar
sudo systemctl start orszem-backend
sudo systemctl status orszem-backend

# 5. Confirm it is actually healthy, not just "started".
curl -s http://127.0.0.1:8081/actuator/health   # {"status":"UP"}
```

`Restart=on-failure` in `deploy/systemd/orszem-backend.service` means a crash restarts the
service automatically; it does not mask a permanent startup failure (a bad migration, a
wrong credential) because Flyway/Spring exit non-zero immediately in those cases and the
unit ends up `failed`, visible in `systemctl status` and the journal, rather than silently
retrying forever.

### What "the deployed revision" is

Phase 14 does not add a version/build endpoint (see §9 of the Phase 14 brief: no new
public diagnostics API). Instead, identify a deployed artifact the boring way, at build
time:

```bash
git rev-parse HEAD                              # the commit being deployed
cd backend && ./gradlew clean bootJar            # -> build/libs/backend.jar
cp build/libs/backend.jar "backend-$(git rev-parse --short HEAD).jar"
```

Record that filename (or just the SHA) in your own deployment log alongside the date. The
same applies to the Public Web bundle - `cd web/public-web && npm run build`, and record
the SHA the `dist/` came from. This is deliberately just documentation/file naming (the
brief's own explicitly-sufficient fallback), not a new mechanism.

## 5. Backup

```bash
set -a; . /etc/orszem/backend.env; set +a
mkdir -p /home/opc/backups   # wherever local backups are kept; not created automatically
./scripts/orszem-backup.sh /home/opc/backups --release-sha "$(git rev-parse HEAD)"
```

Produces three files per run (see `scripts/orszem-backup.sh`'s own header for the full
contract):

- `orszem-v2-<UTC timestamp>.dump` - the `pg_dump -Fc` archive itself.
- `orszem-v2-<UTC timestamp>.dump.sha256` - its checksum.
- `orszem-v2-<UTC timestamp>.metadata.txt` - timestamp, source host/db, application git
  SHA, PostgreSQL client version, filename, checksum. No credentials.

The script already verifies the archive is non-empty and that `pg_restore -l` can read it
before it is considered a real backup, and never silently overwrites an existing one.

### Backup destination

A backup that lives only on the machine it backs up is not a disaster-recovery backup -
only protection against a bad deployment on the *same* machine. Two distinct concepts:

- **A. Local/staging output** - `/home/opc/backups` (or wherever chosen) on the production
  VM itself. Fast, but does not survive losing that VM.
- **B. Off-host copy** - at least one verified copy must exist somewhere other than the
  production VM for genuine disaster recovery. Phase 14 does not choose or configure this
  destination: no paid storage may be assumed (§2/§15 of the brief), and no free
  destination is configured in this repository today. This is recorded as a known
  limitation in `docs/PHASE_14_ENGINEERING_REPORT.md` §Z - it is an explicit owner
  decision, not an oversight.

### Backup scheduling (template only, not activated)

```
# /etc/systemd/system/orszem-backup.timer  (example only - not installed by Phase 14)
[Timer]
OnCalendar=*-*-* 03:00:00
Persistent=true

[Install]
WantedBy=timers.target
```

paired with a `.service` unit that runs `orszem-backup.sh` with the environment sourced as
above. **Not enabled in this phase** - §51 of the brief requires explicit owner approval
before any production cron/timer is activated. This is a template for that later decision.

### Is there a recent backup?

```bash
./scripts/orszem-backup-age-check.sh --dir /home/opc/backups --max-age-hours 26
```

Exits non-zero (with the newest backup's actual age printed) if the newest `*.dump` is
older than the threshold, or if there is none. No production threshold is hard-wired -
`--max-age-hours` is always required. Useful wired into a preflight step or checked
manually; it does not alert anyone by itself (§52 - no paid alerting is added; manual
operational checking is the documented limitation until the owner chooses otherwise).

### Backup file sensitivity

Treat every `.dump` file as sensitive: it can contain user data, report data, audit
history and account identifiers. Never commit one, never attach one to a CI artifact,
never paste its contents into a screenshot, a chat, or the engineering report. See §Z of
`docs/PHASE_14_ENGINEERING_REPORT.md`.

## 6. Restore

**Destructive. Read this whole section before running it against anything that matters.**

### The target must be a freshly created, empty database

`orszem-restore.sh` only ever restores INTO an already-empty database - it never drops,
recreates, or "cleans" a populated one, and it refuses outright if the target has any
application table or view in it already. This is deliberate, not a missing feature:
`pg_restore --clean --if-exists` only knows how to drop objects the *archive itself*
names - an object that exists in the target but was never in the archive (leftover from a
newer schema, from a previous different restore, anything created by hand) is invisible to
`--clean` and would be silently left behind. That is exactly the wrong failure mode for
disaster recovery / rollback, where the target may have started from a *newer* schema than
the archive being restored. So the contract is simpler and stronger: create a fresh, empty
target first; the script proves it is empty (read-only check) before touching it.

```bash
# 1. Stop the backend - stop writes before anything else.
sudo systemctl stop orszem-backend

# 2. Create a fresh, empty target database - a NEW name, never a database that already
#    exists, even one you intend to overwrite (see above for why "restore will clean it
#    for me" is not true). This script never drops a database; if a previous restore
#    rehearsal left one behind under this name, that is a separate, deliberate operator
#    decision - pick a new name instead of reusing it.
sudo -u postgres psql -c "CREATE DATABASE orszem_v2_restore_target OWNER orszem_v2;"

# 3. Restore into it.
export PGPASSWORD='...'   # never on the command line
./scripts/orszem-restore.sh \
  --dump /home/opc/backups/orszem-v2-2026-09-18T030000Z.dump \
  --target-environment production-restore \
  --host 127.0.0.1 --port 5432 --database orszem_v2_restore_target --user orszem_v2 \
  --confirm-restore

# 4. Verify (§9 below) BEFORE pointing production at it.

# 5. Only once verified: point ORSZEM_DB_URL at the restored database (rename it to the
#    name the application expects, or update ORSZEM_DB_URL to match) and start the backend.
```

Every connection flag is required and none of them default to the running application's
own `ORSZEM_DB_*` configuration - see the script's own header for why. Without
`--confirm-restore` the script performs every safety check and then exits non-zero without
touching anything ("dry run"), so it is always safe to first run the exact same command
without that flag and read what it says.

Safety checks, each of which aborts before the target database is touched:

1. the dump file exists and is non-empty;
2. its companion `.sha256` matches (or `--skip-checksum-verification` was passed
   explicitly, with a loud warning);
3. `pg_restore -l` can read its table of contents (rejects a corrupted/truncated archive);
4. the target database is empty - a read-only inspection (no table/view outside
   `pg_catalog`/`information_schema`/`pg_toast*`); a non-empty target is refused with the
   specific object(s) found named in the error, and is never modified;
5. `--confirm-restore` was actually passed.

### After a restore

- Start (or restart) the backend against the now-restored database and confirm
  `/actuator/health` is `UP`. Flyway will recognise the restored `flyway_schema_history`
  and apply nothing further, unless a genuinely new migration exists that the backup
  predates - in that case it applies only that migration, exactly as a normal deploy
  would.
- Verify the invariants in §9 below before declaring the restore complete.

### Session restore semantics

A restore replays whatever sessions were valid at the backup's snapshot moment - it does
not automatically revoke or expire anything (§21). Document this to whoever is restoring:
if the restore point is old, sessions from it may already have naturally expired by
`session-lifetime`; if it is recent, some may still be genuinely valid, which is normal and
expected logical-snapshot behaviour, not a bug.

If an emergency requires invalidating every session at once *in addition to* restoring
(for example: the restore is part of responding to a credential compromise), there is no
built-in "nuke all sessions" endpoint (that would be exactly the kind of hidden admin
backdoor the brief prohibits in §21). The precise, reviewed, transactional statement is:

```sql
-- Run manually, deliberately, against the restored database only, by an operator who has
-- read this. Revokes every session; touches nothing else.
UPDATE sessions SET revoked_at = now() WHERE revoked_at IS NULL;
```

Confirm this statement still matches the current `sessions` table shape before running it
(inspect the latest migration that touches `sessions`) - it is documented here, not
automated, precisely so a human reviews it against the schema that exists at the time.

## 7. Restore drill (repeatable, automated)

```bash
cd backend && ./gradlew bootJar
cd ..
./scripts/orszem-restore-drill.sh
```

Runs the whole chain - empty-DB bootstrap, a real fixture row, a real Public report and
its capability, `orszem-backup.sh` raced against a second, real, concurrently-submitted
Public report (proving the snapshot is never torn - §81), three negative restore tests
(corrupted archive, mismatched checksum, and a non-empty target with a sentinel object the
archive knows nothing about - all three must be refused without touching the target), the
real restore into a freshly created empty database, backend startup against the restored
database, and invariant checks (including the concurrent-write snapshot invariant and the
Public capability round trip) - against Docker containers it creates and destroys itself
(`orszem_drill_*` only; see the script's cleanup trap). Safe to run repeatedly, including
in CI (`.github/workflows/deploy-config.yml`). See
`docs/PHASE_14_ENGINEERING_REPORT.md` §M for a narrated run and what it proved.

## 8. Database backup before deploy

**Mandatory** whenever a deployment includes a new Flyway migration relative to what is
currently running:

```bash
./scripts/orszem-backup.sh /home/opc/backups --release-sha "$(git rev-parse HEAD)"
```

If the deployment does **not** include a new migration (application-code-only change),
a backup is still recommended before anything production-facing, but is not forced by
tooling - re-run it as often as your own risk tolerance wants, but do not run it on every
ordinary process *restart* (a crash-restart via `Restart=on-failure` is not a "version
deployment" and does not need its own backup).

## 9. Restore verification checklist

After any restore (drill or real), confirm - not merely "row counts look plausible":

- [ ] `flyway_schema_history` intact, no unexpected new rows;
- [ ] a known user's `service_id` present and unchanged;
- [ ] a known Public report's public id, `submitted_at`, and routing snapshot
      (`service_area_id` / reason) unchanged;
- [ ] a known audit event's id/time/type unchanged;
- [ ] a known moderation episode's history unchanged;
- [ ] ServiceArea / RailwayLine configuration intact;
- [ ] a Public report capability captured before the backup still resolves the same
      report after restore, and a wrong capability still returns generic
      `REPORT_NOT_FOUND` (404) - never a different error, never a different status;
- [ ] critical DB constraints still exist (foreign keys, uniqueness) - `\d+ <table>` in
      `psql` against the restored database, spot-checked against the migration that
      created them.

Never treat "the restore command exited 0" alone as proof.

## 10. Rollback

Two different scenarios - do not conflate them.

**A. Application-only rollback** (schema is backwards-compatible with the previous
release - i.e. the release being rolled back to introduced no migration the current
schema depends on):

```bash
sudo systemctl stop orszem-backend
sudo install -o orszem -g orszem -m 0644 /path/to/previous-backend.jar /home/opc/apps/orszem-v2/backend.jar
sudo systemctl start orszem-backend
```

Keep the previous known-good jar around (named with its git SHA, §4 above) specifically so
this is possible without rebuilding under pressure.

**B. Database-impacting rollback** (the release being rolled back introduced a migration
that changed something the previous application version cannot tolerate):

Do **not** "downgrade migrations", do not manually edit or delete rows from
`flyway_schema_history`, and do not attempt to restore over the live, populated
production database in place - `orszem-restore.sh` refuses that outright (§6). The
preferred and only supported recovery is:

1. stop the backend (stop writes);
2. create a fresh, empty target database (§6) - never the live one;
3. restore the known-good backup taken before the migration in question was applied
   (§8 - this is exactly why a pre-migration backup is mandatory) into that fresh target;
4. verify the restored invariants (§9);
5. point the application at the restored database (rename it into place, or update
   `ORSZEM_DB_URL`) and deploy the application version that matches that backup's schema.

See `docs/PHASE_14_ENGINEERING_REPORT.md` §U for the current rollback-compatibility matrix
(which recent Phases actually shipped a migration, and whether app-only rollback across
them is safe).

## 11. Caddy validation / reload

```bash
sudo caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
sudo systemctl reload caddy      # graceful; does not drop connections
```

Always validate before reloading. `deploy-config.yml` runs the same validation, the public
routing-policy check, the access-log header-redaction check, and the Public Web CSP check
in CI on every push and PR - see `docs/PHASE_14_ENGINEERING_REPORT.md` §R.

## 12. Logs

- Backend: journal only (`StandardOutput=journal` in the systemd unit) -
  `journalctl -u orszem-backend -f`. No application log files to rotate. journald's own
  retention/rotation policy applies (`/etc/systemd/journald.conf` -
  `SystemMaxUse=`/`MaxRetentionSec=` if a tighter bound than the distribution default is
  wanted - not changed by Phase 14).
- Caddy: `/var/log/caddy/*.log`, already scoped by the Caddyfile's `access_log` snippet,
  which redacts `Authorization` and `X-Orszem-Report-Access` from the log entry itself
  (the proxied request to the backend is untouched) - see §64 verification below. Rotate
  with the distribution's `logrotate` (a `caddy` package normally installs a sane default;
  confirm one exists rather than assuming).
- Never retain a log that captured a raw secret. If one ever is found (should never
  happen, given the redaction rules above), treat it exactly like a committed secret: stop,
  do not print it, report path and remediation need.

## 13. Common failure cases

| Symptom | Likely cause | What to check |
|---|---|---|
| `orszem-backend` fails to start, exits fast | missing/wrong `ORSZEM_DB_*` | `./scripts/orszem-preflight.sh`, `journalctl -u orszem-backend -n 50` |
| starts, but `/actuator/health` never `UP` | DB unreachable, migration failure | `sudo -u postgres psql -c "\l"`, `journalctl` for a Flyway stack trace |
| `caddy validate` fails | syntax error / bad host | read its own error - it names the line |
| `caddy reload` succeeds but a route 404s that shouldn't | `block_internal_paths` or `handle` ordering | `./scripts/verify-caddy-routing.sh` locally against the same file |
| backup script fails with "only N MB free" | disk filling up | `df -h` on the backup output filesystem before retrying |
| restore script refuses with a checksum mismatch | archive corrupted/tampered/wrong file | re-copy the archive from its verified source; never override without understanding why |
| port 8081 or 5432 answers from `0.0.0.0` | a config drifted from loopback-only | re-run the exposure check in `SERVER_RUNBOOK.md` §5, fix `ORSZEM_BIND_ADDRESS` / `postgresql.conf`'s `listen_addresses` |

## 14. Preflight checklist (before any real production deployment)

See `docs/PHASE_14_ENGINEERING_REPORT.md` §AA for the full owner-gate table. In addition,
mechanically:

- [ ] current `main`/release SHA known and recorded;
- [ ] every CI workflow green on that exact SHA (5 workflow files - `backend`, `android`,
      `web`, `deploy-config`, `reference-data` - `deploy-config` alone now reports 2 check
      runs, `caddy` and `backup-restore-scripts`, so 6 check runs total);
- [ ] `./scripts/orszem-preflight.sh` passes against the real environment file;
- [ ] a fresh backup exists and `./scripts/orszem-backup-age-check.sh` confirms it;
- [ ] `caddy validate` passes against the real Caddyfile;
- [ ] the previous known-good backend jar is retained for application-only rollback;
- [ ] the restore procedure has been drilled recently (`orszem-restore-drill.sh` or a real
      restore rehearsal) - not merely read.

## 15. Post-deployment smoke checklist

- Public Web: homepage loads; the reference-data catalog (settlement search) responds; no
  CSP violation in the browser console.
- API: `curl -sI https://api.orszembejelento.hu/api/v1/meta` returns 200 over HTTPS.
- Service: a real (non-production, unless explicitly owner-approved) login, viewing the
  report queue, and logout all work end to end.
- Public: capability lookup for an existing report works. Submitting a **throwaway** test
  report against production requires explicit owner approval first (§50 of the brief) -
  never do this automatically.
- Admin: audit/analytics reachable to the correct role only.

## 16. Owner-gated actions

These are **never** performed by tooling or automatically - they require the owner acting
directly, or explicit owner approval immediately before execution:

- shutting down public/Service V1;
- deleting or replacing any production database;
- importing the V2 schema into a live production DB for the first time;
- changing production DNS;
- changing the public IP arrangement or deleting a VM;
- replacing production signing keys;
- destroying V1 data;
- rotating live credentials;
- enabling a new production deployment;
- activating any scheduled backup timer against real production data;
- enabling automated backup retention/deletion against real production data.

See `docs/PHASE_14_ENGINEERING_REPORT.md` §AA for the current status of each named gate.
