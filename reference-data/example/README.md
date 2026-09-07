# Example dataset — entirely fictional

Every name in this directory is invented. `Példafalva` ("Example-village"),
`Mintaváros` ("Sample-town") and `Tesztület` ("Test-body") are not real Hungarian
settlements; line codes `900`/`901` are not real railway lines. Nothing here was derived
from KSH, VPE, MÁV or GYSEV.

## Purpose

Two things, both format-level, neither data-level:

1. **CI self-check.** The `reference-data` workflow validates this manifest on every push
   and pull request, so `reference-data/tools/validate-canonical.mjs` stays exercised even
   though the real derived dataset is not committed here (see
   `reference-data/local-research/README.md` and
   [ADR 0006](../../docs/architecture/adr/0006-reference-import-gates-and-partial-coverage-routing.md)).
2. **A worked example of the manifest/CSV format** for anyone reading this repository, with
   `verificationStatus: VERIFIED`, `coverageStatus: COMPLETE` and `reuseStatus: CLEARED` all
   satisfied — the "everything cleared" case, which the real dataset currently is not.

## Validate it yourself

```bash
node reference-data/tools/validate-canonical.mjs reference-data/example/manifest.json
```

## Import it (backend maintenance CLI)

Because it is `reuseStatus: CLEARED`, this is one of the few datasets the importer will
actually accept — useful for exercising `reference-import` end-to-end against a real
PostgreSQL database without touching any real data:

```bash
./scripts/orszem-admin reference-validate reference-data/example
./scripts/orszem-admin reference-diff     reference-data/example
./scripts/orszem-admin reference-import   reference-data/example
```

This is a different (and much smaller) thing from the backend's own synthetic test
fixtures (`ReferenceDatasetFixture.kt`, generated at test time, never committed) — this
directory is the tracked, human-browsable equivalent, kept in the public repository
deliberately.
