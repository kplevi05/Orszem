# Railway settlement and service-area mapping — engineering report

## Outcome

This phase prepares a reviewable railway routing dataset without changing production.
It links OpenStreetMap railway evidence to canonical KSH settlements, then derives the
operational ServiceArea from the already-reviewed county/Budapest territory plan.

Generated review snapshot (2026-09-23):

- 3,178 canonical KSH settlements;
- 121 referenced railway-line codes;
- 921 proposed settlement–RailwayLine relations;
- 803 settlements with at least one proposed relation;
- 2,375 settlements left without a relation and therefore still covered by the
  nationwide UNCLASSIFIED fallback;
- 20 operational ServiceAreas (19 counties and Budapest);
- 50 lines crossing more than one ServiceArea.

Tata (`20127`) is matched to line `1` and the
`ORSZEM-KOMAROM-ESZTERGOM` ServiceArea.

## Source and evidence gates

The railway evidence is derived from the Geofabrik Hungary OpenStreetMap extract,
licensed under ODbL. The input PBF SHA-256 is recorded in the generated manifest.
No PENDING VPE, KTI or GYSEV dataset is used or derived.

Automatic matching is deliberately conservative:

- exact normalized KSH settlement-name match only;
- only station/halt objects within 100 metres of a referenced railway way;
- railway references must match the restricted Hungarian line-code format;
- every accepted row retains its OSM object ID, source name and measured distance;
- ambiguous and unmatched objects are quarantined in `needs-review.csv`;
- no fuzzy matching and no fabricated RailwayLine, relation or area assignment.

The generated manifest remains `UNVERIFIED`, has
`automaticImportAllowed: false`, and is rejected by the normal operational-plan build
unless the explicit offline review option is supplied. The derived operational preview
is marked `OFFLINE_UNVERIFIED_SOURCE_REVIEW` and `runtimeCompatible: false`.

## Backend design

Migration V007 introduces `service_area_settlement_lines`, keyed by the canonical
settlement–RailwayLine relation and referencing a ServiceArea. This permits a railway
line crossing multiple operational territories without assigning the whole line to one
area.

Routing semantics:

- a configured settlement–line pair routes to its mapped ServiceArea;
- an unconfigured pair never borrows another settlement's mapping on the same line;
- existing immutable routing snapshots are not rewritten;
- zero-relation settlements remain accepted through the existing UNCLASSIFIED fallback;
- legacy whole-line mappings remain supported, but whole-line and pair mapping modes
  cannot be mixed for one line.

SUPER_ADMIN receives preview, apply and list operations with optimistic reference and
current-assignment checks. Apply is atomic, audited and guarded against concurrent area
deactivation or competing assignment changes. Reference imports cannot remove a
settlement, line or relation used by an operational pair mapping.

## Verification

- OSM/territory generator tests: 18 passing locally.
- Full backend suite: green after correcting an invalid test fixture that attempted a
  forbidden SUPER_ADMIN-to-SUPER_ADMIN HTTP deactivation. Production authorization was
  not changed.
- Backend, Android build/test/lint, Web, reference-data and deployment/restore workflows
  are all green on final GitHub HEAD `1512635e916d2f517fb02dbc3a8fd279feaa72fe`.

Coverage includes authorization, atomic rollback, concurrent assignment, routing during
concurrent moves, legacy-mode incompatibility, inactive references/areas, immutable
history, import protection and territorial report visibility.

### Evidence sampling

The 921 unique proposed pairs are backed by 925 OSM evidence rows (four pairs have two
evidence objects). Distance distribution is conservative: 750 evidence rows are within
10 metres of the referenced way, 888 within 25 metres, 911 within 50 metres and all 925
within the 100-metre hard limit. The median is 4.3 metres and the maximum is 95.3 metres.

The quarantine contains 838 station/halt objects: 764 have no unique exact KSH-name
match and 74 have no referenced railway way inside the distance limit. They contribute
no canonical relation.

Sampling also found OSM references that may describe industrial, internal or otherwise
non-operational line identifiers (for example `262e`, `300b` and `400`). Their syntax is
valid under the deliberately broad evidence parser, but syntax is not sufficient for
production verification. Promotion must either classify these references against an
approved source or add a reviewed infrastructure-type exclusion rule. They must not be
silently promoted as ordinary public RailwayLines.

## Owner-review and rollout gate

This phase must stop before production import. Promotion requires:

1. review of the generated `needs-review.csv` and a representative sample of accepted
   evidence, including multi-area lines and border settlements;
2. an explicit decision on the acceptable OSM verification/promotion procedure;
3. a cleared, versioned import package with attribution and checksums;
4. preview/diff review against production, backup and restore drill;
5. final CI green on the exact reviewed commit;
6. owner approval for import and backend rollout.

The nationwide UNCLASSIFIED policy remains enabled until verified coverage and open
assignment state make its separate cutover safe.
