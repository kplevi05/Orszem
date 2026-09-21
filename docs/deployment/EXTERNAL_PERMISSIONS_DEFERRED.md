# EXTERNAL PERMISSIONS — DEFERRED

**Owner decision (2026-09-21): external permissions and reuse approvals are deferred.** They are documented and prepared
here and added to the production/compliance backlog. **No request has been sent, and nothing waits on them:** the runnable
evaluation environment uses only cleared or fictional data (see [EVALUATION_ENVIRONMENT.md](EVALUATION_ENVIRONMENT.md)).

| # | Permission | Status | Blocks | Where it is prepared |
|---|---|---|---|---|
| 1 | **KTI VPE Igazgatóság**: written confirmation to use, publish through the API, modify/normalise and commercially use the railway line roster and settlement↔line relations derived from HÜSZ 2026/2027 annexes 5.2-4 and 5.2-5 | **Prepared. NOT SENT. SEND LATER.** `vpe@kti.hu` not verified as the current contact | Production use of the real railway dataset (B9-A). **Does not block the evaluation** | [VPE_PERMISSION_REQUEST_DRAFT.md](VPE_PERMISSION_REQUEST_DRAFT.md), [B9_DECISION_PACKAGE.md](B9_DECISION_PACKAGE.md) |
| 2 | **GYSEV Zrt.**: whether GYSEV-derived rows (annex 5.2-5) need separate permission; gysev.hu's legal notice forbids putting site content in a database without written permission | **Asked inside request #1 (question 6); may require a separate request.** NOT SENT | The GYSEV part of #1 | same draft |
| 3 | Any other external party | **None discovered** while preparing the evaluation (below) | | |

Both railway components stay `reuseStatus: PENDING`. The recovered VPE/GYSEV-derived dataset (backup outside Git) is **not** used in the
evaluation environment, and the importer's gate (`REFERENCE_DATASET_REUSE_NOT_CLEARED`) is unchanged.

## Checked while preparing the runnable evaluation

| Component | Permission needed? |
|---|---|
| KSH settlement names (real dataset) | Already cleared (CC BY 4.0, attribution shipped). The evaluation does not even use them |
| Evaluation data (`reference-data/example`, `reference-data/evaluation`) | Fictional, invented for this repository; no third-party rights |
| Container images (PostgreSQL, Eclipse Temurin, Caddy) | Free open-source images from public registries; no permission needed; no paid service |
| Android evaluation APKs | Debug-signed, labelled evaluation builds; no store, no external approval |

## Later production/compliance gates (unchanged, owner-only)

Real railway dataset clearance (#1, #2); production signing key; DNS/HTTPS cutover; V1 backup, retirement and rollback; production
configuration and secrets. See [PRODUCTION_READINESS.md](../PRODUCTION_READINESS.md).
