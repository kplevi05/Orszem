# Hungarian Wikipedia — licence and attribution

**Source:** Magyar Wikipédia (Hungarian Wikipedia), specifically the article
*Magyarországi vasútvonalak listája* and the dedicated line articles it links to.

**Licence:** Creative Commons Attribution-ShareAlike 4.0 International (**CC BY-SA 4.0**).

## Required attribution

Any distribution of content derived from these articles must display:

> Forrás: Magyar Wikipédia — https://hu.wikipedia.org, CC BY-SA 4.0

**ShareAlike note:** unlike the CC BY 4.0 sources already used in this project (KSH), CC BY-SA
requires that any adaptation distributed onward carry the same licence. The *facts* used here
(a line's endpoint place names, derived from the cited legal instruments the Wikipedia table
itself transcribes) are not copyrightable in themselves; only the article text and any verbatim
excerpt would be. `railway-line-display-name-decisions.json`'s `evidence` fields summarize and
paraphrase rather than quote, and the approved `approvedDisplayName` values are short factual
place-name pairs (e.g. `"75 – Vác–Balassagyarmat"`), not creative expression — consistent with
how this project has always treated place names as facts, not as text requiring ShareAlike
propagation. If a future decision ever needs to quote article prose at length, that quote (and
only that quote) would need to carry a CC BY-SA notice of its own.

## Scope note — three distinct signals, not one "official" source

The cited legal instruments (*2011. évi CXCVI. törvény* 1. melléklete, *2005. évi CLXXXIII.
törvény* 4. melléklete, *194/2016. (VII. 13.) Korm. rendelet* 1. melléklete) are the actual
legal source; the Wikipedia table is a third-party transcription of them, not the decree text
itself. See `docs/RAILWAY_LINE_DISPLAY_NAME_AND_DETAIL_REVIEW.md` §2 for how this project keeps
"legal endpoint", "common name" and "our own verified section" as three separate concepts rather
than treating the Wikipedia table as automatically authoritative.

## Retrieved artefacts

| Article | URL | Retrieved | Used for |
|---|---|---|---|
| Magyarországi vasútvonalak listája | https://hu.wikipedia.org/wiki/Magyarorsz%C3%A1gi_vas%C3%BAtvonalak_list%C3%A1ja | 2026-09-29 | Legal endpoint pairs for 33 of the 38 promoted lines |
| Budapest–Hegyeshalom–Rajka-vasútvonal | https://hu.wikipedia.org/wiki/Budapest%E2%80%93Hegyeshalom%E2%80%93Rajka-vas%C3%BAtvonal | 2026-09-30 | Line 1 |
| Bicske–Székesfehérvár-vasútvonal | https://hu.wikipedia.org/wiki/Bicske%E2%80%93Sz%C3%A9kesfeh%C3%A9rv%C3%A1r-vas%C3%BAtvonal | 2026-09-30 | Line 6 |
| Apafa–Mátészalka-vasútvonal | https://hu.wikipedia.org/wiki/Apafa%E2%80%93M%C3%A1t%C3%A9szalka-vas%C3%BAtvonal | 2026-09-30 | Line 110 |
| Mátészalka–Nagykároly-vasútvonal | https://hu.wikipedia.org/wiki/M%C3%A1t%C3%A9szalka%E2%80%93Nagyk%C3%A1roly-vas%C3%BAtvonal | 2026-09-30 | Line 115 |
| Kunszentmiklós-Tass–Dunapataj-vasútvonal | https://hu.wikipedia.org/wiki/Kunszentmikl%C3%B3s-Tass%E2%80%93Dunapataj-vas%C3%BAtvonal | 2026-09-30 | Line 151 |

No page content was copied verbatim into this repository; `railway-line-display-name-decisions.json`
records only short place-name pairs and paraphrased evidence summaries, per-line, with the source
URL and retrieval date attached to each decision.
