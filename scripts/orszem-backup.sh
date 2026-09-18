#!/usr/bin/env bash
#
# Őrszem V2 — PostgreSQL logical backup.
#
# Produces one pg_dump -Fc (custom format) archive plus two small, non-secret companion
# files: a SHA-256 checksum and a metadata summary. Custom format is used deliberately
# (not plain SQL) because it is compressed and lets pg_restore inspect or selectively
# restore an archive without touching the database - see docs/OPERATIONS_RUNBOOK.md.
#
# This script never embeds the database password on the command line (it would be
# visible to every other process via `ps`); it reads the same environment the backend
# service itself uses.
#
# Usage:
#   ./scripts/orszem-backup.sh <OUTPUT_DIR> [--min-free-mb N] [--release-sha SHA]
#
# Required environment (the same variables the systemd service reads from
# /etc/orszem/backend.env - see deploy/env/backend.env.example):
#   ORSZEM_DB_URL       jdbc:postgresql://host:port/dbname
#   ORSZEM_DB_USERNAME
#   ORSZEM_DB_PASSWORD
#
# Exit codes: 0 success. Any failure exits non-zero with a message on stderr and no
# archive is left half-written (pg_dump's own output goes to a temporary name first).
set -euo pipefail

# --- argument parsing ----------------------------------------------------------------
OUTPUT_DIR=""
MIN_FREE_MB=256
RELEASE_SHA="${ORSZEM_RELEASE_SHA:-}"

usage() {
  cat <<'USAGE'
Usage: orszem-backup.sh <OUTPUT_DIR> [--min-free-mb N] [--release-sha SHA]

  OUTPUT_DIR        Directory the backup archive and its companion files are written
                     to. Must already exist and be writable. Not created automatically.
  --min-free-mb N   Conservative sanity check only (default 256): refuse to start if the
                     output filesystem reports less free space than this. This is NOT a
                     production capacity guarantee - it only stops the obvious failure
                     mode of writing a truncated dump onto an already-full disk.
  --release-sha SHA  Git commit SHA of the running application, recorded in the metadata
                     file. Defaults to $ORSZEM_RELEASE_SHA if set; otherwise "unknown".

Required environment: ORSZEM_DB_URL, ORSZEM_DB_USERNAME, ORSZEM_DB_PASSWORD
(the same variables /etc/orszem/backend.env provides to the backend service).
USAGE
}

if [ $# -lt 1 ]; then
  usage >&2
  exit 2
fi

OUTPUT_DIR="$1"
shift

while [ $# -gt 0 ]; do
  case "$1" in
    --min-free-mb)
      [ $# -ge 2 ] || { echo "ERROR: --min-free-mb requires a value" >&2; exit 2; }
      MIN_FREE_MB="$2"
      shift 2
      ;;
    --release-sha)
      [ $# -ge 2 ] || { echo "ERROR: --release-sha requires a value" >&2; exit 2; }
      RELEASE_SHA="$2"
      shift 2
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      echo "ERROR: unknown argument '$1'" >&2
      usage >&2
      exit 2
      ;;
  esac
done

RELEASE_SHA="${RELEASE_SHA:-unknown}"

# --- required configuration ------------------------------------------------------------
for required in ORSZEM_DB_URL ORSZEM_DB_USERNAME ORSZEM_DB_PASSWORD; do
  if [ -z "${!required:-}" ]; then
    echo "ERROR: $required is not set." >&2
    echo "On the server:  set -a; . /etc/orszem/backend.env; set +a" >&2
    exit 1
  fi
done

if [ ! -d "$OUTPUT_DIR" ]; then
  echo "ERROR: output directory '$OUTPUT_DIR' does not exist." >&2
  exit 1
fi
if [ ! -w "$OUTPUT_DIR" ]; then
  echo "ERROR: output directory '$OUTPUT_DIR' is not writable." >&2
  exit 1
fi

# --- parse the JDBC URL into libpq connection parameters --------------------------------
# ORSZEM_DB_URL looks like jdbc:postgresql://host:port/dbname[?params]. pg_dump/pg_restore
# use libpq, which does not understand the jdbc: scheme, so this is translated once, here,
# rather than duplicated in every script that needs the database.
JDBC_BODY="${ORSZEM_DB_URL#jdbc:postgresql://}"
if [ "$JDBC_BODY" = "$ORSZEM_DB_URL" ]; then
  echo "ERROR: ORSZEM_DB_URL does not look like jdbc:postgresql://host:port/dbname" >&2
  exit 1
fi
HOST_PORT="${JDBC_BODY%%/*}"
DB_AND_PARAMS="${JDBC_BODY#*/}"
DB_NAME="${DB_AND_PARAMS%%\?*}"
PG_HOST="${HOST_PORT%%:*}"
PG_PORT="${HOST_PORT#*:}"
if [ "$PG_PORT" = "$HOST_PORT" ]; then
  PG_PORT=5432
fi
if [ -z "$PG_HOST" ] || [ -z "$DB_NAME" ]; then
  echo "ERROR: could not parse host/database out of ORSZEM_DB_URL" >&2
  exit 1
fi

export PGPASSWORD="$ORSZEM_DB_PASSWORD"

echo "Backing up:"
echo "  host:port  ${PG_HOST}:${PG_PORT}"
echo "  database   ${DB_NAME}"
echo "  user       ${ORSZEM_DB_USERNAME}"
echo "(password not shown)"

# --- disk space sanity check -----------------------------------------------------------
FREE_MB="$(df -Pk "$OUTPUT_DIR" | awk 'NR==2 { print int($4/1024) }')"
if [ "$FREE_MB" -lt "$MIN_FREE_MB" ]; then
  echo "ERROR: only ${FREE_MB} MB free in '$OUTPUT_DIR', need at least ${MIN_FREE_MB} MB." >&2
  echo "Refusing to start a dump that would likely fail onto a full disk." >&2
  exit 1
fi

# --- filenames --------------------------------------------------------------------------
TIMESTAMP="$(date -u +%Y-%m-%dT%H%M%SZ)"
BASENAME="orszem-v2-${TIMESTAMP}"
DUMP_FILE="${OUTPUT_DIR%/}/${BASENAME}.dump"
CHECKSUM_FILE="${DUMP_FILE}.sha256"
METADATA_FILE="${OUTPUT_DIR%/}/${BASENAME}.metadata.txt"
TMP_FILE="${DUMP_FILE}.partial"

# Never silently overwrite an existing backup - the timestamp is second-resolution, so
# this only ever fires on a genuine collision (two runs in the same second, or a stale
# leftover), and either way overwriting silently would be the wrong default.
if [ -e "$DUMP_FILE" ] || [ -e "$CHECKSUM_FILE" ] || [ -e "$METADATA_FILE" ]; then
  echo "ERROR: '$DUMP_FILE' (or a companion file) already exists. Not overwriting." >&2
  exit 1
fi

cleanup_partial() {
  rm -f "$TMP_FILE"
}
trap cleanup_partial EXIT

# --- the actual backup -------------------------------------------------------------------
if ! pg_dump -Fc \
      --host="$PG_HOST" --port="$PG_PORT" \
      --username="$ORSZEM_DB_USERNAME" --no-password \
      --dbname="$DB_NAME" \
      --file="$TMP_FILE"; then
  echo "ERROR: pg_dump failed. No archive written." >&2
  exit 1
fi

# --- verify the archive before it is considered a real backup ----------------------------
if [ ! -s "$TMP_FILE" ]; then
  echo "ERROR: pg_dump produced an empty file. Refusing to keep it." >&2
  exit 1
fi

if ! pg_restore -l "$TMP_FILE" >/dev/null 2>&1; then
  echo "ERROR: pg_restore -l could not read the archive just produced. Refusing to keep it." >&2
  exit 1
fi

mv "$TMP_FILE" "$DUMP_FILE"
trap - EXIT

SIZE_BYTES="$(wc -c < "$DUMP_FILE" | tr -d ' ')"
CHECKSUM="$(sha256sum "$DUMP_FILE" | awk '{print $1}')"
echo "${CHECKSUM}  $(basename "$DUMP_FILE")" > "$CHECKSUM_FILE"

PG_SERVER_VERSION="$(pg_dump --version | awk '{print $NF}')"

cat > "$METADATA_FILE" <<METADATA
# Őrszem V2 backup metadata. No credentials. Safe to read by anyone who may see the
# filename; the .dump file itself is sensitive (see docs/OPERATIONS_RUNBOOK.md) and this
# file is not a substitute for protecting it.
created_utc=${TIMESTAMP}
source_host_port=${PG_HOST}:${PG_PORT}
source_database=${DB_NAME}
application_git_sha=${RELEASE_SHA}
pg_dump_client_version=${PG_SERVER_VERSION}
filename=$(basename "$DUMP_FILE")
size_bytes=${SIZE_BYTES}
sha256=${CHECKSUM}
METADATA

echo
echo "OK: backup created"
echo "  archive   $DUMP_FILE  (${SIZE_BYTES} bytes)"
echo "  checksum  $CHECKSUM_FILE"
echo "  metadata  $METADATA_FILE"
echo
echo "Reminder: this archive contains real application data and must not be committed,"
echo "uploaded as a CI artifact, or left only on this machine (see docs/OPERATIONS_RUNBOOK.md"
echo "'Backup destination')."
