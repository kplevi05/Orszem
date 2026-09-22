# Nationwide KSH fallback — production rollout plan

**Prepared only. Do not treat this document as evidence that production was changed.**

This rollout makes the already-live V2 usable nationwide before cleared railway-line
coverage is available. It deploys no new Android/Web client code: the shipped clients
already handle a canonical settlement with zero verified RailwayLine candidates by
submitting with `railwayLineId = null`. The server-side changes in this release make
those resulting `UNCLASSIFIED` reports operationally actionable by ordinary active
`SERVICE_USER` accounts while the temporary fallback switch is enabled.

The only real reference material used by this rollout is
`reference-data/cleared/` (KSH settlement catalogue, CC BY 4.0). The PENDING
VPE/KTI/GYSEV railway datasets are not part of this rollout.

## 1. Hard gates before touching live

1. The feature branch is merged to `main`.
2. All six GitHub checks are green on the exact merged `main` SHA:
   backend, Android, Web, reference-data validate, Caddy, backup/restore scripts.
3. Record that exact SHA as `RELEASE_SHA`.
4. Keep the currently deployed `backend.jar` as the previous known-good application
   artifact.
5. On the VM, create a fresh V2 backup and run the disposable restore drill:

   ```bash
   cd /home/opc/apps/orszem-v2
   ./backup.sh backup
   ./backup.sh drill
   ```

   Do not continue unless both succeed.

There is no Flyway migration in this phase. The backup is still required because the
reference import changes production data.

## 2. Stage the exact merged artifacts

Build the backend from the exact merged `RELEASE_SHA`, never from an uncommitted tree:

```bash
git checkout "$RELEASE_SHA"
cd backend
./gradlew clean bootJar
```

Stage that JAR as `/home/opc/apps/orszem-v2/run/backend.jar.next` first; do not replace
the running JAR yet.

Copy the complete directory `reference-data/cleared/` to:

```text
/home/opc/apps/orszem-v2/datasets/cleared/
```

The server-side copy must contain exactly:

- `manifest.json`
- `settlements.csv`
- `railway-lines.csv` (header only)
- `settlement-railway-lines.csv` (header only)

Do not copy anything from `reference-data/local-research/`.

Update the server's live Compose file from the merged repository version. The backend
environment must explicitly contain:

```yaml
ORSZEM_UNCLASSIFIED_SERVICE_USER_ACCESS_ENABLED: "true"
```

Validate the Compose configuration before applying it:

```bash
cd /home/opc/apps/orszem-v2
docker compose --env-file .env config >/dev/null
```

## 3. Validate and dry-run the cleared KSH dataset

Use the supported maintenance CLI against the live V2 database:

```bash
cd /home/opc/apps/orszem-v2
./admin.sh reference-validate /datasets/cleared
./admin.sh reference-diff /datasets/cleared
```

The diff must be reviewed before import.

Expected shape:

- all 3,178 cleared KSH settlements are represented;
- settlement coverage is `COMPLETE`;
- RailwayLine coverage is `PARTIAL`;
- settlement↔RailwayLine coverage is `PARTIAL`;
- the import must **not** deactivate/remove existing RailwayLines merely because the
  cleared dataset contains zero railway rows;
- the import must **not** remove existing settlement↔line relations merely because the
  cleared dataset contains zero relation rows;
- fictional placeholder settlements absent from the KSH catalogue may become inactive;
  historical reports remain resolvable because reference rows are not hard-deleted.

If the diff proposes RailwayLine or relation removal, STOP. That contradicts the intended
PARTIAL-coverage safety property.

## 4. Import the KSH settlement catalogue

After the diff is accepted:

```bash
./admin.sh reference-import /datasets/cleared
```

Immediately re-run:

```bash
./admin.sh reference-diff /datasets/cleared
```

It should now report no pending settlement changes for the imported dataset.

Do not manually create RailwayLines or settlement↔line mappings as part of this rollout.

## 5. Deploy the backend and enable the nationwide operational pool

Replace the backend artifact atomically enough to keep rollback simple:

```bash
cd /home/opc/apps/orszem-v2
cp run/backend.jar "run/backend.pre-ksh-$RELEASE_SHA.jar"
mv run/backend.jar.next run/backend.jar
docker compose --env-file .env up -d --force-recreate backend
docker compose --env-file .env ps
```

Wait until `orszem-v2-backend` reports healthy.

The Public Web bundle and Android APKs do not need replacement for this feature.

## 6. Production smoke checks

Read-only checks first:

```bash
curl -fsS 'https://api.orszembejelento.hu/api/v1/public/reference/settlements?query=Tata'
curl -fsS 'https://api.orszembejelento.hu/api/v1/meta'
```

Verify:

- `Tata` appears as a canonical settlement result;
- the KSH source attribution remains present where the current API/client contract exposes it;
- actuator/OpenAPI/Swagger remain blocked publicly;
- backend logs contain no exception loop or secret-shaped value.

Then perform one deliberately-created throwaway Public report only with owner approval:

1. select a real KSH settlement that has zero verified line relations;
2. submit without a RailwayLine;
3. confirm Public receives the normal accepted status;
4. with an ACTIVE ordinary SERVICE_USER, confirm the report appears in the normal NEW queue
   as `Besorolatlan`;
5. claim it;
6. confirm it appears only in that SERVICE_USER's IN_PROGRESS queue;
7. return it and confirm it reappears in the nationwide NEW pool;
8. claim again and close it;
9. confirm Public status becomes CLOSED and Service Archive remains readable;
10. confirm a territorial SERVICE_USER still cannot see a routed report outside their
    ServiceArea.

No internal routing reason code should be shown in either client.

## 7. Temporary-policy cutover rule

Do **not** turn
`ORSZEM_UNCLASSIFIED_SERVICE_USER_ACCESS_ENABLED` back to `false` while ordinary
SERVICE_USER accounts still hold open UNCLASSIFIED assignments.

With the strict policy restored, those assignments would intentionally cease to be
SERVICE_USER-visible. Before disabling the fallback, first ensure every open UNCLASSIFIED
assignment has been returned, closed, or otherwise resolved by a supervisor.

This is an operational cutover prerequisite, not a reason to invent an area mapping.

## 8. Rollback

### Backend-only rollback

Because this phase has no schema migration, the previous JAR can be restored:

```bash
cd /home/opc/apps/orszem-v2
# restore the saved previous JAR to run/backend.jar
docker compose --env-file .env up -d --force-recreate backend
```

Also set the temporary fallback variable back to `false`/remove it if the previous backend
does not know the property.

### Full pre-rollout data rollback

The KSH import is a production-data change. If the production reference state itself must be
restored exactly to its pre-rollout state, stop writes and restore the verified backup taken
in §1 into a fresh database using the established V2 restore procedure. Do not edit
`flyway_schema_history` and do not destructively overwrite the live database in place.

V1 remains untouched throughout this rollout.
