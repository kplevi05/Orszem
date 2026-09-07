# V1 database archive — PENDING

**Status: NOT DONE.** This step could not be executed from the development workstation:
no SSH private key for the server exists there (`~/.ssh` contains only `known_hosts`).
It has not been faked or approximated, and no V2 work has touched the server.

> **Blocking rule.** No destructive change to the V1 server or database may happen until
> a dump produced by this procedure exists and has been verified non-empty and restorable.
> That includes anything in `V1_DECOMMISSION.md`.

V1 data is **not** migrated into V2. This archive exists so the demonstration data is
recoverable if it is ever needed for reference, not because V2 consumes it.

## Procedure (run by the owner, on the server)

The V1 database runs as a container with no published port, so the dump goes through
`docker compose exec`.

```bash
ssh opc@<server-ip>
cd /home/opc/apps/orszem

# Custom format (-Fc): compressed, and restorable selectively with pg_restore.
docker compose --env-file .env -f infra/compose/docker-compose.prod.yml \
  exec -T db pg_dump -Fc -U orszem orszem > ~/orszem-v1-$(date +%F-%H%M).dump
```

This is read-only with respect to the database: `pg_dump` takes a consistent snapshot and
changes nothing. The V1 application can stay running.

## Verify the dump

Do not skip this. An empty or truncated dump is worse than no dump, because it looks like
a backup.

```bash
ls -lh ~/orszem-v1-*.dump          # must exist and be clearly non-zero
pg_restore --list ~/orszem-v1-*.dump | head -30   # must list real objects
```

`pg_restore --list` reads the archive's table of contents without restoring anything. If
it errors or lists nothing, the dump is bad — take it again.

## Store it off the server

A backup that lives only on the machine it backs up is not a backup. Copy it somewhere
durable:

```bash
scp opc@<server-ip>:~/orszem-v1-*.dump /path/to/local/backup/
```

Then record — outside this repository — where it went and its SHA-256. **Do not commit the
dump.** It contains the demo dataset and the demo service user's password hash.

## After it is verified

1. Note the date, filename and SHA-256 in your own records.
2. Proceed to `V1_DECOMMISSION.md`, which is blocked until this is done.
3. The V2 database is created fresh and empty; see `SERVER_RUNBOOK.md`. No V1 table,
   role or credential is reused.
