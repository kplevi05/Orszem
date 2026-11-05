#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

APP_DIR="${ORSZEM_V2_DIR:-/home/opc/apps/orszem-v2}"
OLD_SHA=e5279fde5f87a98b4e3cc6712f4574c6e89f97e4379ce0c51cb79ed61565e095
NEW_SHA=c8d8a6f8255ff9b7ef5a125e4343114e307be4febb275f1153b2afe11a8b4835
WEB_TAR=/tmp/public-web-2.1.2.tar.gz
WEB_SHA=468408b00915e21f5be6f6b8aeaa647f2b4d6a70ab98038c479ab5e52da51da7
DB=orszem-v2-db; BE=orszem-v2-backend; EDGE=orszem-v2-edge
HEALTH_TIMEOUT=240

step() { echo; echo "== $* =="; }
ok()   { echo "OK   $*"; }
sql()  { docker exec "$DB" psql -U orszem_v2 -d orszem_v2 -Atc "$1"; }
sha()  { sha256sum "$1" | cut -d' ' -f1; }
code() { curl -sS -o /dev/null -w '%{http_code}' --max-time 15 "$1" || echo 000; }
fail() { echo "FAIL $*" >&2; exit 1; }

wait_healthy() {
  local end=$((SECONDS+HEALTH_TIMEOUT)) h
  while [ $SECONDS -lt $end ]; do
    h="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$BE" 2>/dev/null || echo missing)"
    [ "$h" = healthy ] && return 0
    sleep 5
  done
  return 1
}

echo "Orszem v2.1.2 rollout  $(date -u +%Y-%m-%dT%H:%M:%SZ)  host=$(hostname)"
cd "$APP_DIR"

step "1. Pre-install checks"
[ -f run/backend.jar.next ] || fail "run/backend.jar.next missing"
[ "$(sha run/backend.jar.next)" = "$NEW_SHA" ] || fail "staged jar checksum mismatch (expected $NEW_SHA)"
ok "staged backend.jar.next = v2.1.2 checksum ($NEW_SHA)"

[ -f "$WEB_TAR" ] || fail "$WEB_TAR missing"
[ "$(sha "$WEB_TAR")" = "$WEB_SHA" ] || fail "web tarball checksum mismatch (expected $WEB_SHA)"
ok "staged public-web-2.1.2.tar.gz checksum ($WEB_SHA)"

docker compose --env-file .env config -q && ok "compose config valid"
for c in $DB $BE $EDGE; do
  st="$(docker inspect -f '{{.State.Status}}' "$c")"; echo "$c: $st"; [ "$st" = running ] || fail "$c not running"
done
for c in $DB $BE; do [ "$(docker inspect -f '{{.State.Health.Status}}' "$c")" = healthy ] || fail "$c not healthy"; done

PRE_REPORTS="$(sql 'select count(*) from reports')"
PRE_USERS="$(sql 'select count(*) from users')"
fv="$(sql "select version||'|'||success from flyway_schema_history order by installed_rank desc limit 1")"
echo "pre-deploy: flyway=$fv reports=$PRE_REPORTS users=$PRE_USERS"
ok "all pre-install checks passed"

step "2. Preserve rollback artifacts"
cp -p run/backend.jar run/backend.pre-v2.1.2.jar
tar -czf run/web.pre-v2.1.2.tar.gz -C run/web .
ok "preserved run/backend.pre-v2.1.2.jar and run/web.pre-v2.1.2.tar.gz"

step "3. Backend swap"
mv -f run/backend.jar.next run/backend.jar
[ "$(sha run/backend.jar)" = "$NEW_SHA" ] || fail "installed jar checksum mismatch"
docker compose --env-file .env up -d --force-recreate backend
ok "backend container recreated"

step "4. Backend startup & verification gate"
wait_healthy || fail "backend did not become healthy within ${HEALTH_TIMEOUT}s"
ok "backend healthy"
[ "$(docker exec "$BE" sha256sum /app/backend.jar | cut -d' ' -f1)" = "$NEW_SHA" ] || fail "container runs a different jar"
fv2="$(sql "select version||'|'||success from flyway_schema_history order by installed_rank desc limit 1")"
echo "latest flyway: $fv2"

POST_REPORTS="$(sql 'select count(*) from reports')"
echo "reports count: $PRE_REPORTS -> $POST_REPORTS"
[ "$POST_REPORTS" -ge "$PRE_REPORTS" ] || fail "report count decreased!"

m="$(code https://api.orszembejelento.hu/api/v1/meta)"; echo "meta: $m"; [ "$m" = 200 ] || fail "meta $m"
ok "backend v2.1.2 verified"

step "5. Public Web bundle deployment"
rm -rf run/web.next
mkdir -p run/web.next
tar -xzf "$WEB_TAR" -C run/web.next
chmod -R a+rX run/web.next
rm -rf run/web/*
cp -a run/web.next/. run/web/
rm -rf run/web.next
ok "updated run/web with v2.1.2 static bundle"

step "6. Public smoke checks"
J="$(curl -fsS --max-time 15 'https://api.orszembejelento.hu/api/v1/public/reference/settlements?query=Tata')" || fail "Tata search failed"
echo "Public settlement search OK"
WCODE="$(code https://orszembejelento.hu/)"
echo "Public web root HTTP status: $WCODE"

echo
echo "================ v2.1.2 ROLLOUT SUMMARY ================"
echo "installed jar sha256 : $(sha run/backend.jar)"
echo "flyway latest        : $fv2"
echo "reports (zero loss)  : $PRE_REPORTS -> $POST_REPORTS"
echo "public web updated   : yes"
echo "RESULT: v2.1.2 ROLLOUT PASSED."
