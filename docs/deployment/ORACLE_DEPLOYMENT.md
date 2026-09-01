# Őrszem Demo v1 — Oracle Cloud deployment runbook

Everything needed to (re)deploy the Demo v1 backend to the Oracle Cloud VM
without any chat history.

```
Internet ──HTTPS :443──▶ Caddy (automatic TLS) ──▶ api:8080 ──▶ db:5432
                             (only published ports: 80, 443)
```

## 1. Target

| | |
| --- | --- |
| Provider | Oracle Cloud Infrastructure |
| Shape | `VM.Standard.A1.Flex`, 1 OCPU / 6 GB RAM, ARM64 (aarch64) |
| OS | Oracle Linux 9 |
| Public IPv4 | `129.159.31.175` |
| SSH user | `opc` |
| Deployment directory | `/home/opc/apps/orszem` |
| Open ports | TCP 22, 80, 443 (OCI security list **and** `firewalld`) |
| Never exposed | PostgreSQL 5432, Spring Boot 8080, Docker daemon |

Prerequisites on the VM: Git, Docker Engine, Docker Compose plugin.
All images used (`postgres:16-alpine`, `eclipse-temurin:21-jre-jammy`,
`gradle:8.14-jdk21`, `caddy:2-alpine`) publish `linux/arm64` variants.

## 2. Stack

`infra/compose/docker-compose.prod.yml`

| Service | Image | Published | Notes |
| --- | --- | --- | --- |
| `db` | `postgres:16-alpine` | — | volume `orszem-db`, tuned for 6 GB (`shared_buffers=256MB`, `max_connections=50`) |
| `api` | built from `infra/docker/api.Dockerfile` | — | Spring profile `demo`, JVM capped at `-Xmx900m` |
| `caddy` | `caddy:2-alpine` | 80, 443 | automatic Let's Encrypt certificate, volumes `caddy-data` / `caddy-config` |

`db` and `api` are attached only to the internal `orszem-internal` bridge network
and declare **no** `ports:` mapping, so they are unreachable from the internet.
All three services use `restart: unless-stopped` and have health checks.

## 3. Hostname / DNS

Caddy needs a DNS name that resolves to the VM in order to obtain a publicly
trusted certificate — Android release builds must not use a self-signed one.

Current value (`ORSZEM_PUBLIC_HOST` in `.env`):

```
129-159-31-175.sslip.io
```

`sslip.io` is a public wildcard-DNS service that resolves
`<dashed-ip>.sslip.io` to that IP, so no DNS zone of your own is required and
Let's Encrypt issues a normal, publicly trusted certificate for it.

**If you later buy a real domain**, this is the only change needed:

| Type | Name | Value | TTL |
| --- | --- | --- | --- |
| `A` | `api.<your-domain>` (or `@`) | `129.159.31.175` | 300 |

Then on the server:

```bash
cd /home/opc/apps/orszem
sed -i 's/^ORSZEM_PUBLIC_HOST=.*/ORSZEM_PUBLIC_HOST=api.your-domain.tld/' .env
scripts/deploy.sh --no-build
```

and rebuild the Demo APKs with the new `-PORSZEM_API_BASE_URL`.

## 4. First deployment

```bash
sudo mkdir -p /home/opc/apps && sudo chown opc:opc /home/opc/apps
cd /home/opc/apps
git clone https://github.com/kplevi05/Orszem.git orszem
cd orszem

cp infra/compose/.env.example .env
chmod 600 .env
# Fill in freshly generated secrets, e.g.
#   ORSZEM_DB_PASSWORD=$(openssl rand -base64 36 | tr -d '\n')
#   ORSZEM_JWT_SECRET=$(openssl rand -base64 48 | tr -d '\n')
#   ORSZEM_DEMO_RESET_TOKEN=$(openssl rand -base64 32 | tr -d '\n')

scripts/deploy.sh
```

`.env` is covered by `.gitignore` (`\.env`) and must never be committed.
The first build takes 10–20 minutes on 1 OCPU; later builds reuse the Gradle
and Docker layer caches.

Flyway runs automatically on API start (`db/migration` + the `db/demo`
repeatable event-catalog migration, because the `demo` profile adds that
location). Do not create the schema by hand.

## 5. Demo baseline

The `demo` profile exposes a token-guarded reset endpoint. It exists **only** in
the `local`/`demo` profiles and requires `X-Demo-Reset-Token`.

```bash
cd /home/opc/apps/orszem
source .env
curl -fsS -X POST -H "X-Demo-Reset-Token: $ORSZEM_DEMO_RESET_TOKEN" \
  "https://$ORSZEM_PUBLIC_HOST/api/v1/admin/demo/reset"
```

Baseline afterwards: **120** total, **16** today, **8** NEW, **6** IN_PROGRESS,
**14** active, **106** archived.

Demo service login: `demo.service` / `OrszemDemo!2026` (demo-only fixture,
stored as an Argon2id hash).

## 6. Routine update

```bash
cd /home/opc/apps/orszem
git fetch --all --prune
git checkout main
git pull --ff-only
scripts/deploy.sh
```

or in one step: `scripts/deploy.sh --pull`.

`scripts/deploy.sh` never runs `git reset --hard` and never touches the
PostgreSQL volume. It always force-recreates the (stateless) `api` container,
because `docker compose up -d` alone would keep the old container running when
an image changes under the same tag — which is exactly what a rebuild and a
rollback both do. It tags each successfully built image as
`orszem-api:<short-sha>` and the previous one as `orszem-api:previous`, so a
rollback target always exists.

## 7. Health verification

```bash
scripts/health-check.sh
```

Checks — and fails on — container health, Flyway completion, the API readiness
probe, Caddy→API reachability, public HTTPS, whether the API container actually
runs the current image (not a stale one), the absence of published 5432/8080,
and `ERROR` lines in the API log. "Container is Up" alone is never accepted.

## 8. Rollback

Application-only; the database is **never** rolled back automatically.

```bash
cd /home/opc/apps/orszem

# 1. Pick the target revision (an image tagged during an earlier deploy)
docker images 'orszem-api*'

# 2a. Fast path — previous image, no rebuild
docker tag orszem-api:previous orszem-api:demo
scripts/deploy.sh --no-build

# 2b. Or roll the checkout back and rebuild
git checkout <previous-good-sha>
scripts/deploy.sh

# 3. Verify
scripts/health-check.sh
```

Data preservation:

* the `orszem-db` volume is never removed by `deploy.sh` or by a rollback;
* `docker compose ... down` (without `-v`) keeps it as well;
* **never** use `docker compose down -v` — that deletes the demo database.

Flyway migrations are forward-only. If a rollback targets a revision **before**
a migration that has already been applied, Flyway will refuse to start with a
validation error. That is deliberate: resolve it by rolling forward, or restore
a database dump taken beforehand:

```bash
# take a dump before a risky deploy
docker compose --env-file .env -f infra/compose/docker-compose.prod.yml \
  exec -T db pg_dump -U orszem orszem > ~/orszem-$(date +%F-%H%M).sql
```

Destructive database rollback is intentionally **not** automated.

## 9. Security checklist

* `db` / `api` publish no ports; only Caddy publishes 80 and 443.
* `firewalld`: `ssh`, `http`, `https` only. Docker daemon TCP socket is not enabled.
* Secrets live only in the server-side `.env` (mode `600`, git-ignored).
* `RequestLoggingFilter` logs method/path/status/duration/correlationId only —
  never headers, bodies, passwords or tokens (`LogSanitizationIT`, AT-044).
* The demo reset endpoint is profile-gated **and** token-gated.
* The repository is public, so the clone needs no deploy key. If it is ever made
  private, add a **read-only** deploy key on the VM and switch the remote to SSH.

## 10. Android Demo builds

The API base URL is a single Gradle property — there are no hardcoded URLs in
the source:

```bash
cd apps/android

# local development (default, Android emulator)
./gradlew :public-app:assembleDebug :service-app:assembleDebug

# Demo build against the public Oracle endpoint
./gradlew :public-app:assembleRelease :service-app:assembleRelease \
  -PORSZEM_API_BASE_URL=https://129-159-31-175.sslip.io/
```

Release builds enforce TLS through `res/xml/network_security_config.xml`
(system trust anchors, cleartext only for `10.0.2.2` / `localhost`). Debug
builds keep cleartext for arbitrary local backends via the `src/debug` override.
Certificate validation is never disabled.
