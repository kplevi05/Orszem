#!/usr/bin/env python3
"""Offline preview of the county-based, pair-level ServiceArea assignment.

Never imports data, never applies a mapping, never touches a database. Input is a
*promoted* (VERIFIED/CLEARED) canonical railway snapshot; metadata gates are checked and the
canonical checksums verified before any source row is trusted. Output is a deterministic,
reviewable proposal: every (settlement, railwayLine) pair gets the ServiceArea of the
settlement's KSH county (data-driven, never from a name or a coordinate), or stays
UNASSIGNED. GYSEV is never assigned automatically; GYSEV override candidates are only
validated and listed, never applied.

Use --check to verify that committed output is byte-identical to a fresh build.
"""
import argparse
import csv
import hashlib
import io
import json
from collections import defaultdict
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
UUID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
HEADERS = {
    "settlements.csv": ["ksh_code", "name", "county_name"],
    "railway-lines.csv": ["line_code", "display_name"],
    "settlement-railway-lines.csv": ["ksh_code", "line_code"],
}
GYSEV_FIELDS = {"kshCode", "lineCode", "lineName", "sourceUrl", "retrievedAt", "evidence", "confidence", "status"}


class PreviewError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise PreviewError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode()


def csv_bytes(header, rows):
    out = io.StringIO(newline="")
    writer = csv.writer(out, lineterminator="\n")
    writer.writerow(header)
    writer.writerows(rows)
    return out.getvalue().encode()


def load_policy(path):
    policy = json.loads(path.read_bytes())
    areas = policy["serviceAreas"]
    require(len(areas) == 8, "exactly the eight owner-created ServiceAreas are expected")
    ids, county_owner = set(), {}
    for name, area in areas.items():
        require(UUID.fullmatch(area["id"]), f"invalid ServiceArea id for {name}")
        require(area["id"] not in ids, f"duplicate ServiceArea id for {name}")
        ids.add(area["id"])
        for county in area["counties"]:
            require(county not in county_owner, f"county {county!r} assigned to two areas")
            county_owner[county] = name
    require(areas["GYSEV"]["counties"] == [], "GYSEV must never receive a county-based assignment")
    for ksh, exc in policy.get("settlementIdExceptions", {}).items():
        require(re.fullmatch(r"[0-9]{5}", ksh), f"exception key {ksh!r} must be a 5-digit KSH code")
        require(exc["serviceArea"] in areas and exc["serviceArea"] != "GYSEV", f"exception {ksh} must target an existing non-GYSEV area")
        require(bool(exc.get("reason")) and "expectedName" in exc and "expectedCounty" in exc, f"exception {ksh} needs a reason, expectedName and expectedCounty")
    inactive = {a["id"] for a in policy["untouchedInactiveAreas"]}
    require(not (inactive & ids), "an untouched INACTIVE area must never be an assignment target")
    return policy, county_owner


def load_dataset(directory):
    manifest_bytes = (directory / "manifest.json").read_bytes()
    manifest = json.loads(manifest_bytes)
    # Gates are checked before any source CSV is opened.
    require(manifest.get("reuseStatus") == "CLEARED", "dataset is not CLEARED; rows were not read")
    require(manifest.get("verificationStatus") == "VERIFIED", "dataset is not VERIFIED; rows were not read")
    require(bool(manifest.get("datasetVersion")), "missing datasetVersion")
    raw, tables = {}, {}
    for name, header in HEADERS.items():
        data = (directory / name).read_bytes()
        require(manifest["canonicalFiles"].get(name) == digest(data), f"checksum mismatch for {name}")
        rows = list(csv.reader(io.StringIO(data.decode("utf-8"), newline="")))
        require(rows and rows[0] == header, f"unexpected header in {name}")
        raw[name], tables[name] = digest(data), rows[1:]
    settlements = {}
    for ksh, name, county in tables["settlements.csv"]:
        require(ksh not in settlements, f"duplicate KSH code {ksh}")
        settlements[ksh] = (name, county)
    lines = {code: name for code, name in tables["railway-lines.csv"]}
    pairs = []
    for ksh, code in tables["settlement-railway-lines.csv"]:
        require(ksh in settlements, f"relation names unknown settlement {ksh}")
        require(code in lines, f"relation names unknown line {code}")
        pairs.append((ksh, code))
    require(len(pairs) == len(set(pairs)), "duplicate settlement-line relation")
    return manifest, raw, settlements, lines, sorted(pairs)


def load_gysev_candidates(path, pairs):
    if not path.exists():
        return []
    data = json.loads(path.read_bytes())
    known, seen, result = set(pairs), set(), []
    for c in data.get("candidates", []):
        require(GYSEV_FIELDS <= set(c), f"GYSEV candidate is missing fields {sorted(GYSEV_FIELDS - set(c))}")
        require(c["status"] == "CANDIDATE_NOT_APPROVED", "a GYSEV candidate must be CANDIDATE_NOT_APPROVED here")
        require(c["sourceUrl"].startswith("https://") and c["retrievedAt"] and c["evidence"], "GYSEV candidate needs a source, date and evidence")
        require("local-research" not in c["sourceUrl"] and "local-research" not in c["evidence"], "PENDING material must never be cited")
        key = (c["kshCode"], c["lineCode"])
        require(key in known, f"GYSEV candidate {key} is not a verified pair")
        require(key not in seen, f"duplicate GYSEV candidate {key}")
        seen.add(key)
        result.append(c)
    return sorted(result, key=lambda c: (c["kshCode"], c["lineCode"]))


def build(promoted_dir, policy_path, gysev_path):
    policy, county_owner = load_policy(policy_path)
    manifest, source_hashes, settlements, lines, pairs = load_dataset(promoted_dir)
    areas = policy["serviceAreas"]
    gysev = load_gysev_candidates(gysev_path, pairs)

    preview, unassigned = [], []
    for ksh, code in pairs:
        name, county = settlements[ksh]
        exception = policy.get("settlementIdExceptions", {}).get(ksh)
        area_name, rule = (county_owner.get(county) if county else None), "COUNTY_RULE"
        if exception is not None:
            # Keyed strictly by KSH code, and only while the canonical record still is exactly
            # what the exception was written for: a renamed/changed record never inherits it,
            # and no other settlement ever matches by name.
            if (name, county) == (exception["expectedName"], exception["expectedCounty"]):
                area_name, rule = exception["serviceArea"], "KSH_ID_EXCEPTION"
            else:
                area_name = None
        if area_name is None:
            reason = ("EXCEPTION_RECORD_MISMATCH" if exception is not None
                      else "MISSING_COUNTY" if not county else "COUNTY_NOT_IN_POLICY")
            row = (ksh, name, county, code, lines[code], "", "", "UNASSIGNED", reason)
            unassigned.append(row)
        else:
            row = (ksh, name, county, code, lines[code], area_name, areas[area_name]["id"], "ASSIGNED_GEOGRAPHIC", rule)
        preview.append(row)

    by_area = defaultdict(lambda: {"pairs": 0, "settlements": set(), "lines": set()})
    by_line = defaultdict(lambda: defaultdict(int))
    for ksh, _, _, code, _, area, _, status, _ in preview:
        if status == "ASSIGNED_GEOGRAPHIC":
            by_area[area]["pairs"] += 1
            by_area[area]["settlements"].add(ksh)
            by_area[area]["lines"].add(code)
            by_line[code][area] += 1
    cross = [(code, "|".join(sorted(a)), "|".join(f"{k}={v}" for k, v in sorted(a.items())))
             for code, a in sorted(by_line.items()) if len(a) > 1]

    require(all(r[5] != "GYSEV" for r in preview), "GYSEV must never be assigned automatically")
    budapest_counties = set(areas["Budapest"]["counties"])
    budapest_rows = [r for r in preview if r[2] in budapest_counties]
    require(all(r[5] == "Budapest" for r in budapest_rows), "Budapest/Pest pairs must all map to Budapest")

    summary = {
        "policyVersion": policy["policyVersion"],
        "datasetVersion": manifest["datasetVersion"],
        "sourceFileSha256": source_hashes,
        "status": "OFFLINE_PREVIEW_NOT_APPLIED",
        "pairs": len(preview),
        "assignedGeographic": len(preview) - len(unassigned),
        "unassigned": len(unassigned),
        "unassignedByReason": {r: sum(1 for u in unassigned if u[8] == r) for r in sorted({u[8] for u in unassigned})},
        "railwayLines": len({r[3] for r in preview}),
        "linesCrossingMultipleAreas": len(cross),
        "byServiceArea": {
            name: {"id": areas[name]["id"], "pairs": by_area[name]["pairs"],
                   "settlements": len(by_area[name]["settlements"]), "lines": len(by_area[name]["lines"])}
            for name in sorted(areas)
        },
        "kshIdExceptionPairs": sum(1 for r in preview if r[8] == "KSH_ID_EXCEPTION"),
        "budapestCheck": {"pairsInBudapestOrPestCounty": len(budapest_rows), "allMappedToBudapest": True},
        "gysevCheck": {"automaticallyAssignedPairs": 0, "overrideCandidatesListed": len(gysev), "overrideCandidatesApplied": 0},
        "untouchedInactiveAreas": [a["id"] for a in policy["untouchedInactiveAreas"]],
    }
    payload = {
        "expectedReferenceVersion": manifest["datasetVersion"],
        "changes": [
            {"kshCode": r[0], "lineCode": r[3], "targetServiceAreaId": r[6], "expectedCurrentServiceAreaId": None}
            for r in preview if r[7] == "ASSIGNED_GEOGRAPHIC"
        ],
    }
    header = ["ksh_code", "settlement_name", "county_name", "line_code", "line_name", "service_area", "service_area_id", "status", "reason"]
    return {
        "pair-area-preview.csv": csv_bytes(header, preview),
        "unassigned-pairs.csv": csv_bytes(header, unassigned),
        "cross-territory-lines.csv": csv_bytes(["line_code", "service_areas", "pairs_per_area"], cross),
        "gysev-override-candidates.json": json_bytes({"status": "CANDIDATES_NOT_APPROVED_NOT_APPLIED", "candidates": gysev}),
        "summary.json": json_bytes(summary),
        # Request body for POST .../settlement-line-mappings/preview|apply. Generated for review
        # only; applying it is a separate, separately-approved production step.
        "apply-batch-payload.json": json_bytes(payload),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--promoted-dir", type=Path, required=True)
    parser.add_argument("--policy", type=Path, default=ROOT / "operational-data/county-v2/policy.json")
    parser.add_argument("--gysev-candidates", type=Path, default=ROOT / "operational-data/county-v2/gysev-override-candidates.json")
    parser.add_argument("--out", type=Path, default=ROOT / "operational-data/county-v2/preview")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    try:
        files = build(args.promoted_dir, args.policy, args.gysev_candidates)
    except (PreviewError, OSError, KeyError, ValueError, json.JSONDecodeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    if args.check:
        stale = [n for n, data in files.items() if not (args.out / n).exists() or (args.out / n).read_bytes() != data]
        if stale:
            print(f"ERROR: committed preview differs from a fresh build: {stale}", file=sys.stderr)
            return 1
        print("preview is up to date")
        return 0
    args.out.mkdir(parents=True, exist_ok=True)
    for name, data in files.items():
        (args.out / name).write_bytes(data)
    print(json.dumps(json.loads(files["summary.json"]), ensure_ascii=False))
    print("Offline proposal only. No database or running service was changed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
