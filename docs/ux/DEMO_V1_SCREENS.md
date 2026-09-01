# Őrszem — Demo v1 UI/UX specifikáció

**Státusz:** implementálandó  
**Platform:** Android / Jetpack Compose / Material 3  
**Alapelv:** kevés képernyő, kevés vizuális zaj, gyors demonstrálhatóság.

## 1. Design rendszer

A színeket kizárólag design tokenként kell használni. Feature-kódban közvetlen hex szín ne legyen.

### 1.1 Public App — világos kék téma

| Token | Alapérték | Szerep |
|---|---|---|
| `PublicBackground` | `#F4F8FC` | fő háttér |
| `PublicSurface` | `#FFFFFF` | kártyák, mezők |
| `PublicPrimary` | `#2477B9` | elsődleges gomb, aktív elem |
| `PublicPrimaryDark` | `#0D3B66` | címek, hangsúly |
| `PublicSecondary` | `#6BA9D6` | másodlagos kék |
| `PublicText` | `#12212F` | fő szöveg |
| `PublicTextMuted` | `#5A6B7A` | másodlagos szöveg |
| `PublicSuccess` | `#2E7D5A` | siker-visszajelzés |
| `PublicError` | `#B3261E` | hiba |

Vizuális irány: világos, tiszta, nyugodt, minimális árnyék, 12–16 dp lekerekítés, sok whitespace.

### 1.2 Service App — sötétkék + sötét citromsárga

| Token | Alapérték | Szerep |
|---|---|---|
| `ServiceBackground` | `#081A2B` | fő háttér |
| `ServiceSurface` | `#102A43` | panelek/kártyák |
| `ServiceSurfaceAlt` | `#173B59` | másodlagos surface |
| `ServiceAccent` | `#D6B82C` | fő kiemelés |
| `ServiceAccentPressed` | `#B89A1F` | nyomott állapot |
| `ServiceText` | `#F4F7FA` | fő szöveg |
| `ServiceTextMuted` | `#B5C3CE` | másodlagos szöveg |
| `ServiceError` | `#FFB4AB` | hiba |
| `ServiceSuccess` | `#91D5B0` | siker |

A sárga csak kiemelés: aktív tab, fő CTA, kulcsszám. Ne legyen nagy sárga felület.

### 1.3 Tipográfia és méretezés

- Material 3 typography alap;
- fő cím: `headlineSmall`;
- képernyőcím: `titleLarge`;
- kártyacím: `titleMedium`;
- törzsszöveg: `bodyMedium`;
- minimum érintési cél: 48 dp;
- normál oldalpadding: 16 dp;
- kártyaköz: 12 dp.

## 2. Public App navigáció

`P01 Home -> P02 Report Form -> P03 Success`

### P01 — Kezdőlap

Tartalom:
- Őrszem név/logóhely;
- rövid leírás: „Jelentsen be gyorsan egy vonaton tapasztalt biztonsági eseményt.”
- fő CTA: `BEJELENTÉS INDÍTÁSA`.

Nincs bottom navigation.

### P02 — Új bejelentés

Egyetlen görgethető űrlap.

Mezők sorrendje:
1. `Mikor történt?`
2. `Melyik vonaton?`
3. `Melyik település közelében?`
4. `Mi történt?`
5. `BEJELENTÉS KÜLDÉSE`

#### Időpont
- alapérték: most;
- date/time pickerrel módosítható;
- jövőbeli időpont nem engedélyezett 5 percnél nagyobb toleranciával.

#### Vonat
- egy soros szövegmező;
- placeholder: `pl. IC 123`;
- trim + többszörös whitespace összevonása;
- 1–64 karakter.

#### Település
- egy soros szövegmező;
- placeholder: `pl. Budapest`;
- mellette/alatta `HELYZET MEGHATÁROZÁSA` ikon/gomb;
- GPS engedélykérés csak a gomb megnyomásakor;
- siker esetén reverse geocodingból csak a településnév kerül a formba;
- koordinátát a backend nem kap;
- sikertelenség esetén snackbar + manuális mező változatlanul használható.

#### Eseménytípus
61 elem miatt nem klasszikus apró dropdown.

Megoldás:
- mezőre kattintva Modal Bottom Sheet;
- kategóriák szerint csoportosított, görgethető lista;
- felül keresőmező **beépítendő**, mert kis többletmunkával jelentősen javítja a 61 elem használhatóságát;
- keresés a magyar labelen, kis/nagybetűtől függetlenül;
- egy elem választása bezárja a sheetet.

#### Küldés
CTA csak akkor aktív, ha minden kötelező mező érvényes.
Küldés alatt progress indicator és dupla kattintás tiltása.

Hálózati hiba:
- az űrlap tartalma maradjon meg;
- hibaüzenet + `ÚJRA` lehetőség;
- Demo v1-ben nincs offline queue.

### P03 — Sikeres beküldés

- siker ikon;
- `Bejelentés elküldve`;
- rövid köszönőszöveg;
- `ÚJ BEJELENTÉS` elsődleges gomb;
- `KEZDŐLAP` másodlagos gomb.

## 3. Service App navigáció

`S01 Login -> S02 Active`

Belépés után három fő tab:
1. `Bejelentések`
2. `Archívum`
3. `Statisztika`

Telefonon Material 3 NavigationBar használható.

### S01 — Login

- ŐRSZEM SZOLGÁLAT cím;
- azonosító;
- jelszó, show/hide ikon;
- `BELÉPÉS`;
- loading állapot;
- hibás credential: inline/surface error, ne külön képernyő.

Sikeres login után token biztonságos helyi tárolásba kerül. App újranyitáskor érvényes token esetén a login átugorható.

Demo JWT alapértelmezett lejárata: **8 óra**.

### S02 — Aktív esetek

Fejléc:
- `Bejelentések`;
- opcionálisan `14 aktív`.

Kártya:
- eseménytípus nagyobb betűvel;
- státusz chip (`ÚJ`, `FOLYAMATBAN`);
- vonat;
- település;
- időpont.

Rendezés:
1. NEW;
2. IN_PROGRESS;
3. csoporton belül `receivedAt DESC`.

Pull-to-refresh megengedett és javasolt. WebSocket nem kell.

Üres állapot:
`Jelenleg nincs aktív bejelentés.`

### S03 — Eset részletei

Mindig megjelenik:
- eseménytípus;
- kategória;
- vonat;
- település;
- esemény időpontja;
- beérkezés;
- státusz.

NEW eset:
- elsődleges CTA: `ESET ELFOGADÁSA`.

IN_PROGRESS eset:
- elfogadás ideje;
- elfogadó szolgálati user;
- CTA: `ESET LEZÁRÁSA ÉS ARCHIVÁLÁSA`.

Konkurens 409 esetén:
- jól látható dialog vagy inline banner;
- report újratöltése;
- szöveg: `Az eset állapota időközben megváltozott.`

### S04 — Archívum

- archivált kártyák `archivedAt DESC`;
- ugyanaz a vizuális report card, de archivált státusz;
- cursor pagination;
- kártya -> S05.

### S05 — Archivált eset részletei

Csak olvasás:
- report adatok;
- elfogadás és archiválás időpontja;
- kezelő felhasználó(k);
- nincs újranyitás, törlés, módosítás.

### S06 — Statisztika

Felső cím:
`Intelligens statisztika`

Alcím:
`Automatikusan számított összesítések`

Ne állítsa, hogy generatív AI működik.

#### KPI kártyák
2x2 grid:
- Összes;
- Ma;
- Aktív;
- Archivált.

#### Top listák
- Leggyakoribb események;
- Legproblémásabb települések;
- Legtöbb bejelentéssel rendelkező vonatok.

#### Kategória-megoszlás
Egyszerű vízszintes progress/bar lista.
Komplex chart library nem szükséges.

A Demo v1 első buildjében napi/heti trendgrafikon **nem kötelező**.

## 4. Loading / error / empty állapot

Minden hálózati képernyő külön kezelje:
- initial loading;
- content;
- empty;
- recoverable error;
- unauthorized.

401 service válasz:
- token törlése;
- vissza S01 login;
- `A munkamenet lejárt. Jelentkezzen be újra.`

## 5. Accessibility minimum

- touch target >= 48 dp;
- szöveg ne legyen csak színnel megkülönböztetve;
- státusz chip szöveget is tartalmaz;
- contentDescription ikonokra, ahol szükséges;
- rendszer font scale mellett a kritikus CTA ne vágódjon le.

## 6. Nem implementálandó UI

Demo v1-ben nincs:
- admin menü;
- user management;
- jelszócsere;
- password reset;
- moderáció/törlés;
- területi választó;
- chat;
- push értesítés;
- fotófeltöltés.
