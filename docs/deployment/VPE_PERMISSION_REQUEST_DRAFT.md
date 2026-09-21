# Draft: written permission request to KTI VPE Igazgatóság (B9-A)

**Status: DRAFT for owner review. NOT SENT.** Nothing has been sent to anyone.

Before sending, the owner should:

1. Confirm that `vpe@kti.hu` is the current official contact for reuse questions (not verified from here).
2. Fill in the `[…]` placeholders (sender, organisation form, contact).
3. Optionally re-download the annex archive and record its SHA-256 (the earlier retrieval was never hashed), then cite that
   exact file. The request below names the URL and retrieval date as recorded.
4. Have anyone with legal expertise you trust read it. This is an engineering draft, not legal advice, and it does not
   assert that any right exists.

The reply, if any, must be kept with the dataset backup. Only a written reply that covers the railway data may be used to set
`reuseStatus: CLEARED` for the two railway components (owner decision, 2026-09-19).

---

**Címzett:** vpe@kti.hu
**Tárgy:** Kérelem írásbeli felhasználási hozzájárulásra – Hálózati Üzletszabályzat 2026/2027, 5.2-4 és 5.2-5 sz. mellékletek adatai (Őrszem nyilvános szolgáltatás)

Tisztelt KTI VPE Igazgatóság!

[Név] vagyok, az Őrszem nevű, nyilvánosan elérhető vasúti eseménybejelentő szolgáltatás (weboldal: orszembejelento.hu, valamint Android-alkalmazás) [üzemeltetője / fejlesztője]. Írásbeli megerősítést kérek arra, hogy az alábbi, Önök által közzétett adatokat az itt leírt módon felhasználhatjuk.

**1. Az érintett forrásdokumentumok**

A VPE által közzétett *Hálózati Üzletszabályzat 2026/2027* (HÜSZ) alábbi mellékletei, az alábbi címről letöltött állapotban (letöltve: 2026. szeptember 7.):
https://vpe.kti.hu/wp-content/uploads/2026/09/husz-2026-2027-ae-sz-modositas.zip

- 5.2-4 sz. melléklet: MÁV szolgálati helyek és vonalkategóriák („AE. sz. módosítás");
- 5.2-5 sz. melléklet: GYSEV szolgálati helyek és vonalkategóriák („D. sz. módosítás").

**2. Mit készítünk ezekből**

A mellékletekből vonalszám-jegyzéket és településhez rendelt vasútvonal-kapcsolatokat tartalmazó adatbázist származtatunk: a vonalszámot, a vonal két végpontja alapján képzett megjelenítési nevet, valamint azt, hogy az egyes szolgálati helyek nevei alapján mely településekhez mely vonalak tartoznak. A szolgálati helyek neveit egyeztetjük a KSH helységnévtárával (kisbetűsítés, néhány létesítménytípus-utótag elhagyása, pontos névegyezés); a származtatott elnevezések és a normalizálás tehát a forrásszövegtől eltérhetnek. Az eredeti dokumentumot nem tesszük közzé, csak a származtatott ténybeli adatsorokat használjuk.

**3. Milyen szolgáltatásban használnánk**

Az adatok nyilvánosan elérhető, éles üzemű szolgáltatásban szerepelnének. A szolgáltatás nyilvános API-ja bárki számára (bejelentkezés nélkül) visszaadja, hogy egy adott településhez mely vasútvonalak tartoznak, ezért a településhez rendelt kapcsolatok, valamint a vonalak jegyzéke (a vonalszámmal és a származtatott névvel) a végpontok lekérdezésével teljes egészében rekonstruálhatók. Ezt tehát a felhasználás részének, gyakorlatilag közzétételnek tekintjük.

**4. Kérdéseink**

Kérjük, írásban jelezzék:

1. Hozzájárulnak-e az 1. pontban megnevezett mellékletek adatainak felhasználásához a 2. és 3. pontban leírt célra és módon?
2. A hozzájárulás kiterjed-e az adatok nyilvános közzétételére, ideértve azt, hogy azok API-n keresztül bárki számára lekérdezhetők és így rekonstruálhatók?
3. A hozzájárulás kiterjed-e az adatok módosítására, átalakítására és származtatására (névképzés, normalizálás, összekapcsolás más adatforrásokkal), és az így létrejött adatbázis továbbadására, illetve nyilvánosságra hozatalára?
4. **A hozzájárulás kiterjed-e az üzleti (kereskedelmi) célú felhasználásra is**, hogy később ne legyen kétséges? Ha nem, kérjük, jelezzék a korlátozást.
5. Előírnak-e forrásmegjelölést? Ha igen, kérjük, adják meg a pontos szöveget és a hivatkozandó webcímet.
6. Vonatkozik-e a hozzájárulás a GYSEV szolgálati helyeire és vonalaira vonatkozó adatokra (5.2-5 sz. melléklet) is, vagy ahhoz a GYSEV Zrt. külön engedélye szükséges? Ha külön engedély kell, kérjük, jelezzék, kit érdemes megkeresnünk.
7. Milyen időtartamra és milyen feltételekkel érvényes a hozzájárulás (például a HÜSZ későbbi módosításaira is kiterjed-e)?

Ha a felhasználásnak más feltételei vannak, vagy más csatornán (például formális nyilatkozat, licenc) szükséges a kérelmet benyújtani, kérjük, tájékoztassanak.

Előre is köszönjük a segítségüket.

Tisztelettel:
[Név]
[Beosztás / szervezet]
[E-mail] · [Telefonszám]
