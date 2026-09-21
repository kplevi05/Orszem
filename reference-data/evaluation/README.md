# Evaluation dataset — entirely fictional

Every name here is invented (`Példafalva`, `Mintaváros`, `Tesztület`, `Nincsvasút`, `Vonalatlan`; lines `900`, `901`, `902`).
Nothing derives from KSH, VPE, MÁV or GYSEV; there are no third-party rights in it.

It is the runnable-evaluation companion to [`../example`](../example): the example dataset (`EXAMPLE-1`, `COMPLETE`) is imported first and
exercises **inference**; this one (`EVAL-2`) is imported second and switches the railway components to **`PARTIAL`**, so nothing is inferred.
It adds a settlement with **no relation** (`Nincsvasút` → `UNCLASSIFIED`) and a settlement whose only line (`902`) is deliberately left
without a service-area mapping (→ `UNCLASSIFIED / RAILWAY_LINE_UNASSIGNED`).

`reuseStatus: CLEARED` here is true only because the data is fictional. It says nothing about the real railway dataset, which stays
`PENDING` (see `docs/deployment/B9_DECISION_PACKAGE.md`).

```bash
node reference-data/tools/validate-canonical.mjs reference-data/evaluation/manifest.json
deploy/eval/eval.sh cli reference-import reference-data/evaluation     # see docs/deployment/EVALUATION_ENVIRONMENT.md
```
