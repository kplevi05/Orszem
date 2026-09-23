#!/usr/bin/env bash
#
# Őrszem V2 — automated backup/restore drill against throwaway PostgreSQL containers.
#
# Proves the whole chain end to end, against Docker containers this script creates and
# destroys itself, never against a database anything else uses. Full narrative in
# docs/PHASE_14_ENGINEERING_REPORT.md §M; in outline:
#
#   1. start a throwaway "source" PostgreSQL and a real backend instance against it
#      (proving §83 empty-DB Flyway bootstrap as a side effect);
#   2. build a real, cross-phase dataset through the real HTTP APIs: a SUPER_ADMIN (via
#      the maintenance CLI), a SERVICE_USER, a MODERATOR, a deactivated user, a
#      ServiceArea with a RailwayLine mapped into it, four Public reports covering NEW/
#      IN_PROGRESS/ARCHIVED/moderation-deleted workflow states, two real login sessions
#      (one left valid, one explicitly logged out before the backup) and the imported
#      reference dataset;
#   3. capture the definitions of the database's critical constraints/indexes - including
#      the routing snapshot's PRIMARY KEY *and*, separately, its FOREIGN KEY back to
#      reports (a primary key is not also a foreign key - found by relation + constraint
#      type, not a hard-coded generated name); and one concrete report/audit event/
#      moderation episode each, by their own immutable ids, not merely their tables'
#      row counts;
#   4. run scripts/orszem-backup.sh for real, with a FIFTH Public report submitted through
#      the real POST endpoint while pg_dump is provably still active - not a race decided
#      by luck, but a PostgreSQL lock used as a deterministic test barrier - then assert
#      the snapshot is never torn;
#   5. negative-test the restore guard against a corrupted archive, a mismatched checksum,
#      and FOUR different shapes of non-empty target (an ordinary table, a sequence, a
#      materialized view, a function) - all refused, none touching the target, each
#      confirmed by directly re-querying the sentinel object itself afterward (not merely
#      "left as found");
#   6. run scripts/orszem-restore.sh for real, into a freshly created, still-empty target;
#   7. start the real backend against the restored database and verify: Flyway's history,
#      every fixture's immutable identity and current state, the constraint/index
#      definitions captured in step 3 (including the routing-snapshot foreign key), the
#      concrete report/audit-event/moderation-episode identities captured in step 3, the
#      concurrent-write snapshot invariant, the Public capability round trip, and both
#      session outcomes (still-valid session still authenticates; already-revoked session
#      stays revoked).
#
# This is defense-in-depth against the classes of object the implementation and these
# tests actually cover (tables, views, materialized views, sequences, foreign tables,
# functions/procedures, custom types/domains/enums, custom schemas, non-default
# extensions) - not a claim that every conceivable PostgreSQL catalog object class is
# exhaustively enumerated. The supported operational contract remains simple: an operator
# creates a genuinely fresh, empty target database for every restore; this guard is the
# safety net that catches the mistake if that did not actually happen.
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
# rewriting by MSYS_NO_PATHCONV=1, scoped locally to each `docker run`/`docker exec` call
# below - it is not exported globally, because it would just as wrongly rewrite this
# script's own later calls to `java`/orszem-admin, which need real, unmangled Windows paths.)
if command -v cygpath >/dev/null 2>&1; then
  VOL_SCRIPT_DIR="$(cygpath -w "$SCRIPT_DIR")"
  VOL_BACKUP_DIR="$(cygpath -w "$BACKUP_DIR")"
else
  VOL_SCRIPT_DIR="$SCRIPT_DIR"
  VOL_BACKUP_DIR="$BACKUP_DIR"
fi

BACKEND_PID=""
BARRIER_WRITE_FD=""
BARRIER_PROC_PID=""
FIXTURE_PASSWORD="korte-szilva-alma-barack-59"

log() { echo "[drill] $*"; }
fail() { echo "[drill] FAIL: $*" >&2; exit 1; }

cleanup() {
  local status=$?
  log "cleaning up (exit code so far: $status) ..."
  if [ -n "$BACKEND_PID" ] && kill -0 "$BACKEND_PID" 2>/dev/null; then
    kill "$BACKEND_PID" 2>/dev/null || true
    wait "$BACKEND_PID" 2>/dev/null || true
  fi
  if [ -n "$BARRIER_WRITE_FD" ]; then
    exec {BARRIER_WRITE_FD}>&- 2>/dev/null || true
  fi
  if [ -n "$BARRIER_PROC_PID" ] && kill -0 "$BARRIER_PROC_PID" 2>/dev/null; then
    kill "$BARRIER_PROC_PID" 2>/dev/null || true
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

# ------------------------------------------------------------------------- small helpers

uuid_v4() {
  local hex
  hex="$(openssl rand -hex 16)"
  echo "${hex:0:8}-${hex:8:4}-${hex:12:4}-${hex:16:4}-${hex:20:12}"
}

new_capability() { echo "pr_$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')"; }

# Issues one HTTP call against the backend; response body lands in $WORKDIR/http.json,
# the status code is echoed to stdout. bearer may be empty for an anonymous call.
http_call() {
  local method="$1" path="$2" body="${3:-}" bearer="${4:-}" extra_header="${5:-}"
  local args=(-s -o "$WORKDIR/http.json" -w '%{http_code}' -X "$method" "http://127.0.0.1:${BACKEND_PORT}${path}")
  [ -n "$body" ] && args+=(-H "Content-Type: application/json" -d "$body")
  [ -n "$bearer" ] && args+=(-H "Authorization: Bearer $bearer")
  [ -n "$extra_header" ] && args+=(-H "$extra_header")
  curl "${args[@]}"
}

expect_status() {
  local actual="$1" expected="$2" msg="$3"
  [ "$actual" = "$expected" ] || { cat "$WORKDIR/http.json" >&2; fail "$msg (http $actual, expected $expected)"; }
}

# Extracts a top-level scalar JSON field's value from the last http_call's response body.
field() {
  grep -oE "\"$1\":\"[^\"]*\"|\"$1\":[0-9]+|\"$1\":(true|false|null)" "$WORKDIR/http.json" \
    | head -1 | sed -E "s/\"$1\"://; s/^\"//; s/\"\$//"
}

sql_source() { docker exec "$SOURCE_DB" psql -U orszem_v2 -d orszem_v2 -tAc "$1"; }
sql_restore() { docker exec "$TARGET_DB" psql -U orszem_v2 -d "$RESTORE_DB" -tAc "$1"; }

# The critical constraint/index definitions this drill compares before backup and after
# restore. Three kinds, all covered because none alone would prove the whole picture:
#   - plain unique indexes (CREATE UNIQUE INDEX never registers as a pg_constraint row -
#     pg_get_constraintdef alone would silently miss every one of these);
#   - the routing snapshot's PRIMARY KEY (identity/uniqueness - one snapshot per report);
#   - the routing snapshot's FOREIGN KEY back to reports (referential integrity - a
#     PRIMARY KEY is not also a FOREIGN KEY; the two are separate pg_constraint rows even
#     though PostgreSQL creates both from the single inline
#     "report_id UUID PRIMARY KEY REFERENCES reports(id)" column definition - confirmed
#     empirically against a real PostgreSQL 16 instance, not assumed). Found by relation +
#     constraint type (conrelid/contype), not by its generated name, precisely because a
#     generated FK name is an implementation detail this query should not depend on.
read -r -d '' CONSTRAINT_QUERY <<'SQL' || true
select 'INDEX:' || indexname || ':' || indexdef
from pg_indexes
where indexname in (
  'ux_users_service_id',
  'ux_reports_public_id',
  'ux_reports_client_submission_id',
  'ux_report_assignments_open_episode',
  'ux_report_moderation_episodes_open_episode',
  'ux_service_area_railway_lines_line',
  'ix_service_area_settlement_lines_area',
  'ix_service_area_settlement_lines_line'
)
union all
select 'CONSTRAINT:' || conname || ':' || pg_get_constraintdef(oid)
from pg_constraint
where conname in (
  'report_routing_snapshots_pkey',
  'service_area_railway_lines_pkey',
  'service_area_settlement_lines_pkey'
)
union all
select 'CONSTRAINT:' || conname || ':' || pg_get_constraintdef(oid)
from pg_constraint
where (
  (conrelid = 'report_routing_snapshots'::regclass and confrelid = 'reports'::regclass)
  or conrelid = 'service_area_settlement_lines'::regclass
) and contype = 'f'
order by 1;
SQL

# ---------------------------------------------------------------------------- start source

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

start_backend_on_source() {
  ORSZEM_DB_URL="jdbc:postgresql://localhost:${SOURCE_PORT}/orszem_v2" \
  ORSZEM_DB_USERNAME=orszem_v2 \
  ORSZEM_DB_PASSWORD=drillpw \
  java -jar "$JAR" \
    --server.address=127.0.0.1 --server.port="$BACKEND_PORT" \
    --spring.main.banner-mode=off \
    > "$WORKDIR/backend.log" 2>&1 &
  BACKEND_PID=$!
  for i in $(seq 1 60); do
    curl -sf "http://127.0.0.1:${BACKEND_PORT}/actuator/health" | grep -q '"status":"UP"' && return 0
    [ "$i" -eq 60 ] && { cat "$WORKDIR/backend.log" >&2; fail "backend never became healthy"; }
    sleep 1
  done
}

start_backend_on_source
log "backend healthy; Flyway applied the current migrations to an empty database (§83 empty-DB bootstrap, proven as a side effect of this same run)."

log "creating the SUPER_ADMIN fixture (a maintenance-CLI super admin, a real code path) ..."
kill "$BACKEND_PID"; wait "$BACKEND_PID" 2>/dev/null || true; BACKEND_PID=""

CREATE_OUTPUT="$(ORSZEM_DB_URL="jdbc:postgresql://localhost:${SOURCE_PORT}/orszem_v2" \
  ORSZEM_DB_USERNAME=orszem_v2 ORSZEM_DB_PASSWORD=drillpw \
  ORSZEM_BACKEND_JAR="$JAR" \
  "$SCRIPT_DIR/orszem-admin" create-super-admin)"
ADMIN_SERVICE_ID="$(echo "$CREATE_OUTPUT" | grep -oE 'SZ-[0-9]+' | head -1)"
ADMIN_TEMP_CREDENTIAL="$(echo "$CREATE_OUTPUT" | grep -oE '[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}' | head -1)"
[ -n "$ADMIN_SERVICE_ID" ] || fail "could not parse the SUPER_ADMIN service ID out of create-super-admin's output"
[ -n "$ADMIN_TEMP_CREDENTIAL" ] || fail "could not parse the SUPER_ADMIN temporary credential out of create-super-admin's output"
log "SUPER_ADMIN: $ADMIN_SERVICE_ID"

log "importing the example reference dataset (reference-data/example - already " \
    "verificationStatus: VERIFIED / reuseStatus: CLEARED, unlike the real, still-PENDING " \
    "reference-data/local-research dataset) ..."
ORSZEM_DB_URL="jdbc:postgresql://localhost:${SOURCE_PORT}/orszem_v2" \
  ORSZEM_DB_USERNAME=orszem_v2 ORSZEM_DB_PASSWORD=drillpw \
  ORSZEM_BACKEND_JAR="$JAR" \
  "$SCRIPT_DIR/orszem-admin" reference-import "$REPO_ROOT/reference-data/example" >"$WORKDIR/reference-import.log" 2>&1 \
  || { cat "$WORKDIR/reference-import.log" >&2; fail "reference-import failed"; }

log "restarting the backend against the source database (as it would run in production) ..."
start_backend_on_source

# ---------------------------------------------------------------------------- auth bootstrap

log "completing the SUPER_ADMIN's forced initial password change ..."
STATUS="$(http_call POST /api/v1/service/auth/complete-password-change \
  "{\"serviceId\":\"${ADMIN_SERVICE_ID}\",\"temporaryPassword\":\"${ADMIN_TEMP_CREDENTIAL}\",\"newPassword\":\"${FIXTURE_PASSWORD}\"}")"
expect_status "$STATUS" 200 "SUPER_ADMIN complete-password-change failed"
ADMIN_BEARER="$(field accessToken)"
[ -n "$ADMIN_BEARER" ] || fail "no accessToken in the SUPER_ADMIN's complete-password-change response"

create_user() { # create_user ROLE -> sets LAST_CREATED_SERVICE_ID / LAST_CREATED_TEMP_CREDENTIAL
  local role="$1"
  STATUS="$(http_call POST /api/v1/service/user-management/users "{\"role\":\"${role}\"}" "$ADMIN_BEARER")"
  expect_status "$STATUS" 200 "creating a ${role} failed"
  LAST_CREATED_SERVICE_ID="$(field serviceId)"
  LAST_CREATED_TEMP_CREDENTIAL="$(field temporaryCredential)"
  [ -n "$LAST_CREATED_SERVICE_ID" ] || fail "no serviceId when creating a ${role}"
  [ -n "$LAST_CREATED_TEMP_CREDENTIAL" ] || fail "no temporaryCredential when creating a ${role}"
}

complete_password_change() { # complete_password_change SERVICE_ID TEMP_CREDENTIAL -> echoes accessToken
  local sid="$1" cred="$2"
  STATUS="$(http_call POST /api/v1/service/auth/complete-password-change \
    "{\"serviceId\":\"${sid}\",\"temporaryPassword\":\"${cred}\",\"newPassword\":\"${FIXTURE_PASSWORD}\"}")"
  expect_status "$STATUS" 200 "complete-password-change failed for ${sid}"
  field accessToken
}

log "creating the SERVICE_USER, MODERATOR and to-be-deactivated fixtures via the real user-management API ..."
create_user SERVICE_USER
SERVICE_SERVICE_ID="$LAST_CREATED_SERVICE_ID"
SERVICE_BEARER="$(complete_password_change "$SERVICE_SERVICE_ID" "$LAST_CREATED_TEMP_CREDENTIAL")"

create_user MODERATOR
MODERATOR_SERVICE_ID="$LAST_CREATED_SERVICE_ID"
MODERATOR_BEARER="$(complete_password_change "$MODERATOR_SERVICE_ID" "$LAST_CREATED_TEMP_CREDENTIAL")"
# MODERATOR acts on the fixture report below without a separate per-area grant.
STATUS="$(http_call POST "/api/v1/service/user-management/users/${MODERATOR_SERVICE_ID}/global-access/grant" "" "$ADMIN_BEARER")"
expect_status "$STATUS" 200 "granting the MODERATOR global access failed"

create_user SERVICE_USER
DEACTIVATED_SERVICE_ID="$LAST_CREATED_SERVICE_ID"
STATUS="$(http_call POST "/api/v1/service/user-management/users/${DEACTIVATED_SERVICE_ID}/deactivate" "" "$ADMIN_BEARER")"
expect_status "$STATUS" 200 "deactivating the fourth fixture user failed"
[ "$(field status)" = "DEACTIVATED" ] || fail "the fourth fixture user's status is not DEACTIVATED after the deactivate call"
log "fixtures: SUPER_ADMIN=$ADMIN_SERVICE_ID  SERVICE_USER=$SERVICE_SERVICE_ID (active)  MODERATOR=$MODERATOR_SERVICE_ID (active)  fourth=$DEACTIVATED_SERVICE_ID (deactivated)"

log "establishing two real login sessions for the session-restore proof (§ Service session) ..."
# Session A: stays valid. Session B: logged out (revoked) before the backup - both are
# real sessions created through the real login/complete-password-change flow above and
# through a second real /login call; their client-held access tokens are kept only in
# this script's own variables/environment, never written back into the database (ADR
# 0008's same "credential held outside the DB" shape as the Public capability, applied
# here to a service session instead).
VALID_SESSION_BEARER="$SERVICE_BEARER"
STATUS="$(http_call POST /api/v1/service/auth/login \
  "{\"serviceId\":\"${SERVICE_SERVICE_ID}\",\"password\":\"${FIXTURE_PASSWORD}\"}")"
expect_status "$STATUS" 200 "second SERVICE_USER login (for the to-be-revoked session) failed"
REVOKED_SESSION_BEARER="$(field accessToken)"
[ -n "$REVOKED_SESSION_BEARER" ] || fail "no accessToken from the second SERVICE_USER login"
STATUS="$(http_call POST /api/v1/service/auth/logout "" "$REVOKED_SESSION_BEARER")"
expect_status "$STATUS" 204 "logging out the to-be-revoked session failed"
# Confirm it is actually rejected NOW, before the backup even happens - otherwise the
# post-restore check would prove nothing.
STATUS="$(http_call GET /api/v1/service/reports/new "" "$REVOKED_SESSION_BEARER")"
[ "$STATUS" = "401" ] || fail "the just-revoked session was not actually rejected before the backup (http $STATUS) - the revocation itself did not work"
log "OK: one session left valid, one real session logged out and confirmed rejected, both before the backup."

# --------------------------------------------------------------------- service area / routing

log "creating a ServiceArea and mapping a real RailwayLine into it via the real admin API ..."
STATUS="$(http_call POST /api/v1/service/service-area-admin/areas "{\"name\":\"Drill-Terulet-$(uuid_v4)\"}" "$ADMIN_BEARER")"
expect_status "$STATUS" 200 "creating the ServiceArea failed"
AREA_ID="$(field id)"
AREA_VERSION_BEFORE="$(field adminVersion)"
[ -n "$AREA_ID" ] || fail "no id in the create-area response"

STATUS="$(http_call GET "/api/v1/service/service-area-admin/railway-lines?query=900" "" "$ADMIN_BEARER")"
expect_status "$STATUS" 200 "listing railway lines failed"
LINE_900_ID="$(grep -oE '"id":"[a-f0-9-]+"' "$WORKDIR/http.json" | head -1 | grep -oE '[a-f0-9-]{36}')"
[ -n "$LINE_900_ID" ] || fail "could not resolve railway line 900's id"

STATUS="$(http_call POST "/api/v1/service/service-area-admin/railway-lines/${LINE_900_ID}/assign" \
  "{\"targetServiceAreaId\":\"${AREA_ID}\",\"expectedCurrentServiceAreaId\":null}" "$ADMIN_BEARER")"
expect_status "$STATUS" 204 "assigning the railway line to the ServiceArea failed"

STATUS="$(http_call POST "/api/v1/service/user-management/users/${SERVICE_SERVICE_ID}/areas/${AREA_ID}/grant" "" "$ADMIN_BEARER")"
expect_status "$STATUS" 200 "granting the SERVICE_USER access to the ServiceArea failed"
log "ServiceArea $AREA_ID created, line $LINE_900_ID mapped into it, SERVICE_USER granted access."

# ------------------------------------------------------------------- reports / workflow states

SETTLEMENT_ID="$(curl -sf "http://127.0.0.1:${BACKEND_PORT}/api/v1/public/reference/settlements?query=P%C3%A9ldafalva" \
  | grep -oE '"id":"[a-f0-9-]+"' | head -1 | grep -oE '[a-f0-9-]{36}')"
[ -n "$SETTLEMENT_ID" ] || fail "could not resolve the example settlement's id"

submit_report() { # submit_report EVENT_TYPE CAPABILITY -> sets LAST_REPORT_ID
  local event_type="$1" capability="$2"
  STATUS="$(http_call POST /api/v1/public/reports \
    "{\"clientSubmissionId\":\"$(uuid_v4)\",\"occurredAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"settlementId\":\"${SETTLEMENT_ID}\",\"railwayLineId\":\"${LINE_900_ID}\",\"eventTypeCode\":\"${event_type}\"}" \
    "" "X-Orszem-Report-Access: ${capability}")"
  expect_status "$STATUS" 201 "submitting a Public report (${event_type}) failed"
  LAST_REPORT_ID="$(field reportId)"
  [ -n "$LAST_REPORT_ID" ] || fail "no reportId in the submission response for ${event_type}"
}

log "submitting four Public reports covering NEW/IN_PROGRESS/ARCHIVED/moderation-deleted ..."

# 1. Capability round trip (§22/§97) - submitted and left untouched, exactly like a
#    Public reporter would leave it, so the capability check below is not entangled with
#    any workflow mutation.
CAPABILITY="$(new_capability)"
WRONG_CAPABILITY="$(new_capability)"
submit_report FIGHT "$CAPABILITY"
CAP_REPORT_ID="$LAST_REPORT_ID"

# 2. Claimed and left IN_PROGRESS.
submit_report LOUD_BEHAVIOR "$(new_capability)"
INPROGRESS_REPORT_ID="$LAST_REPORT_ID"
STATUS="$(http_call POST "/api/v1/service/reports/${INPROGRESS_REPORT_ID}/claim" '{"expectedVersion":0}' "$SERVICE_BEARER")"
expect_status "$STATUS" 200 "claiming the IN_PROGRESS fixture report failed"

# 3. Claimed then closed -> ARCHIVED.
submit_report THEFT "$(new_capability)"
ARCHIVED_REPORT_ID="$LAST_REPORT_ID"
STATUS="$(http_call POST "/api/v1/service/reports/${ARCHIVED_REPORT_ID}/claim" '{"expectedVersion":0}' "$SERVICE_BEARER")"
expect_status "$STATUS" 200 "claiming the to-be-archived fixture report failed"
STATUS="$(http_call POST "/api/v1/service/reports/${ARCHIVED_REPORT_ID}/close" '{"expectedVersion":1}' "$SERVICE_BEARER")"
expect_status "$STATUS" 200 "closing the to-be-archived fixture report failed"

# 4. Moderation-deleted - one real moderation episode.
submit_report VANDALISM "$(new_capability)"
MODERATED_REPORT_ID="$LAST_REPORT_ID"
STATUS="$(http_call POST "/api/v1/service/moderation/reports/${MODERATED_REPORT_ID}/delete" \
  '{"expectedVersion":0,"reason":"OTHER"}' "$MODERATOR_BEARER")"
expect_status "$STATUS" 204 "moderation-deleting the fixture report failed"

log "OK: capability=$CAP_REPORT_ID  in-progress=$INPROGRESS_REPORT_ID  archived=$ARCHIVED_REPORT_ID  moderated=$MODERATED_REPORT_ID"

LOOKUP_BEFORE="$(curl -s -o /dev/null -w '%{http_code}' \
  "http://127.0.0.1:${BACKEND_PORT}/api/v1/public/reports/${CAP_REPORT_ID}" \
  -H "X-Orszem-Report-Access: ${CAPABILITY}")"
[ "$LOOKUP_BEFORE" = "200" ] || fail "capability lookup before backup returned $LOOKUP_BEFORE, expected 200"

# ------------------------------------------------------------- pre-backup snapshot of truth

# Everything checked again after restore, captured now so "unchanged" means something.
BEFORE_INPROGRESS_ROW="$(sql_source "select status,assigned_user_id from reports where public_id='${INPROGRESS_REPORT_ID}'")"
BEFORE_ARCHIVED_ROW="$(sql_source "select status,archived_at from reports where public_id='${ARCHIVED_REPORT_ID}'")"
# The representative report's full immutable identity: public_id, submitted_at, status,
# and its routing snapshot's identity/area/reason together - not merely two of its
# columns in isolation.
BEFORE_REPORT_IDENTITY_ROW="$(sql_source "select r.public_id, r.submitted_at, r.status, s.routing_status, s.service_area_id, s.routing_reason
  from reports r join report_routing_snapshots s on s.report_id = r.id
  where r.public_id = '${INPROGRESS_REPORT_ID}'")"
[ -n "$BEFORE_REPORT_IDENTITY_ROW" ] || fail "sanity check failed: the representative report's identity row could not be read from the source database"
# One concrete moderation episode: its own id, reason, the actual deletion timestamp, its
# open/closed state, and the immutable stored id of the actor who deleted it - not just
# reason and open/closed.
BEFORE_MODERATION_ROW="$(sql_source "select e.id, e.reason, e.deleted_at, e.restored_at is null, e.deleted_by_user_id
  from report_moderation_episodes e join reports r on r.id = e.report_id
  where r.public_id = '${MODERATED_REPORT_ID}'")"
[ -n "$BEFORE_MODERATION_ROW" ] || fail "sanity check failed: the moderation episode row could not be read from the source database"
# One concrete audit event, identified precisely (not merely counted): the
# REPORT_MODERATION_DELETED event the moderation-delete call above must have written,
# tied to this exact report by its internal id - its own id, type and timestamp.
BEFORE_AUDIT_EVENT_ROW="$(sql_source "select a.id, a.event_type, a.created_at
  from audit_events a join reports r on r.id = a.target_id
  where a.event_type = 'REPORT_MODERATION_DELETED' and r.public_id = '${MODERATED_REPORT_ID}'")"
[ -n "$BEFORE_AUDIT_EVENT_ROW" ] || fail "sanity check failed: the concrete REPORT_MODERATION_DELETED audit event could not be found in the source database"
BEFORE_AUDIT_COUNT="$(sql_source "select count(*) from audit_events")"
BEFORE_REFERENCE_ROW="$(sql_source "select dataset_version,is_current from reference_dataset_imports where is_current")"
BEFORE_AREA_MAPPING_ROW="$(sql_source "select service_area_id from service_area_railway_lines where railway_line_id='${LINE_900_ID}'")"
BEFORE_DEACTIVATED_STATUS="$(sql_source "select status from users where service_id='${DEACTIVATED_SERVICE_ID}'")"
[ "$BEFORE_DEACTIVATED_STATUS" = "DEACTIVATED" ] || fail "sanity check failed: fourth user is not DEACTIVATED in the source database"

log "capturing critical constraint/index definitions before backup ..."
BEFORE_CONSTRAINTS="$(sql_source "$CONSTRAINT_QUERY")"
CONSTRAINT_ROW_COUNT="$(echo "$BEFORE_CONSTRAINTS" | grep -c ':' || true)"
[ "$CONSTRAINT_ROW_COUNT" -ge 14 ] || fail "expected at least 14 constraint/index definitions captured (8 indexes + 3 PKs + 3 FKs), got $CONSTRAINT_ROW_COUNT - the comparison would prove nothing if this list were incomplete"
echo "$BEFORE_CONSTRAINTS" | grep -q "report_routing_snapshots_.*fkey\|report_routing_snapshots.*FOREIGN KEY" \
  || fail "the routing-snapshot -> reports FOREIGN KEY was not found in the captured constraint list - a PRIMARY KEY is not also a FOREIGN KEY, and the comparison must cover both"
log "OK: captured $CONSTRAINT_ROW_COUNT constraint/index definitions, including the routing-snapshot FOREIGN KEY back to reports (not just its PRIMARY KEY)."

# ------------------------------------------------- deterministic concurrent-write + backup

log "setting up a deterministic pg_dump/write overlap barrier (§2 of the correction brief) ..."
# A throwaway table pg_dump will try to lock (it locks every table it is about to dump,
# to protect against concurrent DDL) but that no application code ever touches. A
# harness-held session takes an ACCESS EXCLUSIVE lock on it FIRST, so pg_dump's own
# attempt to lock it blocks - definitive, catalog-visible proof that pg_dump has started
# and is still running (pg_stat_activity shows it waiting on a real lock, not merely
# "recently launched"), for exactly as long as the harness chooses to hold the barrier.
# This is test-only synchronization: scripts/orszem-backup.sh itself is completely
# unmodified and has no idea this table exists.
sql_source "create table zz_drill_concurrency_barrier (id int)" >/dev/null

coproc BARRIER { docker exec -i "$SOURCE_DB" psql -U orszem_v2 -d orszem_v2 -v ON_ERROR_STOP=1 2>&1; }
BARRIER_PROC_PID=$!
BARRIER_WRITE_FD="${BARRIER[1]}"
echo "BEGIN;" >&"$BARRIER_WRITE_FD"
echo "LOCK TABLE zz_drill_concurrency_barrier IN ACCESS EXCLUSIVE MODE;" >&"$BARRIER_WRITE_FD"
echo "SELECT 'DRILL_BARRIER_LOCK_ACQUIRED';" >&"$BARRIER_WRITE_FD"

BARRIER_CONFIRMED=0
for i in $(seq 1 100); do
  if read -r -t 0.2 -u "${BARRIER[0]}" barrier_line; then
    [[ "$barrier_line" == *"DRILL_BARRIER_LOCK_ACQUIRED"* ]] && { BARRIER_CONFIRMED=1; break; }
  fi
done
[ "$BARRIER_CONFIRMED" -eq 1 ] || fail "the concurrency barrier lock was never confirmed held - cannot prove overlap"
log "OK: barrier lock confirmed held (read-only catalog confirmation, not a sleep)."

log "launching scripts/orszem-backup.sh for real - it will block on the barrier ..."
( MSYS_NO_PATHCONV=1 docker run --rm --network "$NETWORK" \
    -v "$VOL_SCRIPT_DIR:/scripts:ro" -v "$VOL_BACKUP_DIR:/backups" \
    -e ORSZEM_DB_URL="jdbc:postgresql://${SOURCE_DB}:5432/orszem_v2" \
    -e ORSZEM_DB_USERNAME=orszem_v2 -e ORSZEM_DB_PASSWORD=drillpw \
    -e ORSZEM_RELEASE_SHA="$(git -C "$SCRIPT_DIR/.." rev-parse HEAD 2>/dev/null || echo unknown)" \
    "$CLIENT_IMAGE" bash /scripts/orszem-backup.sh /backups > "$WORKDIR/backup.log" 2>&1 ) &
BACKUP_RUN_PID=$!

BACKUP_PG_DUMP_PID=""
for i in $(seq 1 100); do
  BACKUP_PG_DUMP_PID="$(sql_source "select pid from pg_stat_activity where application_name='pg_dump' and wait_event_type='Lock' limit 1")"
  [ -n "$BACKUP_PG_DUMP_PID" ] && break
  sleep 0.2
done
[ -n "$BACKUP_PG_DUMP_PID" ] || fail "pg_dump never showed up blocked on the barrier - cannot prove overlap deterministically"
log "OK: pg_dump backend pid=$BACKUP_PG_DUMP_PID is connected and blocked on the barrier lock (catalog-confirmed, not assumed)."

log "submitting the concurrent Public report through the real POST endpoint, right now, while pg_dump is confirmed blocked ..."
CONCURRENT_SUBMISSION_ID="$(uuid_v4)"
CONCURRENT_CAPABILITY="$(new_capability)"
STATUS="$(http_call POST /api/v1/public/reports \
  "{\"clientSubmissionId\":\"${CONCURRENT_SUBMISSION_ID}\",\"occurredAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"settlementId\":\"${SETTLEMENT_ID}\",\"eventTypeCode\":\"THEFT\"}" \
  "" "X-Orszem-Report-Access: ${CONCURRENT_CAPABILITY}")"
expect_status "$STATUS" 201 "the concurrent Public report submission itself failed - the barrier proof could not be exercised"
log "OK: the concurrent write committed for real (http 201) - clientSubmissionId=$CONCURRENT_SUBMISSION_ID."

STILL_BLOCKED="$(sql_source "select count(*) from pg_stat_activity where pid=${BACKUP_PG_DUMP_PID}")"
[ "$STILL_BLOCKED" = "1" ] || fail "pg_dump (pid $BACKUP_PG_DUMP_PID) was no longer present immediately after the concurrent write committed - overlap was not actually proven this run"
log "OK: the SAME pg_dump backend (pid $BACKUP_PG_DUMP_PID) was still connected immediately after the write committed - genuine temporal overlap, directly observed, not assumed."
# Because pg_dump's REPEATABLE READ snapshot is established at its transaction's first
# statement - well before it reaches the per-table locking phase this barrier blocks it
# at - this write is expected to fall OUTSIDE that snapshot deterministically, every run.
# Both outcomes are still checked and accepted below; only a torn (partial) result fails.

log "releasing the barrier, letting pg_dump finish ..."
echo "COMMIT;" >&"$BARRIER_WRITE_FD"
exec {BARRIER_WRITE_FD}>&-
BARRIER_WRITE_FD=""
wait "$BARRIER_PROC_PID" 2>/dev/null || true
BARRIER_PROC_PID=""

wait "$BACKUP_RUN_PID" || { cat "$WORKDIR/backup.log" >&2; fail "orszem-backup.sh failed"; }
cat "$WORKDIR/backup.log"

sql_source "drop table zz_drill_concurrency_barrier" >/dev/null

DUMP_FILE="$(find "$BACKUP_DIR" -maxdepth 1 -name '*.dump' | head -1)"
[ -n "$DUMP_FILE" ] || fail "backup script did not leave a .dump file behind"
log "backup produced: $DUMP_FILE"

# Sanity: this real, committed write must be visible on the SOURCE database regardless of
# whether the dump caught it - it only might be absent from the SNAPSHOT, never from the
# live source that accepted it.
SOURCE_HAS_CONCURRENT="$(sql_source "select exists(select 1 from reports where client_submission_id = '${CONCURRENT_SUBMISSION_ID}')")"
[ "$SOURCE_HAS_CONCURRENT" = "t" ] || fail "the concurrent report was accepted (201) but is not in the source database at all - that is a real backend bug, not a snapshot-timing question"

log "stopping the backend before restore ..."
kill "$BACKEND_PID"; wait "$BACKEND_PID" 2>/dev/null || true; BACKEND_PID=""

# --------------------------------------------------------------------------- target + negatives

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

attempt_restore() { # attempt_restore DUMP_PATH_IN_CONTAINER DATABASE [--skip-checksum-verification] -> exit status via $?, log in $WORKDIR/restore-attempt.log
  local dump="$1" db="$2" extra="${3:-}"
  MSYS_NO_PATHCONV=1 docker run --rm --network "$NETWORK" \
    -v "$VOL_SCRIPT_DIR:/scripts:ro" -v "$VOL_BACKUP_DIR:/backups" \
    -e PGPASSWORD=drillpw \
    "$CLIENT_IMAGE" bash /scripts/orszem-restore.sh \
    --dump "$dump" --target-environment orszem-drill \
    --host "$TARGET_DB" --port 5432 --database "$db" --user orszem_v2 \
    $extra --confirm-restore >"$WORKDIR/restore-attempt.log" 2>&1
}

log "negative test: a corrupted archive must be refused before touching the target ..."
# Truncated to a quarter of its real size: non-empty (passes the "file exists and is
# non-zero" check) but structurally unreadable as a custom-format archive.
CORRUPT_COPY="$WORKDIR/corrupt.dump"
FULL_SIZE="$(wc -c < "$DUMP_FILE" | tr -d ' ')"
head -c "$((FULL_SIZE / 4))" "$DUMP_FILE" > "$CORRUPT_COPY"
cp "$CORRUPT_COPY" "$BACKUP_DIR/corrupt.dump"
if attempt_restore /backups/corrupt.dump orszem_v2 --skip-checksum-verification; then
  cat "$WORKDIR/restore-attempt.log" >&2
  fail "restore script accepted a corrupted archive - this must never happen"
fi
log "OK: corrupted archive was refused."

log "negative test: a mismatched checksum must be refused ..."
mkdir -p "$BACKUP_DIR/badsum"
cp "$DUMP_FILE" "$BACKUP_DIR/badsum/"
echo "0000000000000000000000000000000000000000000000000000000000000000  $(basename "$DUMP_FILE")" \
  > "$BACKUP_DIR/badsum/$(basename "$DUMP_FILE").sha256"
if attempt_restore "/backups/badsum/$(basename "$DUMP_FILE")" orszem_v2; then
  cat "$WORKDIR/restore-attempt.log" >&2
  fail "restore script accepted a mismatched checksum - this must never happen"
fi
log "OK: mismatched checksum was refused."

TABLE_COUNT_BEFORE="$(docker exec "$TARGET_DB" psql -U orszem_v2 -d orszem_v2 -tAc \
  "select count(*) from information_schema.tables where table_schema='public'")"
[ "$TABLE_COUNT_BEFORE" -eq 0 ] || fail "target database was not empty after the first two negative tests (expected 0 tables, got $TABLE_COUNT_BEFORE)"
log "OK: target database is still untouched (0 tables) after both archive-integrity negative tests."

# Four shapes of "non-empty" (§1 of the correction brief) - each against its own small,
# otherwise-empty database, so the refusal reason is unambiguous. Each uses the real,
# valid, correctly-checksummed archive: the ONLY thing wrong is the pre-existing object.
#
# After the refused restore, the sentinel object itself is queried directly in its own
# catalog (pg_class for relations, pg_proc for the function) and its exact expected form
# asserted - "target left as found" is a claim, not a check, unless something actually
# re-reads the object and confirms it is still there, still the right kind, unchanged.
# information_schema.tables cannot do this: it does not even list sequences or functions,
# and a materialized view's presence there is an implementation detail, not the object's
# defining property (relkind is).

assert_relation_survived() { # assert_relation_survived DB NAME EXPECTED_RELKIND
  local db="$1" name="$2" expected_kind="$3" found
  found="$(docker exec "$TARGET_DB" psql -U orszem_v2 -d "$db" -tAc \
    "select relkind from pg_class where relname='${name}' and relnamespace='public'::regnamespace")"
  [ "$found" = "$expected_kind" ] \
    || fail "sentinel relation '${name}' (expected relkind '${expected_kind}') was not found unchanged in '${db}' after the refused restore - got relkind '${found:-<absent>}'"
}

assert_function_survived() { # assert_function_survived DB NAME
  local db="$1" name="$2" found
  found="$(docker exec "$TARGET_DB" psql -U orszem_v2 -d "$db" -tAc \
    "select count(*) from pg_proc where proname='${name}' and pronamespace='public'::regnamespace")"
  [ "$found" = "1" ] \
    || fail "sentinel function '${name}' was not found unchanged in '${db}' after the refused restore (found ${found:-0} matching pg_proc rows, expected 1)"
}

run_nonempty_negative_test() { # run_nonempty_negative_test LABEL DB_NAME DDL SENTINEL_NAME ASSERT_KIND
  # ASSERT_KIND is a pg_class relkind letter ('r' table, 'S' sequence, 'm' materialized
  # view) or the literal word "function".
  local label="$1" db="$2" ddl="$3" sentinel_name="$4" assert_kind="$5"
  log "negative test: a non-empty target (${label}) must be refused ..."
  docker exec "$TARGET_DB" psql -U orszem_v2 -d postgres -c "create database ${db}" >/dev/null
  docker exec "$TARGET_DB" psql -U orszem_v2 -d "$db" -c "$ddl" >/dev/null
  if attempt_restore "/backups/$(basename "$DUMP_FILE")" "$db"; then
    cat "$WORKDIR/restore-attempt.log" >&2
    fail "restore script accepted a non-empty target (${label}) - this must never happen"
  fi
  grep -qi "not empty" "$WORKDIR/restore-attempt.log" \
    || fail "restore was refused for ${label}, but not for the expected reason - see $WORKDIR/restore-attempt.log"
  grep -qi "$sentinel_name" "$WORKDIR/restore-attempt.log" \
    || fail "the refusal for ${label} did not name the offending object (expected to see '${sentinel_name}') - see $WORKDIR/restore-attempt.log"
  if [ "$assert_kind" = "function" ]; then
    assert_function_survived "$db" "$sentinel_name"
  else
    assert_relation_survived "$db" "$sentinel_name" "$assert_kind"
  fi
  log "OK: non-empty target (${label}) was refused, named correctly, and the sentinel object '${sentinel_name}' was directly re-queried and confirmed still present in its original form."
}

run_nonempty_negative_test "an ordinary table" orszem_v2_nonempty_table \
  "create table sentinel_not_in_archive (id int primary key)" sentinel_not_in_archive r
run_nonempty_negative_test "a sequence with no table at all" orszem_v2_nonempty_sequence \
  "create sequence sentinel_sequence" sentinel_sequence S
run_nonempty_negative_test "a materialized view" orszem_v2_nonempty_matview \
  "create materialized view sentinel_matview as select 1 as x" sentinel_matview m
run_nonempty_negative_test "a user-defined function" orszem_v2_nonempty_function \
  "create function sentinel_fn() returns int as \$\$ select 1 \$\$ language sql" sentinel_fn function

# --------------------------------------------------------------------- the real restore

RESTORE_DB=orszem_v2_restore_ok
log "creating a fresh, empty target database ($RESTORE_DB) for the real restore ..."
docker exec "$TARGET_DB" psql -U orszem_v2 -d postgres -c "create database ${RESTORE_DB}" >/dev/null

log "running scripts/orszem-restore.sh for real, against the fresh empty target ..."
attempt_restore "/backups/$(basename "$DUMP_FILE")" "$RESTORE_DB" \
  || { cat "$WORKDIR/restore-attempt.log" >&2; fail "the real restore failed"; }
cat "$WORKDIR/restore-attempt.log"

log "starting the real backend against the restored database ..."
ORSZEM_DB_URL="jdbc:postgresql://localhost:${TARGET_PORT}/${RESTORE_DB}" \
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

# ------------------------------------------------------------------------- constraint proof

log "comparing critical constraint/index definitions: source snapshot vs. restored database ..."
AFTER_CONSTRAINTS="$(sql_restore "$CONSTRAINT_QUERY")"
if [ "$BEFORE_CONSTRAINTS" != "$AFTER_CONSTRAINTS" ]; then
  echo "--- before (source) ---" >&2; echo "$BEFORE_CONSTRAINTS" >&2
  echo "--- after (restored) ---" >&2; echo "$AFTER_CONSTRAINTS" >&2
  fail "constraint/index definitions differ between the source snapshot and the restored database"
fi
log "OK: all $CONSTRAINT_ROW_COUNT critical constraint/index definitions are byte-identical after restore, including both legacy whole-line routing and the settlement+line routing table's PK, reference-pair FK, ServiceArea FK and lookup indexes."

# -------------------------------------------------------------- cross-phase invariant checks

MIGRATION_COUNT="$(sql_restore "select count(*) from flyway_schema_history where success = true")"
log "flyway_schema_history has $MIGRATION_COUNT successful row(s) after restore (no reapplication expected)."

for sid in "$ADMIN_SERVICE_ID" "$SERVICE_SERVICE_ID" "$MODERATOR_SERVICE_ID" "$DEACTIVATED_SERVICE_ID"; do
  FOUND="$(sql_restore "select service_id from users where service_id = '${sid}'")"
  [ "$FOUND" = "$sid" ] || fail "user $sid was not found in the restored database - Service IDs must survive unchanged"
done
AFTER_DEACTIVATED_STATUS="$(sql_restore "select status from users where service_id='${DEACTIVATED_SERVICE_ID}'")"
[ "$AFTER_DEACTIVATED_STATUS" = "DEACTIVATED" ] || fail "the fourth user's status changed across restore (was DEACTIVATED, now '$AFTER_DEACTIVATED_STATUS')"
log "OK: all four user Service IDs survived, and the deactivated user is still DEACTIVATED (never resurrected by restore)."

AFTER_INPROGRESS_ROW="$(sql_restore "select status,assigned_user_id from reports where public_id='${INPROGRESS_REPORT_ID}'")"
[ "$AFTER_INPROGRESS_ROW" = "$BEFORE_INPROGRESS_ROW" ] || fail "the IN_PROGRESS report's status/assignment changed across restore (before='$BEFORE_INPROGRESS_ROW' after='$AFTER_INPROGRESS_ROW')"
AFTER_ARCHIVED_ROW="$(sql_restore "select status,archived_at from reports where public_id='${ARCHIVED_REPORT_ID}'")"
[ "$AFTER_ARCHIVED_ROW" = "$BEFORE_ARCHIVED_ROW" ] || fail "the ARCHIVED report's status/archived_at changed across restore (before='$BEFORE_ARCHIVED_ROW' after='$AFTER_ARCHIVED_ROW')"
AFTER_REPORT_IDENTITY_ROW="$(sql_restore "select r.public_id, r.submitted_at, r.status, s.routing_status, s.service_area_id, s.routing_reason
  from reports r join report_routing_snapshots s on s.report_id = r.id
  where r.public_id = '${INPROGRESS_REPORT_ID}'")"
[ "$AFTER_REPORT_IDENTITY_ROW" = "$BEFORE_REPORT_IDENTITY_ROW" ] \
  || fail "the representative report's identity (public_id/submitted_at/status/routing snapshot) changed across restore (before='$BEFORE_REPORT_IDENTITY_ROW' after='$AFTER_REPORT_IDENTITY_ROW')"
AFTER_MODERATION_ROW="$(sql_restore "select e.id, e.reason, e.deleted_at, e.restored_at is null, e.deleted_by_user_id
  from report_moderation_episodes e join reports r on r.id = e.report_id
  where r.public_id = '${MODERATED_REPORT_ID}'")"
[ "$AFTER_MODERATION_ROW" = "$BEFORE_MODERATION_ROW" ] \
  || fail "the moderation episode (id/reason/deleted_at/open-state/actor) changed across restore (before='$BEFORE_MODERATION_ROW' after='$AFTER_MODERATION_ROW')"
log "OK: IN_PROGRESS/ARCHIVED workflow states, the representative report's full identity (public_id/submitted_at/status/routing snapshot), and the moderation episode's full identity (id/reason/timestamp/state/actor) are all byte-identical after restore."

AFTER_AUDIT_EVENT_ROW="$(sql_restore "select a.id, a.event_type, a.created_at
  from audit_events a join reports r on r.id = a.target_id
  where a.event_type = 'REPORT_MODERATION_DELETED' and r.public_id = '${MODERATED_REPORT_ID}'")"
[ "$AFTER_AUDIT_EVENT_ROW" = "$BEFORE_AUDIT_EVENT_ROW" ] \
  || fail "the concrete REPORT_MODERATION_DELETED audit event (id/type/timestamp) changed or disappeared across restore (before='$BEFORE_AUDIT_EVENT_ROW' after='$AFTER_AUDIT_EVENT_ROW')"
AFTER_AUDIT_COUNT="$(sql_restore "select count(*) from audit_events")"
[ "$AFTER_AUDIT_COUNT" -ge "$BEFORE_AUDIT_COUNT" ] || fail "audit_events row count dropped across restore (before=$BEFORE_AUDIT_COUNT after=$AFTER_AUDIT_COUNT)"
log "OK: the concrete REPORT_MODERATION_DELETED audit event's id/type/timestamp survived unchanged, and the total row count is intact too ($BEFORE_AUDIT_COUNT rows before backup, $AFTER_AUDIT_COUNT after restore - the concurrent write may add at most one more, never fewer)."

AFTER_REFERENCE_ROW="$(sql_restore "select dataset_version,is_current from reference_dataset_imports where is_current")"
[ "$AFTER_REFERENCE_ROW" = "$BEFORE_REFERENCE_ROW" ] || fail "the current reference-import row changed across restore (before='$BEFORE_REFERENCE_ROW' after='$AFTER_REFERENCE_ROW')"
AFTER_AREA_MAPPING_ROW="$(sql_restore "select service_area_id from service_area_railway_lines where railway_line_id='${LINE_900_ID}'")"
[ "$AFTER_AREA_MAPPING_ROW" = "$BEFORE_AREA_MAPPING_ROW" ] || fail "the ServiceArea<->RailwayLine mapping changed across restore (before='$BEFORE_AREA_MAPPING_ROW' after='$AFTER_AREA_MAPPING_ROW')"
log "OK: reference-import revision state and the ServiceArea/RailwayLine mapping are unchanged after restore."

log "verifying the concurrent-write snapshot invariant against the restored database ..."
CONCURRENT_REPORT_PRESENT="$(sql_restore "select exists(select 1 from reports where client_submission_id = '${CONCURRENT_SUBMISSION_ID}')")"
CONCURRENT_SNAPSHOT_PRESENT="$(sql_restore "select exists(select 1 from report_routing_snapshots s join reports r on r.id = s.report_id
                 where r.client_submission_id = '${CONCURRENT_SUBMISSION_ID}')")"
if [ "$CONCURRENT_REPORT_PRESENT" != "$CONCURRENT_SNAPSHOT_PRESENT" ]; then
  fail "PARTIAL STATE DETECTED for the concurrent report: reports row present=$CONCURRENT_REPORT_PRESENT, report_routing_snapshots row present=$CONCURRENT_SNAPSHOT_PRESENT - these must always match"
fi
if [ "$CONCURRENT_REPORT_PRESENT" = "t" ]; then
  log "the concurrent write landed INSIDE the backup snapshot - report and its routing snapshot are both present, consistently. (Unexpected given the barrier's timing, but explicitly still a valid, accepted outcome - not a failure.)"
else
  log "OK: the concurrent write landed OUTSIDE the backup snapshot, as the barrier's timing predicts - report and its routing snapshot are both absent, consistently, after restore."
fi

log "verifying the Public capability invariant (§22/§97) against the restored database ..."
LOOKUP_AFTER="$(curl -s -o "$WORKDIR/lookup-after.json" -w '%{http_code}' \
  "http://127.0.0.1:${BACKEND_PORT}/api/v1/public/reports/${CAP_REPORT_ID}" \
  -H "X-Orszem-Report-Access: ${CAPABILITY}")"
[ "$LOOKUP_AFTER" = "200" ] || { cat "$WORKDIR/lookup-after.json" >&2; fail "capability lookup after restore returned $LOOKUP_AFTER, expected 200 - the capability did not survive the snapshot"; }
log "OK: the same capability still resolves the same report after restore."

WRONG_LOOKUP="$(curl -s -o "$WORKDIR/lookup-wrong.json" -w '%{http_code}' \
  "http://127.0.0.1:${BACKEND_PORT}/api/v1/public/reports/${CAP_REPORT_ID}" \
  -H "X-Orszem-Report-Access: ${WRONG_CAPABILITY}")"
[ "$WRONG_LOOKUP" = "404" ] || fail "a wrong capability against the restored database returned $WRONG_LOOKUP, expected generic 404"
grep -qi "REPORT_NOT_FOUND" "$WORKDIR/lookup-wrong.json" \
  || fail "wrong-capability response body did not carry REPORT_NOT_FOUND: $(cat "$WORKDIR/lookup-wrong.json")"
log "OK: a wrong capability still returns the generic REPORT_NOT_FOUND 404 after restore."

log "verifying service-session restore behaviour ..."
STATUS="$(http_call GET /api/v1/service/reports/new "" "$VALID_SESSION_BEARER")"
[ "$STATUS" = "200" ] || { cat "$WORKDIR/http.json" >&2; fail "the still-valid session's access token no longer authenticates after restore (http $STATUS) - session state did not survive the snapshot"; }
log "OK: the session that was valid before the backup still authenticates against the restored database."
STATUS="$(http_call GET /api/v1/service/reports/new "" "$REVOKED_SESSION_BEARER")"
[ "$STATUS" = "401" ] || { cat "$WORKDIR/http.json" >&2; fail "the already-revoked session was NOT rejected after restore (http $STATUS) - a restore must never resurrect a revoked session"; }
log "OK: the session that was already revoked before the backup remains revoked after restore."

kill "$BACKEND_PID"; wait "$BACKEND_PID" 2>/dev/null || true; BACKEND_PID=""

log "all checks passed."
