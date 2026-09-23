#!/usr/bin/env python3
"""Promote a reviewed OSM railway candidate set into an importable canonical dataset.

This is the ONLY place `verificationStatus` may become `VERIFIED` for an OSM-derived
dataset. It never runs as a side effect of `build-osm-railway-reference.py`, of a normal
CI push, or of downloading OSM data — it is a deliberate, separate command a reviewer
runs after decisions have been recorded (see `decisions/README.md`).

Promotion succeeds only when EVERY deterministically-accepted (kshCode, lineCode) pair
candidate has a matching decision file that is ACCEPTED and whose evidenceHash still
matches the candidate's current evidenceHash. If even one is missing, stale, or REJECTED,
this script writes a `promotion-status.json` report describing exactly what is missing
and exits non-zero WITHOUT writing any manifest — never a partially-VERIFIED dataset.
"""

import argparse
import csv
import hashlib
import json
from pathlib import Path
import sys


class PromotionBlocked(Exception):
    pass


def sha256_bytes(value):
    return hashlib.sha256(value).hexdigest()


def write_csv(path, header, rows):
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(header)
        writer.writerows(rows)


def load_candidates(review_dir):
    data = json.loads((review_dir / "candidates.json").read_text(encoding="utf-8"))
    return {c["candidateId"]: c for c in data["candidates"]}, data["policyVersion"]


def load_decisions(decisions_dir):
    decisions = {}
    for path in sorted(decisions_dir.glob("*.json")):
        if path.name == "README.md":
            continue
        try:
            record = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            raise PromotionBlocked(f"{path.name}: not valid JSON ({exc})") from exc
        required = {"candidateId", "decision", "reasonCode", "note", "evidenceHash", "policyVersion", "reviewer", "decidedAt"}
        missing = required - record.keys()
        if missing:
            raise PromotionBlocked(f"{path.name}: missing required field(s) {sorted(missing)} - a blank approval file is never valid")
        if record["decision"] not in ("ACCEPTED", "REJECTED"):
            raise PromotionBlocked(f"{path.name}: decision must be ACCEPTED or REJECTED, got {record['decision']!r}")
        if not record["note"].strip():
            raise PromotionBlocked(f"{path.name}: note must not be empty")
        if record["candidateId"] in decisions:
            raise PromotionBlocked(f"duplicate decision for candidateId {record['candidateId']!r}")
        decisions[record["candidateId"]] = record
    return decisions


def promote(review_dir, decisions_dir, out_dir, expect_policy_version=None):
    candidates, dataset_policy_version = load_candidates(review_dir)
    if expect_policy_version is not None and expect_policy_version != dataset_policy_version:
        raise PromotionBlocked(
            f"review dataset policyVersion is {dataset_policy_version!r}, expected {expect_policy_version!r} "
            "- pass the version you actually reviewed against, or omit --expect-policy-version deliberately",
        )
    decisions = load_decisions(decisions_dir)

    pair_candidates = {cid: c for cid, c in candidates.items() if c["category"] == "ORDINARY_ACCEPTED_BY_RULE"}

    report = {
        "totalPairCandidates": len(pair_candidates),
        "accepted": [],
        "missingDecision": [],
        "staleDecision": [],
        "rejected": [],
        "wrongPolicyVersion": [],
    }

    for candidate_id, candidate in sorted(pair_candidates.items()):
        decision = decisions.get(candidate_id)
        if decision is None:
            report["missingDecision"].append(candidate_id)
            continue
        if decision["policyVersion"] != dataset_policy_version:
            report["wrongPolicyVersion"].append(candidate_id)
            continue
        if decision["evidenceHash"] != candidate["evidenceHash"]:
            report["staleDecision"].append(candidate_id)
            continue
        if decision["decision"] == "REJECTED":
            report["rejected"].append(candidate_id)
            continue
        report["accepted"].append(candidate_id)

    # A decision file whose candidateId does not correspond to any known pair candidate at
    # all (typo, stale file from a removed candidate, or an attempt to smuggle a decision
    # for something the build never produced) is also a hard block - never silently ignored.
    unknown_decisions = sorted(set(decisions) - set(candidates))
    if unknown_decisions:
        report["unknownDecisions"] = unknown_decisions

    ready = (
        report["totalPairCandidates"] > 0
        and not report["missingDecision"]
        and not report["staleDecision"]
        and not report["rejected"]
        and not report["wrongPolicyVersion"]
        and not unknown_decisions
    )
    report["readyForPromotion"] = ready

    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "promotion-status.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8",
    )

    if not ready:
        return report, None

    # Rebuild the canonical three files from ONLY accepted, current decisions - never from
    # the raw deterministic-rule output directly, even though in a fully-approved run the
    # two sets are identical by construction.
    accepted_pairs = sorted(
        (candidates[cid]["kshCode"], candidates[cid]["lineCode"]) for cid in report["accepted"]
    )
    used_codes = sorted({code for _, code in accepted_pairs})

    settlements_bytes = (review_dir / "settlements.csv").read_bytes()
    (out_dir / "settlements.csv").write_bytes(settlements_bytes)
    write_csv(out_dir / "railway-lines.csv", ["line_code", "display_name"],
              [(code, f"{code}. számú vasútvonal") for code in used_codes])
    write_csv(out_dir / "settlement-railway-lines.csv", ["ksh_code", "line_code"], accepted_pairs)

    review_manifest = json.loads((review_dir / "manifest.json").read_text(encoding="utf-8"))
    canonical = {name: sha256_bytes((out_dir / name).read_bytes())
                 for name in ("settlements.csv", "railway-lines.csv", "settlement-railway-lines.csv")}
    manifest = {
        "datasetVersion": review_manifest["datasetVersion"].replace("OSM-HU-RAIL-REVIEW-", "OSM-HU-RAIL-VERIFIED-"),
        "generatedAt": review_manifest["generatedAt"],
        "verificationStatus": "VERIFIED",
        "coverageStatus": "PARTIAL",
        "reuseStatus": "CLEARED",
        "sources": review_manifest["sources"],
        "canonicalFiles": canonical,
        "counts": {
            "settlements": review_manifest["counts"]["settlements"],
            "railwayLines": len(used_codes),
            "settlementRailwayLineMappings": len(accepted_pairs),
        },
        "coverage": {
            "settlements": "COMPLETE", "railwayLines": "PARTIAL", "settlementRailwayLines": "PARTIAL",
            "settlementsWithVerifiedRelations": len({k for k, _ in accepted_pairs}),
        },
        "review": {
            "promotedFromPolicyVersion": dataset_policy_version,
            "promotedPairCount": len(accepted_pairs),
            "automaticImportAllowed": True,
            "runtimeCompatible": True,
            "status": "REVIEWED_PROMOTED",
        },
    }
    (out_dir / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return report, manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--review-dir", type=Path, default=Path("reference-data/osm-review"))
    parser.add_argument("--decisions-dir", type=Path, default=None, help="Defaults to <review-dir>/decisions")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--expect-policy-version", default=None)
    args = parser.parse_args()
    decisions_dir = args.decisions_dir or (args.review_dir / "decisions")
    try:
        report, manifest = promote(args.review_dir, decisions_dir, args.out, args.expect_policy_version)
    except PromotionBlocked as exc:
        print(f"BLOCKED: {exc}", file=sys.stderr)
        return 2
    if manifest is None:
        print(json.dumps({"readyForPromotion": False, **{k: v for k, v in report.items() if k != "readyForPromotion"}}, ensure_ascii=False, indent=2))
        print(
            f"NOT PROMOTED - {len(report['missingDecision'])} missing, {len(report['staleDecision'])} stale, "
            f"{len(report['rejected'])} rejected, {len(report['wrongPolicyVersion'])} wrong-policy-version decision(s) "
            f"out of {report['totalPairCandidates']} candidate(s). See promotion-status.json.",
            file=sys.stderr,
        )
        return 1
    print(f"PROMOTED {manifest['datasetVersion']}: {manifest['counts']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
