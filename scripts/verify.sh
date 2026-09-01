#!/usr/bin/env bash
# Full local verification: OpenAPI lint + backend build & tests.
# Android build is verified separately (needs the Android SDK): see README.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

echo "==> OpenAPI contract lint"
npx --yes @redocly/cli@latest lint --config contracts/openapi/redocly.yaml contracts/openapi/orszem-v1.yaml

echo "==> Backend build & tests"
if command -v docker >/dev/null 2>&1 && ! (cd services/api && ./gradlew -q help >/dev/null 2>&1 && java -version >/dev/null 2>&1); then
  # Local dev machine without a usable JDK / with an incompatible Docker Desktop.
  scripts/dev-backend-test.sh build
else
  (cd services/api && ./gradlew build)
fi

echo "==> OK"
