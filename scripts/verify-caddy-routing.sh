#!/usr/bin/env bash
#
# Verifies the PUBLIC routing policy of deploy/caddy/Caddyfile.
#
# The policy under test: the only thing reachable through the public edge is the
# intended /api/* surface. Spring Boot's management endpoints, the generated OpenAPI
# document and Swagger UI must never be publicly routed, on any host.
#
# This is a real end-to-end check, not a text search. It adapts the actual Caddyfile and
# patches only environment concerns - the listen address, TLS, the log destination and the
# static root - so no certificate, DNS or root privileges are needed. Routing and host
# matching come from the real file. It then runs Caddy against two stand-ins: an upstream
# that reports whether it was reached at all, and a stub SPA index so that "blocked by
# policy" is distinguishable from "static file simply missing".
#
# Requires: caddy, node, curl.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CADDYFILE="$ROOT/deploy/caddy/Caddyfile"
WORK="$(mktemp -d)"
EDGE_PORT="${EDGE_PORT:-9080}"
UPSTREAM_PORT=8081   # must match the reverse_proxy target in the Caddyfile

cleanup() {
  [ -n "${CADDY_PID:-}" ] && kill "$CADDY_PID" 2>/dev/null || true
  [ -n "${STUB_PID:-}" ] && kill "$STUB_PID" 2>/dev/null || true
  rm -rf "$WORK"
}
trap cleanup EXIT

for tool in caddy node curl; do
  command -v "$tool" >/dev/null 2>&1 || { echo "missing required tool: $tool" >&2; exit 1; }
done

# A stub standing in for Spring Boot. Any request that reaches it is, by definition,
# publicly routed to the backend - which is exactly what must not happen for the
# internal paths.
cat > "$WORK/stub.js" <<'JS'
const http = require('http');
http.createServer((req, res) => {
  res.writeHead(200, { 'Content-Type': 'text/plain' });
  res.end('UPSTREAM_REACHED ' + req.url);
}).listen(process.argv[2], '127.0.0.1');
JS
node "$WORK/stub.js" "$UPSTREAM_PORT" &
STUB_PID=$!

# Adapt the real Caddyfile, then patch ONLY environment concerns: the listen address,
# automatic HTTPS, and the log destination. Routing and host matching - the thing under
# test - come from the real file untouched.
caddy adapt --config "$CADDYFILE" --adapter caddyfile > "$WORK/config.json"

# A stand-in for the deployed Public Web build. Without it, the apex's file_server would
# 404 simply because the root is missing, and "blocked by policy" would be
# indistinguishable from "file not found" - the assertions below would pass even with the
# policy removed. With a real index.html present, try_files would happily serve 200 for
# /actuator/health unless the deny rules genuinely take precedence.
mkdir -p "$WORK/web"
echo '<!doctype html><title>stub spa</title>STUB_SPA_INDEX' > "$WORK/web/index.html"

WEB_ROOT="$WORK/web" node - "$WORK/config.json" <<'JS'
const fs = require('fs');
const path = process.argv[2];
let raw = fs.readFileSync(path, 'utf8');
// Point the static root at the stub SPA (an environment concern, like the listen address).
raw = raw.split('/home/opc/apps/orszem-v2/web').join(process.env.WEB_ROOT);
const cfg = JSON.parse(raw);

for (const srv of Object.values(cfg.apps.http.servers)) {
  srv.listen = [':' + (process.env.EDGE_PORT || '9080')];
  srv.automatic_https = { disable: true, disable_redirects: true };
  delete srv.tls_connection_policies;
}
delete cfg.apps.tls;

// The real config logs to /var/log/caddy, which needs root. Logging is not what this
// script tests, so send it to stdout and keep the script runnable as a normal user.
for (const log of Object.values(cfg.logging?.logs ?? {})) {
  if (log.writer) log.writer = { output: 'stdout' };
}

fs.writeFileSync(path, JSON.stringify(cfg));
JS

caddy run --config "$WORK/config.json" > "$WORK/caddy.log" 2>&1 &
CADDY_PID=$!

ready=0
for _ in $(seq 1 100); do
  if curl -sf -o /dev/null -H 'Host: orszembejelento.hu' "http://127.0.0.1:$EDGE_PORT/api/v1/meta"; then
    ready=1
    break
  fi
  sleep 0.2
done
if [ "$ready" -ne 1 ]; then
  echo "Caddy did not become ready on port $EDGE_PORT. Log follows:" >&2
  cat "$WORK/caddy.log" >&2
  exit 1
fi

fail=0

# assert <host> <path> <expected-status> <reached|blocked> <description>
assert() {
  local host="$1" path="$2" want_status="$3" want_reach="$4" desc="$5"
  local body status reach
  body="$(curl -s -H "Host: $host" -o "$WORK/body" -w '%{http_code}' "http://127.0.0.1:$EDGE_PORT$path")"
  status="$body"
  if grep -q 'UPSTREAM_REACHED' "$WORK/body" 2>/dev/null; then reach=reached; else reach=blocked; fi

  if [ "$status" = "$want_status" ] && [ "$reach" = "$want_reach" ]; then
    printf 'ok    %-26s %-24s -> %s, %s\n' "$host" "$path" "$status" "$reach"
  else
    printf 'FAIL  %-26s %-24s -> %s, %s (expected %s, %s)  [%s]\n' \
      "$host" "$path" "$status" "$reach" "$want_status" "$want_reach" "$desc"
    fail=1
  fi
}

echo "== intended API surface reaches the backend =="
assert api.orszembejelento.hu /api/v1/meta        200 reached "API host must proxy /api/*"
assert orszembejelento.hu     /api/v1/meta        200 reached "apex must proxy /api/* same-origin"

echo
echo "== internal Spring endpoints must never be publicly routed =="
for host in api.orszembejelento.hu orszembejelento.hu; do
  assert "$host" /actuator            404 blocked "actuator root"
  assert "$host" /actuator/health     404 blocked "health must not be public"
  assert "$host" /actuator/env        404 blocked "env must not be public"
  assert "$host" /v3/api-docs         404 blocked "OpenAPI document must not be public"
  assert "$host" /v3/api-docs/swagger-config 404 blocked "springdoc config must not be public"
  assert "$host" /swagger-ui.html     404 blocked "Swagger UI must not be public"
  assert "$host" /swagger-ui/index.html 404 blocked "Swagger UI assets must not be public"
  assert "$host" /webjars/x.js        404 blocked "Swagger UI webjars must not be public"
done

echo
echo "== apex still serves the Public Web itself =="
# The deny rules must not break the site itself: the SPA and its client-side routes
# still have to be served.
assert_spa() {
  local path="$1" status
  status="$(curl -s -H 'Host: orszembejelento.hu' -o "$WORK/body" -w '%{http_code}' "http://127.0.0.1:$EDGE_PORT$path")"
  if [ "$status" = "200" ] && grep -q STUB_SPA_INDEX "$WORK/body"; then
    printf 'ok    %-26s %-24s -> 200, SPA index\n' orszembejelento.hu "$path"
  else
    printf 'FAIL  %-26s %-24s -> %s, not the SPA index\n' orszembejelento.hu "$path" "$status"
    fail=1
  fi
}
assert_spa /
assert_spa /valahol/mely/utvonal

echo
echo "== API host serves nothing but the API =="
assert api.orszembejelento.hu /                   404 blocked "no site root on the API host"
assert api.orszembejelento.hu /index.html         404 blocked "no static content on the API host"

echo
echo "== www redirects to the apex =="
code="$(curl -s -o /dev/null -w '%{http_code}' -H 'Host: www.orszembejelento.hu' "http://127.0.0.1:$EDGE_PORT/anything")"
loc="$(curl -s -o /dev/null -w '%{redirect_url}' -H 'Host: www.orszembejelento.hu' "http://127.0.0.1:$EDGE_PORT/anything")"
if [ "$code" = "301" ] && [ "$loc" = "https://orszembejelento.hu/anything" ]; then
  printf 'ok    %-26s %-24s -> %s %s\n' www.orszembejelento.hu /anything "$code" "$loc"
else
  printf 'FAIL  www redirect -> %s %s (expected 301 https://orszembejelento.hu/anything)\n' "$code" "$loc"
  fail=1
fi

echo
if [ "$fail" -eq 0 ]; then
  echo "Caddy public routing policy verified."
else
  echo "Caddy public routing policy VIOLATED - see failures above." >&2
fi
exit "$fail"
