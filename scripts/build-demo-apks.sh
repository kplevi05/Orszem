#!/usr/bin/env bash
# Őrszem Demo v1.1 — build both pilot release APKs against the public HTTPS API.
#
#   scripts/build-demo-apks.sh                              # pilot default
#   ORSZEM_API_BASE_URL=https://example.org/ scripts/build-demo-apks.sh
#
# Requires JDK 21 and the Android SDK (platform 35 + build-tools 35.0.0) — the
# same set .github/workflows/android.yml provisions.
#
# Local development is unaffected: without -PORSZEM_API_BASE_URL the build keeps
# its emulator default (http://10.0.2.2:8080/).
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT_DIR/apps/android"
OUT_DIR="${ORSZEM_APK_OUT_DIR:-$ANDROID_DIR/build/demo-apks}"

# OrszemApi declares the "api/v1/..." path segments itself, so this is the origin
# only — matching the http://10.0.2.2:8080/ development default.
BASE_URL="${ORSZEM_API_BASE_URL:-https://129-159-31-175.sslip.io/}"

case "$BASE_URL" in
  https://*) ;;
  *) echo "The Demo build must use an https:// base URL (got: $BASE_URL)" >&2; exit 1 ;;
esac
case "$BASE_URL" in
  */) ;;
  *) echo "The base URL must end with a trailing slash: $BASE_URL" >&2; exit 1 ;;
esac
case "$BASE_URL" in
  *api/v1*) echo "The base URL must be the origin only: $BASE_URL" >&2; exit 1 ;;
esac

echo "==> Building Demo v1.1 APKs against $BASE_URL"
cd "$ANDROID_DIR"
./gradlew --no-daemon -PORSZEM_API_BASE_URL="$BASE_URL" \
  :public-app:assembleRelease :service-app:assembleRelease

mkdir -p "$OUT_DIR"
cp "$ANDROID_DIR/public-app/build/outputs/apk/release/public-app-release.apk" \
   "$OUT_DIR/orszem-public-demo-v1.1.apk"
cp "$ANDROID_DIR/service-app/build/outputs/apk/release/service-app-release.apk" \
   "$OUT_DIR/orszem-szolgalat-demo-v1.1.apk"

echo "==> Őrszem (public):   $OUT_DIR/orszem-public-demo-v1.1.apk"
echo "==> Őrszem Szolgálat:  $OUT_DIR/orszem-szolgalat-demo-v1.1.apk"
echo
echo "Signing certificate (compare with the distributed Demo v1 APK before shipping"
echo "an in-place upgrade — see docs/deployment/ORACLE_DEPLOYMENT.md):"
BUILD_TOOLS="$(ls -d "${ANDROID_HOME:-$ANDROID_SDK_ROOT}"/build-tools/* 2>/dev/null | sort -V | tail -1 || true)"
if [ -n "$BUILD_TOOLS" ] && [ -x "$BUILD_TOOLS/apksigner" ]; then
  "$BUILD_TOOLS/apksigner" verify --print-certs "$OUT_DIR/orszem-public-demo-v1.1.apk" \
    | grep -iE "SHA-256 digest|Subject" || true
else
  echo "  (apksigner not found; run: apksigner verify --print-certs <apk>)"
fi
