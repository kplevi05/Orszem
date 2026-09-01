# Őrszem Demo v1 — Claude Code handoff

## Használat

1. Hozd létre / nyisd meg az `orszem` Git repositoryt.
2. Másold bele ennek a planning packnek a tartalmát a megfelelő útvonalakra.
3. A repository gyökerében legyen:
   - `CLAUDE.md`
   - `MASTER_PROMPT_DEMO_V1.md`
4. Indítsd Claude Code-ot a repository gyökerében.
5. Add neki a `MASTER_PROMPT_DEMO_V1.md` teljes tartalmát vagy mondd meg neki, hogy olvassa el és hajtsa végre.
6. Ne adj neki külön, egymással ellentmondó követelményeket a chatből; a repository dokumentumai legyenek az igazságforrás.

## Mielőtt Claude kódol

Ellenőrizd csak ezt:
- a planning pack fájljai a megfelelő mappákban vannak;
- Git repository inicializálva van;
- Java/Android SDK környezet rendelkezésre áll;
- Docker fut;
- Claude Code hozzáfér a repositoryhoz.

A hosting nem szükséges a helyi implementáció megkezdéséhez.

## Mikor kell emberi döntés?

Claude csak akkor álljon meg, ha a Master Promptban meghatározott owner-level blocker merül fel.

A jelenlegi ismert owner kérdések a `docs/DECISIONS_REQUIRING_OWNER.md` fájlban vannak, de egyik sem blokkolja a helyi Demo v1 implementációt.
