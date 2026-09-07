# ADR 0006 — import gates on the reference dataset, and the PARTIAL-coverage routing rule

**Status:** accepted · 2026-09-07

## Context

The canonical reference dataset (`reference-data/local-research/`) carries a manifest with two
independent status fields, established when the dataset was built:
`verificationStatus` (is a relation that's present *correct*) and `coverageStatus` (does the
dataset cover *every* settlement). Building the backend importer surfaced two more
decisions that the manifest and the future routing service both need to get right.

## Decision 1 — `reuseStatus` is a third, independent axis

Correctness and redistribution rights are different questions. A relation can be exactly
right and still not be ours to redistribute outside the running service. The manifest gains
a third field:

```json
"reuseStatus": "PENDING" | "CLEARED"
```

- **PENDING** — the reuse basis recorded under `sources` (mandatory regulatory publication,
  factual rows only, source document not redistributed — see `PHASE_3B_DECISION_GATE.md`
  §5–6) is sound for internal use and for running the service, but no source has issued an
  explicit written reuse grant.
- **CLEARED** — written confirmation exists.

**The backend reference importer refuses to `import` a `PENDING` dataset.** Not a warning,
not a `--force` flag — a hard refusal (`REFERENCE_DATASET_REUSE_NOT_CLEARED`), the same way
it hard-refuses an `UNVERIFIED` one. The real VPE-derived dataset under
`reference-data/local-research/` is `reuseStatus: PENDING` today, so it **cannot currently be
imported into a production database**, by construction rather than by discipline. It
remains committed as research/candidate material. Backend tests exercise the importer
against small synthetic fixtures marked `reuseStatus: CLEARED`, never against the real
dataset — the real dataset only becomes importable once VPE confirms in writing and someone
edits that one field.

There is deliberately no bypass. A casual `--force` on a reuse gate is exactly the kind of
switch that gets flipped under deadline pressure and never revisited; the only way past this
gate is to change the fact it checks.

## Decision 2 — PARTIAL coverage forbids one-relation auto-inference

This constrains Phase 3C, which has not been implemented yet. Recorded now so the rule
cannot be lost or "simplified away" when routing is built.

`coverageStatus: PARTIAL` means *absence of another relation is not evidence that none
exists* — the dataset simply doesn't cover everything yet (§7 of the decision gate report
explains why: the HÜSZ annexes enumerate service points, not every settlement a line
crosses).

The tempting shortcut, when a settlement resolves to **exactly one** verified railway line:
infer that line automatically and route on it. That shortcut is **only sound when the
active dataset's relevant scope is proven `COMPLETE`**. Under `PARTIAL`, a settlement with
one known relation might have others the dataset hasn't captured yet — routing that
silently narrows to "the one we happen to know about" would be asserting a completeness the
data doesn't have, exactly the failure mode `PARTIAL` exists to prevent.

**The rule for Phase 3C:**

- While the active dataset (or the relevant scope) is `PARTIAL`: absence of an *explicit*
  railway-line selection on the incoming report must conservatively produce
  `RAILWAY_LINE_NOT_SELECTED` (or the Phase 3C equivalent) — **even when exactly one
  verified relation is currently known** for that settlement.
- Automatic one-line inference from a single verified relation is permitted **only** once
  the dataset (or the specific scope being routed) is proven `COMPLETE`.

This is not implemented by this ADR — Phase 3C is out of scope until the importer lands and
the dataset path is settled. It is documented here as a binding constraint on that future
work, and Phase 3C must add tests proving both directions: a `PARTIAL` settlement with one
relation still requires explicit selection, and a `COMPLETE` scope permits inference.

## Consequences

- The manifest schema, the offline builder, and the offline validator all carry
  `reuseStatus` (`reference-data/tools/build-canonical.mjs`,
  `reference-data/tools/validate-canonical.mjs`). CI validates it like every other field.
- The backend importer's gate is data-driven, not environment-driven: the same code path
  runs against fixtures in tests and against a real dataset in production, and refuses
  identically whenever `reuseStatus != CLEARED` or `verificationStatus != VERIFIED`.
- `docs/DECISIONS_REQUIRING_OWNER.md` (B9) still tracks obtaining that written confirmation
  as an owner action; this ADR does not resolve it, it only makes the system behave
  correctly while it stays open.
