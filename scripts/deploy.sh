#!/usr/bin/env bash
# Őrszem Demo v1 — remote deploy / update.
#
#   scripts/deploy.sh              # update to the current checked-out revision
#   scripts/deploy.sh --pull       # git pull --ff-only first, then deploy
#   scripts/deploy.sh --no-build   # reuse the existing image (fast restart)
#
# Non-destructive: never touches the PostgreSQL volume, never `git reset --hard`.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

COMPOSE_FILE="infra/compose/docker-compose.prod.yml"
ENV_FILE="${ORSZEM_ENV_FILE:-$ROOT_DIR/.env}"
DO_PULL=0
DO_BUILD=1

for arg in "$@"; do
  case "$arg" in
    --pull) DO_PULL=1 ;;
    --no-build) DO_BUILD=0 ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    *) echo "Unknown argument: $arg" >&2; exit 2 ;;
  esac
done

[ -f "$ENV_FILE" ] || { echo "Missing $ENV_FILE — copy infra/compose/.env.example and fill it in." >&2; exit 1; }

dc() { docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"; }

if [ "$DO_PULL" -eq 1 ]; then
  echo "==> git fetch && git pull --ff-only"
  git fetch --all --prune
  git pull --ff-only
fi

IMAGE_NAME="$(grep -E '^ORSZEM_API_IMAGE=' "$ENV_FILE" | cut -d= -f2-)"
IMAGE_NAME="${IMAGE_NAME:-orszem-api:demo}"
IMAGE_REPO="${IMAGE_NAME%%:*}"
PREV_IMAGE_ID="$(docker image inspect --format '{{.Id}}' "$IMAGE_NAME" 2>/dev/null || true)"
REVISION="$(git rev-parse --short HEAD)"
echo "==> Deploying revision $REVISION (image $IMAGE_NAME)"

if [ "$DO_BUILD" -eq 1 ]; then
  echo "==> Building API image (this takes several minutes on 1 OCPU)"
  dc build api
  NEW_IMAGE_ID="$(docker image inspect --format '{{.Id}}' "$IMAGE_NAME" 2>/dev/null || true)"
  # Keep a rollback target: the revision tag, and the image this deploy replaced.
  if [ -n "$PREV_IMAGE_ID" ] && [ "$PREV_IMAGE_ID" != "$NEW_IMAGE_ID" ]; then
    docker tag "$PREV_IMAGE_ID" "$IMAGE_REPO:previous" && echo "    tagged $IMAGE_REPO:previous"
  elif [ -n "$PREV_IMAGE_ID" ]; then
    echo "    image unchanged; $IMAGE_REPO:previous left as is"
  fi
  docker tag "$IMAGE_NAME" "$IMAGE_REPO:$REVISION" && echo "    tagged $IMAGE_REPO:$REVISION"
fi

echo "==> docker compose up -d"
# `docker compose up -d` decides on recreation from the service *config*, which
# contains the image name but not its resolved digest. Both a rebuild and a
# rollback move `$IMAGE_NAME` to a different image under the same tag, so without
# an explicit force the old container — and the old code — would keep running.
# The API is stateless; recreating it costs a few seconds and is always correct.
dc up -d --remove-orphans --force-recreate api
dc up -d --remove-orphans

echo "==> Waiting for health"
"$ROOT_DIR/scripts/health-check.sh"
