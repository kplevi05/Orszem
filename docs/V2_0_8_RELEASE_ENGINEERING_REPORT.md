# v2.0.8 — release engineering report

Állapot: **kiadva és productionben ellenőrizve.** Kiadási jegyzet: `docs/releases/RELEASE_NOTES_2.0.8.md`.

## 1. Hatókör és változások

1. **Szolgálati területek (`service_area_settlements`) és 0% ticket-vesztés:**
   - `V009__settlement_service_areas_and_report_note.sql` létrehozza a `service_area_settlements` táblát, kiüríti a korábbi `service_area_settlement_lines` és `service_area_railway_lines` táblákat, a nyitott bejelentéseket `UNCLASSIFIED` állapotba állítja, és frissíti a `ck_report_routing_snapshots_coherence` megszorítást.
   - `SUPER_ADMIN` számára elérhető a szolgálati területek létrehozása, átnevezése, aktiválása, deaktiválása és törlése, valamint települések hozzárendelése és eltávolítása.
   - A besorolatlan (`UNCLASSIFIED`) bejelentések minden szerepkör (`SUPER_ADMIN`, `MODERATOR`, `SERVICE_USER`) számára láthatók.
   - A Service Android alkalmazás Bejelentések képernyőjén közvetlen szolgálati terület + `Besorolatlan` szűrő-chipek érhetők el.
2. **Bejelentés megjegyzés mező (`reports.note VARCHAR(256)`):**
   - Opcionális, szélső szóközöktől megtisztított, legfeljebb 256 Unicode code point hosszúságú megjegyzés mező a Public Weben és a Public Android alkalmazásban (Room `MIGRATION_1_2`, séma v2 exportálva), megjelenítve a Service Android alkalmazásban is.

## 2. Verziózás

| Komponens | Változás |
|---|---|
| Backend | 2.0.7 → 2.0.8 |
| Public Android | 2.0.7 → 2.0.8, `versionCode` 8 → 9 |
| Service Android | 2.0.7 → 2.0.8, `versionCode` 8 → 9 |
| Public Web | 2.0.7 → 2.0.8 |

## 3. Artifact ujjlenyomatok (`work/release-2.0.8/`)

- `backend-2.0.8.jar` (55 149 190 B): `5b3d30072229b81ed96568f1b313f902ae78c95131fdaf344381bf1f330d9fb7`
- `public-web-2.0.8.tar.gz` (373 768 B): `c2572acd2a730f679ad27ae6f61ac884542237ace0e91bc5a0ddd9b6c2dde2ba`
- `service-app-release-unsigned.apk` (2 139 523 B): `b05e824b72ff896e1ded29f03a624908193475f1cc32af3be570a2c687239bec`
- `public-app-release-unsigned.apk` (2 003 684 B): `7a303a845029a83a65c8cfe14de2f693fafb46f9aa34ac55785ec57646c73dbf`
- `service-app-release-2.0.8-SIGNED.apk`: `f2276993c8eec5664d020391c40db5c171d55e6fbf05ca0b3ef2e479f180619d`
- `public-app-release-2.0.8-SIGNED.apk`: `9b4718a2ec1141b39a7d6736d827333e6ab17137c4a839822c2d0f013710ff92`
- Aláíró tanúsítvány SHA-256 (`apksigner verify --verbose --print-certs`):
  `e62ade239e0265f5ddc15e3018135ad0a5930d950cbf7390b55d1315d7b303cf`

## 4. Production rollout és migrációs bizonyíték (2026-11-04)

- **Preflight backup és disposable restore drill:**
  - Dump: `/home/opc/backups/v2/orszem_v2_20261104T095706Z.dump` (305 046 B)
  - SHA-256: `90fd611aa7d9c5f1a7b40c60db222ec448b47e08139778166a901e28172fa602`
  - Eredmény: 19/19 tábla bájtra azonos (`RESULT: every table restored with identical content`).
- **Rollout (`v2.0.8-deploy.sh`):**
  - Megőrzött rollback csomagok: `run/backend.pre-v2.0.8.jar` és `run/web.pre-v2.0.8.tar.gz`.
  - Telepített `run/backend.jar` SHA-256: `5b3d30072229b81ed96568f1b313f902ae78c95131fdaf344381bf1f330d9fb7`.
  - `orszem-v2-backend` container állapota: `healthy`.
  - `flyway_schema_history` legutolsó bejegyzés: `009 | true`.
  - Bejelentések száma (0% ticket-vesztés): `85 -> 85`.
  - `/api/v1/meta`: HTTP `200`.
  - Public Web (`run/web`) frissítve a `c2572acd2a730f679ad27ae6f61ac884542237ace0e91bc5a0ddd9b6c2dde2ba` csomagból; `https://orszembejelento.hu/` HTTP `200`, publikus településkeresés smoke teszt `OK`.
