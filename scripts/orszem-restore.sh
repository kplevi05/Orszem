#!/usr/bin/env bash
#
# Őrszem V2 — PostgreSQL restore from a pg_dump -Fc archive produced by
# scripts/orszem-backup.sh.
#
# This operation is inherently destructive to its target database, so unlike the backup
# script it never infers its target from ambient configuration (it does NOT read
# ORSZEM_DB_URL / ORSZEM_DB_USERNAME / ORSZEM_DB_PASSWORD - those belong to the running
# application, not to this script, precisely so a stray environment cannot make this
# script silently restore over whatever database the backend happens to be pointed at).
# Every connection parameter is a required, explicit flag.
#
# Usage:
#   ./scripts/orszem-restore.sh \
#     --dump <PATH> \
#     --target-environment <NAME> \
#     --host <HOST> --port <PORT> --database <DBNAME> --user <USER> \
#     --confirm-restore
#
# PGPASSWORD must be exported by the caller (never passed as an argument).
#
# --target-environment is free text the operator supplies (e.g. "throwaway-drill",
# "staging") - it has no effect on behaviour. It exists purely so the operator has to
# type out, in the same command, what they believe they are restoring into; there is no
# default value and no way to omit it.
#
# --confirm-restore is required. Without it, the script prints what it WOULD do and
# exits non-zero without touching the target - it never silently proceeds.
#
# Safety checks, in order, any of which aborts before anything is dropped or written:
#   1. --dump file exists and is non-empty.
#   2. Its companion .sha256 file (if present) matches. Missing companion checksum is
#      allowed only with --skip-checksum-verification, which prints a loud warning.
#   3. `pg_restore -l` can read the archive's table of contents (rejects a corrupted or
#      truncated dump before anything is touched).
#   4. All required flags, including --confirm-restore, are present.
set -euo pipefail

DUMP=""
TARGET_ENV=""
PG_HOST=""
PG_PORT=""
DB_NAME=""
PG_USER=""
CONFIRM=0
SKIP_CHECKSUM=0

usage() {
  cat <<'USAGE'
Usage: orszem-restore.sh --dump PATH --target-environment NAME \
         --host HOST --port PORT --database DBNAME --user USER \
         --confirm-restore [--skip-checksum-verification]

Every connection flag is required. Nothing defaults to the application's own
ORSZEM_DB_* configuration, on purpose - see this script's header comment.

Export PGPASSWORD before running. Never pass the password as an argument.

This REPLACES the contents of the target database. It is meant for a dedicated
restore/drill/disaster-recovery target, never for a database anything else is using.
USAGE
}

while [ $# -gt 0 ]; do
  case "$1" in
    --dump) DUMP="${2:-}"; shift 2 ;;
    --target-environment) TARGET_ENV="${2:-}"; shift 2 ;;
    --host) PG_HOST="${2:-}"; shift 2 ;;
    --port) PG_PORT="${2:-}"; shift 2 ;;
    --database) DB_NAME="${2:-}"; shift 2 ;;
    --user) PG_USER="${2:-}"; shift 2 ;;
    --confirm-restore) CONFIRM=1; shift ;;
    --skip-checksum-verification) SKIP_CHECKSUM=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; usage >&2; exit 2 ;;
  esac
done

MISSING=()
[ -n "$DUMP" ]       || MISSING+=("--dump")
[ -n "$TARGET_ENV" ] || MISSING+=("--target-environment")
[ -n "$PG_HOST" ]    || MISSING+=("--host")
[ -n "$PG_PORT" ]    || MISSING+=("--port")
[ -n "$DB_NAME" ]    || MISSING+=("--database")
[ -n "$PG_USER" ]    || MISSING+=("--user")
if [ "${#MISSING[@]}" -gt 0 ]; then
  echo "ERROR: missing required argument(s): ${MISSING[*]}" >&2
  usage >&2
  exit 2
fi

if [ -z "${PGPASSWORD:-}" ]; then
  echo "ERROR: PGPASSWORD is not exported." >&2
  exit 1
fi

echo "Restore target:"
echo "  environment  ${TARGET_ENV}"
echo "  host:port    ${PG_HOST}:${PG_PORT}"
echo "  database     ${DB_NAME}"
echo "  user         ${PG_USER}"
echo "  dump         ${DUMP}"
echo "(password not shown)"
echo
echo "This will DROP AND RECREATE every object pg_restore --clean touches inside"
echo "database '${DB_NAME}' on ${PG_HOST}:${PG_PORT}. Existing content in that database"
echo "will be replaced by the archive's content."

# --- 1. dump file present and non-empty --------------------------------------------------
if [ ! -f "$DUMP" ]; then
  echo "ERROR: dump file '$DUMP' does not exist." >&2
  exit 1
fi
if [ ! -s "$DUMP" ]; then
  echo "ERROR: dump file '$DUMP' is empty." >&2
  exit 1
fi

# --- 2. checksum ---------------------------------------------------------------------------
CHECKSUM_FILE="${DUMP}.sha256"
if [ -f "$CHECKSUM_FILE" ]; then
  echo "Verifying checksum against ${CHECKSUM_FILE} ..."
  if ! (cd "$(dirname "$DUMP")" && sha256sum -c "$(basename "$CHECKSUM_FILE")") >/dev/null 2>&1; then
    echo "ERROR: checksum mismatch. The archive may be corrupt or tampered with. Refusing to restore." >&2
    exit 1
  fi
  echo "Checksum OK."
elif [ "$SKIP_CHECKSUM" -eq 1 ]; then
  echo "WARNING: no companion .sha256 file found; proceeding anyway because" >&2
  echo "         --skip-checksum-verification was passed explicitly." >&2
else
  echo "ERROR: no companion checksum file '$CHECKSUM_FILE' found." >&2
  echo "Pass --skip-checksum-verification only if you have verified the archive's" >&2
  echo "provenance some other way." >&2
  exit 1
fi

# --- 3. archive integrity: reject a corrupted/truncated dump before touching the target ----
echo "Checking archive integrity with pg_restore -l ..."
if ! pg_restore -l "$DUMP" >/dev/null 2>&1; then
  echo "ERROR: pg_restore -l could not read '$DUMP'. It looks corrupted or truncated." >&2
  echo "Refusing to restore. The target database was not touched." >&2
  exit 1
fi
echo "Archive integrity OK."

# --- 4. explicit confirmation ---------------------------------------------------------------
if [ "$CONFIRM" -ne 1 ]; then
  echo
  echo "Dry run only: --confirm-restore was not passed, so nothing was restored."
  echo "Re-run with --confirm-restore once you are certain of the target above."
  exit 1
fi

echo
echo "Restoring ..."
if ! pg_restore \
      --host="$PG_HOST" --port="$PG_PORT" \
      --username="$PG_USER" --no-password \
      --dbname="$DB_NAME" \
      --clean --if-exists --no-owner --no-privileges \
      "$DUMP"; then
  echo "ERROR: pg_restore reported a failure. Inspect its output above; the target" >&2
  echo "       database may now be in a partially-restored state and should not be" >&2
  echo "       trusted until re-restored from a known-good archive." >&2
  exit 1
fi

echo
echo "OK: restore completed into ${DB_NAME} on ${PG_HOST}:${PG_PORT}."
echo "This script performed no session revocation and no data mutation beyond restoring"
echo "the archive's own content - see docs/OPERATIONS_RUNBOOK.md 'Session restore"
echo "semantics' for the documented, separate, manual procedure if live sessions from the"
echo "backup point must be invalidated."
