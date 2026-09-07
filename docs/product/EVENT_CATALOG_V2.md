# Őrszem V2 — eseménykatalógus (referencia)

**Státusz:** aktív V2 referenciadokumentum
**Katalógus verzió:** `v2.0-draft`
**Kategóriák:** 7 · **Eseménytípusok:** 61

> **Ez dokumentáció, nem implementáció.** A taxonómia kódban, adatbázisban és API-ban
> még nincs megvalósítva: az `1. fázis` szándékosan nem tartalmaz üzleti funkciót. A
> tényleges adathalmaz és betöltése a `reference-data/` alatt, a 3. fázisban készül el.

Ez a lista a Demo v1.1 alatt validált taxonómia továbbvitele. Nem hivatkozásként épül a
`docs/archive/` tartalmára — az archívum kizárólag történeti referencia —, hanem önálló,
aktív V2 dokumentumként hordozza tovább a felülvizsgált tartalmat. Az eredeti, befagyasztott
Demo v1 változat a `demo-v1.1-final` tagen érhető el.

## 1. Szabályok (V2)

- A `code` **stabil gépi domain-azonosító**. Meglévő kód jelentése soha nem változtatható
  meg. Ez a V2 egyik alapszabálya, nem demo-korlátozás.
- A magyar `label` bármikor pontosítható nyelvi vagy érthetőségi okból anélkül, hogy a
  korábbi bejelentésekkel való kapcsolat elveszne.
- Új eseménytípus **új, stabil `code` értékkel** kerül be.
- Használatban lévő eseménytípust **soha nem törlünk**, csak inaktiválunk
  (`active = false`). A Demo v1 hard delete viselkedése nem öröklődik a V2-be.
- Későbbi metaadat (lokalizáció, kockázati szint, szolgálati routing) külön mezőben vagy
  táblában bővíthető, és nem változtatja meg a meglévő kódok jelentését.
- A gépi kód nem jelenik meg a lakossági felületen; ott a `label` látszik.
- A `sortOrder` határozza meg a kategóriák és típusok sorrendjét.

## 2. Nyitott kérdések a 3. fázis előtt

Ezeket a tulajdonosnak kell eldöntenie, mielőtt a katalógus adathalmazzá válik — lásd
`docs/DECISIONS_REQUIRING_OWNER.md`:

- marad-e a szabad szöveges leírás teljes tiltása, vagy lesz korlátozott kiegészítő mező;
- kell-e kockázati szint vagy szolgálati routing metaadat a típusokhoz;
- kell-e a `label` mellé hivatalos, jogszabályi megfeleltetés.

## 3. Kategóriák

| Sorrend | Kód | Megjelenítési név | Eseménytípusok |
|---:|---|---|---:|
| 10 | `VIOLENCE_DANGER` | Erőszak és közvetlen veszély | 10 |
| 20 | `DISTURBANCE_HARASSMENT` | Rendzavarás és zaklatás | 10 |
| 30 | `THEFT_PROPERTY` | Lopás és vagyon elleni esemény | 9 |
| 40 | `SUSPICIOUS_ACTIVITY` | Gyanús személy, tárgy vagy tevékenység | 8 |
| 50 | `MEDICAL_WELFARE` | Egészségügyi és segítségnyújtási esemény | 7 |
| 60 | `SAFETY_HAZARD` | Közlekedésbiztonsági és műszaki veszély | 10 |
| 70 | `RULE_VIOLATION_OTHER` | Szabályszegés és egyéb biztonsági esemény | 7 |

## 4. Eseménytípusok

### 4.1 Erőszak és közvetlen veszély

| Sorrend | Kód | UI-címke | Rövid jelentés |
|---:|---|---|---|
| 10 | `FIGHT` | Verekedés | Két vagy több személy közötti fizikai konfliktus. |
| 20 | `KNIFE_ATTACK` | Késelés | Késsel vagy más szúró-vágó eszközzel elkövetett támadás vagy annak közvetlen észlelése. |
| 30 | `PHYSICAL_ASSAULT` | Fizikai bántalmazás | Egy személy fizikai bántalmazása, amely nem verekedésként írható le. |
| 40 | `THREAT` | Fenyegetés | Személy elleni komoly verbális vagy egyértelmű fenyegetés. |
| 50 | `WEAPON_THREAT` | Fegyverrel fenyegetés | Fegyverrel vagy fegyvernek látszó eszközzel történő fenyegetés. |
| 60 | `ROBBERY` | Rablás | Erőszakkal vagy fenyegetéssel történő értékelvétel. |
| 70 | `PASSENGER_ASSAULT` | Utas megtámadása | Utas elleni közvetlen fizikai támadás. |
| 80 | `STAFF_ASSAULT` | Személyzet megtámadása | Vasúti vagy szolgálati személyzet elleni közvetlen fizikai támadás. |
| 90 | `GROUP_CONFLICT` | Csoportos konfliktus | Több személyt vagy csoportot érintő, eszkalálódó fizikai konfliktus. |
| 100 | `SEXUAL_ASSAULT_SUSPECTED` | Szexuális erőszak gyanúja | Szexuális jellegű kényszerítés vagy erőszak észlelésének gyanúja. |

### 4.2 Rendzavarás és zaklatás

| Sorrend | Kód | UI-címke | Rövid jelentés |
|---:|---|---|---|
| 10 | `LOUD_BEHAVIOR` | Hangoskodás | Tartósan vagy szélsőségesen zavaró hangoskodás. |
| 20 | `SHOUTING` | Kiabálás | A környezetet zavaró vagy konfliktushoz kapcsolódó kiabálás. |
| 30 | `AGGRESSIVE_BEHAVIOR` | Agresszív viselkedés | Fenyegető, támadó vagy erősen agresszív magatartás fizikai támadás nélkül. |
| 40 | `DISORDERLY_CONDUCT` | Rendbontás | A rendet vagy az utasok biztonságérzetét jelentősen zavaró magatartás. |
| 50 | `PASSENGER_HARASSMENT` | Utasok zaklatása | Egy vagy több utas ismételt vagy célzott zaklatása. |
| 60 | `STAFF_HARASSMENT` | Személyzet zaklatása | Vasúti vagy szolgálati személyzet célzott zaklatása. |
| 70 | `SEXUAL_HARASSMENT` | Szexuális zaklatás | Nem kívánt, szexuális jellegű zaklató magatartás. |
| 80 | `VERBAL_CONFLICT` | Szóbeli konfliktus | Heves vita vagy verbális konfliktus, amely még nem vált fizikaivá. |
| 90 | `INTOXICATED_PERSON` | Erősen ittas személy | Erősen ittas személy, akinek állapota rendzavarást vagy biztonsági problémát okoz. |
| 100 | `SUSPECTED_DRUG_USE` | Kábítószer-használat gyanúja | Kábítószer vagy más tiltott szer használatának észlelt gyanúja. |

### 4.3 Lopás és vagyon elleni esemény

| Sorrend | Kód | UI-címke | Rövid jelentés |
|---:|---|---|---|
| 10 | `THEFT` | Lopás | Tulajdon jogosulatlan eltulajdonítása. |
| 20 | `ATTEMPTED_THEFT` | Lopási kísérlet | Észlelt, de be nem fejezett lopási kísérlet. |
| 30 | `PICKPOCKETING` | Zsebtolvajlás | Személyes tárgy észrevétlen eltulajdonítása vagy annak kísérlete. |
| 40 | `BAGGAGE_THEFT` | Csomag vagy poggyász eltulajdonítása | Csomag, táska vagy poggyász eltulajdonítása. |
| 50 | `VANDALISM` | Rongálás | Vasúti vagy utastéri tulajdon szándékos megrongálása. |
| 60 | `GRAFFITI` | Graffiti | Vasúti jármű vagy berendezés összefirkálása, festése. |
| 70 | `SEAT_DAMAGE` | Ülés megrongálása | Ülés vagy annak tartozékainak szándékos megrongálása. |
| 80 | `WINDOW_DAMAGE` | Ablak megrongálása | Vonatablak megrongálása vagy betörése. |
| 90 | `TRAIN_EQUIPMENT_DAMAGE` | Vonati berendezés megrongálása | A jármű egyéb utastéri vagy műszaki berendezésének megrongálása. |

### 4.4 Gyanús személy, tárgy vagy tevékenység

| Sorrend | Kód | UI-címke | Rövid jelentés |
|---:|---|---|---|
| 10 | `SUSPICIOUS_PERSON` | Gyanús személy | Olyan személy, akinek viselkedése konkrét biztonsági aggályt kelt. |
| 20 | `SUSPICIOUS_GROUP` | Gyanús csoport | Olyan csoport, amelynek viselkedése konkrét biztonsági aggályt kelt. |
| 30 | `SUSPICIOUS_PACKAGE` | Gyanús csomag | Tulajdonságai vagy elhelyezése miatt gyanúsnak tűnő csomag. |
| 40 | `UNATTENDED_PACKAGE` | Elhagyott csomag | Gazdátlannak vagy felügyelet nélkül hagyottnak tűnő csomag. |
| 50 | `SUSPICIOUS_OBJECT` | Gyanús tárgy | Nem csomag jellegű, biztonsági aggályt keltő ismeretlen tárgy. |
| 60 | `SUSPECTED_WEAPON` | Fegyvernek tűnő tárgy | Lőfegyvernek, késnek vagy más fegyvernek tűnő tárgy észlelése fenyegetés nélkül. |
| 70 | `SUSPECTED_EXPLOSIVE` | Robbanóanyag vagy robbanószerkezet gyanúja | Robbanóanyagra vagy robbanószerkezetre utaló konkrét gyanú. |
| 80 | `TAMPERING_WITH_EQUIPMENT` | Gyanús beavatkozás vasúti berendezésbe | Vasúti vagy vonati berendezés jogosulatlannak tűnő manipulálása. |

### 4.5 Egészségügyi és segítségnyújtási esemény

| Sorrend | Kód | UI-címke | Rövid jelentés |
|---:|---|---|---|
| 10 | `ILLNESS` | Rosszullét | Olyan rosszullét, amely segítséget vagy személyzeti beavatkozást igényelhet. |
| 20 | `UNCONSCIOUS_PERSON` | Eszméletlen személy | Eszméletlen vagy nem reagáló személy észlelése. |
| 30 | `INJURED_PERSON` | Sérült személy | Láthatóan sérült vagy sérülés miatt segítségre szoruló személy. |
| 40 | `PERSON_NEEDS_HELP` | Segítségre szoruló személy | Olyan személy, aki láthatóan segítségre szorul, de a probléma nem sorolható pontosabban más eseménytípusba. |
| 50 | `CONFUSED_PERSON` | Zavart vagy dezorientált személy | Zavartnak, tájékozatlannak vagy dezorientáltnak tűnő személy. |
| 60 | `UNACCOMPANIED_CHILD` | Felügyelet nélkül maradt gyermek | Kísérő nélkül vagy veszélyeztetett helyzetben lévő gyermek. |
| 70 | `POSSIBLE_OVERDOSE` | Túladagolás gyanúja | Gyógyszer vagy szer túladagolására utaló állapot gyanúja. |

### 4.6 Közlekedésbiztonsági és műszaki veszély

| Sorrend | Kód | UI-címke | Rövid jelentés |
|---:|---|---|---|
| 10 | `FIRE_OR_SMOKE` | Tűz vagy füst | Tűz, füst vagy égésre utaló jel a vonaton vagy közvetlen környezetében. |
| 20 | `DOOR_MALFUNCTION` | Ajtó meghibásodása | Utasbiztonságot érintő ajtóhiba vagy rendellenes működés. |
| 30 | `BROKEN_GLASS_HAZARD` | Törött üveg vagy sérülésveszély | Törött üveg vagy más éles, sérülésveszélyt okozó elem. |
| 40 | `CARRIAGE_OBSTRUCTION` | Közlekedést akadályozó tárgy | A kocsiban történő biztonságos közlekedést akadályozó tárgy. |
| 50 | `LIQUID_SPILL` | Csúszásveszélyes kiömlött folyadék | Olyan kiömlött folyadék, amely elcsúszás vagy elesés veszélyét okozza. |
| 60 | `EMERGENCY_EXIT_BLOCKED` | Vészkijárat akadályozva | Vészkijárat vagy menekülési útvonal akadályozása. |
| 70 | `EMERGENCY_EQUIPMENT_DAMAGED` | Vészhelyzeti berendezés sérült | Vészjelző, vésznyitó vagy más vészhelyzeti berendezés sérülése. |
| 80 | `DANGEROUS_BEHAVIOR_NEAR_DOOR` | Veszélyes viselkedés az ajtónál | Ajtó, peron vagy ki-/beszállási terület közelében végzett közvetlenül veszélyes magatartás. |
| 90 | `PERSON_ON_TRACK_OR_DANGER_ZONE` | Személy a vágányon vagy veszélyzónában | Személy észlelése vágányon vagy más, közvetlen vasúti veszélyzónában. |
| 100 | `ELECTRICAL_OR_SPARK_HAZARD` | Elektromos hiba vagy szikrázás gyanúja | Szikrázás, elektromos rendellenesség vagy hasonló veszély észlelése. |

### 4.7 Szabályszegés és egyéb biztonsági esemény

| Sorrend | Kód | UI-címke | Rövid jelentés |
|---:|---|---|---|
| 10 | `SMOKING` | Dohányzás | Dohányzás a vonaton vagy más tiltott vasúti területen. |
| 20 | `VAPING` | Elektromos cigaretta használata | E-cigaretta vagy hasonló eszköz használata tiltott helyen. |
| 30 | `LITTERING` | Szemetelés | Hulladék szándékos eldobása vagy jelentős szemetelés. |
| 40 | `DOOR_OBSTRUCTION` | Ajtó szándékos akadályozása | Vonatajtó szándékos kitámasztása vagy akadályozása. |
| 50 | `AISLE_OBSTRUCTION` | Átjáró szándékos akadályozása | Folyosó vagy átjáró szándékos eltorlaszolása személyekkel vagy tárgyakkal. |
| 60 | `MISUSE_OF_EMERGENCY_EQUIPMENT` | Vészhelyzeti berendezés indokolatlan használata | Vészjelző, vészfék vagy más vészhelyzeti eszköz indokolatlan működtetése. |
| 70 | `OTHER_SAFETY_EVENT` | Egyéb biztonsági esemény | Más felsorolt eseménytípusba nem illő, de biztonsági szempontból releváns esemény. |

