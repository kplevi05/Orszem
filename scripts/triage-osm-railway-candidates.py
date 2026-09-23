#!/usr/bin/env python3
"""Structured, machine-generated triage of OSM_EVIDENCE_ACCEPTED railway candidates.

Produces a breakdown along the dimensions a reviewer needs before looking at any individual
row - never a substitute for review, but the thing that makes 919 candidates tractable
instead of a blind line-by-line scroll. Reads only files already committed under
reference-data/osm-review/ and operational-data/county-v1/osm-review-generated/ - no
network access, no PENDING dataset, nothing not already in this repository.

"Unusual" line code, for this triage only, means: two or more trailing letters, OR a leading
numeric portion greater than 160 (review-policy.json's own eligibility rule already caps a
code at 3 digits + 2 letters; this triage threshold is a *review priority* signal on top of
that, not an acceptance/rejection rule - see review-policy.json's "unusualLineCodes" section).
"""

import argparse
import csv
import json
import re
from collections import defaultdict
from pathlib import Path

LINE_CODE_NUMERIC_PREFIX = re.compile(r"^([0-9]+)")
LINE_CODE_LETTER_SUFFIX = re.compile(r"([A-Za-z]+)$")
UNUSUAL_THRESHOLD = 160

# The exact codes the review brief named as requiring individual, documented investigation.
NAMED_UNUSUAL_CODES = {
    "70AX", "262e", "268", "284", "300b", "306", "342", "351", "353", "371", "372", "392", "400",
}


def is_unusual_code(code):
    letters = LINE_CODE_LETTER_SUFFIX.search(code)
    if letters and len(letters.group(1)) >= 2:
        return True
    numeric = LINE_CODE_NUMERIC_PREFIX.match(code)
    if numeric and int(numeric.group(1)) > UNUSUAL_THRESHOLD:
        return True
    return code in NAMED_UNUSUAL_CODES


def read_csv_rows(path):
    if not path.exists():
        return []
    with path.open(encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle))


def distance_band(distance_m):
    d = float(distance_m)
    if d <= 10:
        return "0-10m"
    if d <= 25:
        return "10-25m"
    if d <= 50:
        return "25-50m"
    return "50-100m"


def build(review_dir, operational_dir, out_path):
    candidates_data = json.loads((review_dir / "candidates.json").read_text(encoding="utf-8"))
    candidates = candidates_data["candidates"]
    evidence_rows = read_csv_rows(review_dir / "evidence.csv")
    non_op_rows = read_csv_rows(review_dir / "non-operational-evidence.csv")
    needs_review_rows = read_csv_rows(review_dir / "needs-review.csv")
    crossing_rows = read_csv_rows(operational_dir / "line-routing-assessment.csv") if operational_dir else []
    crossing_lines = {r["line_code"] for r in crossing_rows if r.get("assessment") == "REQUIRES_SETTLEMENT_LINE_ROUTING"}

    accepted = [c for c in candidates if c["category"] == "OSM_EVIDENCE_ACCEPTED"]

    by_line_code = defaultdict(list)
    for row in evidence_rows:
        by_line_code[row["line_code"]].append(row)

    ordinary_codes = sorted({c["lineCode"] for c in accepted})
    unusual_codes = sorted(code for code in ordinary_codes if is_unusual_code(code))

    evidence_by_pair = defaultdict(list)
    for row in evidence_rows:
        evidence_by_pair[(row["ksh_code"], row["line_code"])].append(row)

    stations_multi_line = defaultdict(set)
    for row in evidence_rows:
        stations_multi_line[row["osm_id"]].add(row["line_code"])
    multi_line_stations = {osm_id: sorted(codes) for osm_id, codes in stations_multi_line.items() if len(codes) > 1}

    far_evidence = [r for r in evidence_rows if float(r["distance_m"]) > 50]

    budapest_or_composite = [
        r for r in needs_review_rows
        if "budapest" in r["osm_name"].lower() or "-" in r["osm_name"] or " " in r["normalized_name"]
    ]

    reason_counts = defaultdict(int)
    for r in needs_review_rows:
        reason_counts[r["reason"]] += 1

    service_tag_counts = defaultdict(int)
    usage_tag_counts = defaultdict(int)
    for r in non_op_rows:
        if r["service_tag"]:
            service_tag_counts[r["service_tag"]] += 1
        if r["usage_tag"]:
            usage_tag_counts[r["usage_tag"]] += 1

    unusual_dossier = []
    for code in sorted(NAMED_UNUSUAL_CODES | set(unusual_codes)):
        occurrences = {
            "lineCode": code,
            "acceptedEvidence": [
                {"kshCode": r["ksh_code"], "osmId": r["osm_id"], "osmName": r["osm_name"], "distanceM": r["distance_m"]}
                for r in by_line_code.get(code, [])
            ],
            "nonOperationalEvidence": [
                {"kshCode": r["ksh_code"], "osmId": r["osm_id"], "osmName": r["osm_name"], "distanceM": r["distance_m"],
                 "serviceTag": r["service_tag"], "usageTag": r["usage_tag"]}
                for r in non_op_rows if r["line_code"] == code
            ],
            "crossesMultipleServiceAreas": code in crossing_lines,
            "independentlyVerified": False,
            "note": (
                "No legally-cleared independent source has been consulted for this code in this "
                "phase - see reference-data/osm-review/decisions/README.md 'Independent sources'. "
                "OSM-internal evidence only; stays at most OSM_EVIDENCE_ACCEPTED until a human "
                "records an INDEPENDENTLY_VERIFIED decision against a documented external source."
            ),
        }
        if occurrences["acceptedEvidence"] or occurrences["nonOperationalEvidence"]:
            unusual_dossier.append(occurrences)

    report = {
        "generatedFrom": {
            "datasetVersion": candidates_data.get("policyVersion"),
            "totalCandidates": len(candidates),
            "osmEvidenceAcceptedPairs": len(accepted),
        },
        "byOrdinaryVsUnusualLineCode": {
            "ordinaryCodeCount": len(ordinary_codes) - len(unusual_codes),
            "unusualCodeCount": len(unusual_codes),
            "unusualCodes": unusual_codes,
        },
        "byNonOperationalTag": {
            "serviceTagCounts": dict(sorted(service_tag_counts.items())),
            "usageTagCounts": dict(sorted(usage_tag_counts.items())),
        },
        "byDistanceBand": {
            band: sum(1 for r in evidence_rows if distance_band(r["distance_m"]) == band)
            for band in ("0-10m", "10-25m", "25-50m", "50-100m")
        },
        "stationsWithMultipleAcceptedLines": {
            "count": len(multi_line_stations),
            "sample": dict(list(sorted(multi_line_stations.items()))[:20]),
        },
        "pairsWithMultipleEvidenceRows": {
            "count": sum(1 for rows in evidence_by_pair.values() if len(rows) > 1),
        },
        "linesCrossingMultipleServiceAreas": {
            "count": len(crossing_lines),
            "lineCodes": sorted(crossing_lines),
        },
        "evidenceFartherThan50Metres": {
            "count": len(far_evidence),
            "rows": [{"kshCode": r["ksh_code"], "lineCode": r["line_code"], "distanceM": r["distance_m"]} for r in far_evidence],
        },
        "budapestOrCompositeStationNames": {
            "count": len(budapest_or_composite),
            "sample": [r["osm_name"] for r in budapest_or_composite[:30]],
        },
        "needsReviewReasonCounts": dict(sorted(reason_counts.items())),
        "namedUnusualLineCodeDossier": unusual_dossier,
    }
    out_path.write_text(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--review-dir", type=Path, default=Path("reference-data/osm-review"))
    parser.add_argument("--operational-dir", type=Path, default=Path("operational-data/county-v1/osm-review-generated"))
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    report = build(args.review_dir, args.operational_dir, args.out)
    print(json.dumps({k: v for k, v in report.items() if k not in ("namedUnusualLineCodeDossier",)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
