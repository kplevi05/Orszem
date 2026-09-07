# Flyway migrations

This directory is **intentionally empty** of migrations.

Phase 1 has no persistent domain model, and inventing a placeholder table just so that a
`V001__…sql` file exists would create schema that no code needs and that a later migration would
have to undo. Flyway is nonetheless fully configured and active: on startup it connects, discovers
this location, finds no migrations, and creates its `flyway_schema_history` table. That is what
`DatabaseBaselineIT` asserts, so migration discovery and database connectivity are genuinely
verified rather than assumed.

The first real migration arrives with the first persistent feature, in Phase 2.

## Rules

- **Forward only.** Once a migration has been applied to any persistent environment (staging,
  production, or a long-lived developer database), it is immutable. Never edit, renumber or delete
  it. Correct it with a new migration.
- **Naming:** `V<version>__<snake_case_description>.sql`, e.g. `V001__create_reports.sql`.
  Repeatable migrations use `R__<description>.sql` and must be idempotent.
- **No demo or seed data here.** V1 shipped its demo fixtures through Flyway `locations`, which
  made test data indistinguishable from schema. V2 keeps seed and reference data out of migrations;
  reference datasets live under `reference-data/` and are loaded explicitly.
- **`flyway.clean` is disabled** in configuration and must stay disabled.
