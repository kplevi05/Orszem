#!/usr/bin/env bash
#
# Őrszem V2 (live stack) — logical backup and disposable restore drill.
#
#   ./backup.sh backup            pg_dump -Fc of orszem_v2 -> /home/opc/backups/v2/orszem_v2_<utc>.dump + .sha256 (mode 600)
#   ./backup.sh drill [dump]      restore into a throwaway, network-less PostgreSQL container and compare EVERY public table
#                                 (row count + md5 over the ordered row text) with the live database; then remove the container
#
# The live database is only read. The drill never touches it and has no network.
set -euo pipefail
umask 077
DIR=/home/opc/backups/v2; mkdir -p "$DIR"; chmod 700 "$DIR"
cd "$(dirname "$0")"
live() { docker exec orszem-v2-db psql -U orszem_v2 -d orszem_v2 -Atc "$1"; }

case "${1:-}" in
  backup)
    F="$DIR/orszem_v2_$(date -u +%Y%m%dT%H%M%SZ).dump"
    docker exec orszem-v2-db pg_dump -Fc -U orszem_v2 -d orszem_v2 > "$F"
    [ -s "$F" ] || { echo "ERROR: empty dump" >&2; rm -f "$F"; exit 1; }
    ( cd "$DIR" && sha256sum "$(basename "$F")" > "$(basename "$F").sha256" && sha256sum -c "$(basename "$F").sha256" )
    echo "path: $F"; ls -l "$F" | awk '{print "bytes:", $5}'; cat "$F.sha256"
    ;;
  drill)
    F="${2:-$(ls -1t "$DIR"/*.dump | head -1)}"
    ( cd "$(dirname "$F")" && sha256sum -c "$(basename "$F").sha256" ) || { echo "ERROR: checksum mismatch" >&2; exit 1; }
    DP="$(head -c 24 /dev/urandom | base64 | tr -dc A-Za-z0-9 | head -c 20)"
    trap 'docker rm -f orszem-v2-restore-drill >/dev/null 2>&1 || true' EXIT
    docker run -d --rm --name orszem-v2-restore-drill --network none -e POSTGRES_PASSWORD="$DP" -e POSTGRES_DB=drill postgres:16-alpine >/dev/null
    for i in $(seq 1 40); do docker exec orszem-v2-restore-drill pg_isready -U postgres -d drill >/dev/null 2>&1 && break; sleep 2; done
    docker exec -i orszem-v2-restore-drill pg_restore -U postgres -d drill --no-owner --exit-on-error < "$F"
    echo "restored $(basename "$F") into a disposable container (no network)"
    bad=0; printf '%-32s %6s %6s  %s\n' table live restored result
    for t in $(live "select tablename from pg_tables where schemaname='public' order by 1"); do
      q="select count(*) || ' ' || coalesce(md5(string_agg(x::text, '|' order by x::text)),'empty') from $t x"
      a="$(live "$q")"; b="$(docker exec orszem-v2-restore-drill psql -U postgres -d drill -Atc "$q")"
      if [ "$a" = "$b" ]; then r="identical"; else r="DIFFERS"; bad=1; fi
      printf '%-32s %6s %6s  %s\n' "$t" "${a%% *}" "${b%% *}" "$r"
    done
    [ "$bad" = 0 ] && echo "RESULT: every table restored with identical content" || { echo "RESULT: MISMATCH" >&2; exit 1; }
    ;;
  *) sed -n '3,9p' "$0"; exit 2 ;;
esac
