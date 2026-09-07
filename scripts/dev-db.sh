#!/usr/bin/env bash
# Start a throwaway PostgreSQL for local backend development.
#
# Development only. The password is a local fixture and is deliberately obvious; it must
# never be reused anywhere else, and no production configuration reads it.
# Integration tests do NOT need this: they start their own container via Testcontainers.
set -euo pipefail

NAME="${ORSZEM_DEV_DB_NAME:-orszem-v2-dev-db}"
PORT="${ORSZEM_DEV_DB_PORT:-5432}"
IMAGE="postgres:16-alpine"

if ! docker info >/dev/null 2>&1; then
  echo "Docker is not running." >&2
  exit 1
fi

if [ "$(docker ps -aq -f "name=^${NAME}$")" ]; then
  docker start "$NAME" >/dev/null
  echo "Started existing container ${NAME}."
else
  docker run -d --name "$NAME" \
    -e POSTGRES_DB=orszem_v2 \
    -e POSTGRES_USER=orszem_v2 \
    -e POSTGRES_PASSWORD=localdev \
    -e TZ=UTC -e PGTZ=UTC \
    -p "127.0.0.1:${PORT}:5432" \
    "$IMAGE" >/dev/null
  echo "Created container ${NAME}."
fi

cat <<ENV

Export these before running the backend:

  export ORSZEM_DB_URL=jdbc:postgresql://localhost:${PORT}/orszem_v2
  export ORSZEM_DB_USERNAME=orszem_v2
  export ORSZEM_DB_PASSWORD=localdev

Stop with:  docker stop ${NAME}
Remove with: docker rm -f ${NAME}
ENV
