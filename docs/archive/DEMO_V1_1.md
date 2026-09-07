# Őrszem Demo v1.1 — archive record

**Archive date:** 2026-09-07
**Archived commit:** `38540f632b8744c10486969c4a0125c208e14c6e`
**Annotated tag:** `demo-v1.1-final` (tag object `86e84427b41f8ed122cf5843edeb351491207b78`)
**Repository:** `https://github.com/kplevi05/Orszem`

Demo v1 / v1.1 was a time-boxed demonstration product. It is complete, frozen and no longer
developed. The tag above makes the entire V1 source reproducible:

```bash
git checkout demo-v1.1-final
```

Because the source is recoverable from Git, it is **not** duplicated inside the V2 tree. The V1
documentation set is preserved for reference under `docs/archive/demo-v1/`; every file there carries
a banner stating it is a historical record only.

> **V1 data, authentication and API are NOT migrated into V2.**
> V2 starts from a clean database, a new authentication design, new application identities and a
> newly grown API contract. Nothing in this archive is a V2 requirement.

---

## 1. Application identities

| Component | Identity at archive time |
|---|---|
| Public Android | `hu.orszem.publicapp` (debug suffix `.debug`) |
| Service Android | `hu.orszem.serviceapp` (debug suffix `.debug`) |
| Backend Gradle group / package root | `hu.orszem` |
| Android versionName / versionCode | `1.1.0` / `2` |
| App display names | `Őrszem`, `Őrszem Szolgálat` |

V2 deliberately uses different identities (`hu.orszembejelento.app`, `hu.orszembejelento.service`,
`hu.orszembejelento.backend`), so V1 and V2 builds can coexist on one device and cannot be mistaken
for upgrades of one another.

## 2. Technology stack

**Backend** — Kotlin 2.1.0, Spring Boot 3.4.5, Gradle 8.14, Java toolchain 21, PostgreSQL 16,
Flyway (single migration `V1__init_demo_schema.sql`), plain Spring JDBC (no JPA), JJWT 0.12.6,
Bucket4j 8.14.0, BouncyCastle 1.80. Single Gradle module, modular-monolith package layout
(`analytics`, `audit`, `auth`, `catalog`, `demo`, `identity`, `reporting`, `servicecase`, `shared`).

**Android** — Kotlin 2.1.0, AGP 8.7.3, Gradle 8.11.1, Compose BOM 2024.12.01, Hilt 2.53.1,
Retrofit 2.11.0, OkHttp 4.12.0, `compileSdk` / `targetSdk` 35, `minSdk` 26, JVM target 17.
Seven Gradle modules: two apps plus `core:{common,model,network,designsystem,testing}`.

**Contract** — hand-written OpenAPI 3.1.0 at `contracts/openapi/orszem-v1.yaml`, linted with
Redocly. No code generation, and no springdoc in the backend.

## 3. Deployment at archive time

| Item | Value |
|---|---|
| Provider | Oracle Cloud Infrastructure |
| Shape / OS | `VM.Standard.A1.Flex`, 1 OCPU / 6 GB, ARM64 — Oracle Linux 9 |
| Public IPv4 | `129.159.31.175` |
| Public host | `129-159-31-175.sslip.io` (wildcard DNS; no owned domain in V1) |
| Public API base URL | `https://129-159-31-175.sslip.io/` |
| SSH user | `opc` |
| Application directory | `/home/opc/apps/orszem` |
| Orchestration | Docker Compose (`infra/compose/docker-compose.prod.yml`) — **no systemd units** |
| Edge | Caddy **container**, the only one publishing ports (80, 443) |
| Database | PostgreSQL 16 container, database and role both `orszem`, never published |
| Backend port | `8080`, internal to the `orszem-internal` bridge network only |

The V2 deployment uses a separate directory, a separate database and a distinct internal port so it
cannot collide with or overwrite this one. See `docs/deployment/SERVER_RUNBOOK.md`.

## 4. Release artifacts and signing identity

The final APKs are not committed (the repository ignores `*.apk`). They exist on the build machine
under `apps/android/build/`. Measured on 2026-09-07 with `apksigner verify --print-certs`
(build-tools 36.0.0):

| Artifact | SHA-256 of file | Signing certificate SHA-256 |
|---|---|---|
| `demo-apks/orszem-public-demo-v1.1.apk` | `82867d9e7173f23f00e327f0fc63b77388e97d2e2f7f95dac7e560a83aeae0dd` | `9bd6d989…85c0` |
| `demo-apks/orszem-szolgalat-demo-v1.1.apk` | `54c64edfd9f3e237cdcd20f8af3a72b9d3fd2a4550300ee47ee44b16f5d8c5cb` | `9bd6d989…85c0` |
| `pilot-apks/orszem-public-demo-v1.1.apk` | `841410b25adc17c3d1c0b85cf71212b3f494e3f1ca80949b8a3adbb636a72d46` | `745f5fa8…a51a` |
| `pilot-apks/orszem-szolgalat-demo-v1.1.apk` | `3acf35450e948e84a52abf2f4fc55a42cba1e522595eee2107e64ffc3fd3609b` | `745f5fa8…a51a` |
| `pilot-apks/orszem-public-demo-v1.1.apk.idsig` | `52124b20872f43c4352d8d7d0cb07c677913a694e896c5d2cc5601b489c83865` | — |
| `pilot-apks/orszem-szolgalat-demo-v1.1.apk.idsig` | `39725ab3d2229c92b703d10b7fadc1c179be1d85e469fb8c63b4d92edb8397d2` | — |

Full certificate fingerprints:

```
debug identity : 9bd6d989587ad7d13c70b4fc251a489e8e174999bec09e78beab8fcb962785c0
pilot identity : 745f5fa8c69f4742bb4ba5cc8044dab008a14d2e649ecc027751fa001065a51a
```

**Two distinct signing identities were measured, not assumed:**

- `9bd6d989…85c0` is the **Android debug identity** (`C=US, O=Android, CN=Android Debug`). Confirmed
  by building a fresh debug APK on the same machine and comparing fingerprints. The `demo-apks/` set
  is debug-signed, matching the build file's `signingConfig = signingConfigs.getByName("debug")`.
- `745f5fa8…a51a` is a **non-debug certificate issued to the project owner** (a real personal or
  organisational DN, not `CN=Android Debug`). The `pilot-apks/` set carries it, is APK Signature
  Scheme v4 signed (`.idsig` present), and was produced by a manual run that no repository script
  reproduces.

The candidate keystore for `745f5fa8…a51a` is `orszem-pilot.jks` in the owner's private signing
directory outside this repository. That could **not** be confirmed here, because reading a keystore
requires its password, which is deliberately never handled by tooling nor recorded anywhere. The
owner can confirm it themselves:

```bash
keytool -list -v -keystore <path-to>/orszem-pilot.jks
```

**Consequence:** whichever set was actually distributed determines the upgrade path. If the
`pilot-apks/` set was distributed, then the private keystore holding `745f5fa8…a51a` is the **only**
way to publish an in-place update to installed V1 builds. That keystore and its password must be
backed up outside the build machine. Neither is committed here, and **neither is reused for V2** —
V2 receives a new long-term release identity, described in `docs/deployment/ANDROID_SIGNING.md`.

## 5. Verification performed at archive time

Run on the archived commit, before any V2 restructuring, on Windows 11 with Temurin JDK 21.0.12.1:

| Command | Result |
|---|---|
| `apps/android> ./gradlew testDebugUnitTest :public-app:assembleDebug :service-app:assembleDebug` | **BUILD SUCCESSFUL** in 1m 37s — 58 unit tests, 0 failures, 0 errors, 0 skipped |
| `services/api> ./gradlew compileKotlin compileTestKotlin test` (unit tests only) | **BUILD SUCCESSFUL** in 42s — 17 unit tests, 0 failures, 0 errors |

V1 was verified as-is. No V1 defect was fixed during archiving.

### 5.1 Backend verification — partial

The backend was compiled and its Docker-free unit tests were run from a worktree checked
out at this tag: main and test sources compile, and all 17 unit tests pass.

**The 53 integration tests were NOT run.** Every one of them starts a PostgreSQL container
through Testcontainers, and the Docker engine on the build machine would not start during
the archiving session (Docker Desktop was running but never provisioned its WSL backend).

This is an environment limitation, not an observed V1 defect. Nothing indicates the
integration tests would fail; they simply were not executed, and this record does not claim
otherwise. They can be run later with `services/api> ./gradlew build` from a checkout of
`demo-v1.1-final` on a machine with a working Docker engine.

## 6. Known limitations of Demo v1.1

- **Demo credentials are public.** The fixture service login (`demo.service` and its password) is
  committed in plaintext across the V1 tree, and its Argon2id hash is in the demo seed SQL. The
  deployment at `https://129-159-31-175.sslip.io/` accepts it. This must not remain reachable on the
  internet — see `docs/deployment/V1_DECOMMISSION.md`.
- **No production signing pipeline.** The build files never defined a release signing config; the
  `pilot-apks/` identity was applied out-of-band by hand.
- **Demo-only behaviour in non-local profiles.** `orszem.demo.deletion-enabled` defaults to `true` in
  both the `local` and `demo` profiles, and a demo reset endpoint exists behind a shared token.
- **Scope was intentionally narrow.** No free-text descriptions, no photos, no push, no offline
  support, no user management, no password reset, no area routing, no moderation, no refresh tokens.
  The report state machine was exactly `NEW → IN_PROGRESS → ARCHIVED`, with no editing or reopening.
- **Every backend integration test requires Docker.** There is no H2 or slice-test tier, and the demo
  seed reaches the database through Flyway `locations` rather than through test fixtures.
- **`MANIFEST.sha256` was already stale** at archive time: it listed the pre-implementation file set
  and an outdated migration path.
- **Database contents are not in Git.** The V1 database is archived separately; see
  `docs/deployment/V1_DATABASE_ARCHIVE.md`. At the time this record was written, that dump had **not**
  yet been taken.

## 7. What was deliberately carried forward into V2

Only material that was re-validated, and only as **new active V2 documents** — never by citing this
archive:

| V2 document | Origin |
|---|---|
| `docs/product/EVENT_CATALOG_V2.md` | the V1 event taxonomy, restated as V2 reference documentation |
| `docs/ENGINEERING_RULES.md` | the V1 source-priority ladder and validation principles |
| `docs/DECISIONS_REQUIRING_OWNER.md` | the still-open production questions, reworded for V2 |

Everything else under `docs/archive/demo-v1/` is history.
