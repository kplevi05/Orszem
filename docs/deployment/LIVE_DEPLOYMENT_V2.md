# V2 live deployment record (Oracle VM `129.159.31.175`)

**Status (2026-09-21): V2 is LIVE on the owner's VM and canonical: `https://orszembejelento.hu`, `https://www.orszembejelento.hu` (301 to the apex) and `https://api.orszembejelento.hu` all resolve to `129.159.31.175` and are served by V2 (§8, §9). V1 still runs beside it, untouched.** The reference data is a fictional placeholder until the external permissions are cleared. Nothing here is a production-readiness claim beyond the checks recorded.

Deployed release: tag **`v2.0.2`** (`20e6b3c5e860e9756be13636ef5e2a830df724e5`); backend JAR sha256 `5d5a073ebf900dc4fea3cdd86c7c9021e4b9be1e97f250466c8d2763ec51cfa2`,
built from that tag. Public Web is the same tag's production build (source maps removed on the server).

## 1. Topology

```
Internet -> V1's Caddy (80/443, still the owner of the ports) --sites orszembejelento.hu, www (301 to the apex), api.orszembejelento.hu, v2.129-159-31-175.sslip.io-->
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

Files in this repository (`deploy/live/`): `docker-compose.yml`, `Caddyfile.edge`, `admin.sh`, `backup.sh`, and the four site blocks added to V1's Caddyfile: `v1-caddy-v2-site.caddy` (interim host),
`v1-caddy-api-site.caddy` (`api`), `v1-caddy-apex-www-site.caddy` (apex and `www`).

**Canonical hosts (live since the cutover, §9):** `https://orszembejelento.hu` (Public Web and `/api/v1`), `https://www.orszembejelento.hu` (301 to the apex) and
`https://api.orszembejelento.hu` (only `/api/*`, everything else 404). All three resolve to `129.159.31.175`. The interim host `https://v2.129-159-31-175.sslip.io/`
(Let's Encrypt certificate via V1's Caddy; sslip.io needs no DNS change) still works and serves the same edge; it was the access path before DNS moved.

## 2. What was changed on the V1 side (the only V1 change: its Caddy configuration)

Made only after the V1 backup gate (§3) passed. **V1's data, containers, images, volumes and compose file were not touched.**

1. Site blocks were appended to V1's `infra/caddy/Caddyfile`, in this order: the interim host (`v1-caddy-v2-site.caddy`), `api.orszembejelento.hu` (§8) and, at the cutover,
   `orszembejelento.hu` and `www` (§9). The V1 part of the file stays byte-identical to the pre-V2 backup (`/home/opc/backups/v1/Caddyfile.pre-v2.*`); every step has its own
   backup (`pre-api`, `pre-logfix`, `pre-cutover`). The V1 checkout is therefore `git`-dirty.
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

## 6. Open items (none blocks the live system)

1. **Production Android APKs: done, see §8.** The V2 release key exists (`C:\Users\ottva\.orszem\signing\orszem-v2-release.jks`, alias `orszem-v2`, certificate SHA-256
   `E6:2A:DE:23:9E:02:65:F5:DD:C1:5E:30:18:13:5A:D0:A5:93:0D:95:0C:BF:73:90:B5:5D:13:15:D7:B3:03:CF`, as printed by the owner; this fingerprint is public).
   Building needs a local, gitignored `keystore.properties`; the passwords stay with the owner.
2. ~~Canonical DNS cutover~~ **Done, see §9.** The apex and `www` used to point at `91.227.139.235`, a Rackhost domain-parking server (finding in
   [CANONICAL_CUTOVER_PLAN.md](CANONICAL_CUTOVER_PLAN.md) §1); nothing was run on it and it was never modified.
3. ~~Certificates for the apex and `www`~~ **Issued at the cutover (§9)**, together with the `api` and interim-host certificates; Caddy renews them.
4. Not exercised: a reboot of the VM (it would interrupt V1 briefly). Evidence instead: `docker` enabled at boot, every container `unless-stopped`, the V2
   upstream published on the always-present `docker0` gateway (no dependency on V1's network), and a full V2 `down`/`up` with the volume kept.
5. V1 is still running and is **not retired**; its rollback dump and config backups are intact. About 42 historical, test-only lines in V1's Caddy log are to be removed when V1 is retired.
6. The V2 reference data is the fictional placeholder (§4) until the KTI/VPE and GYSEV permissions (PREPARED / NOT SENT / SEND LATER) are cleared; the PENDING dataset is unused.
7. A second, independent backup of the V2 signing key cannot be verified from here (owner's responsibility).

## 7. Verification before the cutover (interim host, from outside the server, over HTTPS, no mocks)

Acceptance suite `deploy/eval/acceptance-api.mjs`: phase A 33/33, phase B 10/10, rate limit 3/3, verify after a backend restart and after
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
`Authorization` by default, not this one). The blocks now delete it from the log (`format filter`), verified with a marker value. **Residual (test-only):** the V1 Caddy container's
Docker log still holds about 42 earlier lines with report credentials of *synthetic* test reports. V1's log is not truncated (it is V1's data); the lines are
to be removed when V1 is finally retired. The V2 edge, backend and database logs are clean.

**Still owner gates:** GO for the apex/`www` cutover (replace, do not add, the two `A` records; lower their TTL to 300 first), CI and merge of the
`deploy/live-v2` and `eval/runnable-environment` branches, the second independent backup of the V2 keystore (not verifiable from here), and the deferred
external permissions (KTI/VPE, GYSEV: PREPARED / NOT SENT / SEND LATER).

**Apex and `www`.** The plan, the validated (not applied) Caddy configuration `deploy/live/v1-caddy-apex-www-site.caddy` and the finding that `91.227.139.235` is a Rackhost
domain-parking server are in [CANONICAL_CUTOVER_PLAN.md](CANONICAL_CUTOVER_PLAN.md). Still waiting for the owner's explicit GO.

## 9. Canonical cutover (2026-09-21)

Executed after the owner's explicit GO and after the owner replaced the two `A` records. **`orszembejelento.hu`, `www.orszembejelento.hu` and `api.orszembejelento.hu` all resolve to `129.159.31.175`
(TTL 300); the apex and `www` were confirmed on `ns1`–`ns4.dns24.hu` before anything was applied.** V1 was not retired and its data was not touched.

- **Caddy.** `deploy/live/v1-caddy-apex-www-site.caddy` was applied exactly as validated (backup `/home/opc/backups/v1/Caddyfile.pre-cutover.20260921T175348Z`), and Let's Encrypt issued
  both certificates within seconds (apex: CN=YE2, `www`: CN=YE1, both valid to 2026-12-20, TLS 1.3, chain verified).
- **Checks from outside.** Apex 200 with the Public Web and the same CSP/HSTS/nosniff headers; `/api/v1/meta` 200; `/actuator/health`, `/v3/api-docs`, `/swagger-ui/…` 404; `https://www…/<path>?<query>`
  → **301** to the same path on the apex; `http://` (apex, `www`, `api`) → 308 to `https://`; `api` unchanged (200, non-API paths 404). Some public resolvers still served the old Rackhost
  address for a while (the previous TTL was 3600); the old address only ever showed the parking page.
- **Web smoke (real browser, `https://www.orszembejelento.hu/uj-bejelentes` → apex).** Settlement, line choice, category, event, submit (RECEIVED); History showed *Beérkezett* → *Feldolgozás
  alatt* (after a claim) → *Lezárva* (after close).
- **Android (production APKs, unchanged `api` host).** Service app: session restored after a force-stop, queue refresh; Public app: history persisted (*Lezárva*). The full workflow on both apps was
  accepted earlier (§8) and nothing changed for them.
- **Rate limit through the apex.** 10 × 201 then 3 × 429 (`Retry-After: 20`), a spoofed `X-Forwarded-For` does not bypass it. The suite's R3 assertion printed 20 stored instead of 10 only because
  it counts every report tagged `EVAL-RATE-`, including the 10 from the earlier run through `api`; per run the database holds exactly 10 (15:54 and 18:00 groups).
- **Ports.** From outside only 22, 80 and 443 are open; 5432, 8080, 8081, 18081 and 2019 are closed. V2 listens only inside the host (`172.17.0.1:18081`).
- **Logs.** V1's Caddy log for the V2 hosts since the cutover: 292 lines, 0 with a report credential (marker test on the apex and `www` also 0), 0 with a 5xx status. V2 edge, backend and
  database logs: no 5xx, no stack traces, no secret-shaped strings; the only WARNs are the two SpringDoc notices (the endpoints are blocked at the edge) and the limiter's count-only notice.
- **V1 rollback artefacts intact.** `orszem_v1_20260921T153254Z.dump` verifies OK; V1 database 128 reports, 1 user; V1 healthy.
- **Backup after the cutover.** `/home/opc/backups/v2/orszem_v2_20260921T180105Z.dump`, 82,834 bytes, sha256 `de1e0f2677dc5e95407cc5fcc746f51e8de8ae1c6c19b622f02fa656da5c9311`, disposable restore identical
  for every table; copy in `C:\Users\ottva\.orszem\backups\v2\`.

Rollback is unchanged ([CANONICAL_CUTOVER_PLAN.md](CANONICAL_CUTOVER_PLAN.md) §5). Still open: the reference data is the fictional placeholder (§4), the KTI/VPE and GYSEV requests are PREPARED / NOT SENT / SEND LATER, the
~42 historical test-only lines in V1's Caddy log are removed when V1 is retired, and V1 itself is still running.
