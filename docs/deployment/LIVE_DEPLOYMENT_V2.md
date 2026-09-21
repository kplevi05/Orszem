# V2 live deployment record (Oracle VM `129.159.31.175`)

**Status (2026-09-21): V2 is RUNNING on the owner's VM alongside V1. `https://api.orszembejelento.hu/` is live, the production Android APKs are built with the V2 key and were accepted against it (§8). It is NOT yet the canonical Web system:** the apex and `www` are unchanged and still resolve to `91.227.139.235` (not touched). The canonical cutover needs the owner's separate explicit GO. Nothing here is a production-readiness claim.

Deployed release: tag **`v2.0.2`** (`20e6b3c5e860e9756be13636ef5e2a830df724e5`); backend JAR sha256 `5d5a073ebf900dc4fea3cdd86c7c9021e4b9be1e97f250466c8d2763ec51cfa2`,
built from that tag. Public Web is the same tag's production build (source maps removed on the server).

## 1. Topology

```
Internet -> V1's Caddy (80/443, unchanged owner of the ports) --site v2.129-159-31-175.sslip.io-->
            orszem-v2-edge  (Caddy; Public Web + /api; published only on 172.17.0.1:18081, a host-internal address)
            -> orszem-v2-backend (Spring Boot 4, no published port) -> orszem-v2-db (PostgreSQL 16, no published port)
```

| | V1 (untouched) | V2 (new) |
|---|---|---|
| Compose project | `orszem` | `orszem-v2` (`/home/opc/apps/orszem-v2`) |
| Containers | `orszem-api-1`, `orszem-caddy-1`, `orszem-db-1` | `orszem-v2-backend`, `orszem-v2-db`, `orszem-v2-edge` |
| Database | `orszem` (volume `orszem_orszem-db`) | `orszem_v2` (volume `orszem_v2_pgdata`), never shared |
| Network | `orszem_orszem-internal` | `orszem_v2_net` (172.29.90.0/24) |
| Logs | Docker json-file | Docker json-file, 10 MB × 5 per container (`docker logs orszem-v2-*`); Caddy access logs on stdout with `Authorization` and `X-Orszem-Report-Access` removed |
| Restart | `unless-stopped` | `unless-stopped`; Docker is enabled at boot |
| Config / secrets | `/home/opc/apps/orszem/.env` | `/home/opc/apps/orszem-v2/.env`, mode 600, holds only the database password (generated on the VM with `openssl rand`, never printed) |

Files in this repository: `deploy/live/docker-compose.yml`, `Caddyfile.edge`, `v1-caddy-v2-site.caddy`, `admin.sh`, `backup.sh`.

**Interim public URL: `https://v2.129-159-31-175.sslip.io/`** (Let's Encrypt certificate obtained by V1's Caddy; sslip.io resolves the name to the VM
without any DNS change). It serves the Public Web at `/` and the API at `/api/v1`. The final hosts (`orszembejelento.hu`, `www` → apex,
`api.orszembejelento.hu`) are prepared in `deploy/caddy/Caddyfile` and are **not** live: see §6.

## 2. What was changed on the V1 side (the only V1 change)

Made only after the V1 backup gate (§3) passed. **V1's data, containers, images, volumes and compose file were not touched.**

1. One site block (`v2.129-159-31-175.sslip.io`, `deploy/live/v1-caddy-v2-site.caddy`) was appended to V1's `infra/caddy/Caddyfile`. The V1 part
   of the file is byte-identical to the pre-V2 backup (`/home/opc/backups/v1/Caddyfile.pre-v2.*`). The V1 checkout is therefore `git`-dirty.
2. **Finding: V1's Caddy container has a stale bind mount.** V1's Caddyfile was replaced on the host on Sep 1, after the container started, so
   the container has held a dangling mount ever since and runs from the configuration it loaded then. The new block was therefore loaded into the running
   Caddy through its admin interface from a copy (`/tmp/Caddyfile.live` inside the container). A *restart* of `orszem-caddy-1` re-binds the current host
   file, which contains the V2 block, so the result is the same either way. A *recreate* is also fine. Nothing else depends on this.

## 3. V1 safety gate (mandatory, passed before any V1 change)

| | |
|---|---|
| Database | `orszem` (user `orszem`), PostgreSQL 16.15, 6 tables, 128 reports, 1 user, 8 MB |
| Backup | `pg_dump -Fc`, **`/home/opc/backups/v1/orszem_v1_20260921T153254Z.dump`**, 32,982 bytes, mode 700 directory |
| SHA-256 | `552a882eda0c28f9071b20c1ff4f0089e972aebd15649c7f265a2f687915bf6b` (`.sha256` beside it, verified) |
| Independent copy | workstation `C:\Users\ottva\.orszem\backups\v1\` (same file, checksum verified), outside Git and OneDrive |
| Restore drill | throwaway `postgres:16-alpine` container **with no network**; all 6 tables identical in row count and md5 over the row text; 54 columns, 19 indexes, 31 constraints in both |
| Config backups | `/home/opc/backups/v1/Caddyfile.pre-v2.*`, `docker-compose.prod.yml.pre-v2.*`, `env.pre-v2.*` (mode 600) |

## 4. V2 database and reference data

Empty database, then Flyway **V001–V006** applied by the backend on start (14.6 s), all successful. Reference data is **fictional and clearly not the
final railway dataset**:

> **INITIAL LIVE REFERENCE DATA — FICTIONAL PLACEHOLDER.** `EXAMPLE-1` then `EVAL-2` (`reference-data/example`, `reference-data/evaluation`): five invented
> settlements, three invented lines. It exists so the whole application can run now. **It must be replaced** by the real, cleared railway dataset after the
> external clearance (KTI/VPE, GYSEV: PREPARED / NOT SENT / SEND LATER, see [EXTERNAL_PERMISSIONS_DEFERRED.md](EXTERNAL_PERMISSIONS_DEFERRED.md)). The PENDING
> VPE/GYSEV-derived dataset was not imported, not copied to the VM, and not used.

Service areas *Északi* and *Déli* (line 900 → North, 901 → South, 902 deliberately unassigned). Accounts were created through the supported tools
(`admin.sh create-super-admin`, then the API): one SUPER_ADMIN, service users for North, South, both and global, a global and a North moderator, and one
service user left on its one-time temporary credential. Credentials are in `C:\Users\ottva\.orszem\live\credentials.local.txt`, never in Git.

## 5. Rollback

| Situation | Action |
|---|---|
| Undo V2 exposure only | On the VM: `cp /home/opc/backups/v1/Caddyfile.pre-v2.<ts> /home/opc/apps/orszem/infra/caddy/Caddyfile`, load it into the running Caddy as in §2, then `cd /home/opc/apps/orszem-v2 && docker compose --env-file .env down` (keeps the volume). V1 is unaffected |
| V1 data damaged | restore `orszem_v1_20260921T153254Z.dump` (checksum first) with `pg_restore` into V1's `orszem` database |
| V2 data damaged | `backup.sh drill` proves the latest dump; restore with `pg_restore` into a fresh `orszem_v2` |
| After DNS cutover | put the old `A` records back (TTL 300) and re-enable V1's site; V1 keeps its data because it is never deleted |

## 6. Not done yet (owner gates)

1. **Production Android APKs: done, see §8.** The V2 release key exists (`C:\Users\ottva\.orszem\signing\orszem-v2-release.jks`, alias `orszem-v2`, certificate SHA-256
   `E6:2A:DE:23:9E:02:65:F5:DD:C1:5E:30:18:13:5A:D0:A5:93:0D:95:0C:BF:73:90:B5:5D:13:15:D7:B3:03:CF`, as printed by the owner; this fingerprint is public).
   Building needs a local, gitignored `keystore.properties`; the passwords stay with the owner.
2. **Owner GO** before touching `orszembejelento.hu`, `www` or `api`. Today the apex and `www` resolve to `91.227.139.235` (purpose unverified, not touched)
   and `api` has no record; the target VM is `129.159.31.175`.
3. The V2 certificate is for the interim host only. Certificates for the final hosts need DNS to resolve to the VM first.
4. Not exercised: a reboot of the VM (it would interrupt V1 briefly). Evidence instead: `docker` enabled at boot, every container `unless-stopped`, the V2
   upstream published on the always-present `docker0` gateway (no dependency on V1's network), and a full V2 `down`/`up` with the volume kept.

## 7. Verification so far (all from outside the server, over HTTPS, no mocks)

Acceptance suite `deploy/eval/acceptance-api.mjs` (evaluation branch): phase A 33/33, phase B 10/10, rate limit 3/3, verify after a backend restart and after
a full V2 down/up 17/17 each; sessions survive. Real V2 backup and disposable restore: 18 tables identical (`backup.sh`). Ports 5432, 8080, 8081, 18081 and
2019 are closed from outside; 22, 80 and 443 are open. Actuator, OpenAPI and Swagger return 404 at the edge. A spoofed `X-Forwarded-For` does not bypass the
submission limit. No secret-shaped strings in the V2 logs.

## 8. API host, production APKs and Android acceptance (2026-09-21)

**`api.orszembejelento.hu`.** `A` → `129.159.31.175`, TTL 300 (created by the owner; verified on `ns1`–`ns4.dns24.hu` and on 1.1.1.1, 8.8.8.8, 9.9.9.9 and
208.67.222.222). The prepared block (`deploy/live/v1-caddy-api-site.caddy`) was appended to V1's Caddyfile and loaded as in §2; Let's Encrypt (CN=YE1) issued
the certificate for `api.orszembejelento.hu` (valid to 2026-12-20), TLS 1.3, chain verified. `GET /api/v1/meta` is 200; every non-API path (`/`, `/index.html`,
`/actuator/health`, `/v3/api-docs`, `/swagger-ui/…`, `/assets/`) is **404**; protected endpoints without a token are 401; `http://` redirects (308) to `https://`.
V1 stayed 200 throughout. The apex and `www` were not changed.

**Production APKs** (built from tag `v2.0.2`, `20e6b3c`, with the owner's protected `keystore.properties`; the worktree copy of it was deleted afterwards). Stored
in `C:\Users\ottva\.orszem\release\v2.0.2\` (not in Git):

| APK | Package | Version | SHA-256 (file) |
|---|---|---|---|
| `orszem-public-2.0.2-release.apk` | `hu.orszembejelento.app` | 2.0.2 (3) | `1835e92f11dae36db2ea9ff058c93188c14360d86b508734710b84ffbc6aa6b7` |
| `orszem-service-2.0.2-release.apk` | `hu.orszembejelento.service` | 2.0.2 (3) | `5ce35ed97a4fd852debe424842cd60fc12b872ad104d22c16547566c11135dc0` |

`apksigner verify` passes for both (APK Signature Scheme v2); the signer certificate SHA-256 is
`e62ade239e0265f5ddc15e3018135ad0a5930d950cbf7390b55d1315d7b303cf`, equal to the fingerprint the owner recorded for `orszem-v2`. Both bake in
`https://api.orszembejelento.hu/`, are not debuggable, and their network policy is HTTPS-only with system trust anchors (`cleartextTrafficPermitted=false`,
no debug domains).

**Android acceptance** (production APKs on the emulator, against the live HTTPS API): Public app: settlement search and line question, submit (RECEIVED),
history, status refresh RECEIVED → PROCESSING → CLOSED; Service app: login, NEW queue, claim, return, claim again, moderator reassign, close, Archive, forced
password change screen (cancelled), session restore after force-stop and after a backend restart, and as SUPER_ADMIN the users, service areas, audit, deleted
reports and analytics screens. The backend confirms the report ended ARCHIVED with the full assignment history. The Web submit was done earlier in a real
browser. Scripted API suite `VERIFY` 17/17 against `api.orszembejelento.hu` after a backend restart.

**Final V2 backup** (after acceptance): `/home/opc/backups/v2/orszem_v2_20260921T165031Z.dump`, 79,966 bytes, sha256
`797082afd8fe9a03167728e318a23a802c93d317cd60aaa445f6c169199e71f7`; disposable restore identical for all 18 tables; a verified copy is in
`C:\Users\ottva\.orszem\backups\v2\`.

**Finding, fixed.** The first version of the two V2/api site blocks in V1's Caddy logged the `X-Orszem-Report-Access` request header (Caddy redacts
`Authorization` by default, not this one). The blocks now delete it from the log (`format filter`), verified with a marker value. **Residual:** the V1 Caddy container's
Docker log still holds about 42 earlier lines with report credentials of *synthetic* test reports. V1's log was not truncated (it is V1's data); it can be cleared
when V1's Caddy is replaced at cutover. The V2 edge, backend and database logs are clean.

**Still owner gates:** GO for the apex/`www` cutover (replace, do not add, the two `A` records; lower their TTL to 300 first), CI and merge of the
`deploy/live-v2` and `eval/runnable-environment` branches, the second independent backup of the V2 keystore (not verifiable from here), and the deferred
external permissions (KTI/VPE, GYSEV: PREPARED / NOT SENT / SEND LATER).
