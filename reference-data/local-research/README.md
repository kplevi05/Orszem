# Local research material — never committed, packaged, or deployed

This directory is **gitignored** (see `.gitignore`). It exists so a real, uncleared
derived dataset can be worked on locally without ever entering tracked Git state.

## What belongs here

The actual VPE HÜSZ-derived files, while `reuseStatus: PENDING` (see
[ADR 0006](../../docs/architecture/adr/0006-reference-import-gates-and-partial-coverage-routing.md)
and
[`DECISIONS_REQUIRING_OWNER.md`](../../docs/DECISIONS_REQUIRING_OWNER.md) B9/B10):

```
reference-data/local-research/
  manifest.json                      -- the real research manifest (reuseStatus: PENDING)
  settlements.csv                    -- KSH data (may also live in reference-data/cleared/)
  railway-lines.csv                  -- real VPE HÜSZ-derived line roster
  settlement-railway-lines.csv       -- real VPE HÜSZ-derived relations
  needs-review.csv                   -- real VPE HÜSZ-derived quarantined candidates
  manual-review.csv                  -- working copy of manual decisions, if any
```

Regenerate it with the same tooling used before, just pointed here instead of a tracked
path:

```bash
node reference-data/tools/build-canonical.mjs \
  --ksh <local KSH workbook dir> --mav <local MAV annex text> --gysev <local GYSEV annex text> \
  --review reference-data/local-research/manual-review.csv \
  --out reference-data/local-research \
  --version <version> --reuse-status PENDING
```

Validate it exactly as any other dataset:

```bash
node reference-data/tools/validate-canonical.mjs reference-data/local-research/manifest.json
```

Import it with the backend maintenance CLI once (and only once) `reuseStatus` is genuinely
`CLEARED` — the importer refuses a `PENDING` dataset unconditionally, with no bypass:

```bash
./scripts/orszem-admin reference-validate reference-data/local-research
./scripts/orszem-admin reference-diff     reference-data/local-research
./scripts/orszem-admin reference-import   reference-data/local-research   # refused while PENDING
```

## Why this directory exists

A dataset can be exactly correct (`verificationStatus: VERIFIED`) and still not be ours to
publish — see `PHASE_3B_DECISION_GATE.md` §6 and §11. Real VPE HÜSZ-derived
row-level content (the railway-line roster, the settlement↔line relations, and the
quarantined candidate rows) was removed from this repository's tracked history because
their reuse status is `PENDING` and the repository is public. This directory is where that
work continues locally, without ever putting the same content back into Git.

**Nothing under this directory is ever:**
- committed (gitignored — see the rule at the bottom of `.gitignore`)
- packaged into a backend artifact, an Android APK, or the Public Web bundle
- referenced by any deployment configuration
- read by any CI workflow

CI validates only `reference-data/example/`, a small, entirely fictional dataset — see
`reference-data/example/README.md`.

## If you are starting fresh

You will need your own local copies of the KSH workbook and the VPE HÜSZ annexes, obtained
and licensed exactly as described in `reference-data/README.md`. Nothing here provides them
— that is the point.
