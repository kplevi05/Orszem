# Őrszem V2 — 2.1.1 kiadási jegyzet

**Állapot: kiadásra kész.**

Alap: `v2.1.0`. Előző szerveroldali kiadás: `v2.1.0`.

## Főbb változások és fejlesztések

1. **Folyamatban lévő bejelentések felosztása a Szolgálati felületen (`ReportsScreen`):**
   - A Folyamatban lévő bejelentések fül tetején a statikus tájékoztató szöveg helyét két szegmentált vezérlőgomb vette át: **Minden eset** | **Saját esetek**.
   - **Minden eset:** megjeleníti a szolgálati munkatárs illetékességi körébe tartozó valamennyi folyamatban lévő esetet a területen, megkönnyítve az áttekintést és az együttműködést a kollégák között.
   - **Saját esetek:** közvetlenül a bejelentkezett felhasználóhoz rendelt ügyeket szűri ki (`assigneeServiceId`), így azonnal láthatóvá válnak a saját feladatok.
   - Üres állapot esetén a felület egyértelmű, szűrés-specifikus üzenetet ad ("Nincs folyamatban lévő bejelentés." vs. "Nincs saját folyamatban lévő bejelentése.").

2. **Backend jogosultsági finomhangolás (`ReportWorkflowPolicy`, `JdbcReportWorkflowQueryRepository`):**
   - A szolgálati munkatársak (`SERVICE_USER`) számára a folyamatban lévő esetek listájánál (`findInProgressQueue`) a backend megnyitja a területen belüli folyamatban lévő bejelentések olvasási jogosultságát ("Minden eset"), miközben a lezárási (`CLOSE`) és visszadobási (`RETURN`) műveletek továbbra is szigorúan csak a saját ügyintézés alatt álló bejelentésekre engedélyezettek.

3. **In-App frissítési jegyzék (v2.1.1, versionCode: 13):**
   - A 2.0.10 és 2.1.0 verziójú Szolgálati alkalmazások automatikusan felajánlják a frissítést az új 2.1.1-es verzióra.
   - A kiadási leírás ékezetes karakterei garantáltan helyes kódolással jelennek meg a felugró frissítési ablakban.

## Verziók és komponensek

| Komponens | Verzió | Megjegyzés |
|---|---:|---|
| Service Android | 2.1.0 (12) → **2.1.1 (13)** | "Minden eset" / "Saját esetek" szűrőgombok a folyamatban lévő nézetben |
| Public Android | 2.1.0 (12) → **2.1.1 (13)** | Verziószám igazítás |
| Backend | 2.1.0 → **2.1.1** | Szolgálati felhasználói folyamatban-nézet kiterjesztés, Flyway V010 séma érintetlen |
| Public Web | 2.1.0 → **2.1.1** | Verziószám igazítás |
