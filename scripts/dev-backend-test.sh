#!/usr/bin/env bash
# Run the backend Gradle build (compile + unit + Testcontainers integration tests)
# in a hermetic Docker-in-Docker environment.
#
# Why: on some Docker Desktop versions the docker-java client used by
# Testcontainers cannot talk to the Desktop API gateway directly. Running the
# build against a real dockerd (dind) sidesteps that entirely and matches how
# CI executes. On plain Linux CI you can just run `./gradlew build` instead.
#
# This script keeps a long-lived build container so the Gradle daemon stays warm
# between runs.
#
# Usage:  scripts/dev-backend-test.sh [gradle args...]   (default: build)
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
API_DIR="$ROOT_DIR/services/api"
NETWORK="orszem-ci"
DIND="orszem-dind"
BUILDER="orszem-build"
GRADLE_IMAGE="gradle:8.14-jdk21"
GRADLE_ARGS=("${@:-build}")

docker network inspect "$NETWORK" >/dev/null 2>&1 || docker network create "$NETWORK" >/dev/null
docker volume create orszem-gradle >/dev/null

if ! docker exec "$DIND" docker info >/dev/null 2>&1; then
  docker rm -f "$DIND" >/dev/null 2>&1 || true
  docker run -d --privileged --name "$DIND" --network "$NETWORK" --network-alias dind \
    -e DOCKER_TLS_CERTDIR="" docker:27-dind --host=tcp://0.0.0.0:2375 >/dev/null
  echo "Waiting for dind..."
  for _ in $(seq 1 40); do sleep 3; docker exec "$DIND" docker info >/dev/null 2>&1 && break; done
fi

if ! docker exec "$BUILDER" true >/dev/null 2>&1; then
  docker rm -f "$BUILDER" >/dev/null 2>&1 || true
  WINPATH="$(cd "$API_DIR" && (pwd -W 2>/dev/null || pwd))"
  MSYS_NO_PATHCONV=1 docker run -d --name "$BUILDER" --network "$NETWORK" \
    -v "$WINPATH:/app" -v orszem-gradle:/root/.gradle \
    -e DOCKER_HOST=tcp://dind:2375 \
    -e TESTCONTAINERS_HOST_OVERRIDE=dind \
    -e TESTCONTAINERS_RYUK_DISABLED=true \
    -w /app --user root "$GRADLE_IMAGE" sleep infinity >/dev/null
fi

docker exec "$BUILDER" ./gradlew "${GRADLE_ARGS[@]}"
