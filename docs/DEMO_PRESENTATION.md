# Őrszem Demo v1 — presentation walkthrough (AT-050)

A reproducible 5-minute end-to-end demo from a clean reset.

## 0. Prepare

```bash
export ORSZEM_JWT_SECRET="$(head -c 48 /dev/urandom | base64)"
export DATABASE_URL="postgresql://orszem:orszem@localhost:5432/orszem"

docker compose -f infra/compose/docker-compose.yml up -d db
( cd services/api && ORSZEM_JWT_SECRET="$ORSZEM_JWT_SECRET" \
  ./gradlew bootRun --args='--spring.profiles.active=local' ) &

scripts/reset-demo.sh
```

Install the two APKs on a device/emulator and set the API base URL to the host
(`http://10.0.2.2:8080/` from the emulator).

## 1. Baseline (Service App → Statistics)

Log in as `demo.service` / `OrszemDemo!2026`. The statistics tab shows:

| KPI | Value |
| --- | ---: |
| Összes | 120 |
| Ma | 16 |
| Aktív | 14 |
| Archivált | 106 |

Anchor values: Hangoskodás = 18, Budapest = 28, IC 123 = 20.

## 2. Public App → new report

1. Open **Őrszem**, tap `BEJELENTÉS INDÍTÁSA`.
2. Időpont: leave "most".
3. Vonat: `IC 123`.
4. Település: `Budapest`.
5. Mi történt? → search "kés" → **Késelés** (`KNIFE_ATTACK`).
6. `BEJELENTÉS KÜLDÉSE` → success screen.

## 3. Service App → handle the case

1. **Bejelentések** tab → the new `Késelés` report is at the top with status `ÚJ`.
2. Open it → `ESET ELFOGADÁSA` → status becomes `FOLYAMATBAN`.
3. `ESET LEZÁRÁSA ÉS ARCHIVÁLÁSA` → status becomes `ARCHIVÁLT`.
4. **Archívum** tab → the report is listed.

## 4. Statistics after the flow

| Stage | total | today | active | archived | KNIFE_ATTACK | Budapest | IC 123 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| after submit | 121 | 17 | 15 | 106 | 4 | 29 | 21 |
| after accept | 121 | 17 | 15 | 106 | 4 | 29 | 21 |
| after archive | 121 | 17 | 14 | 107 | 4 | 29 | 21 |

If any value or step differs, the demo is not in a presentable state — re-run
`scripts/reset-demo.sh` and start over.

## Automated equivalent

`services/api` → `PresentationFlowIT` executes exactly this flow against a real
PostgreSQL and asserts every number above.
