#!/usr/bin/env bash
#
# Verifies the PUBLIC routing policy of deploy/caddy/Caddyfile.
#
# The policy under test: the only thing reachable through the public edge is the
# intended /api/* surface. Spring Boot's management endpoints, the generated OpenAPI
# document and Swagger UI must never be publicly routed, on any host.
#
# This is a real end-to-end check, not a text search. It adapts the actual Caddyfile,
# patches only the listen address and TLS (so no real certificate or DNS is needed),
# runs Caddy, and asserts what each route does against a stub upstream that reports
# whether it was reached at all.
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

# Adapt the real Caddyfile, then patch ONLY transport concerns: listen on a local port
# and disable automatic HTTPS. Routing and host matching come from the real file.
caddy adapt --config "$CADDYFILE" --adapter caddyfile > "$WORK/config.json" 2>/dev/null

node - "$WORK/config.json" <<'JS'
const fs = require('fs');
const path = process.argv[2];
const cfg = JSON.parse(fs.readFileSync(path, 'utf8'));
for (const srv of Object.values(cfg.apps.http.servers)) {
  srv.listen = [':' + (process.env.EDGE_PORT || '9080')];
  srv.automatic_https = { disable: true, disable_redirects: true };
  delete srv.tls_connection_policies;
}
delete cfg.apps.tls;
fs.writeFileSync(path, JSON.stringify(cfg));
JS

caddy run --config "$WORK/config.json" > "$WORK/caddy.log" 2>&1 &
CADDY_PID=$!

for _ in $(seq 1 50); do
  curl -s -o /dev/null -H 'Host: orszembejelento.hu' "http://127.0.0.1:$EDGE_PORT/" && break
  sleep 0.2
done

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
