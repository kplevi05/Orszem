#!/usr/bin/env bash
#
# Verifies that deploy/caddy/Caddyfile's access log never contains the value of
# X-Orszem-Report-Access, a live Public report credential (ADR 0008). Caddy's structured
# access log includes every request header by default unless a header is explicitly
# filtered out - X-Orszem-Report-Access is a custom header, so nothing protects it except
# this Caddyfile's own rule. (Authorization is also asserted absent below, but Caddy 2.11.4
# already redacts that one unconditionally as a built-in default, independent of anything
# configured here - confirmed by testing, not assumed. This Caddyfile's explicit rule for
# it is redundant defence-in-depth, not a fix for a prior gap.)
#
# This is a real end-to-end check, not a text search of the Caddyfile: it runs the actual
# adapted config against a real request carrying known sentinel header values, and inspects
# the log output Caddy itself produced. It also runs a NEGATIVE CONTROL for
# X-Orszem-Report-Access - the same request against a config with that redaction rule
# stripped - to prove the positive result is not a false pass (a broken or silently-no-op
# filter would make both runs look identical). No negative control is possible for
# Authorization, since Caddy protects it even with this Caddyfile's own rule removed.
#
# Requires: caddy, node, curl. Companion to scripts/verify-caddy-routing.sh, which covers
# public routing policy; logging content is a distinct concern with its own harness.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CADDYFILE="$ROOT/deploy/caddy/Caddyfile"
WORK="$(mktemp -d)"
EDGE_PORT="${EDGE_PORT:-9082}"
UPSTREAM_PORT=8081   # must match the reverse_proxy target in the Caddyfile

# Sentinel values distinctive enough that they cannot appear in a log entry by accident
# (timestamps, headers Caddy adds itself, etc).
REPORT_ACCESS_SENTINEL="pr_REDACTION-CANARY-QNYK1F6M2X8V0ZP3RT7GJ"
AUTHORIZATION_SENTINEL="Bearer REDACTION-CANARY-AT-9H3JQK6X0ZMV2GN7YB"

CADDY_PID=""
STUB_PID=""
cleanup() {
  [ -n "$CADDY_PID" ] && kill "$CADDY_PID" 2>/dev/null || true
  [ -n "$STUB_PID" ] && kill "$STUB_PID" 2>/dev/null || true
  rm -rf "$WORK"
}
trap cleanup EXIT

for tool in caddy node curl; do
  command -v "$tool" >/dev/null 2>&1 || { echo "missing required tool: $tool" >&2; exit 1; }
done

cat > "$WORK/stub.js" <<'JS'
const http = require('http');
http.createServer((req, res) => { res.writeHead(200, { 'Content-Type': 'text/plain' }); res.end('OK'); })
  .listen(process.argv[2], '127.0.0.1');
JS

mkdir -p "$WORK/web"
echo '<!doctype html><title>stub</title>' > "$WORK/web/index.html"

# adapt_and_run <listen-port> <strip-redaction: 0|1> -> writes $WORK/caddy.log, sets CADDY_PID
adapt_and_run() {
  local listen_port="$1" strip="$2"
  caddy adapt --config "$CADDYFILE" --adapter caddyfile > "$WORK/config.json"

  WEB_ROOT="$WORK/web" LISTEN_PORT="$listen_port" STRIP="$strip" node - "$WORK/config.json" <<'JS'
const fs = require('fs');
const path = process.argv[2];
let raw = fs.readFileSync(path, 'utf8');
raw = raw.split('/home/opc/apps/orszem-v2/web').join(process.env.WEB_ROOT);
const cfg = JSON.parse(raw);

for (const srv of Object.values(cfg.apps.http.servers)) {
  srv.listen = [':' + process.env.LISTEN_PORT];
  srv.automatic_https = { disable: true, disable_redirects: true };
  delete srv.tls_connection_policies;
}
delete cfg.apps.tls;

for (const log of Object.values(cfg.logging?.logs ?? {})) {
  if (log.writer) log.writer = { output: 'stdout' };
  // NEGATIVE CONTROL ONLY: simulate the redaction rule being removed or broken, to prove
  // this script would actually catch that regression rather than passing unconditionally.
  if (process.env.STRIP === '1' && log.encoder && log.encoder.format === 'filter') {
    log.encoder = { format: 'console' };
  }
}

fs.writeFileSync(path, JSON.stringify(cfg));
JS

  caddy run --config "$WORK/config.json" > "$WORK/caddy.log" 2>&1 &
  CADDY_PID=$!

  local ready=0
  for _ in $(seq 1 100); do
    if curl -sf -o /dev/null -H 'Host: api.orszembejelento.hu' "http://127.0.0.1:$listen_port/api/v1/meta"; then
      ready=1
      break
    fi
    sleep 0.2
  done
  if [ "$ready" -ne 1 ]; then
    echo "Caddy did not become ready on port $listen_port. Log follows:" >&2
    cat "$WORK/caddy.log" >&2
    exit 1
  fi
}

stop_caddy() {
  [ -n "$CADDY_PID" ] && kill "$CADDY_PID" 2>/dev/null || true
  wait "$CADDY_PID" 2>/dev/null || true
  CADDY_PID=""
  # The Caddyfile's admin endpoint (127.0.0.1:2019) is one fixed address shared by every
  # instance this script starts. A second instance must not attempt to bind it until the
  # first has genuinely released it - `wait` on the shell job is not always enough of a
  # guarantee on every platform, so this polls the actual socket instead of trusting a
  # fixed sleep.
  for _ in $(seq 1 50); do
    curl -sf -o /dev/null "http://127.0.0.1:2019/config/" 2>/dev/null || break
    sleep 0.1
  done
}

fail=0

node "$WORK/stub.js" "$UPSTREAM_PORT" &
STUB_PID=$!

# ------------------------------------------------------------ positive: redaction applied

adapt_and_run "$EDGE_PORT" 0

curl -s -H 'Host: api.orszembejelento.hu' \
  -H "X-Orszem-Report-Access: $REPORT_ACCESS_SENTINEL" \
  -H "Authorization: $AUTHORIZATION_SENTINEL" \
  "http://127.0.0.1:$EDGE_PORT/api/v1/meta" > /dev/null

# Also exercise the apex host and the Public reports path shape, so the rule is proven for
# both sites' log files and is not accidentally scoped to only one host or one path.
curl -s -H 'Host: orszembejelento.hu' \
  -H "X-Orszem-Report-Access: $REPORT_ACCESS_SENTINEL" \
  -H "Authorization: $AUTHORIZATION_SENTINEL" \
  "http://127.0.0.1:$EDGE_PORT/api/v1/public/reports/00000000-0000-0000-0000-000000000000" > /dev/null

sleep 0.3
stop_caddy

echo "== positive: neither credential value appears in the access log =="
if grep -qF "$REPORT_ACCESS_SENTINEL" "$WORK/caddy.log"; then
  echo "FAIL  X-Orszem-Report-Access value found in the access log"
  fail=1
else
  echo "ok    X-Orszem-Report-Access value absent from the access log"
fi
if grep -qF "$AUTHORIZATION_SENTINEL" "$WORK/caddy.log"; then
  echo "FAIL  Authorization value found in the access log"
  fail=1
else
  echo "ok    Authorization value absent from the access log"
fi

# The requests must still have been logged at all - an empty/missing log would make the
# absence above meaningless (nothing was checked, not something that passed). Caddy's
# console encoder inserts a space after each JSON colon, so this deliberately does not
# assume compact JSON spacing.
if grep -q '"uri": "/api/v1/meta"' "$WORK/caddy.log" && grep -q '"uri": "/api/v1/public/reports/' "$WORK/caddy.log"; then
  echo "ok    both requests were actually logged (redaction removed only the header value)"
else
  echo "FAIL  the requests were not logged at all - the check above proves nothing"
  fail=1
fi

echo
echo "== negative control: without the redaction rule, both values DO leak =="
rm -f "$WORK/caddy.log"
adapt_and_run "$((EDGE_PORT + 1))" 1

curl -s -H 'Host: api.orszembejelento.hu' \
  -H "X-Orszem-Report-Access: $REPORT_ACCESS_SENTINEL" \
  -H "Authorization: $AUTHORIZATION_SENTINEL" \
  "http://127.0.0.1:$((EDGE_PORT + 1))/api/v1/meta" > /dev/null

sleep 0.3
stop_caddy

# Authorization is deliberately excluded from this negative control: Caddy 2.11.4 already
# redacts it unconditionally, as a built-in default, independent of anything this Caddyfile
# configures (confirmed by running exactly this scenario - the value never appears even
# with the explicit filter rule removed). Only X-Orszem-Report-Access needs a real negative
# control, because it is not one of Caddy's built-in recognised sensitive headers - this
# Caddyfile's own rule is the only thing protecting it, which is exactly what this proves.
if grep -qF "$REPORT_ACCESS_SENTINEL" "$WORK/caddy.log"; then
  echo "ok    negative control leaks the report-access credential - this script would catch the real rule being removed"
else
  echo "FAIL  negative control did NOT leak the report-access credential - this test cannot actually detect a regression"
  fail=1
fi

kill "$STUB_PID" 2>/dev/null || true
STUB_PID=""

echo
if [ "$fail" -eq 0 ]; then
  echo "Caddy header redaction verified."
else
  echo "Caddy header redaction VIOLATED - see failures above." >&2
fi
exit "$fail"
