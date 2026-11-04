# v2.0.10 — release engineering report

Állapot: **előkészítve kiadásra és élesítésre.** Kiadási jegyzet: `docs/releases/RELEASE_NOTES_2.0.10.md`.

## 1. Hatókör és változások

1. **Önfrissítés az Őrszem Szolgálat appban (`hu.orszembejelento.service`):**
   - Új csomag: `hu.orszembejelento.service.update`
     - Domain: `UpdateManifest`, `UpdateDecision`, `UpdatePolicy`
     - Adatréteg: `UpdateRepository`, `HttpUpdateRepository`, `ApkVerifier`, `AndroidApkVerifier`, `ApkInstaller`, `AndroidApkInstaller`, `UpdateInstallReceiver`
     - Felület: `UpdateViewModel`, `UpdateGate`
   - Engedély: `REQUEST_INSTALL_PACKAGES` hozzáadva az `AndroidManifest.xml`-hez.
   - Biztonsági feltételek: same-origin APK URL ellenőrzés, SHA-256 hash validáció, csomagnév és aláíró tanúsítvány egyezőség (`e62ade239e0265f5ddc15e3018135ad0a5930d950cbf7390b55d1315d7b303cf`).
   - Tesztlefedettség: 24 új egységteszt (`UpdatePolicyTest`, `UpdateViewModelTest`, `HttpUpdateRepositoryTest`), az összes korábbi teszt sikeres.

2. **Szerveroldali hosting (`/letoltes/`):**
   - `deploy/live/Caddyfile.edge`: `handle_path /letoltes/*` blokk a `/srv/downloads` statikus kiszolgálására `Cache-Control: no-cache` beállítással a JSON manifestekre.
   - `deploy/live/docker-compose.yml`: `./run/downloads:/srv/downloads:ro` kötetcsatolás az `edge` konténerhez.
   - `deploy/caddy/Caddyfile`: szinkronizált útvonalkezelés.

## 2. Verziózás

| Komponens | Változás |
|---|---|
| Service Android | 2.0.9 (10) → **2.0.10 (11)** |
| Public Android | 2.0.9 (10) — változatlan |
| Backend | 2.0.9 — változatlan |
| Public Web | 2.0.9 — változatlan |

## 3. Artifact ujjlenyomatok (`work/release-2.0.10/`)

- `service-app-release-unsigned.apk` (2 142 660 B): `b448a4557d3cd425ef34dd9c4a6db89f6e6db9b7157cd163c999c6074d2a5667`
- Aláírt APK és `latest.json`: aláírás után a `sign-service-apk.ps1` futtatásával keletkezik.
- Tanúsítvány ujjlenyomat SHA-256: `e62ade239e0265f5ddc15e3018135ad0a5930d950cbf7390b55d1315d7b303cf`
