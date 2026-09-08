#!/usr/bin/env bash
#
# Verifies deploy/caddy/Caddyfile's Content-Security-Policy and Permissions-Policy headers
# for the Public Web origin (orszembejelento.hu) - Phase 5 brief §37-38, §68.
#
# This is a real end-to-end check, not a text search: it serves the ACTUAL built Public Web
# bundle (web/public-web/dist - built separately, by `npm run build`, before this script
# runs) through the real, adapted Caddyfile, and asserts the response headers on the real
# served index page. It does not build the bundle itself, so a stale dist/ would be caught
# by CI running `npm run build` immediately before this step, same ordering the `web`
# workflow already uses for its own checks.
#
# A live-browser check (loading the served page in a real browser and confirming no CSP
# violation appears in the console, including after navigating into the report flow) was
# additionally performed manually for this phase and is not scripted here - browser
# automation is outside what a bash+curl script can do; see PHASE_5_ENGINEERING_REPORT.md.
#
# Requires: caddy, node, curl. Companion to scripts/verify-caddy-routing.sh and
# scripts/verify-caddy-header-redaction.sh, which cover different concerns with their own
# harnesses.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CADDYFILE="$ROOT/deploy/caddy/Caddyfile"
WEB_DIST="$ROOT/web/public-web/dist"
WORK="$(mktemp -d)"
EDGE_PORT="${EDGE_PORT:-9083}"
UPSTREAM_PORT=8081   # must match the reverse_proxy target in the Caddyfile

CADDY_PID=""
STUB_PID=""
ADMIN_PORT=""
cleanup() {
  [ -n "$CADDY_PID" ] && kill "$CADDY_PID" 2>/dev/null || true
  [ -n "$STUB_PID" ] && kill "$STUB_PID" 2>/dev/null || true
  rm -rf "$WORK"
}
trap cleanup EXIT

for tool in caddy node curl; do
  command -v "$tool" >/dev/null 2>&1 || { echo "missing required tool: $tool" >&2; exit 1; }
done

if [ ! -f "$WEB_DIST/index.html" ]; then
  echo "missing $WEB_DIST/index.html - run 'npm run build' in web/public-web first" >&2
  exit 1
fi

cat > "$WORK/stub.js" <<'JS'
const http = require('http');
http.createServer((req, res) => { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end('{}'); })
  .listen(process.argv[2], '127.0.0.1');
JS

node "$WORK/stub.js" "$UPSTREAM_PORT" &
STUB_PID=$!

ADMIN_PORT="$((EDGE_PORT + 10000))"
caddy adapt --config "$CADDYFILE" --adapter caddyfile > "$WORK/config.json"

WEB_ROOT="$WEB_DIST" LISTEN_PORT="$EDGE_PORT" ADMIN_PORT="$ADMIN_PORT" node - "$WORK/config.json" <<'JS'
const fs = require('fs');
const path = process.argv[2];
let raw = fs.readFileSync(path, 'utf8');
raw = raw.split('/home/opc/apps/orszem-v2/web').join(process.env.WEB_ROOT);
const cfg = JSON.parse(raw);

cfg.admin = { listen: '127.0.0.1:' + process.env.ADMIN_PORT };

for (const srv of Object.values(cfg.apps.http.servers)) {
  srv.listen = [':' + process.env.LISTEN_PORT];
  srv.automatic_https = { disable: true, disable_redirects: true };
  delete srv.tls_connection_policies;
}
delete cfg.apps.tls;

for (const log of Object.values(cfg.logging?.logs ?? {})) {
  if (log.writer) log.writer = { output: 'stdout' };
}

fs.writeFileSync(path, JSON.stringify(cfg));
JS

caddy run --config "$WORK/config.json" > "$WORK/caddy.log" 2>&1 &
CADDY_PID=$!

ready=0
for _ in $(seq 1 100); do
  if curl -sf -o /dev/null -H 'Host: orszembejelento.hu' "http://127.0.0.1:$EDGE_PORT/"; then
    ready=1
    break
  fi
  sleep 0.2
done
if [ "$ready" -ne 1 ]; then
  echo "Caddy did not become ready. Log follows:" >&2
  cat "$WORK/caddy.log" >&2
  exit 1
fi

fail=0
response_headers="$(curl -s -D - -o "$WORK/body.html" -H 'Host: orszembejelento.hu' "http://127.0.0.1:$EDGE_PORT/")"

echo "== response headers on the served index page =="

csp_line="$(printf '%s' "$response_headers" | grep -i '^content-security-policy:' || true)"
if [ -z "$csp_line" ]; then
  echo "FAIL  no Content-Security-Policy header present"
  fail=1
else
  echo "ok    Content-Security-Policy present: $csp_line"
  for directive in "default-src 'self'" "script-src 'self'" "style-src 'self'" "connect-src 'self'" "object-src 'none'" "frame-ancestors 'none'"; do
    if printf '%s' "$csp_line" | grep -qF "$directive"; then
      echo "ok    CSP contains: $directive"
    else
      echo "FAIL  CSP is missing expected directive: $directive"
      fail=1
    fi
  done
  if printf '%s' "$csp_line" | grep -qi "unsafe-eval"; then
    echo "FAIL  CSP must not contain unsafe-eval"
    fail=1
  else
    echo "ok    CSP does not contain unsafe-eval"
  fi
fi

pp_line="$(printf '%s' "$response_headers" | grep -i '^permissions-policy:' || true)"
if [ -z "$pp_line" ]; then
  echo "FAIL  no Permissions-Policy header present"
  fail=1
else
  echo "ok    Permissions-Policy present: $pp_line"
  if printf '%s' "$pp_line" | grep -q "geolocation=()"; then
    echo "ok    Permissions-Policy denies geolocation"
  else
    echo "FAIL  Permissions-Policy does not deny geolocation"
    fail=1
  fi
fi

echo
echo "== the served page is the real built bundle, not an empty stub =="
if grep -q '<div id="root">' "$WORK/body.html" && grep -qo '/assets/index-[A-Za-z0-9_-]*\.js' "$WORK/body.html"; then
  echo "ok    served index.html references the built JS bundle"
else
  echo "FAIL  served index.html does not look like the real build output"
  fail=1
fi

echo
if [ "$fail" -eq 0 ]; then
  echo "Caddy CSP / Permissions-Policy verified."
else
  echo "Caddy CSP / Permissions-Policy check FAILED - see above." >&2
fi
exit "$fail"
