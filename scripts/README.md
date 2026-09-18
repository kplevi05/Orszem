# Scripts

Most of these are developer convenience only and are not required to build, test or
deploy — those are plain Gradle and npm commands, documented in the root `README.md`. The
Phase 14 operator scripts (backup, restore, preflight) are the exception: they are meant
to run on the production host itself, invoked from `docs/OPERATIONS_RUNBOOK.md`.

| Script | Purpose |
|---|---|
| `dev-db.sh` | start a throwaway PostgreSQL for local backend development |
| `orszem-admin` | maintenance CLI — administrator provisioning/recovery, reference-data import (see `docs/deployment/MAINTENANCE_CLI.md`) |
| `orszem-backup.sh` | production `pg_dump -Fc` backup, with checksum and metadata (see `docs/OPERATIONS_RUNBOOK.md` §5) |
| `orszem-restore.sh` | production `pg_restore` restore — explicit target only, never a default (see `docs/OPERATIONS_RUNBOOK.md` §6) |
| `orszem-preflight.sh` | checks an environment file has the required variables *present*, never prints values |
| `orszem-backup-age-check.sh` | "is there a backup newer than N hours?" — an operator/preflight check |
| `orszem-restore-drill.sh` | automated, repeatable backup→restore proof against throwaway Docker containers (also runs in CI — see `docs/PHASE_14_ENGINEERING_REPORT.md` §M) |
| `verify-caddy-routing.sh` | assert the public Caddy routing policy: only `/api/*` is exposed |
| `verify-caddy-header-redaction.sh` | assert `Authorization`/`X-Orszem-Report-Access` never reach the Caddy access log |
| `verify-caddy-csp.sh` | assert the Public Web CSP/Permissions-Policy headers on the real built bundle |

Actual production **deployment** (installing the artifact, first-time server setup) is
still **not** scripted from a developer machine — it is a documented procedure the owner
runs on the server; see `docs/deployment/SERVER_RUNBOOK.md`. Backup and restore, by
contrast, are scripted deliberately (Phase 14) because an ad-hoc, undocumented backup
procedure is exactly the kind of thing that quietly stops working.

Nothing in this directory may contain a credential, a hostname belonging to a live
environment, or anything that would fail if the owner's machine were set up differently.
`orszem-restore.sh` never defaults its target to anything - see its own header comment.
