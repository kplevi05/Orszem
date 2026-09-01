# GitHub feltöltési gyorsindító

Ez a mappa közvetlenül használható az Őrszem Git repository gyökereként.

## Feltöltés

Ha új repositoryt hozol létre:

```bash
cd orszem
git init
git add .
git commit -m "docs: initialize Orszem Demo v1 specification"
git branch -M main
git remote add origin <GITHUB_REPOSITORY_URL>
git push -u origin main
```

## Claude Code indítás

A repository gyökerében indítsd Claude Code-ot, majd add neki:

> Read `MASTER_PROMPT_DEMO_V1_FINAL.md` completely and execute it. Treat the repository specifications and contracts it references as the source of truth. Begin at M0 and continue autonomously through M7. Stop only for owner-level blockers explicitly defined in the Master Prompt.

A régi, nem-final Master Prompt nincs ebben a csomagban, hogy ne legyen kétértelmű az indítás.
