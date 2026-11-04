# Őrszem V2 — 2.0.8 kiadási jegyzet

**Állapot: kiadva és productionben élesítve.**

Alap: `main` `e27c5b5`. Előző publikus kiadás: `v2.0.7`.

## Változások

1. **Szolgálati területek átalakítása — manuális település-hozzárendelés, teljes életciklus és 0% ticket-vesztés:**
   - A korábbi, előre feltöltött vasútvonal- és település-vasútvonal páros hozzárendelések helyét a manuális, település alapú szolgálati terület hozzárendelés (`service_area_settlements`) vette át.
   - `SUPER_ADMIN` jogosultsággal a szolgálati területek létrehozhatók, átnevezhetők, aktiválhatók, deaktiválhatók és törölhetők (`POST /api/v1/service/service-areas/areas/{areaId}/delete`).
   - Minden szolgálati terület szerkesztő felületén kereshető listából adhatók hozzá és távolíthatók el települések (`GET /api/v1/service/service-areas/settlements`, `POST .../assign`, `POST .../unassign`).
   - A beérkező bejelentések a településük alapján automatikusan a megfelelő aktív szolgálati területhez kerülnek (`ROUTED`).
   - **0% ticket-vesztés garancia:** ha egy település nincs aktív szolgálati területhez rendelve, vagy a szolgálati területet deaktiválják/törlik, a bejelentés `UNCLASSIFIED` (Besorolatlan) állapotba kerül, és minden aktív szolgálati felhasználó számára látható marad.
   - A Service Android alkalmazás Bejelentések nézetében vízszintes szűrő-chipekkel (`Összes`, `Besorolatlan`, illetve az egyes aktív szolgálati területek) egy érintéssel szűrhetők a bejelentések.

2. **Szabad szöveges megjegyzés mező (maximum 256 karakter):**
   - A Public Web (`https://orszembejelento.hu`) és a Public Android alkalmazás bejelentő űrlapja kiegészült egy opcionális, legfeljebb 256 karakteres szöveges megjegyzés mezővel (`reports.note VARCHAR(256)`).
   - A beküldött megjegyzés megjelenik a publikus visszaigazoló képernyőn, a helyi előzményekben, valamint a Service Android alkalmazás bejelentés-részletező és moderációs nézeteiben.

## Verziók és élesített egységek

| Komponens | Verzió | Production állapot |
|---|---:|---|
| Backend | 2.0.7 → 2.0.8 | Élesítve (`V009` migrációval) |
| Public Web | 2.0.7 → 2.0.8 | Élesítve (`run/web` frissítve) |
| Service Android | 2.0.7 (8) → 2.0.8 (9) | Aláírva és ellenőrizve (`service-app-release-2.0.8-SIGNED.apk`) |
| Public Android | 2.0.7 (8) → 2.0.8 (9) | Aláírva és ellenőrizve (`public-app-release-2.0.8-SIGNED.apk`) |

Részletes kiadási és production bizonyíték: `docs/V2_0_8_RELEASE_ENGINEERING_REPORT.md`.
