# OSM railway review proposals

Files in this directory are research notes only. They are deliberately separate from
`../decisions/` and are never read by the promotion tool.

A proposal may be produced with automated research assistance, but it is not a human
decision and must never contain or imply `humanApproved: true`. Moving a proposal into
`../decisions/` requires a human reviewer to inspect the cited page, its source chain,
the candidate evidence and the current review policy, then create a decision using the
schema in `../decisions/README.md`.

`BLOCKED_PROHIBITED_SOURCE_LINEAGE` means the Wikipedia article cites HÜSZ/VPE for the
relevant line facts. Such an article cannot be used to route around the prohibition on
PENDING VPE/KTI/GYSEV row-level data.

`READY_FOR_HUMAN_SOURCE_REVIEW` means that the article directly corroborates the
settlement/line pair and no VPE citation was found in the rendered page. This is still
not approval: the reviewer must confirm that the cited assertion is adequately sourced
and independent before creating an `INDEPENDENTLY_VERIFIED` decision.

`READY_FOR_HUMAN_APPROVAL` means that the detailed source-chain check is complete: a
pinned Wikipedia revision directly identifies the line and settlement relationship, its
licence and references were inspected, and no prohibited VPE/HÜSZ lineage was found. It
is still non-promotable until a human opens the pinned revision and explicitly approves
that specific candidate.
