# Scripts

Developer convenience only. Nothing here is required to build, test or deploy — those are
plain Gradle and npm commands, documented in the root `README.md`.

| Script | Purpose |
|---|---|
| `dev-db.sh` | start a throwaway PostgreSQL for local backend development |

Deployment is **not** scripted from a developer machine. It is a documented procedure the
owner runs on the server; see `docs/deployment/SERVER_RUNBOOK.md`.

Nothing in this directory may contain a credential, a hostname belonging to a live
environment, or anything that would fail if the owner's machine were set up differently.
