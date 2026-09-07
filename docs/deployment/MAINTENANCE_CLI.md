# Maintenance CLI — administrator provisioning, recovery, and reference data

Creating the first administrator, recovering one that is locked out, and importing the
reference dataset are the operations that could hand someone complete control of the
system or corrupt the data routing depends on.

They are therefore **not HTTP endpoints**. They are gated on shell access to the server,
which is a strictly higher bar than any password could be: there is no remote maintenance
interface to attack, no default administrator account, no seeded credential and no master
password. A test asserts that no HTTP route to either action exists.

## Invocation

```bash
./scripts/orszem-admin create-super-admin
./scripts/orszem-admin reset-super-admin-password SZ-123456
./scripts/orszem-admin reference-validate reference-data/local-research
./scripts/orszem-admin reference-diff     reference-data/local-research
./scripts/orszem-admin reference-import   reference-data/local-research
```

The wrapper runs the ordinary backend jar with `--spring.main.web-application-type=none`, so
the process opens no port. If a maintenance action is ever set on a process that *is* serving
HTTP, the runner refuses to act and exits with code 2 — a maintenance action must never
happen as a side effect of a normal deployment.

## Configuration

The same database settings the service uses:

```bash
set -a
. /etc/orszem/backend.env
set +a
```

`ORSZEM_BACKEND_JAR` overrides the jar location (default `backend/build/libs/backend.jar`).

## `create-super-admin`

Creates an account with:

| Field | Value |
|---|---|
| `role` | `SUPER_ADMIN` |
| `status` | `ACTIVE` |
| `must_change_password` | `true` |
| service ID | random, `SZ-` plus six CSPRNG digits |

and prints, once:

```
  Service ID:          SZ-544067
  Temporary credential: XXXX-XXXX-XXXX-XXXX
```

The credential is written **directly to the console, never through the logging framework**,
so it cannot outlive its single use in a log file, a journal or a log aggregator. Only its
Argon2id hash is stored. An audit row is written with `actorType = SYSTEM` and
`source = MAINTENANCE_CLI`, carrying the service ID but never the credential.

The administrator must change the password on first sign-in; the temporary credential is
useless afterwards.

## `reset-super-admin-password`

Recovery for a locked-out administrator. It:

- resolves the user and **confirms the role is `SUPER_ADMIN`**
- locks the user row
- generates a new temporary credential and replaces the password hash
- sets `must_change_password`
- revokes every session of that user
- writes an audit row
- prints the new credential once

It deliberately does **not**:

- work on any account that is not a `SUPER_ADMIN`
- reactivate a deactivated account — that is an administrative decision, not a side effect
  of password recovery
- change roles
- accept a password as an argument

Failures exit non-zero with a plain message, for example:

```
ERROR: SZ-111222 is not a SUPER_ADMIN; refusing to reset
```

## `reference-validate` / `reference-diff` / `reference-import`

Validates, previews and applies a canonical reference dataset directory (three CSVs plus
`manifest.json` — see `reference-data/README.md`). All three take the dataset directory as
their one argument.

`reference-validate` and `reference-diff` are **read-only** and run on any dataset,
including one that is `UNVERIFIED` or `reuseStatus: PENDING` — that is exactly how a
candidate dataset gets reviewed before it is cleared for import.

`reference-import` is the only command that writes to the database, and it is the only one
gated:

- refuses anything that is not `verificationStatus: VERIFIED`
- refuses anything that is not `reuseStatus: CLEARED` — **there is no flag to bypass
  either check.** See [ADR 0006](../architecture/adr/0006-reference-import-gates-and-partial-coverage-routing.md).
  The real dataset under `reference-data/local-research/` is `reuseStatus: PENDING` today, so it
  cannot currently be imported anywhere, including production.
- re-running the same dataset version with byte-identical content is a no-op, not an error
- the same dataset version with *different* content is refused
  (`REFERENCE_DATASET_VERSION_CONFLICT`)
- refuses to deactivate a railway line that is currently assigned to a service area
  (`REFERENCE_LINE_IN_USE`) — the whole import rolls back rather than silently orphaning
  that configuration

The whole operation runs inside one transaction behind a PostgreSQL advisory lock, so two
`reference-import` runs against the same server never interleave. A settlement or railway
line already known keeps its internal UUID across versions (matched by `ksh_code` /
`line_code`); one no longer present in the dataset is deactivated, never deleted. An
`REFERENCE_DATASET_IMPORTED` audit row and a `reference_dataset_imports` provenance row are
written only on an actual, successful import.

```
$ ./scripts/orszem-admin reference-import reference-data/local-research
ERROR: REFERENCE_DATASET_REUSE_NOT_CLEARED
  refusing to import: reuseStatus is PENDING, not CLEARED (see ADR 0006 - obtain written
  confirmation, then set reuseStatus: CLEARED)
```

## Operational notes

- A service ID is an identifier, not a secret, so passing it as an argument is fine. A
  password never is, and none is accepted.
- Run these from an interactive session. Piping the output into a file would defeat the point
  of printing the credential only to the terminal.
- If the credential is lost before first use, run the reset command again; there is no way to
  recover the old one, by design.
