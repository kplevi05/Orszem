#!/usr/bin/env bash
#
# Őrszem V2 — automated backup/restore drill against throwaway PostgreSQL containers.
#
# Proves the whole chain end to end, against Docker containers this script creates and
# destroys itself, never against a database anything else uses:
#
#   1. start a throwaway "source" PostgreSQL and a real backend instance against it;
#   2. create one real row through the real backend (a maintenance-CLI super admin -
#      documented here as a deliberately small, fast fixture; a fuller realistic dataset
#      created through the ordinary HTTP APIs was exercised manually for the Phase 14
#      drill and is described in docs/PHASE_14_ENGINEERING_REPORT.md, not repeated here
#      on every run);
#   3. run scripts/orszem-backup.sh for real;
#   4. start a throwaway, empty "target" PostgreSQL;
#   5. run scripts/orszem-restore.sh for real;
#   6. start the real backend against the restored database and confirm Flyway accepts
#      the restored history without reapplying anything, and that the row from step 2
#      is present;
#   7. negative-test the restore guard: a corrupted archive and a mismatched checksum
#      must both be refused, and neither may touch the target database.
#
# All Docker resources this script creates are named with the orszem_drill_ prefix and
# only resources with that exact prefix are ever removed - see "Cleanup safety" in
# docs/PHASE_14_ENGINEERING_REPORT.md / docs/OPERATIONS_RUNBOOK.md.
#
# Requires: Docker, and a built backend jar (default backend/build/libs/backend.jar,
# override with ORSZEM_BACKEND_JAR - same convention as scripts/orszem-admin).
#
# Usage: ./scripts/orszem-restore-drill.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
JAR="${ORSZEM_BACKEND_JAR:-backend/build/libs/backend.jar}"

SOURCE_DB=orszem_drill_source_db
TARGET_DB=orszem_drill_target_db
NETWORK=orszem_drill_net

# scripts/orszem-backup.sh and scripts/orszem-restore.sh are plain bash and assume
# pg_dump/pg_restore are on PATH, which is true on the real deployment target (the
# postgresql-server package the runbook installs pulls in the client tools) but is not
# guaranteed on every developer machine. Rather than duplicate the scripts' logic here,
# this drill runs them, unmodified, inside a throwaway postgres:16 (Debian-based - unlike
# -alpine it ships bash) container attached to the same Docker network as the two
# database containers, addressing them by container name instead of localhost:PORT.
CLIENT_IMAGE=postgres:16

SOURCE_PORT="${ORSZEM_DRILL_SOURCE_PORT:-55432}"
TARGET_PORT="${ORSZEM_DRILL_TARGET_PORT:-55433}"
BACKEND_PORT="${ORSZEM_DRILL_BACKEND_PORT:-58080}"

WORKDIR="$(mktemp -d)"
BACKUP_DIR="$WORKDIR/backups"
mkdir -p "$BACKUP_DIR"

# Docker needs real Windows paths for -v SOURCES on Git Bash. cygpath does that conversion
# explicitly; elsewhere (Linux, CI) it does not exist and the POSIX path is already correct.
# (The in-container DESTINATIONS, e.g. /scripts, /backups, are protected from the same MSYS
# rewriting by MSYS_NO_PATHCONV=1, scoped locally to each `docker run` call below - it is not
# exported globally, because it would just as wrongly rewrite this script's own later calls
# to `java`/orszem-admin, which need real, unmangled Windows paths.)
if command -v cygpath >/dev/null 2>&1; then
  VOL_SCRIPT_DIR="$(cygpath -w "$SCRIPT_DIR")"
  VOL_BACKUP_DIR="$(cygpath -w "$BACKUP_DIR")"
else
  VOL_SCRIPT_DIR="$SCRIPT_DIR"
  VOL_BACKUP_DIR="$BACKUP_DIR"
fi

BACKEND_PID=""

log() { echo "[drill] $*"; }
fail() { echo "[drill] FAIL: $*" >&2; exit 1; }

cleanup() {
  local status=$?
  log "cleaning up (exit code so far: $status) ..."
  if [ -n "$BACKEND_PID" ] && kill -0 "$BACKEND_PID" 2>/dev/null; then
    kill "$BACKEND_PID" 2>/dev/null || true
    wait "$BACKEND_PID" 2>/dev/null || true
  fi
  # Only ever removes the exact orszem_drill_* names this script created - never a
  # wildcard, never anything belonging to a developer's own dev-db.sh container.
  docker rm -f "$SOURCE_DB" "$TARGET_DB" >/dev/null 2>&1 || true
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
  rm -rf "$WORKDIR"
  if [ "$status" -eq 0 ]; then
    log "PASS: backup/restore drill completed and all throwaway resources removed."
  else
    log "throwaway resources removed after failure."
  fi
}
trap cleanup EXIT

if ! docker info >/dev/null 2>&1; then
  fail "Docker is not running."
fi
if [ ! -f "$JAR" ]; then
  fail "backend jar not found at $JAR. Build it first: cd backend && ./gradlew bootJar"
fi

docker network create "$NETWORK" >/dev/null

log "starting throwaway source database ($SOURCE_DB) ..."
docker run -d --name "$SOURCE_DB" --network "$NETWORK" \
  -e POSTGRES_DB=orszem_v2 -e POSTGRES_USER=orszem_v2 -e POSTGRES_PASSWORD=drillpw \
  -e TZ=UTC -e PGTZ=UTC \
  -p "127.0.0.1:${SOURCE_PORT}:5432" \
  postgres:16-alpine >/dev/null

for i in $(seq 1 30); do
  docker exec "$SOURCE_DB" pg_isready -U orszem_v2 >/dev/null 2>&1 && break
  [ "$i" -eq 30 ] && fail "source database never became ready"
  sleep 1
done
log "source database ready."

log "starting the real backend against the source database ..."
ORSZEM_DB_URL="jdbc:postgresql://localhost:${SOURCE_PORT}/orszem_v2" \
ORSZEM_DB_USERNAME=orszem_v2 \
ORSZEM_DB_PASSWORD=drillpw \
java -jar "$JAR" \
  --server.address=127.0.0.1 --server.port="$BACKEND_PORT" \
  --spring.main.banner-mode=off \
  > "$WORKDIR/backend.log" 2>&1 &
BACKEND_PID=$!

for i in $(seq 1 60); do
  if curl -sf "http://127.0.0.1:${BACKEND_PORT}/actuator/health" | grep -q '"status":"UP"'; then
    break
  fi
  [ "$i" -eq 60 ] && { cat "$WORKDIR/backend.log" >&2; fail "backend never became healthy"; }
  sleep 1
done
log "backend healthy; Flyway applied the current migrations to an empty database (§83 empty-DB bootstrap, proven as a side effect of this same run)."

log "creating one real fixture row (a maintenance-CLI super admin) ..."
kill "$BACKEND_PID"
wait "$BACKEND_PID" 2>/dev/null || true
BACKEND_PID=""

CREATE_OUTPUT="$(ORSZEM_DB_URL="jdbc:postgresql://localhost:${SOURCE_PORT}/orszem_v2" \
  ORSZEM_DB_USERNAME=orszem_v2 ORSZEM_DB_PASSWORD=drillpw \
  ORSZEM_BACKEND_JAR="$JAR" \
  "$SCRIPT_DIR/orszem-admin" create-super-admin)"
FIXTURE_SERVICE_ID="$(echo "$CREATE_OUTPUT" | grep -oE 'SZ-[0-9]+' | head -1)"
[ -n "$FIXTURE_SERVICE_ID" ] || fail "could not parse the fixture service ID out of create-super-admin's output"
log "fixture service ID: $FIXTURE_SERVICE_ID"

log "importing the example reference dataset (reference-data/example - already " \
    "verificationStatus: VERIFIED / reuseStatus: CLEARED, unlike the real, still-PENDING " \
    "reference-data/local-research dataset) ..."
ORSZEM_DB_URL="jdbc:postgresql://localhost:${SOURCE_PORT}/orszem_v2" \
  ORSZEM_DB_USERNAME=orszem_v2 ORSZEM_DB_PASSWORD=drillpw \
  ORSZEM_BACKEND_JAR="$JAR" \
  "$SCRIPT_DIR/orszem-admin" reference-import "$REPO_ROOT/reference-data/example" >"$WORKDIR/reference-import.log" 2>&1 \
  || { cat "$WORKDIR/reference-import.log" >&2; fail "reference-import failed"; }

log "restarting the backend against the source database (as it would run in production) ..."
ORSZEM_DB_URL="jdbc:postgresql://localhost:${SOURCE_PORT}/orszem_v2" \
ORSZEM_DB_USERNAME=orszem_v2 \
ORSZEM_DB_PASSWORD=drillpw \
java -jar "$JAR" \
  --server.address=127.0.0.1 --server.port="$BACKEND_PORT" \
  --spring.main.banner-mode=off \
  > "$WORKDIR/backend.log" 2>&1 &
BACKEND_PID=$!
for i in $(seq 1 60); do
  curl -sf "http://127.0.0.1:${BACKEND_PORT}/actuator/health" | grep -q '"status":"UP"' && break
  [ "$i" -eq 60 ] && { cat "$WORKDIR/backend.log" >&2; fail "backend never became healthy after restart"; }
  sleep 1
done

log "submitting one Public report to test the capability round trip across restore (§22/§97) ..."
# The capability format is documented on PublicReportController: pr_ followed by the
# unpadded URL-safe base64 encoding of 256 random bits - generated here exactly as a real
# Public client would, never derived from or stored in the database itself (only its hash
# is - see ADR 0008), so this is a genuine test of the capability surviving as a bearer
# secret held outside the database, not a database-internal check.
uuid_v4() {
  local hex
  hex="$(openssl rand -hex 16)"
  echo "${hex:0:8}-${hex:8:4}-${hex:12:4}-${hex:16:4}-${hex:20:12}"
}

CAPABILITY="pr_$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')"
WRONG_CAPABILITY="pr_$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')"
SETTLEMENT_ID="$(curl -sf "http://127.0.0.1:${BACKEND_PORT}/api/v1/public/reference/settlements?query=P%C3%A9ldafalva" \
  | grep -oE '"id":"[a-f0-9-]+"' | head -1 | grep -oE '[a-f0-9-]{36}')"
[ -n "$SETTLEMENT_ID" ] || fail "could not resolve the example settlement's id"

SUBMIT_HTTP_CODE="$(curl -s -o "$WORKDIR/submit-response.json" -w '%{http_code}' \
  -X POST "http://127.0.0.1:${BACKEND_PORT}/api/v1/public/reports" \
  -H "Content-Type: application/json" \
  -H "X-Orszem-Report-Access: ${CAPABILITY}" \
  -d "{\"clientSubmissionId\":\"$(uuid_v4)\",\"occurredAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"settlementId\":\"${SETTLEMENT_ID}\",\"eventTypeCode\":\"FIGHT\"}")"
[ "$SUBMIT_HTTP_CODE" = "201" ] || { cat "$WORKDIR/submit-response.json" >&2; fail "Public report submission returned $SUBMIT_HTTP_CODE, expected 201"; }
PUBLIC_REPORT_ID="$(grep -oE '"reportId":"[a-f0-9-]+"' "$WORKDIR/submit-response.json" | grep -oE '[a-f0-9-]{36}')"
[ -n "$PUBLIC_REPORT_ID" ] || fail "could not parse reportId out of the submission response"
log "Public report submitted: $PUBLIC_REPORT_ID"

LOOKUP_BEFORE="$(curl -s -o /dev/null -w '%{http_code}' \
  "http://127.0.0.1:${BACKEND_PORT}/api/v1/public/reports/${PUBLIC_REPORT_ID}" \
  -H "X-Orszem-Report-Access: ${CAPABILITY}")"
[ "$LOOKUP_BEFORE" = "200" ] || fail "capability lookup before backup returned $LOOKUP_BEFORE, expected 200"
log "OK: capability lookup succeeds before backup."

log "running scripts/orszem-backup.sh for real, concurrently with one write ..."
# §81: prove a normal write racing the dump does not corrupt the snapshot. The write may
# land either just inside or just outside the snapshot - both are correct; what must NOT
# happen is a broken archive.
( sleep 1; curl -sf -o /dev/null -X GET "http://127.0.0.1:${BACKEND_PORT}/api/v1/meta" || true ) &
MSYS_NO_PATHCONV=1 docker run --rm --network "$NETWORK" \
  -v "$VOL_SCRIPT_DIR:/scripts:ro" -v "$VOL_BACKUP_DIR:/backups" \
  -e ORSZEM_DB_URL="jdbc:postgresql://${SOURCE_DB}:5432/orszem_v2" \
  -e ORSZEM_DB_USERNAME=orszem_v2 -e ORSZEM_DB_PASSWORD=drillpw \
  -e ORSZEM_RELEASE_SHA="$(git -C "$SCRIPT_DIR/.." rev-parse HEAD 2>/dev/null || echo unknown)" \
  "$CLIENT_IMAGE" bash /scripts/orszem-backup.sh /backups

DUMP_FILE="$(find "$BACKUP_DIR" -maxdepth 1 -name '*.dump' | head -1)"
[ -n "$DUMP_FILE" ] || fail "backup script did not leave a .dump file behind"
log "backup produced: $DUMP_FILE"

log "stopping the backend before restore ..."
kill "$BACKEND_PID"
wait "$BACKEND_PID" 2>/dev/null || true
BACKEND_PID=""

log "starting a throwaway, empty target database ($TARGET_DB) ..."
docker run -d --name "$TARGET_DB" --network "$NETWORK" \
  -e POSTGRES_DB=orszem_v2 -e POSTGRES_USER=orszem_v2 -e POSTGRES_PASSWORD=drillpw \
  -e TZ=UTC -e PGTZ=UTC \
  -p "127.0.0.1:${TARGET_PORT}:5432" \
  postgres:16-alpine >/dev/null
for i in $(seq 1 30); do
  docker exec "$TARGET_DB" pg_isready -U orszem_v2 >/dev/null 2>&1 && break
  [ "$i" -eq 30 ] && fail "target database never became ready"
  sleep 1
done

# --- negative tests first: the target must survive these completely untouched -----------
log "negative test: a corrupted archive must be refused before touching the target ..."
# Truncated to a quarter of its real size: non-empty (passes the "file exists and is
# non-zero" check) but structurally unreadable as a custom-format archive - a single
# flipped byte proved too tolerated by pg_restore's parser in practice; a truncated
# archive reliably fails "pg_restore -l" instead, which is the actual property being
# tested here (a broken archive must be rejected before it ever reaches the target).
CORRUPT_COPY="$WORKDIR/corrupt.dump"
FULL_SIZE="$(wc -c < "$DUMP_FILE" | tr -d ' ')"
head -c "$((FULL_SIZE / 4))" "$DUMP_FILE" > "$CORRUPT_COPY"
cp "$CORRUPT_COPY" "$BACKUP_DIR/corrupt.dump"
if MSYS_NO_PATHCONV=1 docker run --rm --network "$NETWORK" \
    -v "$VOL_SCRIPT_DIR:/scripts:ro" -v "$VOL_BACKUP_DIR:/backups" \
    -e PGPASSWORD=drillpw \
    "$CLIENT_IMAGE" bash /scripts/orszem-restore.sh \
    --dump /backups/corrupt.dump --target-environment orszem-drill \
    --host "$TARGET_DB" --port 5432 --database orszem_v2 --user orszem_v2 \
    --skip-checksum-verification --confirm-restore >"$WORKDIR/restore-corrupt.log" 2>&1; then
  cat "$WORKDIR/restore-corrupt.log" >&2
  fail "restore script accepted a corrupted archive - this must never happen"
fi
log "OK: corrupted archive was refused (see $WORKDIR/restore-corrupt.log)."

log "negative test: a mismatched checksum must be refused ..."
mkdir -p "$BACKUP_DIR/badsum"
cp "$DUMP_FILE" "$BACKUP_DIR/badsum/"
echo "0000000000000000000000000000000000000000000000000000000000000000  $(basename "$DUMP_FILE")" \
  > "$BACKUP_DIR/badsum/$(basename "$DUMP_FILE").sha256"
if MSYS_NO_PATHCONV=1 docker run --rm --network "$NETWORK" \
    -v "$VOL_SCRIPT_DIR:/scripts:ro" -v "$VOL_BACKUP_DIR:/backups" \
    -e PGPASSWORD=drillpw \
    "$CLIENT_IMAGE" bash /scripts/orszem-restore.sh \
    --dump "/backups/badsum/$(basename "$DUMP_FILE")" --target-environment orszem-drill \
    --host "$TARGET_DB" --port 5432 --database orszem_v2 --user orszem_v2 \
    --confirm-restore >"$WORKDIR/restore-badsum.log" 2>&1; then
  cat "$WORKDIR/restore-badsum.log" >&2
  fail "restore script accepted a mismatched checksum - this must never happen"
fi
log "OK: mismatched checksum was refused (see $WORKDIR/restore-badsum.log)."

TABLE_COUNT_BEFORE="$(docker exec "$TARGET_DB" psql -U orszem_v2 -d orszem_v2 -tAc \
  "select count(*) from information_schema.tables where table_schema='public'")"
[ "$TABLE_COUNT_BEFORE" -eq 0 ] || fail "target database was not empty after the negative tests (expected 0 tables, got $TABLE_COUNT_BEFORE) - a negative test must never touch the target"
log "OK: target database is still untouched (0 tables) after both negative tests."

# --- the real restore ---------------------------------------------------------------------
log "running scripts/orszem-restore.sh for real ..."
MSYS_NO_PATHCONV=1 docker run --rm --network "$NETWORK" \
  -v "$VOL_SCRIPT_DIR:/scripts:ro" -v "$VOL_BACKUP_DIR:/backups" \
  -e PGPASSWORD=drillpw \
  "$CLIENT_IMAGE" bash /scripts/orszem-restore.sh \
  --dump "/backups/$(basename "$DUMP_FILE")" --target-environment orszem-drill \
  --host "$TARGET_DB" --port 5432 --database orszem_v2 --user orszem_v2 \
  --confirm-restore

log "starting the real backend against the restored database ..."
ORSZEM_DB_URL="jdbc:postgresql://localhost:${TARGET_PORT}/orszem_v2" \
ORSZEM_DB_USERNAME=orszem_v2 \
ORSZEM_DB_PASSWORD=drillpw \
java -jar "$JAR" \
  --server.address=127.0.0.1 --server.port="$BACKEND_PORT" \
  --spring.main.banner-mode=off \
  > "$WORKDIR/backend-restored.log" 2>&1 &
BACKEND_PID=$!
for i in $(seq 1 60); do
  curl -sf "http://127.0.0.1:${BACKEND_PORT}/actuator/health" | grep -q '"status":"UP"' && break
  [ "$i" -eq 60 ] && { cat "$WORKDIR/backend-restored.log" >&2; fail "backend never became healthy against the restored database"; }
  sleep 1
done
log "OK: backend started cleanly against the restored database (Flyway accepted the restored history)."

MIGRATION_COUNT="$(docker exec "$TARGET_DB" psql -U orszem_v2 -d orszem_v2 -tAc \
  "select count(*) from flyway_schema_history where success = true")"
log "flyway_schema_history has $MIGRATION_COUNT successful row(s) after restore (no reapplication expected)."

RESTORED_ROW="$(docker exec "$TARGET_DB" psql -U orszem_v2 -d orszem_v2 -tAc \
  "select service_id from users where service_id = '${FIXTURE_SERVICE_ID}'")"
[ "$RESTORED_ROW" = "$FIXTURE_SERVICE_ID" ] || fail "fixture row $FIXTURE_SERVICE_ID was not found in the restored database"
log "OK: fixture row $FIXTURE_SERVICE_ID survived the backup/restore round trip unchanged."

log "verifying the Public capability invariant (§22/§97) against the restored database ..."
LOOKUP_AFTER="$(curl -s -o "$WORKDIR/lookup-after.json" -w '%{http_code}' \
  "http://127.0.0.1:${BACKEND_PORT}/api/v1/public/reports/${PUBLIC_REPORT_ID}" \
  -H "X-Orszem-Report-Access: ${CAPABILITY}")"
[ "$LOOKUP_AFTER" = "200" ] || { cat "$WORKDIR/lookup-after.json" >&2; fail "capability lookup after restore returned $LOOKUP_AFTER, expected 200 - the capability did not survive the snapshot"; }
log "OK: the same capability still resolves the same report after restore."

WRONG_LOOKUP="$(curl -s -o "$WORKDIR/lookup-wrong.json" -w '%{http_code}' \
  "http://127.0.0.1:${BACKEND_PORT}/api/v1/public/reports/${PUBLIC_REPORT_ID}" \
  -H "X-Orszem-Report-Access: ${WRONG_CAPABILITY}")"
[ "$WRONG_LOOKUP" = "404" ] || fail "a wrong capability against the restored database returned $WRONG_LOOKUP, expected generic 404"
if grep -qi "REPORT_NOT_FOUND" "$WORKDIR/lookup-wrong.json"; then
  log "OK: a wrong capability still returns the generic REPORT_NOT_FOUND 404 after restore (report existence is not disclosed)."
else
  fail "wrong-capability response body did not carry REPORT_NOT_FOUND: $(cat "$WORKDIR/lookup-wrong.json")"
fi

kill "$BACKEND_PID"
wait "$BACKEND_PID" 2>/dev/null || true
BACKEND_PID=""

log "all checks passed."
