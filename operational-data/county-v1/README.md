# Saját Őrszem területi beosztás — ORSZEM-COUNTY-1

**Elkészült, offline javaslat. Nem került adatbázisba; a futó besorolást nem módosítja.**

A tulajdonos 2026-09-22-én felhatalmazást adott egy értelmes saját területi beosztás
kialakítására. Ennek első változata **19 vármegye + Budapest**, összesen 20 terület.
Ez első féltől származó Őrszem-munkaszervezés, **nem** a MÁV, GYSEV vagy más szervezet
hivatalos illetékességi területeinek állítása. Nem állít azonos munkaterhelést a területekre.

## Mi készült el?

- Mind a **3178** KSH-bejegyzés pontosan egy saját területhez tartozik.
- A KSH `ksh_code` azonosító szöveg marad; a kezdő nullák megmaradnak.
- A vármegye mezőből pontos egyezéssel készül a hozzárendelés, nincs névhasonlósági becslés.
- Budapest összesített sorában (`13578`) a vármegye üres. Ez egy dokumentált,
  névvel és eredeti mezőértékkel is ellenőrzött kivétel; a 23 `főváros` kerületi sorral
  együtt mind a 24 bejegyzés az `ORSZEM-BUDAPEST` területhez tartozik. A forrásban
  nem töröljük és nem vonjuk össze ezeket a különböző azonosítókat.
- Tata (`20127`) az `ORSZEM-KOMAROM-ESZTERGOM` területhez került.

Forrás: [KSH Helységnévtár](https://www.ksh.hu), állapot: 2025-01-01,
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
Módosítás: saját területi azonosító és hozzárendelés hozzáadása. A forrás CSV nem változott.
Részletes eredet: `reference-data/LICENSES/KSH-helysegnevtar.md` és a forrás manifest.

## Fájlok

| Fájl a `generated/` könyvtárban | Tartalom |
| --- | --- |
| `service-areas.csv` | 20 saját terület; stabil, saját `area_code` azonosítókkal |
| `settlement-service-areas.csv` | 3178 települési besorolás, névvel és a besorolás alapjával |
| `settlement-line-service-areas.csv` | Csak VERIFIED és CLEARED forrásban ténylegesen szereplő település–vonal párok területi besorolása |
| `line-routing-assessment.csv` | Vonalanként mutatja, hogy több terület érintett-e; PARTIAL forrásból nem állít teljes vonalra vonatkozó illetékességet |
| `unresolved-settlements.csv` | Mely településekhez hiányzik ellenőrzött vasúti kapcsolat; ez nem jelenti, hogy nincs ott vasút |
| `summary.json` | Számosságok, területenkénti darabszámok, eredet, ellenőrzőösszegek és a futó rendszerrel való kompatibilitás |

A jelenleg engedélyezett `KSH-SETTLEMENTS-1` csomagban **0 vonal és 0 kapcsolat** van.
Ennek megfelelően a vasúti összerendelési CSV fejlécen kívül üres, és mind a 3178
településnél hiányzik a vasúti referencia. Ez szándékosan látható hiány, nem kész vasúti
adatbázis. A PENDING kutatási adatokat a program nem használja; az adatvédelmi/licenc
állapotot a CSV-k megnyitása előtt ellenőrzi. Nem módosít manifestet CLEARED-re.

## Újraelőállítás és ellenőrzés

A repó gyökeréből, Python 3.12-vel, külső csomag és hálózat nélkül:

```bash
python3 scripts/build-territory-plan.py
python3 scripts/build-territory-plan.py --check
python3 -m unittest discover -s scripts/tests -v
```

Az eredmény determinisztikus. A `--check` nem ír semmit, és hibával áll le, ha a
bejegyzett eredmény eltér a forrásból újraszámolttól. A CI ugyanezt ellenőrzi.
Másik, már engedélyezett és ellenőrzött teljes településkatalógust tartalmazó csomag:

```bash
python3 scripts/build-territory-plan.py --dataset /path/to/cleared-dataset --out /tmp/territory-review
```

Ez **nem importáló parancs**. Ismeretlen vármegye, elavult Budapest-kivétel, hiányos
eredet, hibás checksum, duplikált kulcs vagy lógó vasúti kapcsolat esetén leáll, még
kimenetírás előtt. Csak a forrásban levő párokat kapcsolja a saját területekhez.

## Mi kell még az éles vasúti routinghoz?

1. Használható VERIFIED/CLEARED vasútvonal- és település–vonal forrás. A korábbi PENDING
   VPE/GYSEV csomag használatának tulajdonosi tiltása továbbra is érvényes.
2. A backend jelenlegi `railway_line_id → service_area_id` modelljének bővítése
   `settlement_id + railway_line_id → service_area_id` kulccsal. Egy vármegyehatárt
   átlépő vonalat nem szabad az első érintett területhez egészében hozzárendelni.
3. Tranzakciós, auditált konfiguráció-import validate/diff/apply folyamatokkal;
   jogosultsági, import-versenyhelyzeti és változatlan történeti snapshot tesztekkel.
4. A tényleges szolgálati felhasználók területi jogosultságainak kiosztása. Ezt a
   területi adatcsomag nem végzi el, nem ad automatikusan hozzáférést senkinek.
5. Ellenőrzött rollout. A nullavonalas bejelentések addig a működő országos
   Besorolatlan folyamatban maradnak.

Ezek közül ebben a változtatásban **a települési beosztás és az offline összerendelő
eszköz készült el**, nem a backend-bővítés, vasúti adatbeszerzés vagy éles import.
Az `operational-data/` fájljait ne adjuk a `reference-import` parancsnak: más fogalmat
és más sémát képviselnek. A meglévő Északi/Déli tesztterületeket sem írjuk felül.
