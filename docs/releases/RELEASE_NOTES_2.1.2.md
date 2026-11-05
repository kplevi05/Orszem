# Őrszem V2 — 2.1.2 kiadási jegyzet

**Állapot: kiadásra kész.**

Alap: `v2.1.1`. Előző szerveroldali kiadás: `v2.1.1`.

## Főbb változások és hibajavítások (Reliability & Integrity Bugfix Release)

1. **Szolgálati terület inaktiválás adatintegritása (JAV-01):**
   - Helyreállítva a szigorú BLOCK üzleti szabály: szolgálati terület inaktiválása meghiúsul (`409 Conflict`), amennyiben a területhez akár egyetlen nyitott (`NEW` vagy `IN_PROGRESS`) bejelentés tartozik.
   - Megszüntetve az inaktiválás alatti korai törlési mellékhatás (`unclassifyAllReportsOfArea`), amely korábban kitörölte a jegyek történeti területét (`service_area_id`).
   - A lezárt (`ARCHIVED`) jegyek területi azonosítója, routing snapshotja és feladatkiosztási története (`report_assignments`) az inaktiválás után is véglegesen és sértetlenül megmarad.

2. **Versenyhelyzetek és konkurens tranzakciók védelme (JAV-02 / JAV-03):**
   - A jegyfelvételi tranzakció (`ClaimReportUseCase`) mostantól explicit megosztott tanácsadói zárat tart a referenciaállapoton (`acquireSharedReferenceStateLock`), megelőzve a versenyhelyzeteket a párhuzamos település-átirányításokkal és terület-inaktiválásokkal szemben.
   - Sorszintű zárolás után a rendszer automatikusan újraolvassa a friss routing snapshotot a legfrissebb jogosultsági elbíráláshoz.

3. **Workflow hibakód-prioritások javítása (JAV-05):**
   - A jegylezárási (`CloseReportUseCase`) és visszaadási (`ReturnReportUseCase`) folyamatokban helyreállt a pontos hibakód-prioritás: a jogosultsági kapu elválasztja a láthatósági vizsgálatot a státuszvizsgálattól. Már lezárt jegynél egyértelmű `409 ReportAlreadyArchivedException`, nyitott vagy kolléga által vitt jegy jogosulatlan lezárásakor `403 ReportWorkflowForbiddenException` képződik a megtévesztő `404` helyett.

4. **Adatbázis-baseline és sémateszt aktualizálás (JAV-06):**
   - A `DatabaseBaselineIT` és `ReportSchemaIT` integrációs tesztek szinkronba hozva a V009 (tisztán településalapú területi besorolás) és V010 (szolgálati üzenetek tábla) Flyway migrációkkal.

5. **Gazdátlan folyamatban lévő jegyek jogosultsági szűrése (JAV-07):**
   - A területhez nem sorolt (`UNCLASSIFIED`) fallback bejelentéseknél a folyamatban lévő ügyek listájában (`findInProgressQueue`) a szolgálati munkatárs kizárólag a saját maga által felvett jegyeket látja, megelőzve az idegen szervezetek közötti felesleges interferenciát. A területhez rendelt (`ROUTED`) jegyeknél a kollégák közötti "Minden eset" láthatóság változatlanul megmaradt.

6. **Webes frontend optimalizálás (JAV-08):**
   - Eltávolításra kerültek a korábbi vonalválasztó felületből megmaradt, nem használt komponensek és típusimportok a `public-web` csomagból.

## Verziók és komponensek

| Komponens | Verzió | Megjegyzés |
|---|---:|---|
| Backend | 2.1.1 → **2.1.2** | Adatintegritási javítások (JAV-01, 02, 03, 05, 06, 07), 930 teszt zöld |
| Public Web | 2.1.1 → **2.1.2** | Kódtisztítás (JAV-08), típusellenőrzött statikus build |
| Service Android | 2.1.1 (13) → **2.1.2 (14)** | Verziószám és versionCode léptetés |
| Public Android | 2.1.1 (13) → **2.1.2 (14)** | Verziószám és versionCode léptetés |
