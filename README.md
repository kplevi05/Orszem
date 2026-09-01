# Őrszem Demo v1

Anonymous rail-safety reporting (**Public App**) → shared backend → service case
handling and analytics (**Service App**).

`Public App → API → PostgreSQL → Service App → esetkezelés → Analytics`

The canonical requirements are in `MASTER_PROMPT_DEMO_V1_FINAL.md` and the
documents it references. Implementation status: `docs/implementation/BUILD_STATUS.md`.

## Repository layout

| Path | Contents |
| --- | --- |
| `contracts/openapi/orszem-v1.yaml` | Canonical HTTP contract (OpenAPI 3.1) |
| `contracts/catalog/event-types.demo-v1.json` | Machine-readable event catalog |
| `services/api/` | Kotlin / Spring Boot modular-monolith backend |
| `apps/android/` | `:public-app` + `:service-app` (two separate APKs) + shared `core/*` |
| `infra/` | Docker Compose (PostgreSQL + API) and the API Dockerfile |
| `scripts/` | `verify.sh`, `reset-demo.sh`, `seed-demo.sh`, `dev-backend-test.sh` |
| `docs/` | Product / UX / architecture / testing / implementation docs, ADRs |

## Quick start (local)

### 1. Backend + database

```bash
export ORSZEM_JWT_SECRET="$(head -c 48 /dev/urandom | base64)"
docker compose -f infra/compose/docker-compose.yml up -d db
cd services/api && ./gradlew bootRun --args='--spring.profiles.active=local'
```

or run everything (DB + API image):

```bash
export ORSZEM_JWT_SECRET="$(head -c 48 /dev/urandom | base64)"
docker compose -f infra/compose/docker-compose.yml up -d
```

The `local`/`demo` profiles load the event catalog and enable the demo seed.

### 2. Demo baseline

```bash
export DATABASE_URL="postgresql://orszem:orszem@localhost:5432/orszem"
scripts/reset-demo.sh
```

or, against a running API in the `demo` profile:

```bash
curl -X POST -H "X-Demo-Reset-Token: $ORSZEM_DEMO_RESET_TOKEN" \
  http://localhost:8080/api/v1/admin/demo/reset
```

Baseline after reset: **120 reports** (8 NEW / 6 IN_PROGRESS / 106 ARCHIVED),
16 "today" reports, one `demo.service` user.

### 3. Android apps

```bash
cd apps/android
./gradlew :public-app:assembleRelease :service-app:assembleRelease
./gradlew testDebugUnitTest        # ViewModel / repository / validation tests
```

APK outputs:
`apps/android/public-app/build/outputs/apk/release/public-app-release.apk`,
`apps/android/service-app/build/outputs/apk/release/service-app-release.apk`.

Point the apps at the backend with `-PORSZEM_API_BASE_URL=...`
(default `http://10.0.2.2:8080/` for the Android emulator).

### 4. Demo login

- username: `demo.service`
- password: `OrszemDemo!2026`

Demo-only fixture. The database stores only an Argon2id hash.

## Remote demo deployment (Oracle Cloud)

The public HTTPS demo backend (Caddy + Spring Boot API + PostgreSQL on Docker
Compose, ARM64) is described end-to-end in
[`docs/deployment/ORACLE_DEPLOYMENT.md`](docs/deployment/ORACLE_DEPLOYMENT.md):
first deployment, `.env` secrets, update, health verification and rollback.

```bash
# on the server
cd /home/opc/apps/orszem
scripts/deploy.sh --pull       # git pull --ff-only + build + up -d + health check
scripts/health-check.sh        # verification only
```

Demo APKs against the public endpoint:

```bash
cd apps/android
./gradlew :public-app:assembleRelease :service-app:assembleRelease \
  -PORSZEM_API_BASE_URL=https://<public-host>/
```

## Verification

```bash
scripts/verify.sh                      # OpenAPI lint + backend build & tests
cd services/api && ./gradlew build     # backend only (needs Docker for Testcontainers)
```

See `docs/testing/DEMO_V1_ACCEPTANCE_TESTS.md` for the acceptance contract and
`docs/DEMO_PRESENTATION.md` for the AT-050 presentation walkthrough.
