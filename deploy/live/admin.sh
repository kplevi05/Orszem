#!/usr/bin/env bash
#
# Őrszem V2 (live stack) — maintenance CLI, run as a one-off container on the V2 network.
#
#   ./admin.sh create-super-admin
#   ./admin.sh reset-super-admin-password SZ-123456
#   ./admin.sh reference-validate|reference-diff|reference-import /datasets/<dir>     (datasets/ is mounted read-only)
#
# The same JAR and the same commands as scripts/orszem-admin; only the launcher differs, because the V2 database is
# reachable only on the private orszem_v2_net network. The database password is read from .env and passed by
# reference (never on a command line). A generated credential is printed once to this terminal and stored nowhere.
set -euo pipefail
cd "$(dirname "$0")"
set -a; . ./.env; set +a
export ORSZEM_DB_PASSWORD="$ORSZEM_V2_DB_PASSWORD"

case "${1:-}" in
  create-super-admin)                  ARGS=(--orszem.maintenance.action=create-super-admin) ;;
  reset-super-admin-password)          ARGS=(--orszem.maintenance.action=reset-super-admin-password --orszem.maintenance.service-id="${2:?service id required}") ;;
  reference-validate|reference-diff|reference-import) ARGS=(--orszem.maintenance.action="$1" --orszem.maintenance.dataset-dir="${2:?dataset dir required}") ;;
  *) sed -n '3,9p' "$0"; exit 2 ;;
esac

exec docker run --rm -i --network orszem_v2_net \
  -e ORSZEM_DB_URL=jdbc:postgresql://db:5432/orszem_v2 -e ORSZEM_DB_USERNAME=orszem_v2 -e ORSZEM_DB_PASSWORD \
  -e JAVA_TOOL_OPTIONS="-Duser.timezone=UTC -Xmx256m -XX:+UseSerialGC" \
  -v "$PWD/run/backend.jar:/app/backend.jar:ro" -v "$PWD/datasets:/datasets:ro" \
  eclipse-temurin:21-jre java -jar /app/backend.jar --spring.main.web-application-type=none --spring.main.banner-mode=off "${ARGS[@]}"
