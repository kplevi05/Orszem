# V2 server runbook

**Status: PREPARED, NOT EXECUTED.** Nothing in this document has been run. The
development workstation holds no SSH key for the server, so no V2 change has touched it
and the V1 deployment is exactly as it was.

Target model, deliberately simple:

```
Caddy  ->  Spring Boot (loopback)  ->  PostgreSQL (loopback)
```

No Docker requirement, no Kubernetes, no containers for their own sake, no message broker.

## 0. Before you start

| Prerequisite | Where |
|---|---|
| Verified V1 database dump | `V1_DATABASE_ARCHIVE.md` — **blocking** |
| DNS delegated and A records live | `DNS.md` — blocking for HTTPS |
| V1 application retired so 80/443 are free | `V1_DECOMMISSION.md` |

Re-verify the VM's current public IPv4 in the OCI console. The `129.159.31.175` in the V1
archive is a record of what was true, not a guarantee of what is true now.

### The port 80/443 conflict

V1's edge is a **Caddy container** that publishes 80 and 443. A host-level V2 Caddy cannot
bind those ports while it is running. Two ways forward:

- **Preferred:** retire V1 first (`V1_DECOMMISSION.md` Option 1), then install host Caddy.
- **If V1 must stay up:** do not start a second Caddy. Instead add the three V2 sites to
  the running V1 container's Caddyfile so one Caddy serves both, and skip section 4 below.
  Back up the existing file first and validate the replacement before reloading:
  ```bash
  cp /home/opc/apps/orszem/infra/caddy/Caddyfile ~/Caddyfile.v1.bak
  docker compose ... exec caddy caddy validate --config /etc/caddy/Caddyfile
  docker compose ... exec caddy caddy reload  --config /etc/caddy/Caddyfile
  ```

**Never overwrite a working Caddy configuration without backing it up and validating the
replacement first.**

## 1. Separate location, V1 untouched

V2 lives in its own directory. V1's `/home/opc/apps/orszem` is never written to.

```bash
sudo mkdir -p /home/opc/apps/orszem-v2/web
sudo useradd --system --home-dir /home/opc/apps/orszem-v2 --shell /usr/sbin/nologin orszem
sudo chown -R orszem:orszem /home/opc/apps/orszem-v2
```

## 2. A clean V2 database

No V1 data is migrated and no V1 table or role is reused.

```bash
sudo dnf install -y postgresql-server postgresql-contrib
sudo postgresql-setup --initdb
sudo systemctl enable --now postgresql

sudo -u postgres psql <<'SQL'
CREATE ROLE orszem_v2 LOGIN PASSWORD 'REPLACE_WITH_A_STRONG_RANDOM_PASSWORD';
CREATE DATABASE orszem_v2 OWNER orszem_v2 ENCODING 'UTF8';
SQL
```

Generate that password with a password manager or `openssl rand -base64 32`. Do not reuse
any Demo v1 credential, and do not type it inline in a shell that records history.

Confirm PostgreSQL stays private — it must listen on loopback only and never appear in the
OCI security list:

```bash
sudo ss -lntp | grep 5432     # expect 127.0.0.1:5432 only
```

## 3. Backend artifact and service

Build the artifact on a workstation or in CI, then copy it up. Production source is never
compiled on the server.

```bash
# workstation
cd backend && ./gradlew clean bootJar        # -> build/libs/backend.jar
scp build/libs/backend.jar opc@<server-ip>:/tmp/

# server
sudo install -o orszem -g orszem -m 0644 /tmp/backend.jar /home/opc/apps/orszem-v2/backend.jar

sudo mkdir -p /etc/orszem
sudo cp deploy/env/backend.env.example /etc/orszem/backend.env
sudo vi /etc/orszem/backend.env          # fill in real values
sudo chown root:orszem /etc/orszem/backend.env
sudo chmod 640 /etc/orszem/backend.env

sudo cp deploy/systemd/orszem-backend.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now orszem-backend
sudo systemctl status orszem-backend
```

Verify it is bound to loopback on the V2 port and not exposed:

```bash
sudo ss -lntp | grep 8081                 # expect 127.0.0.1:8081
curl -s http://127.0.0.1:8081/actuator/health    # {"status":"UP"}
curl -s http://127.0.0.1:8081/api/v1/meta
```

Port 8081 is used precisely so V2 cannot collide with a Demo v1 backend on 8080.

Flyway runs at startup. Phase 1 has no migrations, so it creates only its
`flyway_schema_history` table — that is expected, and confirms migrations are wired up.

## 4. Public Web and Caddy

```bash
# workstation
cd web/public-web && npm ci && npm run build
scp -r dist/* opc@<server-ip>:/tmp/web/

# server
sudo rsync -a --delete /tmp/web/ /home/opc/apps/orszem-v2/web/
sudo chown -R orszem:orszem /home/opc/apps/orszem-v2/web

sudo dnf install -y caddy
sudo cp deploy/caddy/Caddyfile /etc/caddy/Caddyfile
sudo mkdir -p /var/log/caddy && sudo chown caddy:caddy /var/log/caddy

sudo caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
sudo systemctl enable --now caddy
```

`caddy validate` on this exact file passed on the development workstation with Caddy
2.11.4 and no warnings. Run it again on the server before reloading, every time.

## 5. Verify from outside

```bash
curl -sI  https://orszembejelento.hu/                  # 200, HTML
curl -sI  https://www.orszembejelento.hu/              # 308 -> https://orszembejelento.hu/
curl -s   https://orszembejelento.hu/api/v1/meta       # same-origin API route
curl -s   https://api.orszembejelento.hu/api/v1/meta   # Android route, same backend
```

Then confirm nothing leaked:

```bash
curl -s https://api.orszembejelento.hu/actuator/health   # expect 404 - not exposed publicly
curl -s https://api.orszembejelento.hu/v3/api-docs       # expect 404
sudo ss -lntp | grep -E '0\.0\.0\.0:(8081|5432)' && echo 'EXPOSED - fix immediately'
```

## 6. Updating later

```bash
sudo systemctl stop orszem-backend
sudo install -o orszem -g orszem -m 0644 /tmp/backend.jar /home/opc/apps/orszem-v2/backend.jar
sudo systemctl start orszem-backend
```

Database changes only ever arrive as **new** forward migrations. A migration that has been
applied to this server is immutable — never edit or renumber it.
