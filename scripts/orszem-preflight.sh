#!/usr/bin/env bash
#
# Őrszem V2 — production configuration preflight.
#
# Checks that the environment file the systemd service will read looks complete enough
# to start, WITHOUT ever printing a secret value. A missing required variable is
# reported as its name only, e.g. "missing ORSZEM_DB_PASSWORD" - never
# "ORSZEM_DB_PASSWORD=" followed by whatever (possibly empty, possibly real) value it
# holds.
#
# This does not replace actually starting the backend: it catches the obvious "forgot to
# fill in the template" class of mistake before that, cheaply and non-destructively.
#
# Usage:
#   ./scripts/orszem-preflight.sh [ENV_FILE]
#   (defaults to /etc/orszem/backend.env)
#
# Exit codes: 0 = all required variables present and superficially well-formed.
#             1 = something is missing or malformed; details on stderr, no values.
set -euo pipefail

ENV_FILE="${1:-/etc/orszem/backend.env}"

if [ ! -f "$ENV_FILE" ]; then
  echo "ERROR: environment file '$ENV_FILE' does not exist." >&2
  exit 1
fi

# Loaded into a subshell-local set of variables only - never echoed, never exported
# beyond this process, which exits immediately after the checks below.
set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a

FAILURES=0

require_present() {
  local name="$1"
  if [ -z "${!name:-}" ]; then
    echo "missing $name" >&2
    FAILURES=$((FAILURES + 1))
  fi
}

# --- required for the backend to start at all -----------------------------------------
require_present ORSZEM_DB_URL
require_present ORSZEM_DB_USERNAME
require_present ORSZEM_DB_PASSWORD

# --- shape checks that do not require revealing the value -------------------------------
if [ -n "${ORSZEM_DB_URL:-}" ]; then
  case "$ORSZEM_DB_URL" in
    jdbc:postgresql://*) ;;
    *)
      echo "malformed ORSZEM_DB_URL (expected jdbc:postgresql://host:port/dbname)" >&2
      FAILURES=$((FAILURES + 1))
      ;;
  esac
fi

if [ -n "${ORSZEM_BIND_ADDRESS:-}" ] && [ "$ORSZEM_BIND_ADDRESS" != "127.0.0.1" ] && [ "$ORSZEM_BIND_ADDRESS" != "localhost" ]; then
  echo "WARNING: ORSZEM_BIND_ADDRESS is '$ORSZEM_BIND_ADDRESS', not loopback." >&2
  echo "         Caddy is meant to be the only public edge - see deploy/caddy/Caddyfile" >&2
  echo "         and docs/OPERATIONS_RUNBOOK.md 'Service port exposure'." >&2
fi

# --- optional connectivity probe, skipped gracefully if pg_isready is unavailable -------
if command -v pg_isready >/dev/null 2>&1 && [ -n "${ORSZEM_DB_URL:-}" ]; then
  JDBC_BODY="${ORSZEM_DB_URL#jdbc:postgresql://}"
  HOST_PORT="${JDBC_BODY%%/*}"
  PROBE_HOST="${HOST_PORT%%:*}"
  PROBE_PORT="${HOST_PORT#*:}"
  [ "$PROBE_PORT" = "$HOST_PORT" ] && PROBE_PORT=5432
  if pg_isready -h "$PROBE_HOST" -p "$PROBE_PORT" >/dev/null 2>&1; then
    echo "OK: PostgreSQL is accepting connections at ${PROBE_HOST}:${PROBE_PORT}."
  else
    echo "WARNING: PostgreSQL at ${PROBE_HOST}:${PROBE_PORT} did not respond to pg_isready." >&2
    echo "         The backend will fail loudly at startup if this is still true then -" >&2
    echo "         see docs/OPERATIONS_RUNBOOK.md 'Backend startup failure modes'." >&2
  fi
else
  echo "NOTE: pg_isready not available here; skipping the connectivity probe (this is only"
  echo "      an early sanity check - Flyway/Spring will still fail loudly on a genuinely"
  echo "      unreachable database at startup)."
fi

if [ "$FAILURES" -gt 0 ]; then
  echo >&2
  echo "PREFLIGHT FAILED: $FAILURES problem(s) above." >&2
  exit 1
fi

echo "PREFLIGHT OK: required configuration is present in $ENV_FILE."
