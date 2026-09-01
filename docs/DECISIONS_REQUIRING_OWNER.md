# Őrszem — tulajdonosi döntést igénylő nyitott pontok

A következő kérdések **nem blokkolják a technikai tervezést**, ezért most alapértelmezéssel haladunk. Kódolás/bemutatás előtt érdemes őket röviden jóváhagyni.

## 1. Demo hosting

Technikai alapértelmezés:
- fejlesztés: helyi Docker Compose;
- bemutató: külön HTTPS-es demo backend + PostgreSQL.

Tőled később kell döntés arról, hogy:
- saját gépről mutatod be;
- vagy interneten elérhető demo környezet legyen.

Hosting szolgáltatót csak akkor választunk, amikor tudjuk, kell-e publikus internetes bemutató és van-e költségkeret.

## 2. Cél Android készülékek

Alapértelmezés: `minSdk 26`.

Csak akkor kell módosítani, ha a megrendelő biztosan régebbi Android készülékeket használ.

## 3. Vizuális finomhangolás

A jelenlegi design tokenek technikai alapértékek. A kék és sötétkék/sárga irány már rögzített, de a végleges árnyalatokat a működő UI első screenshotjai után érdemes együtt finomítani.

## 4. Napi/heti trendgrafikon

Technikai döntés: az első Demo v1 buildből kihagyjuk.
A KPI + toplista + kategóriamegoszlás elegendő a stabil bemutatóhoz.

Ha a bemutató előtt úgy érzed, hogy kell még egy látványos grafikon, külön kis feature-ként hozzáadható.

## 5. Production kérdések — későbbre

Ezeket most nem kell eldönteni:
- szolgálati területek pontos definíciója;
- egy user egy vagy több területhez tartozhat-e;
- Production vonatkatalógus / vasúti API;
- településlista vagy szabad szöveg;
- közös `1234` reset jelszó végleges megtartása vagy egyszer használatos kódra váltás;
- valódi AI szolgáltató/modell;
- adatmegőrzési idő;
- pontos spam/rate-limit szabályok.

Ezeket a Demo elfogadása után, a Production specifikációban zárjuk le.
