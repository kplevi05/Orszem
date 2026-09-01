#!/usr/bin/env bash
# Őrszem Demo v1 — deployment health verification.
# Exits non-zero if anything is not actually working (not merely "Up").
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

COMPOSE_FILE="infra/compose/docker-compose.prod.yml"
ENV_FILE="${ORSZEM_ENV_FILE:-$ROOT_DIR/.env}"
dc() { docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"; }

# shellcheck disable=SC1090
HOST="$(grep -E '^ORSZEM_PUBLIC_HOST=' "$ENV_FILE" | cut -d= -f2-)"
FAIL=0
ok()   { printf '  \033[32mOK\033[0m   %s\n' "$1"; }
bad()  { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAIL=1; }

echo "== containers"
dc ps

echo "== waiting for db + api health (max 5 min)"
for _ in $(seq 1 60); do
  DB="$(docker inspect -f '{{.State.Health.Status}}' "$(dc ps -q db)" 2>/dev/null || echo missing)"
  API="$(docker inspect -f '{{.State.Health.Status}}' "$(dc ps -q api)" 2>/dev/null || echo missing)"
  [ "$DB" = healthy ] && [ "$API" = healthy ] && break
  sleep 5
done
[ "${DB:-}" = healthy ] && ok "PostgreSQL healthy" || bad "PostgreSQL health = ${DB:-unknown}"
[ "${API:-}" = healthy ] && ok "API healthy"        || bad "API health = ${API:-unknown}"

echo "== Flyway"
# NOTE: capture first — `... | grep -q` would SIGPIPE the producer and, with
# `set -o pipefail`, report a false failure.
API_LOG="$(dc logs api 2>&1 || true)"
if printf '%s' "$API_LOG" | grep -qE "Successfully applied [0-9]+ migration|Successfully validated [0-9]+ migration|No migration necessary"; then
  ok "Flyway migration completed"
else
  bad "No Flyway success line in API logs"
fi

echo "== internal API readiness"
if dc exec -T api curl -fsS http://127.0.0.1:8080/actuator/health/readiness 2>/dev/null | grep -q '"status":"UP"'; then
  ok "API readiness UP"
else
  bad "API readiness probe did not report UP"
fi

echo "== reverse proxy -> API (inside the Docker network)"
if dc exec -T caddy wget -qO- "http://api:8080/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
  ok "Caddy can reach the API"
else
  bad "Caddy cannot reach the API"
fi

if [ -n "$HOST" ]; then
  echo "== public HTTPS ($HOST)"
  CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 "https://$HOST/actuator/health" || echo 000)"
  [ "$CODE" = 200 ] && ok "https://$HOST/actuator/health -> 200" || bad "https://$HOST/actuator/health -> $CODE"
  CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 "https://$HOST/api/v1/public/event-types" || echo 000)"
  [ "$CODE" = 200 ] && ok "event catalog reachable over HTTPS" || bad "event catalog -> $CODE"
fi

echo "== running image"
WANT_IMAGE="$(grep -E '^ORSZEM_API_IMAGE=' "$ENV_FILE" | cut -d= -f2-)"
WANT_ID="$(docker image inspect --format '{{.Id}}' "${WANT_IMAGE:-orszem-api:demo}" 2>/dev/null || true)"
RUN_ID="$(docker inspect --format '{{.Image}}' "$(dc ps -q api)" 2>/dev/null || true)"
if [ -n "$WANT_ID" ] && [ "$WANT_ID" = "$RUN_ID" ]; then
  ok "API container runs the current ${WANT_IMAGE:-orszem-api:demo} image"
else
  bad "API container runs a stale image (tag=$WANT_ID running=$RUN_ID)"
fi

echo "== port exposure"
PUBLISHED="$(dc ps --format '{{.Ports}}' || true)"
for p in 5432 8080; do
  if printf '%s' "$PUBLISHED" | grep -q ":$p->"; then
    bad "port $p is published by a container"
  else
    ok "port $p is not published"
  fi
done

echo "== API error log scan"
ERR_LINES="$(printf '%s' "$API_LOG" | grep -E '\bERROR\b' | tail -20 || true)"
if [ -n "$ERR_LINES" ]; then
  echo "  --- ERROR lines ---"
  printf '%s\n' "$ERR_LINES"
  bad "ERROR lines present in the API log"
else
  ok "no ERROR lines in the API log"
fi

exit "$FAIL"
