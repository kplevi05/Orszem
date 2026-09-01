#!/usr/bin/env bash
# Seed the demo baseline (does not truncate first). For a clean baseline use
# scripts/reset-demo.sh instead.
set -euo pipefail

: "${DATABASE_URL:?A DATABASE_URL környezeti változó kötelező (pl. postgresql://orszem:orszem@localhost:5432/orszem).}"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEMO_DIR="$ROOT_DIR/services/api/src/main/resources/db/demo"

echo "Őrszem Demo v1 seed..."
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 \
  -f "$DEMO_DIR/010_demo_service_user.sql" \
  -f "$DEMO_DIR/020_demo_reports.sql"
echo "Demo seed kész."
