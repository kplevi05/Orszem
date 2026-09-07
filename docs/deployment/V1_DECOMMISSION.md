# Retire the public Demo v1.1 deployment — REQUIRED FOLLOW-UP

**Status: OUTSTANDING. Owner action.**

This is a required security follow-up, not a suggestion.

## Why

The Demo v1.1 deployment at `https://129-159-31-175.sslip.io/` is reachable by anyone on
the internet and accepts the service login `demo.service` with a password that is
committed in plaintext in this repository's history and in the archived V1 documentation.
Its Argon2id hash is in the archived demo seed SQL. Anyone who reads the repository can
sign in to the Service API.

Additionally, in the `demo` profile `ORSZEM_DEMO_DELETION_ENABLED` defaults to **true**,
so report deletion is reachable, and a demo reset endpoint exists behind a shared token.

The demonstration is over and the source is preserved by the `demo-v1.1-final` tag, so the
running deployment is no longer needed to preserve anything. A published credential on a
public endpoint cannot stay up long-term.

## Prerequisite — do not skip

Complete `V1_DATABASE_ARCHIVE.md` first and verify the dump. Nothing below may run until a
verified `pg_dump -Fc` archive exists off the server.

## The VM and its public IP are RETAINED

> **Decommissioning here means retiring the V1 _application_ and its `sslip.io` exposure —
> not the server.**
>
> The same Oracle Cloud VM and the same public IPv4 are the intended V2 host, and the DNS
> records in `DNS.md` point at that address. **Do not release the OCI public IP, do not
> terminate the instance, and do not close TCP 80/443** — V2 needs them.
>
> Releasing the IP or the VM is a separate decision that must be taken explicitly and on
> its own merits. It is not part of this procedure.

## Option 1 — retire the V1 application (preferred)

```bash
ssh opc@<server-ip>
cd /home/opc/apps/orszem

# Stop and remove the V1 containers. Volumes are intentionally NOT removed:
# no -v flag, so the database volume survives until the dump is proven restorable.
docker compose --env-file .env -f infra/compose/docker-compose.prod.yml down

# Confirm nothing is listening on 80/443 from V1 any more
sudo ss -lntp | grep -E ':80|:443' || echo "V1 edge is down"
```

This frees ports 80 and 443, which the V2 Caddy needs. The VM, the public IP, the OCI
security list and the firewall rules are all left exactly as they are.

Once V2 is serving and the dump has been confirmed restorable, the V1 volumes can be
removed separately:

```bash
docker volume ls | grep orszem      # inspect first
# docker volume rm <name>           # only when you are certain
```

## Option 2 — keep V1 running, but close service access

Use this only if the pilot genuinely must stay available for a while.

1. **Rotate the service password.** Generate a new one in your password manager — do not
   reuse anything from the repository, and do not paste it into a shell that records
   history. Update the `demo.service` user's hash in the V1 database directly.
2. **Disable the demo reset endpoint** by blanking its token, and **disable deletion**, in
   `/home/opc/apps/orszem/.env`:
   ```
   ORSZEM_DEMO_RESET_TOKEN=
   ORSZEM_DEMO_DELETION_ENABLED=false
   ```
3. Apply and verify:
   ```bash
   cd /home/opc/apps/orszem
   docker compose --env-file .env -f infra/compose/docker-compose.prod.yml up -d --force-recreate api
   curl -s -o /dev/null -w '%{http_code}\n' -X POST https://<v1-host>/api/v1/admin/demo/reset
   # expect a rejection, not 200
   ```
4. **Set an end date** and carry out Option 1 by then.

Note that Option 2 leaves the old credential valid anywhere it was reused, and leaves an
internet-facing demo application that no longer receives updates. Option 1 is better.

## Option 3 — not acceptable

Leaving it as it is. The credential is public.

## Done when

- [ ] Verified V1 database dump exists off the server
- [ ] V1 application no longer serves the public internet (or service access is closed and
      an end date is recorded)
- [ ] The VM, public IP and ports 80/443 are still intact for V2
- [ ] Recorded in your own notes what was done and when
