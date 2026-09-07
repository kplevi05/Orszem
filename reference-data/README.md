# Reference data

**Empty by design. Datasets arrive in Phase 3.**

This directory will hold the machine-readable reference datasets the backend loads —
the event taxonomy, and whatever is decided for settlements and train identification.

## Rules

- **Reference data is not seed data and not test fixtures.** Demo v1 delivered its demo
  dataset through Flyway `locations`, which made fixtures indistinguishable from schema.
  V2 keeps them apart: migrations own the schema, this directory owns the data, and it is
  loaded explicitly.
- **Codes are stable identifiers.** Once a `code` is published, its meaning never changes.
  Display labels may be corrected freely; codes may not be repurposed.
- **Nothing is hard-deleted** once records may reference it. Entries are deactivated.
- Each dataset carries its own version and a note on where it came from.

## Current state

| Dataset | Status |
|---|---|
| Event taxonomy (7 categories, 61 types) | documented in `docs/product/EVENT_CATALOG_V2.md`, **not yet a dataset** |
| Settlements | undecided — see `docs/DECISIONS_REQUIRING_OWNER.md` B4 |
| Train identification | undecided — see `docs/DECISIONS_REQUIRING_OWNER.md` B3 |

The event taxonomy exists as documentation only. It is deliberately not implemented in
code, the database or the API yet: taxonomy is out of scope until Phase 3.
