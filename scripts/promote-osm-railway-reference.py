#!/usr/bin/env python3
"""Promote INDEPENDENTLY_VERIFIED OSM railway candidates into an importable dataset.

This is the ONLY place `verificationStatus` may become `VERIFIED` for an OSM-derived
dataset. It never runs as a side effect of `build-osm-railway-reference.py`, of a normal
CI push, or of downloading OSM data - it is a deliberate, separate command a reviewer runs
after decisions have been recorded (see `decisions/README.md`).

OSM cannot verify itself: every candidate `build-osm-railway-reference.py` produces is, at
most, `OSM_EVIDENCE_ACCEPTED` - internally consistent within one source, never independently
corroborated. This script never promotes a candidate on that tier alone, no matter how many
of them exist. Only a decision file with `decisionTier: INDEPENDENTLY_VERIFIED`, made by a
human (`reviewerType: HUMAN`, `humanApproved: true`) against at least one documented,
legally-checked independent source, with a still-current `evidenceHash`, ever reaches the
promoted output.

If zero candidates carry a current INDEPENDENTLY_VERIFIED decision, promotion correctly
produces **no manifest at all** - never a `VERIFIED` dataset with zero rows, and never a
manifest built from `OSM_EVIDENCE_ACCEPTED` alone. A non-empty verified subset promotes
*only that subset*, leaving every other candidate exactly where it was.
"""

import argparse
import csv
import hashlib
import json
from pathlib import Path
import sys

VALID_TIERS = {"REJECTED", "QUARANTINED", "OSM_EVIDENCE_ACCEPTED", "INDEPENDENTLY_VERIFIED"}
# OSM_EVIDENCE_ACCEPTED is candidates.json's own record of the deterministic rule's result -
# never something a human decision *file* asserts (decisions/README.md). A file claiming it
# would be indistinguishable, on paper, from a genuine human review that merely agreed with
# the rule - exactly the ambiguity this gate must never allow.
TIERS_VALID_IN_A_DECISION_FILE = {"REJECTED", "QUARANTINED", "INDEPENDENTLY_VERIFIED"}
REQUIRED_METHOD_FOR_TIER = {
    "REJECTED": "HUMAN_REVIEW",
    "QUARANTINED": "HUMAN_REVIEW",
    "INDEPENDENTLY_VERIFIED": "INDEPENDENT_SOURCE_VERIFICATION",
}


class PromotionBlocked(Exception):
    pass


DISPLAY_NAME_SCHEMA_VERSION = 1
REQUIRED_DISPLAY_NAME_DECISION_FIELDS = {
    "lineCode", "approvedDisplayName", "source", "evidence", "decisionStatus",
    "humanApproved", "policyVersion",
}
REQUIRED_DISPLAY_NAME_SOURCE_FIELDS = {"url", "retrievedAt", "sourceType", "license"}


def load_display_name_decisions(path, used_codes, expect_policy_version=None):
    """Load owner-approved line display-name overrides.

    Returns `{line_code: approved_display_name}` for exactly the lines with a current,
    `humanApproved: true` decision. A line with no entry here, or an entry that is not
    `humanApproved: true`, is simply absent from the returned mapping - the caller keeps
    the safe placeholder for it. This function never invents or infers a name.

    Hard failure (never a partial/silent result) on:
      - the file missing or not valid JSON;
      - `schemaVersion` not the one this promoter understands;
      - a policyVersion mismatch, if `expect_policy_version` was given;
      - a decision missing a required field, or its `source` missing a required subfield;
      - two decisions naming the same `lineCode` (duplicate);
      - a decision naming a `lineCode` that is not among this run's own promoted
        `used_codes` - i.e. a decision that no longer corresponds to a currently verified
        line (the closest equivalent here to a "stale" decision: railway line names have
        no per-candidate evidence hash to go stale against the way OSM pair decisions do,
        so staleness is defined structurally instead - the decision has drifted away from
        the dataset it was meant to describe).
    """
    if not path.is_file():
        raise PromotionBlocked(f"{path}: display-name decisions file not found")
    try:
        doc = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise PromotionBlocked(f"{path}: not valid JSON ({exc})") from exc

    if doc.get("schemaVersion") != DISPLAY_NAME_SCHEMA_VERSION:
        raise PromotionBlocked(
            f"{path}: schemaVersion is {doc.get('schemaVersion')!r}, expected {DISPLAY_NAME_SCHEMA_VERSION!r}",
        )
    file_policy_version = doc.get("policyVersion")
    if not file_policy_version:
        raise PromotionBlocked(f"{path}: missing top-level policyVersion")
    if expect_policy_version is not None and expect_policy_version != file_policy_version:
        raise PromotionBlocked(
            f"{path}: policyVersion is {file_policy_version!r}, expected {expect_policy_version!r} - "
            "pass the version you actually reviewed against, or omit --expect-name-policy-version deliberately",
        )

    decisions = doc.get("decisions")
    if not isinstance(decisions, list):
        raise PromotionBlocked(f"{path}: 'decisions' must be a list")

    seen_codes = set()
    approved = {}
    for i, record in enumerate(decisions):
        if not isinstance(record, dict):
            raise PromotionBlocked(f"{path}: decisions[{i}] is not an object")
        missing = REQUIRED_DISPLAY_NAME_DECISION_FIELDS - record.keys()
        if missing:
            raise PromotionBlocked(f"{path}: decisions[{i}] missing required field(s) {sorted(missing)}")
        source = record["source"]
        if not isinstance(source, dict) or (REQUIRED_DISPLAY_NAME_SOURCE_FIELDS - source.keys()):
            src_missing = REQUIRED_DISPLAY_NAME_SOURCE_FIELDS - (source.keys() if isinstance(source, dict) else set())
            raise PromotionBlocked(f"{path}: decisions[{i}].source missing required field(s) {sorted(src_missing)}")
        if not str(record["approvedDisplayName"]).strip():
            raise PromotionBlocked(f"{path}: decisions[{i}].approvedDisplayName must not be empty")
        if not str(record["evidence"]).strip():
            raise PromotionBlocked(f"{path}: decisions[{i}].evidence must not be empty")
        if record["policyVersion"] != file_policy_version:
            raise PromotionBlocked(
                f"{path}: decisions[{i}].policyVersion {record['policyVersion']!r} does not match the "
                f"file's own policyVersion {file_policy_version!r}",
            )

        code = record["lineCode"]
        if code in seen_codes:
            raise PromotionBlocked(f"{path}: duplicate decision for lineCode {code!r}")
        seen_codes.add(code)

        if code not in used_codes:
            raise PromotionBlocked(
                f"{path}: decisions[{i}] names lineCode {code!r}, which is not among this run's "
                "promoted line codes - a stale decision for a line no longer in the verified subset",
            )

        if record["humanApproved"] is True:
            approved[code] = record["approvedDisplayName"]
        elif record["humanApproved"] is not False:
            raise PromotionBlocked(f"{path}: decisions[{i}].humanApproved must be a boolean")

    return approved


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


def validate_decision(path, record):
    required = {
        "candidateId", "decisionTier", "decisionMethod", "reviewerType", "humanApproved",
        "reasonCode", "note", "evidenceHash", "policyVersion", "reviewer", "decidedAt",
    }
    missing = required - record.keys()
    if missing:
        raise PromotionBlocked(f"{path.name}: missing required field(s) {sorted(missing)} - a blank approval file is never valid")

    tier = record["decisionTier"]
    if tier not in VALID_TIERS:
        raise PromotionBlocked(f"{path.name}: decisionTier must be one of {sorted(VALID_TIERS)}, got {tier!r}")
    if tier not in TIERS_VALID_IN_A_DECISION_FILE:
        raise PromotionBlocked(
            f"{path.name}: decisionTier {tier!r} may never appear in a decision file - "
            "OSM_EVIDENCE_ACCEPTED is candidates.json's own machine record, not a human decision",
        )
    if record["reviewerType"] != "HUMAN":
        raise PromotionBlocked(f"{path.name}: reviewerType must be HUMAN in a decision file, got {record['reviewerType']!r}")
    if record["decisionMethod"] != REQUIRED_METHOD_FOR_TIER[tier]:
        raise PromotionBlocked(
            f"{path.name}: decisionTier {tier!r} requires decisionMethod "
            f"{REQUIRED_METHOD_FOR_TIER[tier]!r}, got {record['decisionMethod']!r}",
        )
    expected_approved = tier == "INDEPENDENTLY_VERIFIED"
    if record["humanApproved"] != expected_approved:
        raise PromotionBlocked(f"{path.name}: humanApproved must be {expected_approved} for decisionTier {tier!r}")
    if not str(record["note"]).strip():
        raise PromotionBlocked(f"{path.name}: note must not be empty")

    if tier == "INDEPENDENTLY_VERIFIED":
        sources = record.get("independentSourcesChecked")
        if not sources or not isinstance(sources, list):
            raise PromotionBlocked(f"{path.name}: INDEPENDENTLY_VERIFIED requires a non-empty independentSourcesChecked list")
        for entry in sources:
            entry_missing = {"url", "retrievedAt", "usageBasis"} - set(entry or {})
            if entry_missing:
                raise PromotionBlocked(f"{path.name}: independentSourcesChecked entry missing {sorted(entry_missing)}")


def load_decisions(decisions_dir):
    decisions = {}
    for path in sorted(decisions_dir.glob("*.json")):
        try:
            record = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            raise PromotionBlocked(f"{path.name}: not valid JSON ({exc})") from exc
        validate_decision(path, record)
        if record["candidateId"] in decisions:
            raise PromotionBlocked(f"duplicate decision for candidateId {record['candidateId']!r}")
        decisions[record["candidateId"]] = record
    return decisions


def promote(review_dir, decisions_dir, out_dir, expect_policy_version=None,
            display_name_decisions_path=None, expect_name_policy_version=None):
    candidates, dataset_policy_version = load_candidates(review_dir)
    if expect_policy_version is not None and expect_policy_version != dataset_policy_version:
        raise PromotionBlocked(
            f"review dataset policyVersion is {dataset_policy_version!r}, expected {expect_policy_version!r} "
            "- pass the version you actually reviewed against, or omit --expect-policy-version deliberately",
        )
    decisions = load_decisions(decisions_dir)

    pair_candidates = {cid: c for cid, c in candidates.items() if c["category"] == "OSM_EVIDENCE_ACCEPTED"}

    report = {
        "totalPairCandidates": len(pair_candidates),
        "independentlyVerified": [],
        "rejected": [],
        "quarantined": [],
        "staleDecision": [],
        "wrongPolicyVersion": [],
        "osmEvidenceOnlyNoHumanDecision": [],
    }

    for candidate_id, candidate in sorted(pair_candidates.items()):
        decision = decisions.get(candidate_id)
        if decision is None:
            report["osmEvidenceOnlyNoHumanDecision"].append(candidate_id)
            continue
        if decision["policyVersion"] != dataset_policy_version:
            report["wrongPolicyVersion"].append(candidate_id)
            continue
        if decision["evidenceHash"] != candidate["evidenceHash"]:
            report["staleDecision"].append(candidate_id)
            continue
        tier = decision["decisionTier"]
        if tier == "REJECTED":
            report["rejected"].append(candidate_id)
        elif tier == "QUARANTINED":
            report["quarantined"].append(candidate_id)
        else:
            report["independentlyVerified"].append(candidate_id)

    # A decision file whose candidateId does not correspond to any known candidate at all
    # (typo, stale file from a removed candidate, or an attempt to smuggle a decision for
    # something the build never produced) is also a hard block - never silently ignored.
    unknown_decisions = sorted(set(decisions) - set(candidates))
    if unknown_decisions:
        report["unknownDecisions"] = unknown_decisions

    verified_count = len(report["independentlyVerified"])
    ready = verified_count > 0 and not unknown_decisions
    report["readyForPromotion"] = ready
    report["promotableCount"] = verified_count

    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "promotion-status.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8",
    )

    if not ready:
        return report, None

    # Rebuild the canonical three files from ONLY the current INDEPENDENTLY_VERIFIED subset -
    # every other candidate, however large that set is, stays exactly where it was.
    verified_pairs = sorted(
        (candidates[cid]["kshCode"], candidates[cid]["lineCode"]) for cid in report["independentlyVerified"]
    )
    used_codes = sorted({code for _, code in verified_pairs})

    display_name_overrides = {}
    display_name_source_meta = None
    if display_name_decisions_path is not None:
        display_name_overrides = load_display_name_decisions(
            display_name_decisions_path, used_codes, expect_name_policy_version,
        )
        decisions_bytes = display_name_decisions_path.read_bytes()
        display_name_source_meta = {
            "decisionsFile": str(display_name_decisions_path),
            "decisionsFileSha256": sha256_bytes(decisions_bytes),
            "overriddenLineCount": len(display_name_overrides),
            "totalLineCount": len(used_codes),
            "attribution": (
                "Egyes vonalnevek forrása a magyar Wikipédia (CC BY-SA 4.0) - lásd "
                "reference-data/LICENSES/Wikipedia-CC-BY-SA.md."
            ),
        }

    settlements_bytes = (review_dir / "settlements.csv").read_bytes()
    (out_dir / "settlements.csv").write_bytes(settlements_bytes)
    write_csv(
        out_dir / "railway-lines.csv", ["line_code", "display_name"],
        [(code, display_name_overrides.get(code, f"{code}. számú vasútvonal")) for code in used_codes],
    )
    write_csv(out_dir / "settlement-railway-lines.csv", ["ksh_code", "line_code"], verified_pairs)

    review_manifest = json.loads((review_dir / "manifest.json").read_text(encoding="utf-8"))
    canonical = {name: sha256_bytes((out_dir / name).read_bytes())
                 for name in ("settlements.csv", "railway-lines.csv", "settlement-railway-lines.csv")}
    dataset_version = review_manifest["datasetVersion"].replace("OSM-HU-RAIL-REVIEW-", "OSM-HU-RAIL-VERIFIED-")
    if display_name_source_meta is not None and display_name_source_meta["overriddenLineCount"] > 0:
        # A display-name-only re-promotion must never share its exact datasetVersion string
        # with an already-imported dataset that has different content: the backend's
        # ReferenceImportUseCase looks up an existing import BY datasetVersion, and a content
        # mismatch under the same version raises ReferenceDatasetVersionConflictException
        # rather than upserting. The suffix is derived from the decisions file's own content,
        # so it changes deterministically whenever the approved name set changes, and stays
        # identical on a byte-for-byte identical re-run (still deterministic promotion output).
        dataset_version = f"{dataset_version}-NAMES-{display_name_source_meta['decisionsFileSha256'][:8]}"
        display_name_source_meta["appliedToDatasetVersionSuffix"] = f"-NAMES-{display_name_source_meta['decisionsFileSha256'][:8]}"
    manifest = {
        "datasetVersion": dataset_version,
        "generatedAt": review_manifest["generatedAt"],
        "verificationStatus": "VERIFIED",
        "coverageStatus": "PARTIAL",
        "reuseStatus": "CLEARED",
        "sources": review_manifest["sources"],
        "canonicalFiles": canonical,
        "counts": {
            "settlements": review_manifest["counts"]["settlements"],
            "railwayLines": len(used_codes),
            "settlementRailwayLineMappings": len(verified_pairs),
        },
        "coverage": {
            "settlements": "COMPLETE", "railwayLines": "PARTIAL", "settlementRailwayLines": "PARTIAL",
            "settlementsWithVerifiedRelations": len({k for k, _ in verified_pairs}),
        },
        "review": {
            "promotedFromPolicyVersion": dataset_policy_version,
            "promotedPairCount": len(verified_pairs),
            "totalCandidateCount": report["totalPairCandidates"],
            "note": (
                "This manifest contains only the INDEPENDENTLY_VERIFIED subset of the review "
                "dataset's candidates. OSM_EVIDENCE_ACCEPTED-only candidates are never included, "
                "regardless of how many exist - see promotion-status.json for the full breakdown."
            ),
            "automaticImportAllowed": True,
            "runtimeCompatible": True,
            "status": "REVIEWED_PROMOTED",
        },
    }
    if display_name_source_meta is not None:
        manifest["displayNameSource"] = display_name_source_meta
    (out_dir / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return report, manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--review-dir", type=Path, default=Path("reference-data/osm-review"))
    parser.add_argument("--decisions-dir", type=Path, default=None, help="Defaults to <review-dir>/decisions")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--expect-policy-version", default=None)
    parser.add_argument(
        "--display-name-decisions", type=Path, default=None,
        help="Owner-approved railway-line display-name decisions file (e.g. "
             "reference-data/osm-review/railway-line-display-name-decisions.json). "
             "Omit to keep every line's safe '<code>. számú vasútvonal' placeholder.",
    )
    parser.add_argument("--expect-name-policy-version", default=None)
    args = parser.parse_args()
    decisions_dir = args.decisions_dir or (args.review_dir / "decisions")
    try:
        report, manifest = promote(
            args.review_dir, decisions_dir, args.out, args.expect_policy_version,
            args.display_name_decisions, args.expect_name_policy_version,
        )
    except PromotionBlocked as exc:
        print(f"BLOCKED: {exc}", file=sys.stderr)
        return 2
    if manifest is None:
        print(json.dumps({k: v for k, v in report.items() if k != "readyForPromotion"}, ensure_ascii=False, indent=2))
        print(
            f"NOT PROMOTED - 0 candidates carry a current INDEPENDENTLY_VERIFIED decision "
            f"(out of {report['totalPairCandidates']} OSM_EVIDENCE_ACCEPTED candidates: "
            f"{len(report['osmEvidenceOnlyNoHumanDecision'])} with no human decision yet, "
            f"{len(report['quarantined'])} quarantined, {len(report['rejected'])} rejected, "
            f"{len(report['staleDecision'])} stale, {len(report['wrongPolicyVersion'])} wrong-policy-version). "
            "See promotion-status.json.",
            file=sys.stderr,
        )
        return 1
    print(f"PROMOTED {manifest['datasetVersion']}: {manifest['counts']} ({manifest['review']['promotedPairCount']} of {report['totalPairCandidates']} candidates)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
