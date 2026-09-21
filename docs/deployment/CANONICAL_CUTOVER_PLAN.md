# Canonical cutover plan: `orszembejelento.hu` and `www` → V2

**Status: PLAN ONLY. Not executed. Waiting for the owner's explicit GO.** Nothing in this document has been applied. V1 stays running and is not retired by this
plan. The API host `api.orszembejelento.hu` is already live ([LIVE_DEPLOYMENT_V2.md](LIVE_DEPLOYMENT_V2.md) §8), and the production Android APKs already
point at it, so **the apps need no rebuild** for this cutover.

## 1. What is at the current apex/`www` target (read-only check, 2026-09-21)

`91.227.139.235` is a **Rackhost domain-parking server**, not an Őrszem host:

| | |
|---|---|
| Reverse DNS | `r139235.rackhostvps.com`; RIPE block `RACKHOST-NET-2`, HU |
| Port 80 / 443 | open (22, 8080, 8443 closed/filtered) |
| Behaviour | every path and every `Host` header returns the same **HTTP 200, 8,720-byte static page**, including `/api/v1/meta`, `/robots.txt`, `/actuator/health`; no redirect from HTTP to HTTPS |
| Page text | title `orszembejelento.hu`; "*Ez a domain a Rackhost-nál került regisztrálásra. Ön, most a domain parkoló oldalát látja. Kérjük ellenőrizze a DNS beállításokat.*" with a link to rackhost.hu |
| Server | `Caddy`; valid Let's Encrypt certificates for `orszembejelento.hu` (issued 2026-09-07) and `www.orszembejelento.hu`; none for `api` (TLS alert, as expected) |
| Explanation in the repo / known infrastructure | Nothing in the repository refers to it. The DNS provider `dns24.hu` and this server are both Rackhost. It is the registrar's parking page that the default records point to. `~/.ssh/known_hosts` also holds an entry for `91.227.138.55` (Rackhost range, dated 2026-09-01); it is not explained by anything in the repository |

So replacing the apex and `www` records takes nothing away from an Őrszem service. Nothing was modified on, or logged into, that host.

## 2. DNS change (owner, at the `dns24.hu` DNS panel)

Replace, do not add: two `A` records for the same name would split traffic between the parking server and V2.

| Name | Type | Current | New | TTL |
|---|---|---|---|---|
| `orszembejelento.hu` (apex, `@`) | A | `91.227.139.235`, 3600 | **`129.159.31.175`** | 300 |
| `www.orszembejelento.hu` | A | `91.227.139.235`, 3600 | **`129.159.31.175`** | 300 |
| `api.orszembejelento.hu` | A | `129.159.31.175`, 300 (done) | unchanged | 300 |

No `AAAA`, `CNAME`, `TXT`, `CAA` or `MX` exists for these names; add none.

**Order.** (a) At least one hour before, lower the TTL of both records from 3600 to 300 while keeping the value; (b) at cutover, replace the value of both
records with `129.159.31.175`. Steps (a) and (b) can be one edit if a slower propagation (up to 1 h at resolvers that cached the old answer) is acceptable.

**Verify propagation.** Query `ns1`–`ns4.dns24.hu` directly until all return only `129.159.31.175` for both names, then the public resolvers 1.1.1.1, 8.8.8.8,
9.9.9.9 and 208.67.222.222 (same method as for `api`; the check script is `dnschk` in the session notes: `dns.Resolver` with `setServers`, `resolve4(..., {ttl:true})`).
Also confirm exactly one `A` record per name and that the `api` record did not change.

## 3. Caddy change (after propagation only)

`deploy/live/v1-caddy-apex-www-site.caddy` (prepared, **validated, not applied**): the apex proxies to the V2 edge on `172.17.0.1:18081` (Public Web, `/api/*`;
actuator, OpenAPI and Swagger stay 404); `www` redirects permanently to the apex; both send HSTS (`includeSubDomains`); the apex access log deletes the
`Authorization` and `X-Orszem-Report-Access` headers. It was validated (a) on its own and (b) appended to V1's *current* Caddyfile in a throwaway container on
the VM. Site addresses in that candidate: V1's own host, `v2.129-159-31-175.sslip.io`, `api.orszembejelento.hu`, `orszembejelento.hu`, `www.orszembejelento.hu`.

Apply exactly as for the earlier blocks: back up the current Caddyfile (`/home/opc/backups/v1/Caddyfile.pre-cutover.<ts>`), append the block in place, validate,
copy it into the running container and `caddy reload` from that copy (V1's container has a stale bind mount, see LIVE_DEPLOYMENT_V2.md §2), confirm V1 still returns 200,
and watch the Caddy log for the two certificates (HTTP-01 on port 80).

## 4. Post-cutover acceptance (from outside the server)

HTTPS and the certificate chain for the apex and `www`; `http://` → `https://`; `www` → apex 308; the Web loads and submits a report; `/actuator/health`, `/v3/api-docs`
and `/swagger-ui` are 404 on the apex; `api` unchanged; Public and Service Android against `https://api.orszembejelento.hu/`; the full report workflow; the submission
limit (10 then 429); logs for 5xx, authentication failures, stack traces and secrets; closed internal ports (5432, 8080, 8081, 18081, 2019); a backend restart and a V2 stack
restart with the data intact; then a fresh V2 `backup.sh backup` and `drill`.

## 5. Rollback

1. In the DNS panel restore the two `A` records to `91.227.139.235` (TTL 300): the names return to the Rackhost parking page.
2. On the VM restore `/home/opc/backups/v1/Caddyfile.pre-cutover.<ts>`, load it into the running Caddy as in §3 and confirm V1 is 200. `api` and the interim host keep working.
3. V1's database, its containers and the backups (`orszem_v1_20260921T153254Z.dump`, sha256 `552a882e…bf6b`) are untouched by all of this.

## 6. Residual data to remove when V1 is finally retired

Before the log filter was fixed, V1's Caddy container logged the `X-Orszem-Report-Access` header of about **42 requests** to the V2 hosts. They belong to **synthetic test
reports only**. They are recorded here as test-only residual data and are to be removed when V1's Caddy container is finally retired or recreated; V1's logs are **not**
truncated now. Nothing else needs cleaning: the V2 edge, backend and database logs are clean, and the filter is in place for the `v2`, `api` and (prepared) apex blocks.
