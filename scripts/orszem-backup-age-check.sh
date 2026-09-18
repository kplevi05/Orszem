#!/usr/bin/env bash
#
# Őrszem V2 — "is there a recent enough backup?" operator/preflight check.
#
# Looks at a backup directory (as produced by scripts/orszem-backup.sh) and fails if the
# newest *.dump archive is older than the given threshold, or if there is none at all.
# A scheduled backup that silently stopped running must not look identical to a healthy
# one - this gives an operator (or a preflight step) one cheap command to tell the
# difference.
#
# No production threshold is hard-wired here on purpose: --max-age-hours has no default
# and must be supplied, so this script never quietly enforces a policy nobody chose.
#
# Usage:
#   ./scripts/orszem-backup-age-check.sh --dir <BACKUP_DIR> --max-age-hours <N>
#
# Exit codes:
#   0  newest *.dump in <BACKUP_DIR> is newer than N hours old.
#   1  no *.dump file found, or the newest one is older than N hours.
#   2  usage error.
set -euo pipefail

DIR=""
MAX_AGE_HOURS=""

usage() {
  cat <<'USAGE'
Usage: orszem-backup-age-check.sh --dir BACKUP_DIR --max-age-hours N

Exits 0 only if BACKUP_DIR contains at least one *.dump file newer than N hours.
Prints the newest backup's age and filename either way. Never inspects file contents
and never prints anything from inside a backup.
USAGE
}

while [ $# -gt 0 ]; do
  case "$1" in
    --dir) DIR="${2:-}"; shift 2 ;;
    --max-age-hours) MAX_AGE_HOURS="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; usage >&2; exit 2 ;;
  esac
done

if [ -z "$DIR" ] || [ -z "$MAX_AGE_HOURS" ]; then
  echo "ERROR: both --dir and --max-age-hours are required." >&2
  usage >&2
  exit 2
fi

if [ ! -d "$DIR" ]; then
  echo "ERROR: '$DIR' is not a directory." >&2
  exit 1
fi

NEWEST="$(find "$DIR" -maxdepth 1 -type f -name '*.dump' -printf '%T@ %p\n' 2>/dev/null \
  | sort -rn | head -n1 || true)"

if [ -z "$NEWEST" ]; then
  echo "FAIL: no *.dump file found in '$DIR'." >&2
  exit 1
fi

NEWEST_EPOCH="${NEWEST%% *}"
NEWEST_PATH="${NEWEST#* }"
NOW_EPOCH="$(date +%s)"
AGE_HOURS="$(( (NOW_EPOCH - ${NEWEST_EPOCH%.*}) / 3600 ))"

echo "Newest backup: $NEWEST_PATH"
echo "Age: ${AGE_HOURS}h (limit: ${MAX_AGE_HOURS}h)"

if [ "$AGE_HOURS" -gt "$MAX_AGE_HOURS" ]; then
  echo "FAIL: newest backup is older than the ${MAX_AGE_HOURS}h limit." >&2
  exit 1
fi

echo "OK: newest backup is within the ${MAX_AGE_HOURS}h limit."
