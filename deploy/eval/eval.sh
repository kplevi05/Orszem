#!/usr/bin/env bash
#
# Őrszem V2 — runnable EVALUATION environment driver (not production).
#
# One isolated Docker Compose project (orszem-eval) with PostgreSQL, the V2 backend JAR and Caddy serving
# the Public Web on 127.0.0.1 only. It shares nothing with the V1 pilot or any server. Synthetic data only.
#
#   deploy/eval/eval.sh init  <backend.jar> <web-dist-dir>   create the local config and stage the artefacts
#   deploy/eval/eval.sh up | down | status | logs
#   deploy/eval/eval.sh cli <orszem-admin args...>           the supported maintenance CLI against the eval DB
#   deploy/eval/eval.sh backup                               pg_dump -Fc of the eval DB + SHA-256
#   deploy/eval/eval.sh restore-drill [dump]                 restore into a disposable DB, compare, drop it
#   deploy/eval/eval.sh destroy                              remove the containers AND the eval data volume
#
# Secrets: the database password is generated on `init` and lives only in $ORSZEM_EVAL_HOME/eval.env
# (default ~/.orszem/eval), outside the repository. Nothing secret is ever committed or printed.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
EVAL_HOME="${ORSZEM_EVAL_HOME:-$HOME/.orszem/eval}"
ENV_FILE="$EVAL_HOME/eval.env"
export MSYS_NO_PATHCONV=1

winpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else echo "$1"; fi; }

dc() { docker compose --env-file "$(winpath "$ENV_FILE")" -f "$(winpath "$HERE/docker-compose.yml")" "$@"; }

require_env() { [ -f "$ENV_FILE" ] || { echo "ERROR: $ENV_FILE not found. Run: $0 init <backend.jar> <web-dist-dir>" >&2; exit 1; }; }

load_env() { require_env; set -a; . "$ENV_FILE"; set +a; }

db_exec() { docker exec -i -e PGPASSWORD="$ORSZEM_EVAL_DB_PASSWORD" orszem-eval-db "$@"; }

case "${1:-}" in
  init)
    JAR="${2:?usage: init <backend.jar> <web-dist-dir>}"; WEB="${3:?usage: init <backend.jar> <web-dist-dir>}"
    [ -f "$JAR" ] || { echo "ERROR: no such jar: $JAR" >&2; exit 1; }
    [ -f "$WEB/index.html" ] || { echo "ERROR: $WEB has no index.html (build the Public Web first)" >&2; exit 1; }
    mkdir -p "$EVAL_HOME/run/web" "$EVAL_HOME/backups"
    cp -f "$JAR" "$EVAL_HOME/run/backend.jar"
    rm -rf "$EVAL_HOME/run/web" && mkdir -p "$EVAL_HOME/run/web" && cp -R "$WEB"/. "$EVAL_HOME/run/web/"
    if [ ! -f "$ENV_FILE" ]; then
      pw="$(node -e 'process.stdout.write(require("crypto").randomBytes(24).toString("hex"))')"
      umask 077
      cat > "$ENV_FILE" <<ENV
ORSZEM_EVAL_DB_PASSWORD=$pw
ORSZEM_EVAL_DB_PORT=55432
ORSZEM_EVAL_HTTP_PORT=18080
ORSZEM_EVAL_RUN=$(winpath "$EVAL_HOME/run")
ENV
      echo "Created $ENV_FILE (mode 600; contains the generated database password; never commit)."
    else
      echo "Kept the existing $ENV_FILE (database password unchanged)."
    fi
    echo "Staged backend.jar sha256: $(sha256sum "$EVAL_HOME/run/backend.jar" | cut -d' ' -f1)"
    ;;
  up)     require_env; dc up -d ;;
  down)   require_env; dc down ;;          # keeps the data volume
  status)
    load_env
    docker ps -a --filter "name=orszem-eval-" --format '{{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'
    echo "--- edge check"; curl -s -o /dev/null -w "GET /api/v1/meta -> %{http_code}\n" "http://127.0.0.1:${ORSZEM_EVAL_HTTP_PORT}/api/v1/meta" || true
    curl -s "http://127.0.0.1:${ORSZEM_EVAL_HTTP_PORT}/api/v1/meta" || true; echo
    ;;
  logs)   require_env; shift; dc logs --tail 200 "$@" ;;
  cli)
    load_env; shift
    export ORSZEM_DB_URL="jdbc:postgresql://127.0.0.1:${ORSZEM_EVAL_DB_PORT}/orszem_eval"
    export ORSZEM_DB_USERNAME=orszem_eval ORSZEM_DB_PASSWORD="$ORSZEM_EVAL_DB_PASSWORD"
    export ORSZEM_BACKEND_JAR="$(winpath "$EVAL_HOME/run/backend.jar")"
    cd "$ROOT" && exec ./scripts/orszem-admin "$@"
    ;;
  backup)
    load_env
    ts="$(date -u +%Y%m%dT%H%M%SZ)"; out="$EVAL_HOME/backups/orszem_eval_$ts.dump"
    db_exec pg_dump -Fc -U orszem_eval -d orszem_eval > "$out"
    [ -s "$out" ] || { echo "ERROR: empty dump" >&2; rm -f "$out"; exit 1; }
    (cd "$EVAL_HOME/backups" && sha256sum "$(basename "$out")" > "$(basename "$out").sha256")
    echo "backup: $out"; cat "$out.sha256"; ls -l "$out" | awk '{print "bytes:", $5}'
    ;;
  restore-drill)
    load_env
    dump="${2:-$(ls -1t "$EVAL_HOME"/backups/*.dump | head -1)}"
    [ -f "$dump" ] || { echo "ERROR: no dump found" >&2; exit 1; }
    (cd "$(dirname "$dump")" && sha256sum -c "$(basename "$dump").sha256") || { echo "ERROR: checksum mismatch, refusing" >&2; exit 1; }
    drill="orszem_eval_drill_$(date -u +%H%M%S)"
    # Only the disposable database is created, written and dropped; the live eval database is only read.
    db_exec psql -U orszem_eval -d postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE $drill" >/dev/null
    trap 'db_exec psql -U orszem_eval -d postgres -c "DROP DATABASE IF EXISTS '"$drill"'" >/dev/null 2>&1 || true' EXIT
    db_exec pg_restore -U orszem_eval -d "$drill" --no-owner --exit-on-error < "$dump"
    echo "restored $(basename "$dump") into disposable database $drill"
    tables="flyway_schema_history users auth_sessions refresh_tokens user_service_areas service_areas service_area_railway_lines railway_lines settlements settlement_railway_lines reference_dataset_imports reports report_routing_snapshots report_assignments report_moderation_episodes audit_events"
    # Row counts AND a content digest of every row (md5 over the ordered row text), live vs restored. The live
    # database is only read; run this while nothing is writing to it, or the digests will legitimately differ.
    digest() { db_exec psql -U orszem_eval -d "$1" -Atc "select count(*) || ' ' || coalesce(md5(string_agg(t::text, '|' order by t::text)), 'empty') from $2 t" 2>/dev/null || echo "n/a n/a"; }
    printf '%-34s %6s %6s  %s\n' table live restored digest
    bad=0
    for t in $tables; do
      live="$(digest orszem_eval "$t")"; rest="$(digest "$drill" "$t")"
      if [ "$live" = "$rest" ]; then verdict="identical (${live#* })"; else verdict="DIFFERS"; bad=1; fi
      printf '%-34s %6s %6s  %s\n' "$t" "${live%% *}" "${rest%% *}" "$verdict"
    done
    [ "$bad" = 0 ] && echo "RESULT: every table restored with identical content" || { echo "RESULT: MISMATCH" >&2; exit 1; }
    ;;
  destroy) require_env; dc down -v ;;
  *) sed -n '2,20p' "$0"; exit 1 ;;
esac
