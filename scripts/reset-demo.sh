#!/usr/bin/env bash
set -euo pipefail

: "${DATABASE_URL:?A DATABASE_URL környezeti változó kötelező.}"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEMO_DIR="$ROOT_DIR/services/api/src/main/resources/db/demo"

echo "Őrszem Demo v1 reset..."
psql "$DATABASE_URL" -v ON_ERROR_STOP=1   -f "$DEMO_DIR/000_reset_demo.sql"   -f "$DEMO_DIR/010_demo_service_user.sql"   -f "$DEMO_DIR/020_demo_reports.sql"

echo "Demo reset kész."
