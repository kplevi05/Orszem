# v2.0.9 — release engineering report

Állapot: **kiadva és productionben ellenőrizve.** Kiadási jegyzet: `docs/releases/RELEASE_NOTES_2.0.9.md`.

## 1. Hatókör és változások

1. **Üzenetküldés a bejelentőnek (`report_messages`, `V010`):**
   - `V010__report_messages.sql` létrehozza a `report_messages` táblát (`id`, `report_id`, `sender_user_id`, `message_text VARCHAR(1000)`, `sent_at TIMESTAMPTZ`), 1–1000 karakteres megszorítással (`ck_report_messages_text_length`) és `(report_id, sent_at ASC, id ASC)` indexszel.
   - `POST /api/v1/service/reports/{publicReportId}/messages` végpont: bármely jogosult szolgálati felhasználó küldhet üzenetet bármely nem törölt bejelentésre; a mentés `REPORT_MESSAGE_SENT` audit eseményt rögzít, és a válasz a frissített `ReportDetailResponse`-t adja vissza.
   - `GET /api/v1/public/reports/{publicReportId}` végpont: a bejelentőnek visszaadja a `messages` listát (`id`, `messageText`, `sentAt`), belső felhasználói azonosítók nélkül.
   - Service Android alkalmazás (`ReportDetailViewModel`, `ReportDetailScreen`): 4 lépéses folyamat (*Üzenet a Bejelentőnek* gomb $\rightarrow$ *Üzenetszerkesztő ablak* $\rightarrow$ *Megerősítő ablak* $\rightarrow$ *Véglegesítés*), a két *Mégse* gomb eltérő működésével és a piszkozat garantált megőrzésével hálózati/szerverhiba esetén.
   - Public Web és Public Android alkalmazás (Room `MIGRATION_2_3`, séma v3 exportálva): az *Üzenetek a szolgálattól* szekció megjelenítése a saját bejelentéseknél.

## 2. Verziózás

| Komponens | Változás |
|---|---|
| Backend | 2.0.8 → 2.0.9 |
| Public Android | 2.0.8 → 2.0.9, `versionCode` 9 → 10 |
| Service Android | 2.0.8 → 2.0.9, `versionCode` 9 → 10 |
| Public Web | 2.0.8 → 2.0.9 |

## 3. Artifact ujjlenyomatok (`work/release-2.0.9/`)

- `backend-2.0.9.jar`: `88beeac84353002508f6a701f2193dd6c0725cca07a2e7f44a6d0267448b35ac`
- `public-web-2.0.9.tar.gz`: `31420203070f7dd2174efe9fe7ac3f465af4c69ca974d30dbd4cc008b9bd1710`
- `service-app-release-unsigned.apk`: `a61f46b4d25232b7f51335212ac79e47db51a930cb780a4b69c81bbef94776c5`
- `public-app-release-unsigned.apk`: `af37d2da352cb5afeffc1e97d9894f9115117ae7ed828d90eba104c6a93ec5c1`
- `service-app-release-2.0.9-SIGNED.apk`: `616f357ca61ce91ab6f4bdac6b9e97bf8874a1e481e1895759f5c1e3fd2600a0`
- `public-app-release-2.0.9-SIGNED.apk`: `a810a7d0b36f3199a941241c0e1e88d08c085414ef5b7dbd88fcf22ec00e9a1f`
- Aláíró tanúsítvány SHA-256 (`apksigner verify --verbose --print-certs`):
  `e62ade239e0265f5ddc15e3018135ad0a5930d950cbf7390b55d1315d7b303cf`

## 4. Production rollout és migrációs bizonyíték (2026-11-04)

- **Preflight backup és disposable restore drill:**
  - Dump: `/home/opc/backups/v2/orszem_v2_20261104T144855Z.dump` (310 435 B)
  - SHA-256: `49d97955b4312ac270c320a95d7fa42071eea88d2934f8e88493d04e15ec12a6`
  - Eredmény: 20/20 tábla bájtra azonos (`RESULT: every table restored with identical content`).
- **Rollout (`v2.0.9-deploy.sh`):**
  - Megőrzött rollback csomagok: `run/backend.pre-v2.0.9.jar` és `run/web.pre-v2.0.9.tar.gz`.
  - Telepített `run/backend.jar` SHA-256: `88beeac84353002508f6a701f2193dd6c0725cca07a2e7f44a6d0267448b35ac`.
  - `orszem-v2-backend` container állapota: `healthy`.
  - `flyway_schema_history` legutolsó bejegyzés: `010 | true`.
  - Bejelentések száma (0% ticket-vesztés): `88 -> 88`.
  - `/api/v1/meta`: HTTP `200`.
  - Public Web (`run/web`) frissítve a `31420203070f7dd2174efe9fe7ac3f465af4c69ca974d30dbd4cc008b9bd1710` csomagból; `https://orszembejelento.hu/` HTTP `200`, publikus településkeresés smoke teszt `OK`.
