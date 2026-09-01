# Őrszem Demo v1 — Planning Pack v1.3

Ez a csomag a Claude Code-dal történő implementáció előtti kanonikus technikai terv.

## Fő dokumentumok

- `CLAUDE.md`
- `docs/product/DEMO_V1_SCOPE.md`
- `docs/product/EVENT_CATALOG.md`
- `docs/product/DEMO_SEED_DATA.md`
- `docs/ux/DEMO_V1_SCREENS.md`
- `docs/architecture/ARCHITECTURE.md`
- `docs/architecture/DATABASE_SCHEMA.md`
- `docs/architecture/BUSINESS_RULES.md`
- `docs/testing/DEMO_V1_ACCEPTANCE_TESTS.md`
- `docs/implementation/IMPLEMENTATION_PLAN.md`
- `docs/DECISIONS_REQUIRING_OWNER.md`

## Gépileg használható szerződések

- `contracts/openapi/orszem-v1.yaml`
- `contracts/catalog/event-types.demo-v1.json`
- `services/api/src/main/resources/db/migration/V1__init_demo_schema.sql`
- `services/api/src/main/resources/db/migration/R__demo_event_catalog.sql`

## Demo seed

- `services/api/src/main/resources/db/demo/000_reset_demo.sql`
- `services/api/src/main/resources/db/demo/010_demo_service_user.sql`
- `services/api/src/main/resources/db/demo/020_demo_reports.sql`
- `services/api/src/main/resources/db/demo/demo-reports.seed.json`
- `scripts/reset-demo.sh`

A demo seed kizárólag `local/demo` környezetre készült.

## Claude Code handoff

- `MASTER_PROMPT_DEMO_V1_FINAL.md`
- `docs/implementation/CLAUDE_HANDOFF.md`
