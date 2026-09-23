# Sample 10 — human approval sheet

This sheet summarizes the detailed source-chain check for
`sample-10-2026-09-23.json`. It is a review aid, not a decision file and not an
approval. Nothing in this directory is consumed by the promotion tool.

## Ready for an explicit human decision

| Candidate | Pair | Pinned evidence | Source-chain assessment | Proposed tier |
|---|---|---|---|---|
| `pair:01508:75` | Ipolyszög — 75 | [Wikipedia revision](https://hu.wikipedia.org/w/index.php?title=V%C3%A1c%E2%80%93Balassagyarmat-vas%C3%BAtvonal&oldid=29222283) | Line 75 and Ipolyszög are both explicit. The historical route narrative naming Ipolyszög carries print-book citations. No VPE/HÜSZ citation found. | `INDEPENDENTLY_VERIFIED` |
| `pair:01526:81` | Somoskőújfalu — 81 | [Wikipedia revision](https://hu.wikipedia.org/w/index.php?title=Hatvan%E2%80%93Somosk%C5%91%C3%BAjfalu-vas%C3%BAtvonal&oldid=29212373) | Line 81 and Somoskőújfalu at km 64.6 are explicit. The article has historical and timetable references, but the route row is not individually footnoted. No VPE/HÜSZ citation found. | `INDEPENDENTLY_VERIFIED` |
| `pair:02097:23` | Gutorfölde — 23 | [Wikipedia revision](https://hu.wikipedia.org/w/index.php?title=Zalaegerszeg%E2%80%93R%C3%A9dics-vas%C3%BAtvonal&oldid=29212023) | Line 23 and Gutorfölde at km 26 are explicit; the settlement article repeats the relationship. The line article lists a railway lexicon, maps and timetables. No VPE/HÜSZ citation found. | `INDEPENDENTLY_VERIFIED` |
| `pair:02185:29` | Csopak — 29 | [Wikipedia revision](https://hu.wikipedia.org/w/index.php?title=B%C3%B6rg%C3%B6nd%E2%80%93Szabadbatty%C3%A1n%E2%80%93Tapolca-vas%C3%BAtvonal&oldid=29315834) | Line 29 and Csopak at km 60 are explicit. The article has MÁV and independent railway references, but the route row is not individually footnoted. No VPE/HÜSZ citation found. | `INDEPENDENTLY_VERIFIED` |
| `pair:02219:29` | Balatonfűzfő — 29 | [Wikipedia revision](https://hu.wikipedia.org/w/index.php?title=B%C3%B6rg%C3%B6nd%E2%80%93Szabadbatty%C3%A1n%E2%80%93Tapolca-vas%C3%BAtvonal&oldid=29315834) | Line 29 and Balatonfűzfő at km 44 are explicit; the lead image also identifies Balatonfűzfő station. No VPE/HÜSZ citation found. | `INDEPENDENTLY_VERIFIED` |
| `pair:02325:116` | Baktalórántháza — 116 | [Wikipedia revision](https://hu.wikipedia.org/w/index.php?title=Ny%C3%ADregyh%C3%A1za%E2%80%93V%C3%A1s%C3%A1rosnam%C3%A9ny-vas%C3%BAtvonal&oldid=28592564) | Line 116 and Baktalórántháza at km 34 are explicit; the article also describes the station's junction history and cites print railway history. No VPE/HÜSZ citation found. | `INDEPENDENTLY_VERIFIED` |
| `pair:02431:15` | Bük — 15 | [Wikipedia revision](https://hu.wikipedia.org/w/index.php?title=Sopron%E2%80%93Szombathely-vas%C3%BAtvonal&oldid=29352684) | GYSEV line 15 and Bük at km 39 are explicit. The bibliography includes print history, a line-specific archived source and GYSEV material. No VPE/HÜSZ citation found. | `INDEPENDENTLY_VERIFIED` |

The current policy permits a human reviewer to use a legally reusable Wikipedia
article as the independent source. The repeated caveat above is therefore a
source-strength disclosure, not an automatic blocker. A human must still open
each pinned revision and approve the candidate explicitly before a decision file
can be created.

## Blocked by prohibited source lineage

| Candidate | Pair | Reason |
|---|---|---|
| `pair:02051:30` | Sávoly — 30 | The checked Wikipedia article points to the VPE line sheet for the relevant line facts. |
| `pair:02264:40` | Vásárosdombó — 40 | The checked Wikipedia article explicitly cites the VPE Hálózati Üzletszabályzat. |
| `pair:02307:100` | Kaba — 100 | The checked Wikipedia article includes HÜSZ material from VPE/KTI in its source chain. |

These three must remain non-promotable unless a genuinely independent,
legally reusable source is found. The pending VPE/KTI/GYSEV material must not be
opened, compared, copied or used to reconstruct the facts.

## Approval boundary

Approval must identify the candidate IDs being approved. Approval of this sheet
means only that the named candidates may be converted into decision files with
`reviewerType: HUMAN`, `humanApproved: true` and the pinned revision recorded in
`independentSourcesChecked`. Silence, a generic request to continue, or an AI
recommendation is not approval.
