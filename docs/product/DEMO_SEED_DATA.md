# Őrszem — Demo v1 seed adatcsomag

**Seed verzió:** `demo-v1.0`  
**Cél:** a szolgálati és statisztikai felület az első indításkor is életszerű és látványosan bemutatható legyen.

## 1. Baseline reset után

| Mutató | Érték |
|---|---:|
| Összes report | 120 |
| Mai report | 16 |
| NEW | 8 |
| IN_PROGRESS | 6 |
| Aktív összesen | 14 |
| ARCHIVED | 106 |

A „mai” adatok dinamikusan a reset futtatásának `Europe/Budapest` naptári napjához igazodnak.

## 2. Demo szolgálati fiók

- Azonosító: `demo.service`
- Megjelenő név: `Demo Szolgálat`
- Demo jelszó: `OrszemDemo!2026`
- Szerep: `SERVICE_USER`

A jelszó csak ebben a demo-specifikációban jelenhet meg; az adatbázisban Argon2id hash található. Production környezetben ezt a seedet tilos futtatni.

## 3. Eseménytípus-eloszlás

| Kód | Megnevezés | Darab |
|---|---|---:|
| `LOUD_BEHAVIOR` | Hangoskodás | 18 |
| `LITTERING` | Szemetelés | 14 |
| `FIGHT` | Verekedés | 12 |
| `VANDALISM` | Rongálás | 10 |
| `THEFT` | Lopás | 9 |
| `SMOKING` | Dohányzás | 8 |
| `PASSENGER_HARASSMENT` | Utasok zaklatása | 7 |
| `SUSPICIOUS_PACKAGE` | Gyanús csomag | 6 |
| `AGGRESSIVE_BEHAVIOR` | Agresszív viselkedés | 5 |
| `ILLNESS` | Rosszullét | 5 |
| `SUSPECTED_DRUG_USE` | Kábítószer-használat gyanúja | 4 |
| `GRAFFITI` | Graffiti | 3 |
| `KNIFE_ATTACK` | Késelés | 3 |
| `STAFF_HARASSMENT` | Személyzet zaklatása | 3 |
| `UNCONSCIOUS_PERSON` | Eszméletlen személy | 3 |
| `DOOR_OBSTRUCTION` | Ajtó szándékos akadályozása | 2 |
| `FIRE_OR_SMOKE` | Tűz vagy füst | 2 |
| `INJURED_PERSON` | Sérült személy | 2 |
| `ROBBERY` | Rablás | 2 |
| `WEAPON_THREAT` | Fegyverrel fenyegetés | 2 |

## 4. Kategória-eloszlás

| Kategória | Darab |
|---|---:|
| `DISTURBANCE_HARASSMENT` | 37 |
| `RULE_VIOLATION_OTHER` | 24 |
| `THEFT_PROPERTY` | 22 |
| `VIOLENCE_DANGER` | 19 |
| `MEDICAL_WELFARE` | 10 |
| `SUSPICIOUS_ACTIVITY` | 6 |
| `SAFETY_HAZARD` | 2 |

## 5. Település-eloszlás

| Település | Darab |
|---|---:|
| Budapest | 28 |
| Vác | 18 |
| Gödöllő | 15 |
| Szolnok | 13 |
| Cegléd | 11 |
| Monor | 9 |
| Hatvan | 8 |
| Nagykáta | 6 |
| Gyömrő | 5 |
| Veresegyház | 4 |
| Aszód | 3 |

## 6. Vonat/járat-eloszlás

| Vonat | Darab |
|---|---:|
| IC 123 | 20 |
| S70 | 17 |
| EC 45 | 15 |
| IC 245 | 13 |
| Z30 | 12 |
| G70 | 10 |
| S60 | 9 |
| IR 87 | 8 |
| S80 | 7 |
| IC 197 | 5 |
| R 452 | 4 |

## 7. Prezentációs ellenőrzőérték

A baseline után a bemutató során küldjünk egy új reportot:

- Esemény: `KNIFE_ATTACK` — Késelés
- Vonat: `IC 123`
- Település: `Budapest`
- Idő: aktuális

Beküldés után elvárt:

- total: `121`
- today: `17`
- active: `15`
- archived: `106`
- `KNIFE_ATTACK`: `4`
- Budapest: `29`
- IC 123: `21`

Elfogadás után az aktív összesen továbbra is `15`.

Archiválás után:

- total: `121`
- today: `17`
- active: `14`
- archived: `107`

Ez a számsor a demo end-to-end adatfolyam egyszerű, látványos ellenőrzése.

## 8. Seed fájlok

- `db/demo/000_reset_demo.sql`
- `db/demo/010_demo_service_user.sql`
- `db/demo/020_demo_reports.sql`
- `db/demo/demo-reports.seed.json`
- `scripts/reset-demo.sh`
