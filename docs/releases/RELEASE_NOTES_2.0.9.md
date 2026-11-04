# Őrszem V2 — 2.0.9 kiadási jegyzet

**Állapot: kiadva és productionben élesítve.**

Alap: `main` `0aa4c52`. Előző publikus kiadás: `v2.0.8`.

## Változások

1. **Üzenetküldés a bejelentőnek (`report_messages`, `V010`):**
   - Az Őrszem Szolgálat (`service-app`) alkalmazásban a ticket adatlapján megjelent az **„Üzenet a Bejelentőnek”** gomb.
   - **4 lépéses megerősített küldési folyamat:**
     1. *Üzenet a Bejelentőnek* gomb megnyomása a ticket adatlapján.
     2. *Üzenetszerkesztő ablak* többsoros szövegmezővel (legfeljebb 1000 karakter) és *Elküld* / *Mégse* gombokkal (a szerkesztő *Mégse* gombja küldés nélkül bezárja a folyamatot).
     3. *Megerősítő ablak:* „Ellenőrizte az üzenet tartalmát? Biztosan el szeretné küldeni? Az elküldött üzenet nem vonható vissza.” *Elküld* / *Mégse* gombokkal (a megerősítő *Mégse* gombja visszalép a szerkesztőbe a beírt szöveg megőrzésével).
     4. *Véglegesítés:* az üzenet kizárólag a backend sikeres mentése (`200 OK`) után minősül elküldöttnek; hiba esetén a folyamat visszalép a szerkesztőbe és a beírt szöveg megmarad.
   - Egy tickethez több üzenet is küldhető bármely státuszban (`NEW`, `IN_PROGRESS`, `ARCHIVED`), az elküldött üzenetek nem módosíthatók és nem vonhatók vissza, és minden üzenethez eltárolásra kerül a küldés időpontja, a küldő munkatárs azonosítója (`SZ-xxxxxx(Becenév)`), valamint `REPORT_MESSAGE_SENT` audit naplóbejegyzés.
2. **Bejelentői üzenetmegjelenítés (Public Web és Public Android):**
   - A bejelentő a saját bejelentésénél a weboldalon (`https://orszembejelento.hu`) és a Public Android alkalmazásban (Room `MIGRATION_2_3`, séma v3) az **„Üzenetek a szolgálattól”** szekcióban időrendben megtekintheti a neki küldött üzeneteket (a küldő belső azonosítóinak kiszivárogtatása nélkül).

## Verziók és élesített egységek

| Komponens | Verzió | Production állapot |
|---|---:|---|
| Backend | 2.0.8 → 2.0.9 | Élesítve (`V010` migrációval) |
| Public Web | 2.0.8 → 2.0.9 | Élesítve (`run/web` frissítve) |
| Service Android | 2.0.8 (9) → 2.0.9 (10) | Aláírva és ellenőrizve (`service-app-release-2.0.9-SIGNED.apk`) |
| Public Android | 2.0.8 (9) → 2.0.9 (10) | Aláírva és ellenőrizve (`public-app-release-2.0.9-SIGNED.apk`) |

Részletes kiadási és production bizonyíték: `docs/V2_0_9_RELEASE_ENGINEERING_REPORT.md`.
